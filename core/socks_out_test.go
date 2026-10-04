package core

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

// testSocksServer:最小 SOCKS5 伺服器(CONNECT,可選帳密),redirect 可改寫目的地。
// 目的地連不上時依錯誤回 REP 5(refused)或 4(host unreachable)。
type testSocksServer struct {
	ln       net.Listener
	user     string
	pass     string
	redirect func(addr string) string
	conns    atomic.Int64
}

func newTestSocksServer(t *testing.T, user, pass string) *testSocksServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &testSocksServer{ln: ln, user: user, pass: pass}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go s.handle(c)
		}
	}()
	return s
}

func (s *testSocksServer) port() int { return s.ln.Addr().(*net.TCPAddr).Port }

func (s *testSocksServer) handle(c net.Conn) {
	defer c.Close()
	s.conns.Add(1)
	var b [262]byte
	if _, err := io.ReadFull(c, b[:2]); err != nil || b[0] != 5 {
		return
	}
	if _, err := io.ReadFull(c, b[:b[1]]); err != nil {
		return
	}
	if s.user != "" {
		c.Write([]byte{5, 2})
		if _, err := io.ReadFull(c, b[:2]); err != nil {
			return
		}
		u := make([]byte, b[1])
		io.ReadFull(c, u)
		io.ReadFull(c, b[:1])
		p := make([]byte, b[0])
		io.ReadFull(c, p)
		if string(u) != s.user || string(p) != s.pass {
			c.Write([]byte{1, 1})
			return
		}
		c.Write([]byte{1, 0})
	} else {
		c.Write([]byte{5, 0})
	}
	if _, err := io.ReadFull(c, b[:4]); err != nil {
		return // 健康檢查只做協商就關閉
	}
	var host string
	switch b[3] {
	case 1:
		io.ReadFull(c, b[:4])
		host = net.IP(b[:4]).String()
	case 4:
		io.ReadFull(c, b[:16])
		host = net.IP(b[:16]).String()
	case 3:
		io.ReadFull(c, b[:1])
		n := int(b[0])
		io.ReadFull(c, b[:n])
		host = string(b[:n])
	}
	io.ReadFull(c, b[:2])
	addr := net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(b[:2]))))
	if s.redirect != nil {
		addr = s.redirect(addr)
	}
	tc, err := net.DialTimeout("tcp", addr, 3*time.Second)
	if err != nil {
		rep := byte(4)
		if errors.Is(err, syscall.ECONNREFUSED) {
			rep = 5
		}
		c.Write([]byte{5, rep, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer tc.Close()
	c.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0})
	go func() { io.Copy(tc, c); tc.(*net.TCPConn).CloseWrite() }()
	io.Copy(c, tc)
}

func TestSocksOutboundEndToEnd(t *testing.T) {
	echo := startEchoServer(t)
	closed := closedPort(t)
	var dnsCount atomic.Int64
	dnsAddr := startTCPDNSServer(t, &dnsCount)
	proxy := newTestSocksServer(t, "", "")
	proxy.redirect = func(addr string) string {
		switch {
		case addr == ipv4ProbeTarget, strings.HasPrefix(addr, "198.51.100.7:"):
			return echo
		case addr == ipv6ProbeTarget:
			return closed // 代理沒有 IPv6
		case addr == "9.9.9.9:53":
			return dnsAddr
		}
		return closed
	}
	engineFd, appFd := socketpair(t)
	plat := newTestPlatform(t)
	if err := Start(engineFd, cfgJSON(t, map[string]any{
		"type": "socks5", "host": "127.0.0.1", "port": proxy.port(),
		"mtu": testMTU, "virtualDns": "10.0.0.53", "dnsUpstream": "9.9.9.9:53", "keepaliveSec": 1,
	}), plat); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(Stop)
	plat.waitState(t, StateConnected, 5*time.Second)
	if v4, v6 := waitServerIP(t, plat); v4 != famYes || v6 != famNo {
		t.Fatalf("expected v4 yes / v6 no via proxy, got %d/%d", v4, v6)
	}
	app := clientStack(t, appFd)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	c, err := gonet.DialContextTCP(ctx, app, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 51, 100, 7}), Port: 80}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	c.Write([]byte("via socks"))
	buf := make([]byte, 9)
	c.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := io.ReadFull(c, buf); err != nil || string(buf) != "via socks" {
		t.Fatalf("echo via socks: %q %v", buf, err)
	}
	c.Close()

	// 代理回 REP=5 → App 在握手階段就被拒(Happy Eyeballs 可退回)
	if _, err := gonet.DialContextTCP(ctx, app, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{203, 0, 113, 9}), Port: 443}, ipv4.ProtocolNumber); err == nil || !strings.Contains(err.Error(), "refused") {
		t.Fatalf("expected refused, got %v", err)
	}

	// DNS 經代理以 DNS-over-TCP 送到上游
	uc, err := gonet.DialUDP(app, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{10, 0, 0, 53}), Port: 53}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer uc.Close()
	uc.SetDeadline(time.Now().Add(5 * time.Second))
	uc.Write(buildQuery(t, 42, "example.com."))
	rb := make([]byte, 512)
	n, err := uc.Read(rb)
	if err != nil {
		t.Fatal(err)
	}
	var m dnsmessage.Message
	if err := m.Unpack(rb[:n]); err != nil || m.ID != 42 || len(m.Answers) != 1 {
		t.Fatalf("dns via socks: %+v %v", m, err)
	}

	// 健康檢查會量到 RTT;統計顯示 1/1 與代理類型
	deadline := time.Now().Add(3 * time.Second)
	for {
		st := GetStats()
		if st.SSHLive == 1 && st.SSHTotal == 1 && st.ServerVersion == "SOCKS5" && st.RTTMillis >= 0 && proxy.conns.Load() > 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("unexpected stats: %+v", *st)
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func TestSocksAuth(t *testing.T) {
	proxy := newTestSocksServer(t, "alice", "s3cret")
	for _, tc := range []struct {
		user, pass string
		want       int
	}{{"alice", "wrong", StateError}, {"", "", StateError}, {"alice", "s3cret", StateConnected}} {
		plat := newTestPlatform(t)
		if err := Start(-1, cfgJSON(t, map[string]any{
			"type": "socks5", "host": "127.0.0.1", "port": proxy.port(), "user": tc.user, "password": tc.pass,
		}), plat); err != nil {
			t.Fatal(err)
		}
		msg := plat.waitState(t, tc.want, 5*time.Second)
		if tc.want == StateError && !strings.Contains(msg, "authentication") {
			t.Fatalf("user=%q: unexpected error %q", tc.user, msg)
		}
		Stop()
	}
}

func TestTestConnectionSocks(t *testing.T) {
	info := startFakeIPInfo(t)
	echo := startEchoServer(t)
	proxy := newTestSocksServer(t, "", "")
	proxy.redirect = func(addr string) string {
		switch addr {
		case "ipinfo.test:80":
			return info
		case ipv4ProbeTarget, ipv6ProbeTarget:
			return echo
		}
		return addr
	}
	res, err := TestConnection(cfgJSON(t, map[string]any{
		"type": "socks5", "host": "127.0.0.1", "port": proxy.port(), "exitCheckUrl": "http://ipinfo.test/json",
	}), newTestPlatform(t))
	if err != nil {
		t.Fatal(err)
	}
	if res.ServerVersion != "SOCKS5" || res.IPv4 != famYes || res.IPv6 != famYes || !strings.Contains(res.ExitInfo, `"country":"JP"`) || res.Fingerprint != "" {
		t.Fatalf("unexpected result: %+v", *res)
	}
	// 代理不可達 → 錯誤
	if _, err := TestConnection(cfgJSON(t, map[string]any{
		"type": "socks5", "host": "127.0.0.1", "port": closedPortNum(t), "connectTimeoutSec": 2,
	}), newTestPlatform(t)); err == nil {
		t.Fatal("expected error for unreachable proxy")
	}
}
