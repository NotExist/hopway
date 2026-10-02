package sshvpn

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"time"
)

// badvpn-udpgw 協定:每個 frame = uint16 LE 長度 + header{flags u8, conid u16 LE}
// + 位址(IPv4 4B / IPv6 16B, network order)+ port(BE)+ payload。
const (
	udpgwFlagKeepalive = 1 << 0
	udpgwFlagRebind    = 1 << 1
	udpgwFlagDNS       = 1 << 2
	udpgwFlagIPv6      = 1 << 3

	udpgwMaxConns    = 512
	udpgwIdleTimeout = 2 * time.Minute
)

// udpgwClient 在單一 SSH channel 上多工所有 UDP session。
type udpgwClient struct {
	dial func(ctx context.Context, addr string) (net.Conn, error)
	addr string
	log  *logger

	mu       sync.Mutex
	conn     net.Conn
	wmu      sync.Mutex
	sessions map[uint16]*udpgwSession
	nextID   uint16
}

type udpgwSession struct {
	id    uint16
	dst   netip.AddrPort
	recv  func([]byte)
	fresh bool // 第一個封包要帶 REBIND,避免 server 端還留著同 conid 的舊連線
	done  chan struct{}
	once  sync.Once
}

func (s *udpgwSession) close() { s.once.Do(func() { close(s.done) }) }

func newUDPGWClient(dial func(context.Context, string) (net.Conn, error), addr string, log *logger) *udpgwClient {
	return &udpgwClient{dial: dial, addr: addr, log: log, sessions: make(map[uint16]*udpgwSession)}
}

func (u *udpgwClient) close() {
	u.mu.Lock()
	c := u.conn
	u.conn = nil
	for id, s := range u.sessions {
		s.close()
		delete(u.sessions, id)
	}
	u.mu.Unlock()
	if c != nil {
		c.Close()
	}
}

func (u *udpgwClient) ensureConn(ctx context.Context) (net.Conn, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.conn != nil {
		return u.conn, nil
	}
	c, err := u.dial(ctx, u.addr)
	if err != nil {
		return nil, err
	}
	u.conn = c
	go u.readLoop(c)
	go u.keepaliveLoop(c)
	return c, nil
}

func (u *udpgwClient) dropConn(c net.Conn) {
	u.mu.Lock()
	if u.conn == c {
		u.conn = nil
		for id, s := range u.sessions {
			s.close()
			delete(u.sessions, id)
		}
	}
	u.mu.Unlock()
	c.Close()
}

func (u *udpgwClient) keepaliveLoop(c net.Conn) {
	t := time.NewTicker(10 * time.Second)
	defer t.Stop()
	for range t.C {
		u.mu.Lock()
		alive := u.conn == c
		u.mu.Unlock()
		if !alive {
			return
		}
		if err := u.writeFrame(c, udpgwFlagKeepalive, 0, netip.AddrPortFrom(netip.IPv4Unspecified(), 0), nil); err != nil {
			u.dropConn(c)
			return
		}
	}
}

func (u *udpgwClient) readLoop(c net.Conn) {
	defer u.dropConn(c)
	var lenBuf [2]byte
	buf := make([]byte, 65535)
	for {
		if _, err := io.ReadFull(c, lenBuf[:]); err != nil {
			return
		}
		n := int(binary.LittleEndian.Uint16(lenBuf[:]))
		if _, err := io.ReadFull(c, buf[:n]); err != nil {
			return
		}
		if n < 3 {
			continue
		}
		flags := buf[0]
		id := binary.LittleEndian.Uint16(buf[1:3])
		off := 3
		if flags&udpgwFlagIPv6 != 0 {
			off += 16 + 2
		} else {
			off += 4 + 2
		}
		if n < off {
			continue
		}
		u.mu.Lock()
		s := u.sessions[id]
		u.mu.Unlock()
		if s != nil {
			s.recv(buf[off:n])
		}
	}
}

func (u *udpgwClient) writeFrame(c net.Conn, flags byte, id uint16, dst netip.AddrPort, payload []byte) error {
	alen := 4
	if dst.Addr().Is6() && !dst.Addr().Is4In6() {
		flags |= udpgwFlagIPv6
		alen = 16
	}
	size := 3 + alen + 2 + len(payload)
	if size > 65535 {
		return errors.New("udp payload too large")
	}
	frame := make([]byte, 2+size)
	binary.LittleEndian.PutUint16(frame, uint16(size))
	frame[2] = flags
	binary.LittleEndian.PutUint16(frame[3:], id)
	if alen == 16 {
		a := dst.Addr().As16()
		copy(frame[5:], a[:])
	} else {
		a := dst.Addr().Unmap().As4()
		copy(frame[5:], a[:])
	}
	binary.BigEndian.PutUint16(frame[5+alen:], dst.Port())
	copy(frame[7+alen:], payload)
	u.wmu.Lock()
	defer u.wmu.Unlock()
	_ = c.SetWriteDeadline(time.Now().Add(10 * time.Second))
	_, err := c.Write(frame)
	return err
}

// open 建立新 session;recv 會在 udpgw 讀取 goroutine 中被呼叫,不可阻塞太久。
func (u *udpgwClient) open(ctx context.Context, dst netip.AddrPort, recv func([]byte)) (*udpgwSession, error) {
	if _, err := u.ensureConn(ctx); err != nil {
		return nil, err
	}
	u.mu.Lock()
	defer u.mu.Unlock()
	if len(u.sessions) >= udpgwMaxConns {
		return nil, errors.New("udpgw: too many sessions")
	}
	for {
		u.nextID++
		if u.nextID == 0 {
			continue
		}
		if _, used := u.sessions[u.nextID]; !used {
			break
		}
	}
	s := &udpgwSession{id: u.nextID, dst: dst, recv: recv, fresh: true, done: make(chan struct{})}
	u.sessions[s.id] = s
	return s, nil
}

func (u *udpgwClient) send(s *udpgwSession, payload []byte) error {
	u.mu.Lock()
	c := u.conn
	_, alive := u.sessions[s.id]
	u.mu.Unlock()
	if c == nil || !alive {
		return net.ErrClosed
	}
	var flags byte
	if s.fresh {
		flags |= udpgwFlagRebind
		s.fresh = false
	}
	if err := u.writeFrame(c, flags, s.id, s.dst, payload); err != nil {
		u.dropConn(c)
		return err
	}
	return nil
}

func (u *udpgwClient) release(s *udpgwSession) {
	u.mu.Lock()
	if u.sessions[s.id] == s {
		delete(u.sessions, s.id)
	}
	u.mu.Unlock()
	s.close()
}
