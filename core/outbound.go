package core

import (
	"context"
	"net"
	"time"
)

// outbound 是「出口」:資料平面只透過 Dial 把連線交給它,不在乎底下是 SSH 還是代理。
// 其餘方法供狀態、統計與網路切換使用。
type outbound interface {
	Dial(ctx context.Context, addr string) (net.Conn, error)
	close()
	// reconnectAll 在網路切換時呼叫:立即重建連線 / 重做健康檢查
	reconnectAll()
	// rtt 為最近一次存活檢查的往返時間(0 = 未知)
	rtt() time.Duration
	// liveCount / total:存活的上游連線數與設定數(SSH 平行連線;代理固定 1)
	liveCount() int
	total() int
	// version 為對端識別(SSH 伺服器版本字串,或代理類型)
	version() string
}

const (
	typeSSH    = "ssh"
	typeSOCKS5 = "socks5"
)

func newOutbound(cfg *Config, plat Platform, log *logger, onState func(int, string)) outbound {
	if cfg.Type == typeSOCKS5 {
		return newSocksOutbound(cfg, plat, log, onState)
	}
	return newSSHPool(cfg, plat, log, onState)
}

func (p *sshPool) total() int { return len(p.slots) }

func (p *sshPool) version() string {
	v, _ := p.serverVersion.Load().(string)
	return v
}
