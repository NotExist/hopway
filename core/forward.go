package sshvpn

import (
	"context"
	"errors"
	"net"
	"time"

	"golang.org/x/crypto/ssh"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// IPv6 能力狀態(伺服器端能否連到 IPv6 目的地)。
const (
	ipv6Unknown = 0
	ipv6Yes     = 1
	ipv6No      = 2
)

// 探測用的 IPv6 目的地:Cloudflare DNS 的 HTTPS port,長期穩定可達。
const ipv6ProbeTarget = "[2606:4700:4700::1111]:443"

// installForwarders 安裝自己的 TCP/UDP forwarder(必須在建立 NIC 之前呼叫)。
//
// tun2socks 預設「先與 App 完成 TCP 三向握手,再連遠端」:遠端連不上時,App 看到的是
// 「連線成功後立刻被斷」,而不是「連不上」,因此不會觸發 Happy Eyeballs(IPv6 失敗改走 IPv4)。
// 這裡改成先開 SSH channel,成功才回 SYN-ACK,失敗則回 RST——App 會得到 connection refused,
// 並立即嘗試下一個位址。代價是 SYN-ACK 晚一個 channel open 的往返,但 App 本來就要等這段時間才有資料。
//
// UDP 方面,不轉送的封包(沒有 udpgw、或目的地不支援)讓 forwarder 回傳 false,
// gVisor 會回 ICMP port unreachable,QUIC 等協定可以立刻退回 TCP,不必等逾時。
func (h *handler) installForwarders(s *stack.Stack) {
	h.stack = s
	tf := tcp.NewForwarder(s, 0, 2<<10, h.forwardTCP)
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tf.HandlePacket)
	uf := udp.NewForwarder(s, h.forwardUDP)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, uf.HandlePacket)
}

// forwardTCP 在 gVisor 為每個 SYN 開的 goroutine 中執行,可以安心阻塞。
func (h *handler) forwardTCP(r *tcp.ForwarderRequest) {
	id := r.ID()
	dst := toAddrPort(id.LocalAddress, id.LocalPort)
	target := dst.String()
	if dst.Addr() == h.vdns {
		if dst.Port() != 53 {
			r.Complete(true)
			return
		}
		target = h.dns.upstream // TCP DNS 到虛擬 DNS → 上游
	}
	// 已確認伺服器沒有 IPv6:不浪費一次 channel open,直接拒絕讓 App 改走 IPv4
	if dst.Addr().Is6() && h.ipv6.Load() == ipv6No {
		h.st.dialFailures.Add(1)
		r.Complete(true)
		return
	}

	ctx, cancel := context.WithTimeout(h.ctx, dialTimeout)
	rc, err := h.pool.Dial(ctx, target)
	cancel()
	if err != nil {
		h.st.dialFailures.Add(1)
		h.log.debugf("tcp %s: %v", target, err)
		r.Complete(true) // RST
		return
	}

	var wq waiter.Queue
	ep, terr := r.CreateEndpoint(&wq)
	if terr != nil {
		rc.Close()
		r.Complete(true)
		return
	}
	r.Complete(false)
	setTCPOptions(h.stack, ep)
	c := gonet.NewTCPConn(&wq, ep)

	defer c.Close()
	defer rc.Close()
	h.st.tcpActive.Add(1)
	h.st.tcpTotal.Add(1)
	defer h.st.tcpActive.Add(-1)
	relay(c, rc, &h.st.tx, &h.st.rx)
}

func (h *handler) forwardUDP(r *udp.ForwarderRequest) bool {
	id := r.ID()
	dst := toAddrPort(id.LocalAddress, id.LocalPort)
	var serve func(net.Conn)
	switch {
	case dst.Port() == 53:
		serve = h.handleDNS
	case h.udpgw != nil && dst.Addr() != h.vdns && !(dst.Addr().Is6() && h.ipv6.Load() == ipv6No):
		serve = func(c net.Conn) { h.handleUDPGW(c, dst) }
	default:
		h.st.udpDropped.Add(1)
		h.log.debugf("udp %s dropped (no udpgw)", dst)
		return false // → ICMP port unreachable
	}
	var wq waiter.Queue
	ep, err := r.CreateEndpoint(&wq)
	if err != nil {
		return false
	}
	go serve(gonet.NewUDPConn(&wq, ep))
	return true
}

// setTCPOptions 與 tun2socks core 相同:keepalive 偵測 App 端消失、套用 stack 的 buffer 預設值。
func setTCPOptions(s *stack.Stack, ep tcpip.Endpoint) {
	ep.SocketOptions().SetKeepAlive(true)
	idle := tcpip.KeepaliveIdleOption(60 * time.Second)
	_ = ep.SetSockOpt(&idle)
	interval := tcpip.KeepaliveIntervalOption(30 * time.Second)
	_ = ep.SetSockOpt(&interval)
	_ = ep.SetSockOptInt(tcpip.KeepaliveCountOption, 9)
	var ss tcpip.TCPSendBufferSizeRangeOption
	if err := s.TransportProtocolOption(header.TCPProtocolNumber, &ss); err == nil {
		ep.SocketOptions().SetSendBufferSize(int64(ss.Default), false)
	}
	var rs tcpip.TCPReceiveBufferSizeRangeOption
	if err := s.TransportProtocolOption(header.TCPProtocolNumber, &rs); err == nil {
		ep.SocketOptions().SetReceiveBufferSize(int64(rs.Default), false)
	}
}

// probeIPv6With 經 dial(SSH direct-tcpip)嘗試連一個 IPv6 目的地,判斷伺服器有沒有 IPv6 對外能力。
// 只有伺服器明確回報「連不上」(OpenChannelError)才判定為沒有;逾時等暫時性錯誤視為無法判定。
func probeIPv6With(ctx context.Context, dial func(context.Context, string) (net.Conn, error)) int32 {
	pctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	c, err := dial(pctx, ipv6ProbeTarget)
	switch {
	case err == nil:
		c.Close()
		return ipv6Yes
	case errors.As(err, new(*ssh.OpenChannelError)):
		return ipv6No
	default:
		return ipv6Unknown
	}
}

// probeIPv6 在引擎 SSH 連上後探測一次,結果用於拒絕 IPv6 連線/過濾 AAAA,並回報平台端。
func (e *engine) probeIPv6(ctx context.Context, h *handler) {
	r := probeIPv6With(ctx, e.pool.Dial)
	if ctx.Err() != nil {
		return // 引擎已停止,不再回報
	}
	if r == ipv6Unknown {
		e.log.debugf("ipv6 probe inconclusive")
		return
	}
	ok := r == ipv6Yes
	if ok {
		h.ipv6.Store(ipv6Yes)
		e.log.infof("server has IPv6 connectivity")
	} else {
		h.ipv6.Store(ipv6No)
		e.log.warnf("server has no IPv6 connectivity; IPv6 destinations will be refused")
	}
	if e.plat != nil {
		e.plat.OnIPv6(ok)
	}
}
