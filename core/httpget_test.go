package core

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// ipinfo 替身:回傳請求來源 IP,驗證請求確實由 SSH 伺服器端發出。
func startFakeIPInfo(t *testing.T) string {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"ip":"%s","country":"JP"}`, strings.Split(r.RemoteAddr, ":")[0])
	}))
	t.Cleanup(srv.Close)
	return strings.TrimPrefix(srv.URL, "http://")
}

func TestFetchViaTunnel(t *testing.T) {
	info := startFakeIPInfo(t)
	srv := newTestSSHServer(t, "pw", nil)
	var mu sync.Mutex
	dialed := map[string]bool{}
	srv.redirect = func(addr string) string {
		mu.Lock()
		dialed[addr] = true
		mu.Unlock()
		if addr == "ipinfo.test:80" {
			return info
		}
		return addr
	}
	if _, err := FetchViaTunnel("http://ipinfo.test/json", 3000); err == nil {
		t.Fatal("expected error when tunnel is not running")
	}
	plat := newTestPlatform(t)
	if err := Start(-1, cfgJSON(t, map[string]any{
		"host": "127.0.0.1", "port": srv.port(), "user": "u", "password": "pw",
	}), plat); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	plat.waitState(t, StateConnected, 5*time.Second)
	body, err := FetchViaTunnel("http://ipinfo.test/json", 5000)
	if err != nil {
		t.Fatal(err)
	}
	// 主機名必須由伺服器端解析(以名稱送出 direct-tcpip),而非手機端
	mu.Lock()
	defer mu.Unlock()
	if !dialed["ipinfo.test:80"] || !strings.Contains(body, `"country":"JP"`) {
		t.Fatalf("dialed=%v body=%q", dialed, body)
	}
}

func TestTestConnectionExitCheck(t *testing.T) {
	info := startFakeIPInfo(t)
	srv := newTestSSHServer(t, "pw", nil)
	srv.redirect = func(addr string) string {
		if addr == "ipinfo.test:80" {
			return info
		}
		return addr
	}
	res, err := TestConnection(cfgJSON(t, map[string]any{
		"host": "127.0.0.1", "port": srv.port(), "user": "u", "password": "pw",
		"exitCheckUrl": "http://ipinfo.test/json",
	}), newTestPlatform(t))
	if err != nil {
		t.Fatal(err)
	}
	if res.ExitError != "" || !strings.Contains(res.ExitInfo, `"ip":"127.0.0.1"`) {
		t.Fatalf("exit info=%q err=%q", res.ExitInfo, res.ExitError)
	}

	// 目的地不可達時回報錯誤而不是讓測試連線整體失敗
	res, err = TestConnection(cfgJSON(t, map[string]any{
		"host": "127.0.0.1", "port": srv.port(), "user": "u", "password": "pw",
		"exitCheckUrl": "http://127.0.0.1:1/json",
	}), newTestPlatform(t))
	if err != nil || res.ExitError == "" {
		t.Fatalf("expected exit error, got res=%+v err=%v", res, err)
	}
}

func TestTestConnectionProbesIPv6(t *testing.T) {
	echo := startEchoServer(t)
	closed := closedPort(t)
	for _, tc := range []struct {
		to   string
		want int64
	}{{echo, famYes}, {closed, famNo}} {
		srv := newTestSSHServer(t, "pw", nil)
		srv.redirect = func(addr string) string {
			if addr == ipv6ProbeTarget {
				return tc.to
			}
			return addr
		}
		res, err := TestConnection(cfgJSON(t, map[string]any{
			"host": "127.0.0.1", "port": srv.port(), "user": "u", "password": "pw",
		}), newTestPlatform(t))
		if err != nil {
			t.Fatal(err)
		}
		if res.IPv6 != tc.want {
			t.Fatalf("ipv6 = %d, want %d", res.IPv6, tc.want)
		}
	}
}

// FetchTrace 經通道:請求由伺服器端連到 Cloudflare 的字面位址(這裡導到本機替身)。
func TestFetchTraceViaTunnel(t *testing.T) {
	trace := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, "fl=1\nip=203.0.113.10\nloc=JP\n")
	}))
	t.Cleanup(trace.Close)
	srv := newTestSSHServer(t, "pw", nil)
	var mu sync.Mutex
	var dialed []string
	srv.redirect = func(addr string) string {
		mu.Lock()
		dialed = append(dialed, addr)
		mu.Unlock()
		if addr == "1.1.1.1:80" {
			return strings.TrimPrefix(trace.URL, "http://")
		}
		return addr
	}
	plat := newTestPlatform(t)
	if err := Start(-1, cfgJSON(t, map[string]any{
		"host": "127.0.0.1", "port": srv.port(), "user": "u", "password": "pw",
	}), plat); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	plat.waitState(t, StateConnected, 5*time.Second)
	e := currentEngine()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	// 公開 API 用 https;這裡以相同路徑(經 pool.Dial)測 http 版,避免測試憑證問題
	body, err := httpGet(ctx, e.pool.Dial, "http://1.1.1.1/cdn-cgi/trace")
	if err != nil || !strings.Contains(body, "loc=JP") {
		t.Fatalf("trace: %q %v", body, err)
	}
	mu.Lock()
	defer mu.Unlock()
	found := false
	for _, a := range dialed {
		found = found || a == "1.1.1.1:80"
	}
	if !found {
		t.Fatalf("trace not dialed by literal address via server: %v", dialed)
	}
}
