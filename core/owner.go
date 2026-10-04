package sshvpn

import (
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// 連線歸屬(偵錯):P2 分 App 路由的前置驗證。開啟 Config.LogOwners 後,每條新連線都向平台查詢
// 是哪個 App(UID)開的並寫入日誌,特別用來確認系統代發的 DNS 查詢會被歸到哪個 UID。

const (
	protoTCP = 6
	protoUDP = 17
)

// logOwner 非同步查詢並記錄一條連線的擁有者。id 為 gVisor 視角:Remote* 是 App 端,Local* 是目的地。
// kind 為額外標記(例如 "DNS"、"dropped")。查詢是一次 binder 呼叫,放到 goroutine 以免拖慢連線建立。
func (h *handler) logOwner(proto int, id stack.TransportEndpointID, kind string) {
	if !h.logOwners || h.plat == nil || h.bg == nil {
		return
	}
	src := toAddrPort(id.RemoteAddress, id.RemotePort)
	dst := toAddrPort(id.LocalAddress, id.LocalPort)
	h.bg.Go(func() {
		owner := h.plat.ConnectionOwner(proto, src.Addr().String(), int(src.Port()), dst.Addr().String(), int(dst.Port()))
		name := "tcp"
		if proto == protoUDP {
			name = "udp"
		}
		if kind != "" {
			name = kind + " " + name
		}
		if h.ctx.Err() == nil {
			h.log.infof("owner %s %s -> %s: %s", name, src, dst, owner)
		}
	})
}
