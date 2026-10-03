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
