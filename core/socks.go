package core

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"time"
)

// socksServer 是精簡的 SOCKS5(RFC 1928,無認證,僅 CONNECT),
// 讓其他 app / 熱點裝置也能直接走同一組 SSH 連線。
type socksServer struct {
	ln   net.Listener
	pool dialer
	log  *logger
	st   *counters
}

func startSocks(addr string, pool dialer, st *counters, log *logger) (*socksServer, error) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	s := &socksServer{ln: ln, pool: pool, log: log, st: st}
	go s.serve()
	log.infof("socks5 proxy listening on %s", ln.Addr())
	return s, nil
}

func (s *socksServer) close() { s.ln.Close() }

func (s *socksServer) serve() {
	for {
		c, err := s.ln.Accept()
		if err != nil {
			return
		}
		go s.handle(c)
	}
}

func (s *socksServer) handle(c net.Conn) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(15 * time.Second))
	addr, err := socksHandshake(c)
	if err != nil {
		s.log.debugf("socks: %v", err)
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
	rc, err := s.pool.Dial(ctx, addr)
	cancel()
	if err != nil {
		_, _ = c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0}) // connection refused
		return
	}
	defer rc.Close()
	if _, err := c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	_ = c.SetDeadline(time.Time{})
	s.st.tcpActive.Add(1)
	s.st.tcpTotal.Add(1)
	defer s.st.tcpActive.Add(-1)
	relay(c, rc, &s.st.tx, &s.st.rx)
}

func socksHandshake(c net.Conn) (string, error) {
	var b [262]byte
	if _, err := io.ReadFull(c, b[:2]); err != nil {
		return "", err
	}
	if b[0] != 5 {
		return "", errors.New("not socks5")
	}
	if _, err := io.ReadFull(c, b[:b[1]]); err != nil {
		return "", err
	}
	if _, err := c.Write([]byte{5, 0}); err != nil {
		return "", err
	}
	if _, err := io.ReadFull(c, b[:4]); err != nil {
		return "", err
	}
	if b[1] != 1 { // 只支援 CONNECT
		_, _ = c.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return "", errors.New("unsupported socks command")
	}
	var host string
	switch b[3] {
	case 1:
		if _, err := io.ReadFull(c, b[:4]); err != nil {
			return "", err
		}
		host = net.IP(b[:4]).String()
	case 4:
		if _, err := io.ReadFull(c, b[:16]); err != nil {
			return "", err
		}
		host = net.IP(b[:16]).String()
	case 3:
		if _, err := io.ReadFull(c, b[:1]); err != nil {
			return "", err
		}
		n := int(b[0])
		if _, err := io.ReadFull(c, b[:n]); err != nil {
			return "", err
		}
		host = string(b[:n])
	default:
		return "", errors.New("bad socks address type")
	}
	if _, err := io.ReadFull(c, b[:2]); err != nil {
		return "", err
	}
	port := binary.BigEndian.Uint16(b[:2])
	return net.JoinHostPort(host, strconv.Itoa(int(port))), nil
}
