package sshvpn

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

// testSSHServer 是支援 password / publickey 認證與 direct-tcpip 的最小 SSH server。
// redirect 可把目的地改寫(讓測試把任意 IP 導向本機 listener)。
type testSSHServer struct {
	ln       net.Listener
	cfg      *ssh.ServerConfig
	redirect func(addr string) string
	probeOK  string // 探測目的地預設導向的本機 listener
	conns    atomic.Int64
	mu       sync.Mutex
	live     []net.Conn
	hostKey  ssh.PublicKey
}

func newTestSSHServer(t *testing.T, password string, authorized ssh.PublicKey) *testSSHServer {
	t.Helper()
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	signer, err := ssh.NewSignerFromKey(priv)
	if err != nil {
		t.Fatal(err)
	}
	s := &testSSHServer{hostKey: signer.PublicKey()}
	s.cfg = &ssh.ServerConfig{
		PasswordCallback: func(c ssh.ConnMetadata, pw []byte) (*ssh.Permissions, error) {
			if password != "" && string(pw) == password {
				return nil, nil
			}
			return nil, io.EOF
		},
		PublicKeyCallback: func(c ssh.ConnMetadata, k ssh.PublicKey) (*ssh.Permissions, error) {
			if authorized != nil && string(k.Marshal()) == string(authorized.Marshal()) {
				return nil, nil
			}
			return nil, io.EOF
		},
	}
	s.cfg.AddHostKey(signer)
	s.ln, err = net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.ln.Close(); s.dropAll() })
	s.probeOK = startEchoServer(t)
	go s.serve()
	return s
}

func (s *testSSHServer) port() int { return s.ln.Addr().(*net.TCPAddr).Port }

func (s *testSSHServer) dropAll() {
	s.mu.Lock()
	for _, c := range s.live {
		c.Close()
	}
	s.live = nil
	s.mu.Unlock()
}

func (s *testSSHServer) serve() {
	for {
		c, err := s.ln.Accept()
		if err != nil {
			return
		}
		go s.handle(c)
	}
}

func (s *testSSHServer) handle(nc net.Conn) {
	// accept 後立刻登記:客戶端在收到認證成功時就會回報已連線,可能早於伺服器端 NewServerConn 返回,
	// 若等握手完成才登記,dropAll() 在這個空窗內會漏掉連線
	s.mu.Lock()
	s.live = append(s.live, nc)
	s.mu.Unlock()
	_, chans, reqs, err := ssh.NewServerConn(nc, s.cfg)
	if err != nil {
		nc.Close()
		return
	}
	s.conns.Add(1)
	go func() {
		for r := range reqs {
			if r.WantReply {
				r.Reply(r.Type == "keepalive@openssh.com", nil)
			}
		}
	}()
	for nch := range chans {
		if nch.ChannelType() != "direct-tcpip" {
			nch.Reject(ssh.UnknownChannelType, "")
			continue
		}
		var p struct {
			Host  string
			Port  uint32
			OHost string
			OPort uint32
		}
		if err := ssh.Unmarshal(nch.ExtraData(), &p); err != nil {
			nch.Reject(ssh.ConnectionFailed, "bad payload")
			continue
		}
		addr := net.JoinHostPort(p.Host, strconv.Itoa(int(p.Port)))
		if s.redirect != nil {
			addr = s.redirect(addr)
		}
		if addr == ipv4ProbeTarget || addr == ipv6ProbeTarget {
			addr = s.probeOK
		}
		go func(nch ssh.NewChannel, addr string) {
			tc, err := net.DialTimeout("tcp", addr, 3*time.Second)
			if err != nil {
				nch.Reject(ssh.ConnectionFailed, err.Error())
				return
			}
			ch, creqs, err := nch.Accept()
			if err != nil {
				tc.Close()
				return
			}
			go ssh.DiscardRequests(creqs)
			// 兩個方向都結束才關閉,避免截斷仍在傳的下行資料
			var wg sync.WaitGroup
			wg.Add(1)
			go func() { defer wg.Done(); io.Copy(ch, tc); ch.CloseWrite() }()
			io.Copy(tc, ch)
			tc.(*net.TCPConn).CloseWrite()
			wg.Wait()
			ch.Close()
			tc.Close()
		}(nch, addr)
	}
}

func startEchoServer(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	return ln.Addr().String()
}

// startTCPDNSServer 以 DNS-over-TCP 回應 A 查詢 → 192.0.2.99(TTL 300),並記錄收到幾個查詢。
func startTCPDNSServer(t *testing.T, count *atomic.Int64) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				var wmu sync.Mutex
				for {
					var h [2]byte
					if _, err := io.ReadFull(c, h[:]); err != nil {
						return
					}
					q := make([]byte, binary.BigEndian.Uint16(h[:]))
					if _, err := io.ReadFull(c, q); err != nil {
						return
					}
					count.Add(1)
					go func() {
						r := fakeAnswer(q)
						out := make([]byte, 2+len(r))
						binary.BigEndian.PutUint16(out, uint16(len(r)))
						copy(out[2:], r)
						wmu.Lock()
						c.Write(out)
						wmu.Unlock()
					}()
				}
			}()
		}
	}()
	return ln.Addr().String()
}

// startUDPGWServer 是 badvpn-udpgw 的 echo 版本:把 payload 原樣以同 conid 回傳。
func startUDPGWServer(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				for {
					var h [2]byte
					if _, err := io.ReadFull(c, h[:]); err != nil {
						return
					}
					f := make([]byte, binary.LittleEndian.Uint16(h[:]))
					if _, err := io.ReadFull(c, f); err != nil {
						return
					}
					if f[0]&udpgwFlagKeepalive != 0 {
						continue
					}
					f[0] &^= udpgwFlagRebind | udpgwFlagDNS
					out := append(append([]byte{}, h[:]...), f...)
					c.Write(out)
				}
			}()
		}
	}()
	return ln.Addr().String()
}

type testPlatform struct {
	t        *testing.T
	mu       sync.Mutex
	states   []int
	msgs     []string
	rejectHK bool
	stateCh  chan int
	ipCh     chan [2]int
}

func newTestPlatform(t *testing.T) *testPlatform {
	return &testPlatform{t: t, stateCh: make(chan int, 64), ipCh: make(chan [2]int, 4)}
}

func (p *testPlatform) Protect(fd int) bool            { return true }
func (p *testPlatform) ResolveHost(host string) string { return "" }
func (p *testPlatform) VerifyHostKey(host string, port int, kt, fp, kb string) bool {
	return !p.rejectHK
}
func (p *testPlatform) OnState(s int, msg string) {
	p.mu.Lock()
	p.states = append(p.states, s)
	p.msgs = append(p.msgs, msg)
	p.mu.Unlock()
	select {
	case p.stateCh <- s:
	default:
	}
}
func (p *testPlatform) Log(level int, msg string) { p.t.Logf("[%d] %s", level, msg) }
func (p *testPlatform) OnServerIP(v4, v6 int)     { p.ipCh <- [2]int{v4, v6} }

func (p *testPlatform) waitState(t *testing.T, want int, d time.Duration) string {
	t.Helper()
	deadline := time.After(d)
	for {
		select {
		case s := <-p.stateCh:
			if s == want {
				p.mu.Lock()
				defer p.mu.Unlock()
				return p.msgs[len(p.msgs)-1]
			}
		case <-deadline:
			t.Fatalf("timeout waiting for state %d (got %v)", want, p.states)
			return ""
		}
	}
}
