package sshvpn

import (
	"encoding/json"
	"errors"
	"net"
	"strconv"
	"strings"
	"time"
)

// Config 由 Kotlin 端以 JSON 傳入;欄位新增時保持向後相容(零值 = 預設)。
type Config struct {
	Host       string `json:"host"`
	Port       int    `json:"port"`
	User       string `json:"user"`
	Password   string `json:"password,omitempty"`
	PrivateKey string `json:"privateKey,omitempty"`
	Passphrase string `json:"passphrase,omitempty"`

	// 平行 SSH 連線數:多條 TCP 連線分攤 channel,避免單一連線 head-of-line blocking
	// 與 OpenSSH 單執行緒加解密瓶頸。
	Connections int `json:"connections,omitempty"`

	KeepaliveSec      int `json:"keepaliveSec,omitempty"`
	ConnectTimeoutSec int `json:"connectTimeoutSec,omitempty"`

	MTU int `json:"mtu,omitempty"`

	// 虛擬 DNS:VPN 介面上宣告的 DNS 位址,查詢以 DNS-over-TCP 經 SSH 送往 DNSUpstream。
	VirtualDNS string `json:"virtualDns,omitempty"`
	// DNSUpstream 可為逗號分隔清單(host 或 host:port)。常見公共 DNS 會自動補上同業者另一協定的位址,
	// 讓 DNS 不依賴伺服器一定有 IPv4(或 IPv6)。
	DNSUpstream  string   `json:"dnsUpstream,omitempty"`
	dnsUpstreams []string // 解析後的清單
	DNSCache     bool     `json:"dnsCache"`

	// badvpn-udpgw 位址(相對於 SSH server),空字串 = 停用 UDP 轉送(DNS 除外)。
	UDPGW string `json:"udpgw,omitempty"`

	// 本機 SOCKS5 proxy 監聽位址,空字串 = 停用。
	SocksListen string `json:"socksListen,omitempty"`

	LogLevel int `json:"logLevel,omitempty"`

	// TestConnection 專用:非空時於測試連線後經通道 GET 此 URL(查出口 IP)。
	// ExitCheckURL 給 IPv4(例如 https://ipinfo.io/json),ExitCheckURL6 給 IPv6(例如 https://v6.ipinfo.io/json)。
	ExitCheckURL  string `json:"exitCheckUrl,omitempty"`
	ExitCheckURL6 string `json:"exitCheckUrl6,omitempty"`
}

func parseConfig(s string) (*Config, error) {
	c := &Config{DNSCache: true}
	if err := json.Unmarshal([]byte(s), c); err != nil {
		return nil, err
	}
	if c.Host == "" {
		return nil, errors.New("host is empty")
	}
	if c.User == "" {
		return nil, errors.New("user is empty")
	}
	if c.Port <= 0 || c.Port > 65535 {
		c.Port = 22
	}
	if c.Connections <= 0 {
		c.Connections = 1
	}
	if c.Connections > 8 {
		c.Connections = 8
	}
	if c.KeepaliveSec <= 0 {
		c.KeepaliveSec = 15
	}
	if c.ConnectTimeoutSec <= 0 {
		c.ConnectTimeoutSec = 15
	}
	if c.MTU <= 0 {
		c.MTU = 8500
	}
	if strings.TrimSpace(c.DNSUpstream) == "" {
		c.DNSUpstream = "1.1.1.1"
	}
	c.dnsUpstreams = expandDNSUpstreams(c.DNSUpstream)
	if c.UDPGW != "" {
		c.UDPGW = withDefaultPort(c.UDPGW, 7300)
	}
	return c, nil
}

func withDefaultPort(addr string, port int) string {
	if _, _, err := net.SplitHostPort(addr); err == nil {
		return addr
	}
	return net.JoinHostPort(addr, strconv.Itoa(port))
}

func (c *Config) keepalive() time.Duration { return time.Duration(c.KeepaliveSec) * time.Second }
func (c *Config) connectTimeout() time.Duration {
	return time.Duration(c.ConnectTimeoutSec) * time.Second
}

// 常見公共 DNS 的 IPv4 ↔ IPv6 對應(同一業者),用於自動補上另一協定。
var dnsFamilyPairs = map[string]string{
	"1.1.1.1":         "2606:4700:4700::1111",
	"1.0.0.1":         "2606:4700:4700::1001",
	"8.8.8.8":         "2001:4860:4860::8888",
	"8.8.4.4":         "2001:4860:4860::8844",
	"9.9.9.9":         "2620:fe::fe",
	"149.112.112.112": "2620:fe::9",
	"208.67.222.222":  "2620:119:35::35",
	"208.67.220.220":  "2620:119:53::53",
}

func init() {
	for v4, v6 := range dnsFamilyPairs {
		dnsFamilyPairs[v6] = v4
	}
}

// expandDNSUpstreams 解析逗號分隔的上游清單、補預設 port,並為已知公共 DNS 補上另一協定的位址。
func expandDNSUpstreams(s string) []string {
	var out []string
	seen := map[string]bool{}
	add := func(hostport string) {
		if !seen[hostport] {
			seen[hostport] = true
			out = append(out, hostport)
		}
	}
	var pairs []string
	for _, f := range strings.FieldsFunc(s, func(r rune) bool { return r == ',' || r == ' ' || r == '\n' || r == ';' }) {
		hp := withDefaultPort(strings.TrimSpace(f), 53)
		add(hp)
		host, port, _ := net.SplitHostPort(hp)
		if other, ok := dnsFamilyPairs[host]; ok {
			pairs = append(pairs, net.JoinHostPort(other, port))
		}
	}
	for _, hp := range pairs {
		add(hp)
	}
	return out
}
