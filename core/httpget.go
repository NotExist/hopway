package sshvpn

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"
)

const maxHTTPBody = 64 << 10

// httpGet 以給定的 dial(通常是經 SSH 的 direct-tcpip)做一次 HTTP GET。
// 目的地主機名由 SSH 伺服器端解析,因此查到的是該伺服器的出口視角。
func httpGet(ctx context.Context, dial func(context.Context, string) (net.Conn, error), url string) (string, error) {
	tr := &http.Transport{
		DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
			return dial(ctx, addr)
		},
		DisableKeepAlives:   true,
		TLSHandshakeTimeout: 10 * time.Second,
	}
	defer tr.CloseIdleConnections()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "SSHTunnelVPN")
	resp, err := (&http.Client{Transport: tr}).Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxHTTPBody))
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("http %d", resp.StatusCode)
	}
	return string(body), nil
}

// FetchViaTunnel 經目前執行中引擎的 SSH 連線做 HTTP GET,回傳 body(上限 64 KB)。
// 用途:查詢出口 IP(例如 https://ipinfo.io/json)。
func FetchViaTunnel(url string, timeoutMs int) (string, error) {
	mu.Lock()
	e := cur
	mu.Unlock()
	if e == nil {
		return "", errors.New("tunnel is not running")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	return httpGet(ctx, e.pool.Dial, url)
}

func fetchOrError(ctx context.Context, dial func(context.Context, string) (net.Conn, error), url string) (string, string) {
	body, err := httpGet(ctx, dial, url)
	if err != nil {
		return "", err.Error()
	}
	return body, ""
}

// Cloudflare trace:以字面位址連線,本身就決定了走 IPv4 或 IPv6,也不依賴 DNS;
// 回應為 key=value 純文字(含 ip=、loc= 國碼)。
const (
	traceURL4 = "https://1.1.1.1/cdn-cgi/trace"
	traceURL6 = "https://[2606:4700:4700::1111]/cdn-cgi/trace"
)

// FetchTrace 查詢「此刻對外的 IP」:viaTunnel 為 true 時經目前的 SSH 通道(=網站看到的 VPN 出口),
// 否則由本機直接連線(=手機實體網路的對外位址)。ipv6 選擇查 IPv6 或 IPv4。回傳 trace 原文。
func FetchTrace(viaTunnel bool, ipv6 bool, timeoutMs int) (string, error) {
	url := traceURL4
	if ipv6 {
		url = traceURL6
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	if viaTunnel {
		mu.Lock()
		e := cur
		mu.Unlock()
		if e == nil {
			return "", errors.New("tunnel is not running")
		}
		return httpGet(ctx, e.pool.Dial, url)
	}
	d := &net.Dialer{}
	return httpGet(ctx, func(ctx context.Context, addr string) (net.Conn, error) {
		return d.DialContext(ctx, "tcp", addr)
	}, url)
}
