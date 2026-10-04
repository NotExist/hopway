package sshvpn

import (
	"context"
	"net"
	"strconv"
	"strings"
	"sync/atomic"
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

// 啟動引擎;probe4 / probe6 決定兩個協定的探測目的地被導向哪裡(echo = 有,closed = 沒有)。
func startEngine(t *testing.T, probe4, probe6 string, extra map[string]any) (*stack.Stack, *testPlatform) {
	t.Helper()
	echo := startEchoServer(t)
	closed := closedPort(t)
	var dnsCount atomic.Int64
	dnsV6 := startTCPDNSServer(t, &dnsCount)
	srv := newTestSSHServer(t, "pw", nil)
	srv.redirect = func(addr string) string {
		switch {
		case addr == ipv4ProbeTarget:
			return probe4
		case addr == ipv6ProbeTarget:
			return probe6
		case strings.HasPrefix(addr, "198.51.100.7:"), strings.HasPrefix(addr, "[2001:db8::1]:"):
			return echo
		case strings.HasPrefix(addr, "[2001:db8::53]:53"):
			return dnsV6
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

func waitServerIP(t *testing.T, p *testPlatform) (int, int) {
	t.Helper()
	select {
	case r := <-p.ipCh:
		return r[0], r[1]
	case <-time.After(10 * time.Second):
		t.Fatal("no OnServerIP report")
		return 0, 0
	}
}

// 修正 1:遠端連不上時,App 應該在握手階段就收到 RST(connection refused),
// 而不是先連上再被斷——這是 Happy Eyeballs 能改走 IPv4 的前提。
func TestUnreachableDestinationIsRefusedAtHandshake(t *testing.T) {
	echo := startEchoServer(t)
	app, plat := startEngine(t, echo, echo, nil)
	waitServerIP(t, plat) // 確保探測完成,避免與測試流量競爭
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
	app, _ := startEngine(t, echo, echo, nil)
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
	app, plat := startEngine(t, startEchoServer(t), closedPort(t), nil)
	if v4, v6 := waitServerIP(t, plat); v4 != famYes || v6 != famNo {
		t.Fatalf("expected v4 yes / v6 no, got %d/%d", v4, v6)
	}
	if st := GetStats(); st.IPv6 != famNo || st.IPv4 != famYes {
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
	app, plat := startEngine(t, echo, echo, nil)
	if v4, v6 := waitServerIP(t, plat); v4 != famYes || v6 != famYes {
		t.Fatalf("expected both families, got %d/%d", v4, v6)
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

// 不假設 IPv4 一定存在:伺服器只有 IPv6 時,IPv4 目的地直接拒絕、A 記錄回空,
// DNS 上游(1.1.1.1 自動補上 2606:4700:4700::1111)改走 IPv6。
func TestIPv6OnlyServer(t *testing.T) {
	echo := startEchoServer(t)
	var dnsCount atomic.Int64
	dnsV6 := startTCPDNSServer(t, &dnsCount)
	closed := closedPort(t)
	srv := newTestSSHServer(t, "pw", nil)
	srv.redirect = func(addr string) string {
		switch {
		case addr == ipv4ProbeTarget:
			return closed
		case addr == ipv6ProbeTarget:
			return echo
		case addr == "[2606:4700:4700::1111]:53":
			return dnsV6
		case strings.HasPrefix(addr, "[2001:db8::1]:"):
			return echo
		}
		return closed // IPv4 目的地(含 1.1.1.1:53)一律連不上
	}
	engineFd, appFd := socketpair(t)
	plat := newTestPlatform(t)
	if err := Start(engineFd, cfgJSON(t, map[string]any{
		"host": "127.0.0.1", "port": srv.port(), "user": "u", "password": "pw",
		"mtu": testMTU, "virtualDns": "10.0.0.53", "dnsUpstream": "1.1.1.1",
	}), plat); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(Stop)
	plat.waitState(t, StateConnected, 5*time.Second)
	if v4, v6 := waitServerIP(t, plat); v4 != famNo || v6 != famYes {
		t.Fatalf("expected v4 no / v6 yes, got %d/%d", v4, v6)
	}
	app := clientStack(t, appFd)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if _, err := gonet.DialContextTCP(ctx, app, tcpip.FullAddress{
		NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 51, 100, 7}), Port: 80,
	}, ipv4.ProtocolNumber); err == nil || !strings.Contains(err.Error(), "refused") {
		t.Fatalf("IPv4 should be refused on an IPv6-only server, got %v", err)
	}
	c, err := gonet.DialContextTCP(ctx, app, v6Dst, ipv6.ProtocolNumber)
	if err != nil {
		t.Fatalf("IPv6 should work: %v", err)
	}
	c.Close()

	query := func(typ dnsmessage.Type) dnsmessage.Message {
		uc, err := gonet.DialUDP(app, nil, &tcpip.FullAddress{
			NIC: 1, Addr: tcpip.AddrFrom4([4]byte{10, 0, 0, 53}), Port: 53,
		}, ipv4.ProtocolNumber)
		if err != nil {
			t.Fatal(err)
		}
		defer uc.Close()
		qb, _ := (&dnsmessage.Message{
			Header:    dnsmessage.Header{ID: 9, RecursionDesired: true},
			Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName("example.com."), Type: typ, Class: dnsmessage.ClassINET}},
		}).Pack()
		uc.SetDeadline(time.Now().Add(5 * time.Second))
		uc.Write(qb)
		buf := make([]byte, 512)
		n, err := uc.Read(buf)
		if err != nil {
			t.Fatalf("dns %v: %v", typ, err)
		}
		var m dnsmessage.Message
		if err := m.Unpack(buf[:n]); err != nil {
			t.Fatal(err)
		}
		return m
	}
	if m := query(dnsmessage.TypeA); len(m.Answers) != 0 || m.RCode != dnsmessage.RCodeSuccess {
		t.Fatalf("A should be filtered on an IPv6-only server: %+v", m)
	}
	// AAAA 經 IPv6 上游取得(fakeAnswer 對任何問題都回一筆,證明有送到上游)
	if m := query(dnsmessage.TypeAAAA); len(m.Answers) != 1 {
		t.Fatalf("AAAA should be answered via IPv6 upstream: %+v", m)
	}
	if dnsCount.Load() < 1 {
		t.Fatal("IPv6 DNS upstream was not used")
	}
}

func TestExpandDNSUpstreams(t *testing.T) {
	got := expandDNSUpstreams("1.1.1.1, 9.9.9.9:5353,dns.example")
	want := []string{"1.1.1.1:53", "9.9.9.9:5353", "dns.example:53", "[2606:4700:4700::1111]:53", "[2620:fe::fe]:5353"}
	if strings.Join(got, " ") != strings.Join(want, " ") {
		t.Fatalf("got %v", got)
	}
}

func TestInterleaveFamilies(t *testing.T) {
	got := interleaveFamilies([]string{"2001:db8::1", " 2001:db8::2", "192.0.2.1", "192.0.2.1", "bad", "192.0.2.2"})
	want := "2001:db8::1 192.0.2.1 2001:db8::2 192.0.2.2"
	if strings.Join(got, " ") != want {
		t.Fatalf("got %v", got)
	}
	if got := interleaveFamilies([]string{"192.0.2.1", "2001:db8::1"}); got[0] != "192.0.2.1" {
		t.Fatalf("system preference (first address family) should lead: %v", got)
	}
}

// Happy Eyeballs:第一個位址黑洞(不回應)時,不必等它逾時,250 ms 後啟動的下一個位址成功即勝出。
func TestHappyEyeballs(t *testing.T) {
	good, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer good.Close()
	go func() {
		for {
			c, err := good.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	port := good.Addr().(*net.TCPAddr).Port
	// 192.0.2.1(TEST-NET-1)不可路由:連線會卡到逾時,模擬純 IPv6 網路上不通的 IPv4
	d := &net.Dialer{}
	start := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := dialHappyEyeballs(ctx, d, []string{"192.0.2.1", "127.0.0.1"}, strconv.Itoa(port))
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	if el := time.Since(start); el > 2*time.Second {
		t.Fatalf("happy eyeballs too slow: %v", el)
	}
	// 全部失敗時回傳錯誤
	if _, err := dialHappyEyeballs(ctx, d, []string{"127.0.0.1"}, closedPortNum(t)); err == nil {
		t.Fatal("expected error")
	}
}

func closedPortNum(t *testing.T) string {
	_, p, _ := net.SplitHostPort(closedPort(t))
	return p
}

// 連線歸屬偵錯:開啟 logOwners 時,DNS(UDP 53)與 TCP 連線都會以 App 端 → 目的地的位址向平台查詢擁有者。
func TestLogConnectionOwners(t *testing.T) {
	echo := startEchoServer(t)
	app, plat := startEngine(t, echo, echo, map[string]any{"logOwners": true})
	waitServerIP(t, plat)

	uc, err := gonet.DialUDP(app, nil, &tcpip.FullAddress{
		NIC: 1, Addr: tcpip.AddrFrom4([4]byte{10, 0, 0, 53}), Port: 53,
	}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	qb, _ := (&dnsmessage.Message{
		Header:    dnsmessage.Header{ID: 5, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName("example.com."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}},
	}).Pack()
	uc.Write(qb)
	uc.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := gonet.DialContextTCP(ctx, app, tcpip.FullAddress{
		NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 51, 100, 7}), Port: 80,
	}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	c.Close()

	deadline := time.Now().Add(3 * time.Second)
	for {
		plat.mu.Lock()
		got := strings.Join(plat.owners, " | ")
		plat.mu.Unlock()
		if strings.Contains(got, "17 10.0.0.2:") && strings.Contains(got, ">10.0.0.53:53") &&
			strings.Contains(got, "6 10.0.0.2:") && strings.Contains(got, ">198.51.100.7:80") {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("owner lookups not made: %s", got)
		}
		time.Sleep(20 * time.Millisecond)
	}
}
