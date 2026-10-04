package core

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

// SOCKS5 出口(RFC 1928,帳密認證 RFC 1929)。每條連線各自連到代理再 CONNECT;
// 目的地以名稱送出時由代理端解析。SOCKS5 本身不加密。

// socksReplyError 是代理對 CONNECT 的拒絕(REP != 0)。
type socksReplyError struct{ code byte }

var socksReplyText = map[byte]string{
	1: "general failure", 2: "not allowed by ruleset", 3: "network unreachable", 4: "host unreachable",
	5: "connection refused", 6: "TTL expired", 7: "command not supported", 8: "address type not supported",
}

func (e *socksReplyError) Error() string {
	if t, ok := socksReplyText[e.code]; ok {
		return "socks5: " + t
	}
	return fmt.Sprintf("socks5: reply %d", e.code)
}

// definite 回報這是否明確代表「出口連不到這個目的地/協定」(用於 IPv4/IPv6 能力判定)。
// general failure(1)可能是暫時性問題,不算。
func (e *socksReplyError) definite() bool {
	switch e.code {
	case 2, 3, 4, 5, 8:
		return true
	}
	return false
}

// socksDialer 不帶狀態:連到代理 → 協商/認證 → CONNECT。
type socksDialer struct {
	cfg  *Config
	plat Platform
	log  *logger
}

func (d *socksDialer) Dial(ctx context.Context, addr string) (net.Conn, error) {
	c, err := d.greet(ctx)
	if err != nil {
		return nil, err
	}
	if dl, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(dl)
	}
	if err := socksConnect(c, addr); err != nil {
		c.Close()
		return nil, err
	}
	_ = c.SetDeadline(time.Time{})
	return c, nil
}

// greet 連到代理並完成方法協商與認證,回傳可以送 CONNECT 的連線(健康檢查也用它)。
func (d *socksDialer) greet(ctx context.Context) (net.Conn, error) {
	c, err := dialTCP(ctx, d.cfg, d.plat, d.log)
	if err != nil {
		return nil, err
	}
	dl := time.Now().Add(d.cfg.connectTimeout())
	if cdl, ok := ctx.Deadline(); ok && cdl.Before(dl) {
		dl = cdl
	}
	_ = c.SetDeadline(dl)
	if err := socksGreet(c, d.cfg.User, d.cfg.Password); err != nil {
		c.Close()
		return nil, err
	}
	_ = c.SetDeadline(time.Time{})
	return c, nil
}

func socksGreet(c net.Conn, user, pass string) error {
	methods := []byte{0x00}
	if user != "" {
		methods = []byte{0x02, 0x00}
	}
	if _, err := c.Write(append([]byte{5, byte(len(methods))}, methods...)); err != nil {
		return err
	}
	var b [2]byte
	if _, err := io.ReadFull(c, b[:]); err != nil {
		return fmt.Errorf("socks5 handshake: %w", err)
	}
	if b[0] != 5 {
		return errors.New("not a SOCKS5 proxy")
	}
	switch b[1] {
	case 0x00:
		return nil
	case 0x02:
		if user == "" {
			return &errFatal{errors.New("authentication required: proxy asks for username/password")}
		}
		if len(user) > 255 || len(pass) > 255 {
			return &errFatal{errors.New("username/password too long")}
		}
		msg := append([]byte{1, byte(len(user))}, user...)
		msg = append(append(msg, byte(len(pass))), pass...)
		if _, err := c.Write(msg); err != nil {
			return err
		}
		if _, err := io.ReadFull(c, b[:]); err != nil {
			return fmt.Errorf("socks5 auth: %w", err)
		}
		if b[1] != 0 {
			return &errFatal{errors.New("authentication failed: proxy rejected username/password")}
		}
		return nil
	case 0xFF:
		if user == "" {
			return &errFatal{errors.New("authentication required by proxy")}
		}
		return &errFatal{errors.New("proxy rejected all authentication methods")}
	default:
		return fmt.Errorf("socks5: unexpected method %d", b[1])
	}
}

func socksConnect(c net.Conn, addr string) error {
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return err
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return err
	}
	req := []byte{5, 1, 0}
	if ip, err := netip.ParseAddr(host); err == nil {
		if ip.Unmap().Is4() {
			a := ip.Unmap().As4()
			req = append(append(req, 1), a[:]...)
		} else {
			a := ip.As16()
			req = append(append(req, 4), a[:]...)
		}
	} else {
		if len(host) > 255 {
			return errors.New("socks5: host name too long")
		}
		req = append(append(req, 3, byte(len(host))), host...)
	}
	req = binary.BigEndian.AppendUint16(req, uint16(port))
	if _, err := c.Write(req); err != nil {
		return err
	}
	var h [4]byte
	if _, err := io.ReadFull(c, h[:]); err != nil {
		return fmt.Errorf("socks5 connect: %w", err)
	}
	if h[1] != 0 {
		return &socksReplyError{code: h[1]}
	}
	// 略過 BND.ADDR / BND.PORT
	var skip int
	switch h[3] {
	case 1:
		skip = 4 + 2
	case 4:
		skip = 16 + 2
	case 3:
		var l [1]byte
		if _, err := io.ReadFull(c, l[:]); err != nil {
			return err
		}
		skip = int(l[0]) + 2
	default:
		return fmt.Errorf("socks5: bad address type %d in reply", h[3])
	}
	_, err = io.CopyN(io.Discard, c, int64(skip))
	return err
}

// socksOutbound:SOCKS5 出口。代理沒有長連線,「已連線」代表代理可達且認證成功;
// 每個 keepalive 間隔做一次健康檢查(連線+協商),同時量 RTT。
type socksOutbound struct {
	socksDialer
	onState func(int, string)
	ctx     context.Context
	cancel  context.CancelFunc
	live    atomic.Bool
	rttNs   atomic.Int64
	kick    chan struct{}
	wg      sync.WaitGroup
}

func newSocksOutbound(cfg *Config, plat Platform, log *logger, onState func(int, string)) *socksOutbound {
	ctx, cancel := context.WithCancel(context.Background())
	s := &socksOutbound{
		socksDialer: socksDialer{cfg: cfg, plat: plat, log: log},
		onState:     onState, ctx: ctx, cancel: cancel, kick: make(chan struct{}, 1),
	}
	s.wg.Add(1)
	go s.health()
	return s
}

func (s *socksOutbound) health() {
	defer s.wg.Done()
	backoff := time.Second
	s.onState(StateConnecting, "")
	for s.ctx.Err() == nil {
		start := time.Now()
		ctx, cancel := context.WithTimeout(s.ctx, s.cfg.connectTimeout())
		c, err := s.greet(ctx)
		cancel()
		wait := s.cfg.keepalive()
		switch {
		case err == nil:
			c.Close()
			s.rttNs.Store(int64(time.Since(start)))
			backoff = time.Second
			if !s.live.Swap(true) {
				s.log.infof("socks5 proxy %s reachable", net.JoinHostPort(s.cfg.Host, strconv.Itoa(s.cfg.Port)))
				s.onState(StateConnected, "1/1")
			}
		case s.ctx.Err() != nil:
			return
		case isFatal(err):
			s.log.errorf("socks5: %v", err)
			s.onState(StateError, err.Error())
			return
		default:
			s.log.warnf("socks5 proxy unreachable: %v (retry in %s)", err, backoff)
			if s.live.Swap(false) {
				s.onState(StateReconnecting, "")
			}
			wait = backoff
			backoff = min(backoff*2, 30*time.Second)
		}
		select {
		case <-time.After(wait):
		case <-s.kick:
		case <-s.ctx.Done():
			return
		}
	}
}

func (s *socksOutbound) close() {
	s.cancel()
	s.wg.Wait()
}

func (s *socksOutbound) reconnectAll() {
	select {
	case s.kick <- struct{}{}:
	default:
	}
}

func (s *socksOutbound) rtt() time.Duration { return time.Duration(s.rttNs.Load()) }

func (s *socksOutbound) liveCount() int {
	if s.live.Load() {
		return 1
	}
	return 0
}

func (s *socksOutbound) total() int      { return 1 }
func (s *socksOutbound) version() string { return "SOCKS5" }
