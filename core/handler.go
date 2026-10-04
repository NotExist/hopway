package sshvpn

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

type counters struct {
	tx, rx       atomic.Int64 // 以 VPN 內 app 的角度:tx = 上傳,rx = 下載
	tcpActive    atomic.Int64
	tcpTotal     atomic.Int64
	udpActive    atomic.Int64
	udpDropped   atomic.Int64 // 沒有 udpgw 或目的地不支援而回 ICMP unreachable 的 UDP 流
	dialFailures atomic.Int64 // 伺服器端連不到目的地(或已知無 IPv6 而直接拒絕)的 TCP 連線
}

type handler struct {
	ctx   context.Context
	pool  dialer
	dns   *dnsClient
	udpgw *udpgwClient
	vdns  netip.Addr
	log   *logger
	st    *counters
	stack *stack.Stack
	// 伺服器的 IPv6 能力:ipv6Unknown / ipv6Yes / ipv6No(由 probeIPv6 設定)
	ipv6 atomic.Int32
}

type dialer interface {
	Dial(ctx context.Context, addr string) (net.Conn, error)
}

const (
	dialTimeout   = 20 * time.Second
	tcpHalfClose  = 60 * time.Second
	udpDNSIdle    = 20 * time.Second
	relayBufSize  = 32 << 10
	maxDNSPayload = 4096
)

var relayBufPool = sync.Pool{New: func() any { b := make([]byte, relayBufSize); return &b }}

func toAddrPort(a tcpip.Address, port uint16) netip.AddrPort {
	ip, _ := netip.AddrFromSlice(a.AsSlice())
	return netip.AddrPortFrom(ip.Unmap(), port)
}

// HandleTCP / HandleUDP 滿足 tun2socks 的 TransportHandler 介面;實際轉送由 installForwarders
// 安裝的 forwardTCP / forwardUDP 負責,這兩個只在 stack 建立到替換 forwarder 之間的空窗被呼叫。
func (h *handler) HandleTCP(c adapter.TCPConn) { c.Close() }
func (h *handler) HandleUDP(c adapter.UDPConn) { c.Close() }

// relay 雙向複製並支援 half-close;一方結束後另一方最多再撐 tcpHalfClose。
func relay(local, remote net.Conn, tx, rx *atomic.Int64) {
	var wg sync.WaitGroup
	wg.Add(2)
	cp := func(dst, src net.Conn, n *atomic.Int64) {
		defer wg.Done()
		bp := relayBufPool.Get().(*[]byte)
		defer relayBufPool.Put(bp)
		_, _ = io.CopyBuffer(&countWriter{dst, n}, src, *bp)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		} else {
			_ = dst.Close()
		}
		_ = src.SetReadDeadline(time.Now().Add(tcpHalfClose))
		_ = dst.SetReadDeadline(time.Now().Add(tcpHalfClose))
	}
	go cp(remote, local, tx)
	cp(local, remote, rx)
	wg.Wait()
}

type countWriter struct {
	w io.Writer
	n *atomic.Int64
}

func (c *countWriter) Write(p []byte) (int, error) {
	n, err := c.w.Write(p)
	c.n.Add(int64(n))
	return n, err
}

func (h *handler) handleDNS(c net.Conn) {
	defer c.Close()
	h.st.udpActive.Add(1)
	defer h.st.udpActive.Add(-1)
	buf := make([]byte, maxDNSPayload)
	var wmu sync.Mutex
	for {
		_ = c.SetReadDeadline(time.Now().Add(udpDNSIdle))
		n, err := c.Read(buf)
		if err != nil {
			return
		}
		h.st.tx.Add(int64(n))
		q := make([]byte, n)
		copy(q, buf[:n])
		go func() {
			var resp []byte
			var err error
			if h.ipv6.Load() == ipv6No && isAAAAQuery(q) {
				// 伺服器沒有 IPv6:AAAA 回空結果,App 只會拿到 IPv4 位址
				resp = emptyAnswer(q)
			} else {
				resp, err = h.dns.Query(h.ctx, q)
			}
			if err != nil {
				h.log.debugf("dns query failed: %v", err)
				resp = servfail(q)
				if resp == nil {
					return
				}
			}
			wmu.Lock()
			_, _ = c.Write(resp)
			wmu.Unlock()
			h.st.rx.Add(int64(len(resp)))
		}()
	}
}

// servfail 依查詢產生 SERVFAIL 回應,讓 app 快速失敗而不是等逾時。
func servfail(q []byte) []byte {
	if len(q) < 12 {
		return nil
	}
	r := make([]byte, len(q))
	copy(r, q)
	r[2] |= 0x80               // QR
	r[3] = (r[3] & 0xF0) | 0x2 // RCODE=SERVFAIL
	r[3] |= 0x80               // RA
	return r
}

func (h *handler) handleUDPGW(c net.Conn, dst netip.AddrPort) {
	defer c.Close()
	ctx, cancel := context.WithTimeout(h.ctx, dialTimeout)
	var lastRecv atomic.Int64
	s, err := h.udpgw.open(ctx, dst, func(p []byte) {
		lastRecv.Store(time.Now().UnixNano())
		if n, err := c.Write(p); err == nil {
			h.st.rx.Add(int64(n))
		}
	})
	cancel()
	if err != nil {
		h.log.debugf("udpgw %s: %v", dst, err)
		return
	}
	defer h.udpgw.release(s)
	h.st.udpActive.Add(1)
	defer h.st.udpActive.Add(-1)

	go func() {
		<-s.done
		c.Close()
	}()
	buf := make([]byte, 65535)
	for {
		_ = c.SetReadDeadline(time.Now().Add(udpgwIdleTimeout))
		n, err := c.Read(buf)
		if err != nil {
			var ne net.Error
			// 只送不收的 session 也可能還在用:最近有收到回應就續命
			if errors.As(err, &ne) && ne.Timeout() &&
				time.Since(time.Unix(0, lastRecv.Load())) < udpgwIdleTimeout {
				continue
			}
			return
		}
		if err := h.udpgw.send(s, buf[:n]); err != nil {
			return
		}
		h.st.tx.Add(int64(n))
	}
}
