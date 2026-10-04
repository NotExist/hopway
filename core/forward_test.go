package sshvpn

import (
	"context"
	"net"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

func closedPort(t *testing.T) string {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	ln.Close()
	return addr
}

var v6Dst = tcpip.FullAddress{NIC: 1, Port: 443,
	Addr: tcpip.AddrFrom16([16]byte{0x20, 0x01, 0x0d, 0xb8, 15: 1})}

// 啟動引擎;probeTo 決定 IPv6 探測目的地被導向哪裡(echo = 有 IPv6,closed = 沒有)。
func startEngine(t *testing.T, probeTo string, extra map[string]any) (*stack.Stack, *testPlatform) {
	t.Helper()
	echo := startEchoServer(t)
	closed := closedPort(t)
	srv := newTestSSHServer(t, "pw", nil)
	srv.redirect = func(addr string) string {
		switch {
		case addr == ipv6ProbeTarget:
			return probeTo
		case strings.HasPrefix(addr, "198.51.100.7:"), strings.HasPrefix(addr, "[2001:db8::1]:"):
			return echo
		}
		return closed // 其他目的地一律連不上
	}
	cfg := map[string]any{
		"host": "127.0.0.1", "port": srv.port(), "user": "u", "password": "pw",
		"mtu": testMTU, "virtualDns": "10.0.0.53", "dnsUpstream": "9.9.9.9",
	}
	for k, v := range extra {
		cfg[k] = v
	}
	engineFd, appFd := socketpair(t)
	plat := newTestPlatform(t)
	if err := Start(engineFd, cfgJSON(t, cfg), plat); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(Stop)
	plat.waitState(t, StateConnected, 5*time.Second)
	return clientStack(t, appFd), plat
}

func waitIPv6(t *testing.T, p *testPlatform) bool {
	t.Helper()
	select {
	case ok := <-p.ipv6Ch:
		return ok
	case <-time.After(10 * time.Second):
		t.Fatal("no OnIPv6 report")
		return false
	}
}

// 修正 1:遠端連不上時,App 應該在握手階段就收到 RST(connection refused),
// 而不是先連上再被斷——這是 Happy Eyeballs 能改走 IPv4 的前提。
func TestUnreachableDestinationIsRefusedAtHandshake(t *testing.T) {
	echo := startEchoServer(t)
	app, _ := startEngine(t, echo, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	start := time.Now()
	_, err := gonet.DialContextTCP(ctx, app, tcpip.FullAddress{
		NIC: 1, Addr: tcpip.AddrFrom4([4]byte{203, 0, 113, 9}), Port: 443,
	}, ipv4.ProtocolNumber)
	if err == nil || !strings.Contains(err.Error(), "refused") {
		t.Fatalf("expected connection refused, got %v", err)
	}
	if time.Since(start) > 3*time.Second {
		t.Fatalf("refusal took too long: %v", time.Since(start))
	}
	if st := GetStats(); st.DialFailures < 1 {
		t.Fatalf("dial failure not counted: %+v", *st)
	}
	// 可達的目的地照常運作
	c, err := gonet.DialContextTCP(ctx, app, tcpip.FullAddress{
		NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 51, 100, 7}), Port: 80,
	}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
}

// 修正 3:沒有 udpgw 時,非 DNS 的 UDP 應立刻收到 ICMP port unreachable。
func TestDroppedUDPGetsPortUnreachable(t *testing.T) {
	echo := startEchoServer(t)
	app, _ := startEngine(t, echo, nil)
	uc, err := gonet.DialUDP(app, nil, &tcpip.FullAddress{
		NIC: 1, Addr: tcpip.AddrFrom4([4]byte{203, 0, 113, 9}), Port: 443,
	}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer uc.Close()
	uc.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := uc.Write([]byte("quic initial")); err != nil {
		t.Fatal(err)
	}
	// gVisor 的 UDP socket 不像 Linux 會把 ICMP 錯誤回報給 Read,改看用戶端 stack 的 ICMP 計數
	deadline := time.Now().Add(3 * time.Second)
	for app.Stats().ICMP.V4.PacketsReceived.DstUnreachable.Value() == 0 {
		if time.Now().After(deadline) {
			t.Fatal("no ICMP destination unreachable received")
		}
		time.Sleep(20 * time.Millisecond)
	}
	if st := GetStats(); st.UDPDropped < 1 {
		t.Fatalf("udp drop not counted: %+v", *st)
	}
}

// 修正 2:伺服器沒有 IPv6 → 回報 OnIPv6(false)、IPv6 目的地直接拒絕、AAAA 回空結果。
func TestServerWithoutIPv6(t *testing.T) {
	app, plat := startEngine(t, closedPort(t), nil)
	if waitIPv6(t, plat) {
		t.Fatal("expected no IPv6")
	}
	if st := GetStats(); st.IPv6 != ipv6No {
		t.Fatalf("stats ipv6 = %d", st.IPv6)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	// 即使是「伺服器端其實可達」的 IPv6 目的地,也因已知無 IPv6 而直接拒絕
	if _, err := gonet.DialContextTCP(ctx, app, v6Dst, ipv6.ProtocolNumber); err == nil || !strings.Contains(err.Error(), "refused") {
		t.Fatalf("expected refused for IPv6, got %v", err)
	}
	// AAAA 查詢不送上游,回 NOERROR 空結果
	uc, err := gonet.DialUDP(app, nil, &tcpip.FullAddress{
		NIC: 1, Addr: tcpip.AddrFrom4([4]byte{10, 0, 0, 53}), Port: 53,
	}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer uc.Close()
	q := (&dnsmessage.Message{
		Header:    dnsmessage.Header{ID: 77, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName("example.com."), Type: dnsmessage.TypeAAAA, Class: dnsmessage.ClassINET}},
	})
	qb, _ := q.Pack()
	uc.SetDeadline(time.Now().Add(3 * time.Second))
	uc.Write(qb)
	buf := make([]byte, 512)
	n, err := uc.Read(buf)
	if err != nil {
		t.Fatal(err)
	}
	var m dnsmessage.Message
	if err := m.Unpack(buf[:n]); err != nil {
		t.Fatal(err)
	}
	if m.ID != 77 || !m.Response || m.RCode != dnsmessage.RCodeSuccess || len(m.Answers) != 0 || len(m.Questions) != 1 {
		t.Fatalf("unexpected AAAA reply: %+v", m)
	}
}

func TestServerWithIPv6(t *testing.T) {
	echo := startEchoServer(t)
	app, plat := startEngine(t, echo, nil)
	if !waitIPv6(t, plat) {
		t.Fatal("expected IPv6 available")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := gonet.DialContextTCP(ctx, app, v6Dst, ipv6.ProtocolNumber)
	if err != nil {
		t.Fatalf("IPv6 via tunnel: %v", err)
	}
	c.Write([]byte("v6"))
	b := make([]byte, 2)
	c.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := c.Read(b); err != nil || string(b) != "v6" {
		t.Fatalf("echo over IPv6: %q %v", b, err)
	}
	c.Close()
}
