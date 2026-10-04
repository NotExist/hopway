# Hopway

以 SSH 伺服器為出口的 Android 全域 VPN(原名 SSH Tunnel VPN,2026-10-04 改名 Hopway),重製自 VPNoverSSH。applicationId / Kotlin package:`not.exist.hopway`。目前狀態、待辦與里程碑見 `HANDOFF.md`(單一事實來源)。

## 結構

- `core/`:Go 資料平面(package `core`,module `github.com/NotExist/hopway/core`),gomobile 匯出成 `not.exist.hopway.core.Core`
  - `engine.go`:對外 API(Start/Stop/GetStats/NetworkChanged/TestConnection)
  - `sshpool.go`:N 條平行 SSH 連線、keepalive、重連、host key callback
  - `handler.go`:netstack TCP/UDP 分派;`dns.go`:DNS-over-TCP pipelining + 快取;`udpgw.go`;`socks.go`;`keys.go`
  - `*_test.go`:端到端測試(in-process SSH server + socketpair 充當 TUN)
- `app/`:Kotlin + Compose
  - `tunnel/TunnelVpnService.kt`:VpnService、前景通知、網路切換、統計輪詢
  - `tunnel/Engine.kt`:config JSON 組裝、Platform 共用實作(TOFU host key 驗證)
  - `data/`:DataStore JSON(profiles 以 Keystore 加密)
  - `ui/`:Navigation 3 + Material 3 各畫面

## 慣例

- Go↔Kotlin 介面只有一份 JSON config(`core/config.go` 的 `Config` ↔ `EngineConfig.json`)。新增欄位時兩邊同步。
- Go 的 `int` 在 Java 端是 `long`。
- `./gradlew assembleDebug` 會自動重建 AAR(`buildGoCore` task,需 `go` 在 PATH;用 `mise exec --` 執行)。
- Go 測試一律加 `-race`。
