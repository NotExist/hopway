package sshvpn

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/net/proxy"
	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// 測試用 socketpair 代替 TUN:非 root 時 socket buffer 上限約 208 KB,寫滿會 EAGAIN 丟包
// (真正的 TUN 寫入不會這樣)。用 1500 的 MTU 讓 buffer 可容納約 140 個封包,
// 避免 -race 變慢時大量丟包、TCP 重傳逾時一路退避而停滯。
const testMTU = 1500

func cfgJSON(t *testing.T, m map[string]any) string {
	b, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// clientStack 模擬「VPN 內的 app」:另一個 gVisor stack 接在 socketpair 的另一端,
// socketpair 扮演 TUN 裝置。
func clientStack(t *testing.T, fd int) *stack.Stack {
	t.Helper()
	ep, err := fdbased.New(&fdbased.Options{FDs: []int{fd}, MTU: testMTU})
	if err != nil {
		t.Fatal(err)
	}
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	if err := s.CreateNIC(1, ep); err != nil {
		t.Fatal(err)
	}
	addr := tcpip.AddrFrom4([4]byte{10, 0, 0, 2})
	if err := s.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol: ipv4.ProtocolNumber, AddressWithPrefix: addr.WithPrefix(),
	}, stack.AddressProperties{}); err != nil {
		t.Fatal(err)
	}
	addr6 := tcpip.AddrFrom16([16]byte{0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2})
	if err := s.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol: ipv6.ProtocolNumber, AddressWithPrefix: addr6.WithPrefix(),
	}, stack.AddressProperties{}); err != nil {
		t.Fatal(err)
	}
	any6, _ := tcpip.NewSubnet(tcpip.AddrFrom16([16]byte{}), tcpip.MaskFromBytes(make([]byte, 16)))
	s.SetRouteTable([]tcpip.Route{{Destination: header4Any(), NIC: 1}, {Destination: any6, NIC: 1}})
	t.Cleanup(func() { s.Close() })
	return s
}

func header4Any() tcpip.Subnet {
	sn, _ := tcpip.NewSubnet(tcpip.AddrFrom4([4]byte{}), tcpip.MaskFromBytes([]byte{0, 0, 0, 0}))
	return sn
}

func socketpair(t *testing.T) (int, int) {
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_SEQPACKET, 0)
	if err != nil {
		t.Fatal(err)
	}
	for _, fd := range fds {
		_ = unix.SetNonblock(fd, true)
	}
	return fds[0], fds[1]
}

func TestEndToEndTCPAndDNS(t *testing.T) {
	echo := startEchoServer(t)
	var dnsCount atomic.Int64
	dnsAddr := startTCPDNSServer(t, &dnsCount)
	srv := newTestSSHServer(t, "pw", nil)
	srv.redirect = func(addr string) string {
		switch {
		case strings.HasPrefix(addr, "198.51.100.7:"):
			return echo
		case addr == "9.9.9.9:53":
			return dnsAddr
		}
		return addr
	}

	engineFd, appFd := socketpair(t)
	plat := newTestPlatform(t)
	err := Start(engineFd, cfgJSON(t, map[string]any{
		"host": "127.0.0.1", "port": srv.port(), "user": "u", "password": "pw",
		"connections": 2, "mtu": testMTU, "virtualDns": "10.0.0.53", "dnsUpstream": "9.9.9.9",
		"dnsCache": true,
	}), plat)
	if err != nil {
		t.Fatal(err)
	}
	defer Stop()
	plat.waitState(t, StateConnected, 5*time.Second)

	app := clientStack(t, appFd)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// 1) 平行多條 TCP,大量資料雙向來回
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			c, err := gonet.DialContextTCP(ctx, app, tcpip.FullAddress{
				NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 51, 100, 7}), Port: 80,
			}, ipv4.ProtocolNumber)
			if err != nil {
				t.Errorf("dial %d: %v", i, err)
				return
			}
			defer c.Close()
			payload := make([]byte, 2<<20)
			rand.Read(payload)
			go func() { c.Write(payload); c.CloseWrite() }()
			got, err := io.ReadAll(c)
			if err != nil {
				t.Errorf("read %d: %v", i, err)
				return
			}
			if !bytes.Equal(got, payload) {
				t.Errorf("conn %d: payload mismatch (%d vs %d bytes)", i, len(got), len(payload))
			}
		}(i)
	}
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(60 * time.Second):
		mu.Lock()
		es := cur.stack.Stats()
		mu.Unlock()
		as := app.Stats()
		t.Fatalf("transfer stalled: app retransmits=%d sendErrs=%d timeouts=%d | engine retransmits=%d sendErrs=%d timeouts=%d | app dropped=%d engine dropped=%d",
			as.TCP.Retransmits.Value(), as.TCP.SegmentSendErrors.Value(), as.TCP.Timeouts.Value(),
			es.TCP.Retransmits.Value(), es.TCP.SegmentSendErrors.Value(), es.TCP.Timeouts.Value(),
			as.DroppedPackets.Value(), es.DroppedPackets.Value())
	}

	st := GetStats()
	if st.TCPTotal < 8 || st.RxBytes < 16<<20 || st.TxBytes < 16<<20 {
		t.Errorf("unexpected stats: %+v", *st)
	}
	if srv.conns.Load() != 2 {
		t.Errorf("expected 2 parallel ssh connections, got %d", srv.conns.Load())
	}

	// 2) UDP DNS 到虛擬 DNS:轉 DNS-over-TCP;同一問題第二次應命中快取
	for i := 0; i < 3; i++ {
		uc, err := gonet.DialUDP(app, nil, &tcpip.FullAddress{
			NIC: 1, Addr: tcpip.AddrFrom4([4]byte{10, 0, 0, 53}), Port: 53,
		}, ipv4.ProtocolNumber)
		if err != nil {
			t.Fatal(err)
		}
		q := buildQuery(t, uint16(1000+i), "example.com.")
		uc.SetDeadline(time.Now().Add(5 * time.Second))
		if _, err := uc.Write(q); err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, 1500)
		n, err := uc.Read(buf)
		if err != nil {
			t.Fatalf("dns read: %v", err)
		}
		var m dnsmessage.Message
		if err := m.Unpack(buf[:n]); err != nil {
			t.Fatal(err)
		}
		if m.ID != uint16(1000+i) || len(m.Answers) != 1 {
			t.Fatalf("bad dns answer: %+v", m)
		}
		if a := m.Answers[0].Body.(*dnsmessage.AResource).A; a != [4]byte{192, 0, 2, 99} {
			t.Fatalf("bad A: %v", a)
		}
		uc.Close()
	}
	if dnsCount.Load() != 1 {
		t.Errorf("expected 1 upstream query (cache), got %d", dnsCount.Load())
	}
	if st := GetStats(); st.DNSCacheHits != 2 {
		t.Errorf("cache hits = %d", st.DNSCacheHits)
	}
}

func TestReconnectAfterServerDrop(t *testing.T) {
	echo := startEchoServer(t)
	srv := newTestSSHServer(t, "pw", nil)
	srv.redirect = func(string) string { return echo }
	plat := newTestPlatform(t)
	if err := Start(-1, cfgJSON(t, map[string]any{
		"host": "127.0.0.1", "port": srv.port(), "user": "u", "password": "pw",
		"keepaliveSec": 1, "socksListen": "127.0.0.1:0",
	}), plat); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	plat.waitState(t, StateConnected, 5*time.Second)
	srv.dropAll()
	plat.waitState(t, StateReconnecting, 5*time.Second)
	plat.waitState(t, StateConnected, 10*time.Second)

	// 斷線後 Dial 會等待重連,而不是直接失敗
	mu.Lock()
	pool := cur.pool
	mu.Unlock()
	srv.dropAll()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	c, err := pool.Dial(ctx, "203.0.113.1:7")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.Write([]byte("ping"))
	buf := make([]byte, 4)
	if _, err := io.ReadFull(c, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("echo after reconnect: %q %v", buf, err)
	}
}

func TestSocksProxy(t *testing.T) {
	echo := startEchoServer(t)
	srv := newTestSSHServer(t, "pw", nil)
	srv.redirect = func(addr string) string {
		if strings.HasPrefix(addr, "example.org:") {
			return echo
		}
		return addr
	}
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	socksAddr := ln.Addr().String()
	ln.Close()
	plat := newTestPlatform(t)
	if err := Start(-1, cfgJSON(t, map[string]any{
		"host": "127.0.0.1", "port": srv.port(), "user": "u", "password": "pw",
		"socksListen": socksAddr,
	}), plat); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	plat.waitState(t, StateConnected, 5*time.Second)
	d, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
	if err != nil {
		t.Fatal(err)
	}
	c, err := d.Dial("tcp", "example.org:443")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	fmt.Fprint(c, "hello")
	buf := make([]byte, 5)
	if _, err := io.ReadFull(c, buf); err != nil || string(buf) != "hello" {
		t.Fatalf("socks echo: %q %v", buf, err)
	}
}

func TestUDPGW(t *testing.T) {
	gw := startUDPGWServer(t)
	srv := newTestSSHServer(t, "pw", nil)
	srv.redirect = func(addr string) string {
		if addr == "127.0.0.1:7300" {
			return gw
		}
		return addr
	}
	engineFd, appFd := socketpair(t)
	plat := newTestPlatform(t)
	if err := Start(engineFd, cfgJSON(t, map[string]any{
		"host": "127.0.0.1", "port": srv.port(), "user": "u", "password": "pw",
		"mtu": testMTU, "udpgw": "127.0.0.1",
	}), plat); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	plat.waitState(t, StateConnected, 5*time.Second)
	app := clientStack(t, appFd)
	for i := 0; i < 3; i++ {
		uc, err := gonet.DialUDP(app, nil, &tcpip.FullAddress{
			NIC: 1, Addr: tcpip.AddrFrom4([4]byte{203, 0, 113, byte(10 + i)}), Port: 4433,
		}, ipv4.ProtocolNumber)
		if err != nil {
			t.Fatal(err)
		}
		uc.SetDeadline(time.Now().Add(5 * time.Second))
		for j := 0; j < 5; j++ {
			msg := []byte(fmt.Sprintf("dgram-%d-%d", i, j))
			if _, err := uc.Write(msg); err != nil {
				t.Fatal(err)
			}
			buf := make([]byte, 1500)
			n, err := uc.Read(buf)
			if err != nil {
				t.Fatalf("udp read: %v", err)
			}
			if string(buf[:n]) != string(msg) {
				t.Fatalf("udp echo mismatch: %q", buf[:n])
			}
		}
		uc.Close()
	}
}

func TestAuthFailureIsFatal(t *testing.T) {
	srv := newTestSSHServer(t, "right", nil)
	plat := newTestPlatform(t)
	if err := Start(-1, cfgJSON(t, map[string]any{
		"host": "127.0.0.1", "port": srv.port(), "user": "u", "password": "wrong",
	}), plat); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	msg := plat.waitState(t, StateError, 5*time.Second)
	if !strings.Contains(msg, "authentication failed") {
		t.Fatalf("unexpected error message %q", msg)
	}
}

func TestHostKeyRejectAndPublicKeyAuth(t *testing.T) {
	kp, err := GenerateKey("ed25519", "test@phone", "secret")
	if err != nil {
		t.Fatal(err)
	}
	if !KeyNeedsPassphrase(kp.PrivateKey) {
		t.Fatal("generated key should be encrypted")
	}
	pub, _, _, _, err := ssh.ParseAuthorizedKey([]byte(kp.PublicKey))
	if err != nil {
		t.Fatal(err)
	}
	srv := newTestSSHServer(t, "", pub)
	cfg := cfgJSON(t, map[string]any{
		"host": "127.0.0.1", "port": srv.port(), "user": "u",
		"privateKey": kp.PrivateKey, "passphrase": "secret", "udpgw": "127.0.0.1",
	})
	res, err := TestConnection(cfg, newTestPlatform(t))
	if err != nil {
		t.Fatal(err)
	}
	if res.Fingerprint != ssh.FingerprintSHA256(srv.hostKey) || res.UDPGWOK {
		t.Fatalf("unexpected test result %+v", res)
	}
	info, err := InspectKey(kp.PrivateKey, "secret")
	if err != nil || info.Fingerprint != kp.Fingerprint {
		t.Fatalf("inspect: %+v %v", info, err)
	}

	p := newTestPlatform(t)
	p.rejectHK = true
	if _, err := TestConnection(cfg, p); err == nil || !strings.Contains(err.Error(), "host key") {
		t.Fatalf("expected host key error, got %v", err)
	}
}

func buildQuery(t *testing.T, id uint16, name string) []byte {
	m := dnsmessage.Message{
		Header: dnsmessage.Header{ID: id, RecursionDesired: true},
		Questions: []dnsmessage.Question{{
			Name: dnsmessage.MustNewName(name), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET,
		}},
	}
	b, err := m.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func fakeAnswer(q []byte) []byte {
	var m dnsmessage.Message
	if err := m.Unpack(q); err != nil || len(m.Questions) == 0 {
		return q
	}
	m.Response = true
	m.RecursionAvailable = true
	m.Answers = []dnsmessage.Resource{{
		Header: dnsmessage.ResourceHeader{Name: m.Questions[0].Name, Type: dnsmessage.TypeA,
			Class: dnsmessage.ClassINET, TTL: 300},
		Body: &dnsmessage.AResource{A: [4]byte{192, 0, 2, 99}},
	}}
	b, _ := m.Pack()
	return b
}
