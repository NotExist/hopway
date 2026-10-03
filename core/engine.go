// Package sshvpn 是 SSH Tunnel VPN 的資料平面:
// TUN fd → gVisor netstack → SSH direct-tcpip channel,整條路徑都在 Go 內完成,
// 不經過本機 SOCKS loopback,也不經過 JVM。
package sshvpn

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"github.com/xjasonlyu/tun2socks/v2/core/option"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

type engine struct {
	cfg    *Config
	plat   Platform
	log    *logger
	cancel context.CancelFunc
	pool   *sshPool
	dns    *dnsClient
	udpgw  *udpgwClient
	socks  *socksServer
	stack  *stack.Stack
	dev    interface{ Close() }
	st     counters
	start  time.Time

	stateMu sync.Mutex
	state   int
	msg     string
}

var (
	mu  sync.Mutex
	cur *engine
)

// Start 以 VpnService 建立的 TUN fd 啟動引擎。fd 的所有權轉交給 Go
// (Kotlin 端應使用 ParcelFileDescriptor.detachFd())。
func Start(tunFd int, configJSON string, p Platform) error {
	cfg, err := parseConfig(configJSON)
	if err != nil {
		return err
	}
	mu.Lock()
	defer mu.Unlock()
	if cur != nil {
		cur.stop()
		cur = nil
	}
	e := &engine{cfg: cfg, plat: p, log: &logger{p: p}, start: time.Now()}
	e.log.level.Store(int32(cfg.LogLevel))
	if err := e.run(tunFd); err != nil {
		e.stop()
		return err
	}
	cur = e
	return nil
}

func (e *engine) run(tunFd int) error {
	ctx, cancel := context.WithCancel(context.Background())
	e.cancel = cancel

	vdns := netip.Addr{}
	if e.cfg.VirtualDNS != "" {
		a, err := netip.ParseAddr(e.cfg.VirtualDNS)
		if err != nil {
			return fmt.Errorf("invalid virtual dns: %w", err)
		}
		vdns = a
	}

	e.pool = newSSHPool(e.cfg, e.plat, e.log, e.setState)
	e.dns = newDNSClient(e.pool.Dial, e.cfg.DNSUpstream, e.cfg.DNSCache, e.log)
	if e.cfg.UDPGW != "" {
		e.udpgw = newUDPGWClient(e.pool.Dial, e.cfg.UDPGW, e.log)
	}
	h := &handler{ctx: ctx, pool: e.pool, dns: e.dns, udpgw: e.udpgw, vdns: vdns, log: e.log, st: &e.st}

	if tunFd >= 0 {
		dev, err := fdbased.Open(strconv.Itoa(tunFd), uint32(e.cfg.MTU), 0)
		if err != nil {
			return fmt.Errorf("open tun: %w", err)
		}
		e.dev = dev
		s, err := core.CreateStack(&core.Config{
			LinkEndpoint:     dev,
			TransportHandler: h,
			Options: []option.Option{
				// SSH 本身已有可靠傳輸,netstack 端放大 buffer 換吞吐
				option.WithTCPReceiveBufferSize(4 << 20),
				option.WithTCPSendBufferSize(4 << 20),
			},
		})
		if err != nil {
			return fmt.Errorf("create stack: %w", err)
		}
		e.stack = s
	}

	if e.cfg.SocksListen != "" {
		s, err := startSocks(e.cfg.SocksListen, e.pool, &e.st, e.log)
		if err != nil {
			e.log.warnf("socks5 proxy disabled: %v", err)
		} else {
			e.socks = s
		}
	}
	e.log.infof("engine started (mtu=%d, connections=%d, dns=%s, udpgw=%q)",
		e.cfg.MTU, e.cfg.Connections, e.cfg.DNSUpstream, e.cfg.UDPGW)
	return nil
}

func (e *engine) setState(s int, msg string) {
	e.stateMu.Lock()
	e.state, e.msg = s, msg
	e.stateMu.Unlock()
	if e.plat != nil {
		e.plat.OnState(s, msg)
	}
}

func (e *engine) stop() {
	if e.cancel != nil {
		e.cancel()
	}
	if e.socks != nil {
		e.socks.close()
	}
	if e.stack != nil {
		e.stack.Close()
		e.stack.Wait()
	}
	if e.dev != nil {
		e.dev.Close()
	}
	if e.dns != nil {
		e.dns.close()
	}
	if e.udpgw != nil {
		e.udpgw.close()
	}
	if e.pool != nil {
		e.pool.close()
	}
	e.log.infof("engine stopped")
}

// Stop 停止引擎並關閉 TUN fd。
func Stop() {
	mu.Lock()
	e := cur
	cur = nil
	mu.Unlock()
	if e != nil {
		e.stop()
		e.setState(StateStopped, "")
	}
}

// IsRunning 回報引擎是否在執行。
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return cur != nil
}

// NetworkChanged 由平台在預設網路切換時呼叫,立即重建 SSH 連線。
func NetworkChanged() {
	mu.Lock()
	e := cur
	mu.Unlock()
	if e != nil {
		e.log.infof("network changed, reconnecting")
		e.pool.reconnectAll()
		e.dns.close()
	}
}

// SetLogLevel 動態調整日誌等級。
func SetLogLevel(level int) {
	mu.Lock()
	defer mu.Unlock()
	if cur != nil {
		cur.log.level.Store(int32(level))
	}
}

// Stats 是統計快照(gomobile 會產生對應 Java class)。
type Stats struct {
	State          int
	Message        string
	TxBytes        int64
	RxBytes        int64
	TCPActive      int64
	TCPTotal       int64
	UDPActive      int64
	DialFailures   int64
	DNSQueries     int64
	DNSCacheHits   int64
	RTTMillis      int64
	SSHLive        int64
	SSHTotal       int64
	UptimeMillis   int64
	ServerVersion  string
}

// GetStats 回傳目前統計;引擎未執行時回傳 nil。
func GetStats() *Stats {
	mu.Lock()
	e := cur
	mu.Unlock()
	if e == nil {
		return nil
	}
	e.stateMu.Lock()
	s := &Stats{State: e.state, Message: e.msg}
	e.stateMu.Unlock()
	s.TxBytes = e.st.tx.Load()
	s.RxBytes = e.st.rx.Load()
	s.TCPActive = e.st.tcpActive.Load()
	s.TCPTotal = e.st.tcpTotal.Load()
	s.UDPActive = e.st.udpActive.Load()
	s.DialFailures = e.st.dialFailures.Load()
	s.DNSQueries = e.dns.queries.Load()
	s.DNSCacheHits = e.dns.hits.Load()
	s.RTTMillis = e.pool.rtt().Milliseconds()
	s.SSHLive = int64(e.pool.liveCount())
	s.SSHTotal = int64(len(e.pool.slots))
	s.UptimeMillis = time.Since(e.start).Milliseconds()
	if v, ok := e.pool.serverVersion.Load().(string); ok {
		s.ServerVersion = v
	}
	return s
}

// TestResult 是連線測試結果。
type TestResult struct {
	ServerVersion string
	HandshakeMs   int64
	RTTMillis     int64
	HostKeyType   string
	Fingerprint   string
	UDPGWOK       bool
	UDPGWError    string
	// ExitInfo 為經通道 GET ExitCheckURL 的回應 body(JSON),ExitError 為失敗原因
	ExitInfo  string
	ExitError string
}

// TestConnection 以給定設定做一次 SSH 握手 + 認證 + keepalive,不建立 VPN。
func TestConnection(configJSON string, p Platform) (*TestResult, error) {
	cfg, err := parseConfig(configJSON)
	if err != nil {
		return nil, err
	}
	res := &TestResult{}
	wrap := &hostKeyCapture{Platform: p, res: res}
	log := &logger{p: p}
	log.level.Store(LogInfo)
	ctx, cancel := context.WithTimeout(context.Background(), cfg.connectTimeout()+5*time.Second)
	defer cancel()
	start := time.Now()
	client, err := dialSSH(ctx, cfg, wrap, log)
	if err != nil {
		var f *errFatal
		if errors.As(err, &f) {
			return nil, f.err
		}
		return nil, err
	}
	defer client.Close()
	res.HandshakeMs = time.Since(start).Milliseconds()
	res.ServerVersion = string(client.ServerVersion())
	t := time.Now()
	if _, _, err := client.SendRequest("keepalive@openssh.com", true, nil); err == nil {
		res.RTTMillis = time.Since(t).Milliseconds()
	}
	if cfg.UDPGW != "" {
		c, err := client.DialContext(ctx, "tcp", cfg.UDPGW)
		if err != nil {
			res.UDPGWError = err.Error()
		} else {
			res.UDPGWOK = true
			c.Close()
		}
	}
	if cfg.ExitCheckURL != "" {
		dial := func(ctx context.Context, addr string) (net.Conn, error) { return client.DialContext(ctx, "tcp", addr) }
		if body, err := httpGet(ctx, dial, cfg.ExitCheckURL); err != nil {
			res.ExitError = err.Error()
		} else {
			res.ExitInfo = body
		}
	}
	return res, nil
}

type hostKeyCapture struct {
	Platform
	res *TestResult
}

func (h *hostKeyCapture) VerifyHostKey(host string, port int, keyType, fp, keyB64 string) bool {
	h.res.HostKeyType, h.res.Fingerprint = keyType, fp
	if h.Platform == nil {
		return true
	}
	return h.Platform.VerifyHostKey(host, port, keyType, fp, keyB64)
}
