package core

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

// 不假設 IPv4 一定存在:伺服器的 IPv4 / IPv6 對外能力都要實測,兩者對稱處理。
//
// 能力狀態:famUnknown(未測或無法判定)/ famYes / famNo。
const (
	famUnknown = 0
	famYes     = 1
	famNo      = 2
)

// 探測目的地:Cloudflare DNS 的 HTTPS port,兩個協定各一個字面位址(不依賴 DNS),長期穩定可達。
const (
	ipv4ProbeTarget = "1.1.1.1:443"
	ipv6ProbeTarget = "[2606:4700:4700::1111]:443"
)

// probeFamily 經 dial(SSH direct-tcpip)連 target,判斷伺服器能否連到該協定的目的地。
// 只有伺服器明確回報「連不上」(OpenChannelError)才判定為 famNo;逾時等暫時性錯誤視為 famUnknown。
func probeFamily(ctx context.Context, dial func(context.Context, string) (net.Conn, error), target string) int32 {
	pctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	c, err := dial(pctx, target)
	switch {
	case err == nil:
		c.Close()
		return famYes
	case errors.As(err, new(*ssh.OpenChannelError)):
		return famNo
	default:
		return famUnknown
	}
}

// probeBoth 同時探測兩個協定。
func probeBoth(ctx context.Context, dial func(context.Context, string) (net.Conn, error)) (v4, v6 int32) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); v4 = probeFamily(ctx, dial, ipv4ProbeTarget) }()
	go func() { defer wg.Done(); v6 = probeFamily(ctx, dial, ipv6ProbeTarget) }()
	wg.Wait()
	return
}

// probeServer 在引擎 SSH 連上後探測一次,結果用於:直接拒絕伺服器不支援協定的連線、
// 過濾 DNS 的 A/AAAA、挑選 DNS 上游,並回報平台端。
func (e *engine) probeServer(ctx context.Context, h *handler) {
	v4, v6 := probeBoth(ctx, e.pool.Dial)
	if ctx.Err() != nil {
		return // 引擎已停止,不再回報
	}
	if v4 != famUnknown {
		h.ipv4.Store(v4)
	}
	if v6 != famUnknown {
		h.ipv6.Store(v6)
	}
	e.log.infof("server connectivity: IPv4 %s, IPv6 %s", famName(v4), famName(v6))
	if v4 == famNo {
		e.log.warnf("server has no IPv4 connectivity; IPv4 destinations will be refused")
	}
	if v6 == famNo {
		e.log.warnf("server has no IPv6 connectivity; IPv6 destinations will be refused")
	}
	if e.plat != nil {
		e.plat.OnServerIP(int(v4), int(v6))
	}
}

func famName(v int32) string {
	switch v {
	case famYes:
		return "yes"
	case famNo:
		return "no"
	}
	return "unknown"
}

// unsupported 回報伺服器確定連不到 a 這個協定的位址。
func (h *handler) unsupported(a netip.Addr) bool {
	if a.Is4() {
		return h.ipv4.Load() == famNo
	}
	return h.ipv6.Load() == famNo
}

// dialHappyEyeballs 依 RFC 8305 的精神連線:IPv6 / IPv4 交錯排列,每 250 ms 啟動下一個嘗試,
// 第一個成功者勝出、其餘取消。在純 IPv6(或 IPv4 不通)的網路上不必等某個位址逾時才換下一個。
func dialHappyEyeballs(ctx context.Context, d *net.Dialer, ips []string, port string) (net.Conn, error) {
	ips = interleaveFamilies(ips)
	if len(ips) == 0 {
		return nil, errors.New("no address to connect")
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type result struct {
		c   net.Conn
		err error
	}
	results := make(chan result, len(ips))
	// 每 250 ms 啟動下一個嘗試;某次嘗試失敗時則立刻啟動下一個
	started := 0
	start := func() {
		ip := ips[started]
		started++
		go func() {
			c, err := d.DialContext(ctx, "tcp", net.JoinHostPort(ip, port))
			if err != nil {
				err = &addrError{ip: ip, err: err}
			}
			results <- result{c, err}
		}()
	}
	start()
	var lastErr error
	pending := 1
	timer := time.NewTimer(250 * time.Millisecond)
	defer timer.Stop()
	for pending > 0 {
		select {
		case r := <-results:
			pending--
			if r.err == nil {
				cancel()
				// 收掉其他晚到的成功連線
				go func(n int) {
					for i := 0; i < n; i++ {
						if x := <-results; x.c != nil {
							x.c.Close()
						}
					}
				}(pending)
				return r.c, nil
			}
			lastErr = r.err
			if started < len(ips) {
				start()
				pending++
				timer.Reset(250 * time.Millisecond)
			}
		case <-timer.C:
			if started < len(ips) {
				start()
				pending++
				timer.Reset(250 * time.Millisecond)
			}
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return nil, lastErr
}

type addrError struct {
	ip  string
	err error
}

func (e *addrError) Error() string { return e.ip + ": " + e.err.Error() }
func (e *addrError) Unwrap() error { return e.err }

// interleaveFamilies 去除空白與重複,並以 IPv6、IPv4 交錯排列(以第一個位址的協定開頭,保留系統排序偏好)。
func interleaveFamilies(ips []string) []string {
	var v4, v6 []string
	seen := map[string]bool{}
	first := ""
	for _, s := range ips {
		a, err := netip.ParseAddr(trimSpace(s))
		if err != nil || seen[a.String()] {
			continue
		}
		seen[a.String()] = true
		if first == "" {
			first = map[bool]string{true: "4", false: "6"}[a.Unmap().Is4()]
		}
		if a.Unmap().Is4() {
			v4 = append(v4, a.Unmap().String())
		} else {
			v6 = append(v6, a.String())
		}
	}
	a, b := v6, v4
	if first == "4" {
		a, b = v4, v6
	}
	out := make([]string, 0, len(v4)+len(v6))
	for i := 0; i < len(a) || i < len(b); i++ {
		if i < len(a) {
			out = append(out, a[i])
		}
		if i < len(b) {
			out = append(out, b[i])
		}
	}
	return out
}

func trimSpace(s string) string {
	for len(s) > 0 && (s[0] == ' ' || s[0] == '\t') {
		s = s[1:]
	}
	for len(s) > 0 && (s[len(s)-1] == ' ' || s[len(s)-1] == '\t') {
		s = s[:len(s)-1]
	}
	return s
}
