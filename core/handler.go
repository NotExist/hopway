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
)

type counters struct {
	tx, rx       atomic.Int64 // 以 VPN 內 app 的角度:tx = 上傳,rx = 下載
	tcpActive    atomic.Int64
	tcpTotal     atomic.Int64
	udpActive    atomic.Int64
	dialFailures atomic.Int64
}

type handler struct {
	ctx   context.Context
	pool  dialer
	dns   *dnsClient
	udpgw *udpgwClient
	vdns  netip.Addr
	log   *logger
	st    *counters
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

func (h *handler) HandleTCP(c adapter.TCPConn) {
	go h.handleTCP(c)
}

func (h *handler) handleTCP(c adapter.TCPConn) {
	defer c.Close()
	id := c.ID()
	dst := toAddrPort(id.LocalAddress, id.LocalPort).String()
	if a := toAddrPort(id.LocalAddress, id.LocalPort); a.Addr() == h.vdns {
		if a.Port() != 53 {
			return
		}
		// TCP DNS 到虛擬 DNS → 直接轉給上游
		dst = h.dns.upstream
	}
	ctx, cancel := context.WithTimeout(h.ctx, dialTimeout)
	rc, err := h.pool.Dial(ctx, dst)
	cancel()
	if err != nil {
		h.st.dialFailures.Add(1)
		h.log.debugf("tcp %s: %v", dst, err)
		return
	}
	defer rc.Close()
	h.st.tcpActive.Add(1)
	h.st.tcpTotal.Add(1)
	defer h.st.tcpActive.Add(-1)
	relay(c, rc, &h.st.tx, &h.st.rx)
}

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

func (h *handler) HandleUDP(c adapter.UDPConn) {
	id := c.ID()
	dst := toAddrPort(id.LocalAddress, id.LocalPort)
	switch {
	case dst.Port() == 53:
		go h.handleDNS(c)
	case h.udpgw != nil && dst.Addr() != h.vdns:
		go h.handleUDPGW(c, dst)
	default:
		// 無 udpgw 時丟棄;QUIC 等會自動退回 TCP
		c.Close()
	}
}

func (h *handler) handleDNS(c adapter.UDPConn) {
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
			resp, err := h.dns.Query(h.ctx, q)
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

func (h *handler) handleUDPGW(c adapter.UDPConn, dst netip.AddrPort) {
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
