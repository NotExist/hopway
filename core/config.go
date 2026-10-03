package sshvpn

import (
	"encoding/json"
	"errors"
	"net"
	"strconv"
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
	VirtualDNS  string `json:"virtualDns,omitempty"`
	DNSUpstream string `json:"dnsUpstream,omitempty"`
	DNSCache    bool   `json:"dnsCache"`

	// badvpn-udpgw 位址(相對於 SSH server),空字串 = 停用 UDP 轉送(DNS 除外)。
	UDPGW string `json:"udpgw,omitempty"`

	// 本機 SOCKS5 proxy 監聽位址,空字串 = 停用。
	SocksListen string `json:"socksListen,omitempty"`

	LogLevel int `json:"logLevel,omitempty"`

	// TestConnection 專用:非空時於測試連線後經通道 GET 此 URL(查出口 IP)。
	ExitCheckURL string `json:"exitCheckUrl,omitempty"`
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
	if c.DNSUpstream == "" {
		c.DNSUpstream = "1.1.1.1"
	}
	c.DNSUpstream = withDefaultPort(c.DNSUpstream, 53)
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
