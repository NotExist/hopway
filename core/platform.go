package sshvpn

import (
	"fmt"
	"sync/atomic"
)

// 連線狀態,對應 Kotlin 端 TunnelState。
const (
	StateIdle         = 0
	StateConnecting   = 1
	StateConnected    = 2
	StateReconnecting = 3
	StateError        = 4
	StateStopped      = 5
)

// 日誌等級。
const (
	LogDebug = 0
	LogInfo  = 1
	LogWarn  = 2
	LogError = 3
)

// Platform 由 Android 端實作(gomobile 會產生對應的 Java interface)。
// 所有方法都可能從任意 goroutine 併發呼叫。
type Platform interface {
	// Protect 讓 socket 繞過 VPN(VpnService.protect)。
	Protect(fd int) bool
	// ResolveHost 以底層網路解析主機名,回傳逗號分隔的 IP;失敗回傳空字串。
	// (Android 上 Go 的純 Go resolver 讀不到 /etc/resolv.conf,故交給平台端。)
	ResolveHost(host string) string
	// VerifyHostKey 回傳 true 表示信任此主機金鑰。
	VerifyHostKey(host string, port int, keyType string, fingerprint string, keyBase64 string) bool
	OnState(state int, message string)
	Log(level int, message string)
}

type logger struct {
	p     Platform
	level atomic.Int32
}

func (l *logger) logf(level int, format string, args ...any) {
	if l == nil || l.p == nil || int32(level) < l.level.Load() {
		return
	}
	l.p.Log(level, fmt.Sprintf(format, args...))
}

func (l *logger) debugf(f string, a ...any) { l.logf(LogDebug, f, a...) }
func (l *logger) infof(f string, a ...any)  { l.logf(LogInfo, f, a...) }
func (l *logger) warnf(f string, a ...any)  { l.logf(LogWarn, f, a...) }
func (l *logger) errorf(f string, a ...any) { l.logf(LogError, f, a...) }
