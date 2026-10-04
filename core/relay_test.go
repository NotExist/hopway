package sshvpn

import (
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

// tcpPair 回傳一對已連線的 TCP conn(支援 CloseWrite,與實際情境一致)。
func tcpPair(t *testing.T) (net.Conn, net.Conn) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	ch := make(chan net.Conn, 1)
	go func() { c, _ := ln.Accept(); ch <- c }()
	a, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	return a, <-ch
}

// 回歸測試:App half-close 之後,下行只要持續有資料就不能被切斷(舊版用固定期限,長下載會被截斷)。
func TestRelayHalfCloseUsesIdleTimeout(t *testing.T) {
	const idle = 200 * time.Millisecond
	app, local := tcpPair(t)     // app ↔ relay 的 local 端
	remote, server := tcpPair(t) // relay 的 remote 端 ↔ 伺服器
	var tx, rx atomic.Int64
	done := make(chan struct{})
	go func() { relayIdle(local, remote, &tx, &rx, idle); close(done) }()

	app.Write([]byte("GET"))
	app.(*net.TCPConn).CloseWrite() // App 送完請求即 half-close

	// 伺服器在 1.2 秒內每 100 ms 送一塊,總時間遠超過 200 ms 的閒置逾時
	go func() {
		io.Copy(io.Discard, server)
		for i := 0; i < 12; i++ {
			server.Write(make([]byte, 1000))
			time.Sleep(100 * time.Millisecond)
		}
		server.(*net.TCPConn).CloseWrite()
	}()
	got, err := io.ReadAll(app)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 12000 {
		t.Fatalf("downstream truncated: got %d bytes, want 12000", len(got))
	}
	<-done

	// 真正閒置時仍會被切:half-close 後完全不送資料,relay 應在逾時後結束
	app2, local2 := tcpPair(t)
	remote2, server2 := tcpPair(t)
	defer server2.Close()
	done2 := make(chan struct{})
	go func() { relayIdle(local2, remote2, &tx, &rx, idle); close(done2) }()
	app2.(*net.TCPConn).CloseWrite()
	select {
	case <-done2:
	case <-time.After(3 * time.Second):
		t.Fatal("idle half-closed connection was not torn down")
	}
}
