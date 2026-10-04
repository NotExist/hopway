package core

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/crypto/ssh"
)

// errFatal 表示重試無意義的錯誤(認證失敗、主機金鑰不符),pool 會停止重連。
type errFatal struct{ err error }

func (e *errFatal) Error() string { return e.err.Error() }
func (e *errFatal) Unwrap() error { return e.err }

func isFatal(err error) bool {
	var f *errFatal
	return errors.As(err, &f)
}

type sshConn struct {
	client *ssh.Client
	active atomic.Int32
	rtt    atomic.Int64 // 最近一次 keepalive 往返(ns)
}

// sshPool 維持 N 條 SSH 連線,把 direct-tcpip channel 分配到負載最輕的一條。
type sshPool struct {
	cfg  *Config
	plat Platform
	log  *logger

	ctx    context.Context
	cancel context.CancelFunc

	mu      sync.Mutex
	slots   []*sshConn
	changed chan struct{} // slots 變動時 close 並換新,作為廣播
	everUp  bool

	onState func(state int, msg string)
	wg      sync.WaitGroup

	kick []chan struct{} // 每個 slot 一個:要求立即重連

	serverVersion atomic.Value // string
}

func newSSHPool(cfg *Config, plat Platform, log *logger, onState func(int, string)) *sshPool {
	ctx, cancel := context.WithCancel(context.Background())
	p := &sshPool{
		cfg: cfg, plat: plat, log: log,
		ctx: ctx, cancel: cancel,
		slots:   make([]*sshConn, cfg.Connections),
		changed: make(chan struct{}),
		onState: onState,
	}
	for i := range p.slots {
		k := make(chan struct{}, 1)
		p.kick = append(p.kick, k)
		p.wg.Add(1)
		go p.maintain(i, k)
	}
	return p
}

func (p *sshPool) close() {
	p.cancel()
	p.mu.Lock()
	for _, c := range p.slots {
		if c != nil {
			c.client.Close()
		}
	}
	p.mu.Unlock()
	p.wg.Wait()
}

// reconnectAll 在網路切換時呼叫:舊連線大概率已死,與其等 keepalive 逾時不如立即重建。
func (p *sshPool) reconnectAll() {
	for _, k := range p.kick {
		select {
		case k <- struct{}{}:
		default:
		}
	}
}

func (p *sshPool) setSlot(i int, c *sshConn) {
	p.mu.Lock()
	p.slots[i] = c
	close(p.changed)
	p.changed = make(chan struct{})
	live := 0
	for _, s := range p.slots {
		if s != nil {
			live++
		}
	}
	first := c != nil && !p.everUp
	if c != nil {
		p.everUp = true
	}
	everUp := p.everUp
	p.mu.Unlock()

	switch {
	case c != nil && (live == 1 || first):
		p.onState(StateConnected, fmt.Sprintf("%d/%d", live, len(p.slots)))
	case c == nil && live == 0 && everUp && p.ctx.Err() == nil:
		p.onState(StateReconnecting, "")
	}
}

func (p *sshPool) liveCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	n := 0
	for _, s := range p.slots {
		if s != nil {
			n++
		}
	}
	return n
}

// rtt 回傳所有存活連線中最小的 keepalive RTT。
func (p *sshPool) rtt() time.Duration {
	p.mu.Lock()
	defer p.mu.Unlock()
	var best int64
	for _, s := range p.slots {
		if s == nil {
			continue
		}
		if r := s.rtt.Load(); r > 0 && (best == 0 || r < best) {
			best = r
		}
	}
	return time.Duration(best)
}

func (p *sshPool) pick() (*sshConn, <-chan struct{}) {
	p.mu.Lock()
	defer p.mu.Unlock()
	var best *sshConn
	for _, s := range p.slots {
		if s != nil && (best == nil || s.active.Load() < best.active.Load()) {
			best = s
		}
	}
	return best, p.changed
}

// Dial 透過 SSH 開 direct-tcpip channel;若目前沒有存活連線,等待重連直到 ctx 逾時。
func (p *sshPool) Dial(ctx context.Context, addr string) (net.Conn, error) {
	for {
		c, changed := p.pick()
		if c != nil {
			c.active.Add(1)
			conn, err := c.client.DialContext(ctx, "tcp", addr)
			if err != nil {
				c.active.Add(-1)
				var oce *ssh.OpenChannelError
				if errors.As(err, &oce) || ctx.Err() != nil {
					return nil, err // 遠端拒絕(連不到目標),不是 SSH 連線問題
				}
				// SSH 連線本身壞了:讓 maintain 重連,這邊稍等再試
				p.log.debugf("dial %s via ssh failed: %v", addr, err)
				select {
				case <-changed:
				case <-time.After(200 * time.Millisecond):
				case <-ctx.Done():
					return nil, ctx.Err()
				}
				continue
			}
			return &trackedConn{Conn: conn, owner: c}, nil
		}
		select {
		case <-changed:
		case <-ctx.Done():
			return nil, fmt.Errorf("no ssh connection available: %w", ctx.Err())
		case <-p.ctx.Done():
			return nil, net.ErrClosed
		}
	}
}

type trackedConn struct {
	net.Conn
	owner *sshConn
	once  sync.Once
}

func (c *trackedConn) Close() error {
	c.once.Do(func() { c.owner.active.Add(-1) })
	return c.Conn.Close()
}

func (c *trackedConn) CloseWrite() error {
	if cw, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return cw.CloseWrite()
	}
	return nil
}

func (p *sshPool) maintain(i int, kick <-chan struct{}) {
	defer p.wg.Done()
	backoff := time.Second
	for p.ctx.Err() == nil {
		if i == 0 && !p.isEverUp() {
			p.onState(StateConnecting, "")
		}
		client, err := dialSSH(p.ctx, p.cfg, p.plat, p.log)
		if err != nil {
			if p.ctx.Err() != nil {
				return
			}
			if isFatal(err) {
				p.log.errorf("ssh: %v", err)
				p.onState(StateError, err.Error())
				return
			}
			p.log.warnf("ssh[%d] connect failed: %v (retry in %s)", i, err, backoff)
			select {
			case <-time.After(backoff):
			case <-kick:
			case <-p.ctx.Done():
				return
			}
			backoff = min(backoff*2, 30*time.Second)
			continue
		}
		backoff = time.Second
		p.serverVersion.Store(string(client.ServerVersion()))
		c := &sshConn{client: client}
		p.log.infof("ssh[%d] connected to %s (%s)", i, client.RemoteAddr(), client.ServerVersion())
		p.setSlot(i, c)

		dead := make(chan struct{})
		go func() { client.Wait(); close(dead) }()
		reason := p.keepalive(c, dead, kick)
		client.Close()
		p.setSlot(i, nil)
		if p.ctx.Err() != nil {
			return
		}
		p.log.warnf("ssh[%d] disconnected: %s", i, reason)
	}
}

func (p *sshPool) isEverUp() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.everUp
}

// keepalive 定期送 keepalive@openssh.com,連續失敗或逾時即判定斷線。
func (p *sshPool) keepalive(c *sshConn, dead <-chan struct{}, kick <-chan struct{}) string {
	interval := p.cfg.keepalive()
	t := time.NewTicker(interval)
	defer t.Stop()
	probe := func() bool {
		res := make(chan error, 1)
		start := time.Now()
		go func() {
			_, _, err := c.client.SendRequest("keepalive@openssh.com", true, nil)
			res <- err
		}()
		select {
		case err := <-res:
			if err != nil {
				return false
			}
			c.rtt.Store(int64(time.Since(start)))
			return true
		case <-time.After(max(interval, 5*time.Second)):
			return false
		case <-dead:
			return false
		}
	}
	go probe() // 立刻量一次 RTT
	for {
		select {
		case <-p.ctx.Done():
			return "stopped"
		case <-dead:
			return "connection closed"
		case <-kick:
			return "network changed"
		case <-t.C:
			if !probe() {
				return "keepalive timeout"
			}
		}
	}
}

func dialSSH(ctx context.Context, cfg *Config, plat Platform, log *logger) (*ssh.Client, error) {
	auth, err := authMethods(cfg)
	if err != nil {
		return nil, &errFatal{err}
	}
	var hostKeyErr error
	sc := &ssh.ClientConfig{
		User:          cfg.User,
		Auth:          auth,
		Timeout:       cfg.connectTimeout(),
		ClientVersion: "SSH-2.0-Hopway",
		HostKeyCallback: func(hostname string, remote net.Addr, key ssh.PublicKey) error {
			fp := ssh.FingerprintSHA256(key)
			if plat != nil && !plat.VerifyHostKey(cfg.Host, cfg.Port, key.Type(), fp,
				base64.StdEncoding.EncodeToString(key.Marshal())) {
				hostKeyErr = fmt.Errorf("host key verification failed (%s %s)", key.Type(), fp)
				return hostKeyErr
			}
			return nil
		},
	}
	sc.SetDefaults()
	// 偏好 AEAD 演算法:arm64 有 AES 指令時 aes-gcm 最快,否則 chacha20 也很快;
	// 都省掉額外 MAC 計算。
	sc.Ciphers = append([]string{
		"aes128-gcm@openssh.com", "chacha20-poly1305@openssh.com", "aes256-gcm@openssh.com",
	}, sc.Ciphers...)

	conn, err := dialTCP(ctx, cfg, plat, log)
	if err != nil {
		return nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(cfg.connectTimeout()))
	addr := net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port))
	cc, chans, reqs, err := ssh.NewClientConn(conn, addr, sc)
	if err != nil {
		conn.Close()
		if hostKeyErr != nil {
			return nil, &errFatal{hostKeyErr}
		}
		if strings.Contains(err.Error(), "unable to authenticate") {
			return nil, &errFatal{fmt.Errorf("authentication failed: %w", err)}
		}
		return nil, err
	}
	_ = conn.SetDeadline(time.Time{})
	return ssh.NewClient(cc, chans, reqs), nil
}

func dialTCP(ctx context.Context, cfg *Config, plat Platform, log *logger) (net.Conn, error) {
	var ips []string
	if net.ParseIP(cfg.Host) != nil {
		ips = []string{cfg.Host}
	} else if plat != nil {
		if r := plat.ResolveHost(cfg.Host); r != "" {
			ips = strings.Split(r, ",")
		}
	}
	if len(ips) == 0 {
		ips = []string{cfg.Host} // 交給 Go resolver
	}
	d := &net.Dialer{
		Timeout:   cfg.connectTimeout(),
		KeepAlive: 30 * time.Second,
		Control: func(network, address string, rc syscall.RawConn) error {
			if plat == nil {
				return nil
			}
			var ok bool
			if err := rc.Control(func(fd uintptr) { ok = plat.Protect(int(fd)) }); err != nil {
				return err
			}
			if !ok {
				return errors.New("VpnService.protect failed")
			}
			return nil
		},
	}
	if len(ips) == 1 && net.ParseIP(strings.TrimSpace(ips[0])) == nil {
		// 平台端解析失敗,交給 Go resolver(含 Happy Eyeballs)
		c, err := d.DialContext(ctx, "tcp", net.JoinHostPort(ips[0], strconv.Itoa(cfg.Port)))
		if err != nil {
			return nil, err
		}
		setNoDelay(c)
		return c, nil
	}
	c, err := dialHappyEyeballs(ctx, d, ips, strconv.Itoa(cfg.Port))
	if err != nil {
		log.debugf("tcp connect %s failed: %v", cfg.Host, err)
		return nil, err
	}
	setNoDelay(c)
	return c, nil
}

func setNoDelay(c net.Conn) {
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
}

func authMethods(cfg *Config) ([]ssh.AuthMethod, error) {
	var m []ssh.AuthMethod
	if strings.TrimSpace(cfg.PrivateKey) != "" {
		signer, err := parseSigner(cfg.PrivateKey, cfg.Passphrase)
		if err != nil {
			return nil, err
		}
		m = append(m, ssh.PublicKeys(signer))
	}
	if cfg.Password != "" {
		pw := cfg.Password
		m = append(m, ssh.Password(pw),
			ssh.KeyboardInteractive(func(name, instr string, qs []string, echos []bool) ([]string, error) {
				ans := make([]string, len(qs))
				for i := range qs {
					ans[i] = pw
				}
				return ans, nil
			}))
	}
	if len(m) == 0 {
		m = append(m, ssh.KeyboardInteractive(func(string, string, []string, []bool) ([]string, error) {
			return nil, nil
		}))
	}
	return m, nil
}

func parseSigner(key, passphrase string) (ssh.Signer, error) {
	b := []byte(key)
	var signer ssh.Signer
	var err error
	if passphrase != "" {
		signer, err = ssh.ParsePrivateKeyWithPassphrase(b, []byte(passphrase))
	} else {
		signer, err = ssh.ParsePrivateKey(b)
		var pe *ssh.PassphraseMissingError
		if errors.As(err, &pe) {
			return nil, errors.New("private key is encrypted: passphrase required")
		}
	}
	if err != nil {
		return nil, fmt.Errorf("invalid private key: %w", err)
	}
	return signer, nil
}
