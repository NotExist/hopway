# HANDOFF — sshtunnelvpn_android

## 當前狀態(2026-10-03)

- Repo:https://github.com/NotExist/sshtunnelvpn_android(public,branch `main`)
- **建置一律走 GitHub Actions**(`.github/workflows/build.yml`),本地不裝 Android SDK/NDK、不跑 Gradle。
  - 三個 workflow 各司其職:`test.yml`(push main/PR,文件變更不觸發;Go + Kotlin 單元測試)、`debug.yml`(只手動觸發:`gh workflow run debug.yml`)、`release.yml`(只在 `v*` tag)。共用環境在 `.github/actions/android-setup`。
  - `versionName` 固定 0.1.0,`versionCode` = 100 + 該 workflow 的 run number(拆分前已發到 15)。
  - debug 以固定金鑰簽章:secret `DEBUG_KEYSTORE_BASE64`(本機備份 `~/.android/sshtunnelvpn-debug.keystore`,`android`/`androiddebugkey`),debug.yml 會驗證簽章。
  - release keystore secrets 尚未建立 → 推 tag 會失敗(刻意,避免發出 debug 簽章的 release)。
- Go core(`core/`)完成,本地 `go test -race` 6 項端到端測試全過(TCP 8×2MB 雙向、平行 SSH、DNS pipelining+快取、udpgw、SOCKS5、斷線重連、認證失敗、host key 拒絕、加密私鑰)。
- Android app(`app/`)**CI 建置通過**,debug APK artifact 名 `SSHTunnelVPN-debug-<versionCode>`(約 27 MB)。
- ABI 限 arm64-v8a / x86_64(與 gomobile target 一致)。
- **P1 完成**(多伺服器設計見 `docs/design-multi-server-routing.md`):伺服器清單延遲(TCP 探測/連線中顯示 keepalive 即時值)、入口/出口 IP 與國家(ipinfo.io;出口經通道查)、依延遲排序、下拉刷新、檢查出口 IP。CI 綠。
- **尚未在實機/模擬器跑過**——功能正確性只由 Go 端到端測試與 CidrTest 保證,UI 與 VpnService 流程未實測。

## 架構重點

- 資料平面全在 Go:TUN fd → gVisor netstack(借用 tun2socks v2 `core`)→ SSH direct-tcpip。無本機 SOCKS loopback。
- Go↔Kotlin 只有一份 JSON config + `Platform` callback interface(protect / resolveHost / verifyHostKey / onState / log)。
- 虛擬位址 198.18.0.1/30、虛擬 DNS 198.18.0.2(避開 LAN bypass 網段)。
- 版本:AGP 9.4.1、Gradle 9.8.0、Kotlin 2.4.20、Compose BOM 2026.09.00、Navigation 3 1.2.0 → **需 compileSdk 37**。

## 下一步

1. 實機測試:連線、切換網路自動重連、分 App 代理、QS 磚、永久連線 VPN、host key 變更對話框、P1 的延遲/出口顯示;**順便驗證 `getConnectionOwnerUid` 對 DNS 的歸屬**(P2 前置)。
2. P2:引擎多出口 + per-app 指定(伺服器/直連/封鎖)、路由熱更新、per-app 流量統計(含「系統 DNS」獨立一列)。
3. 正式簽章:設定 repo secrets `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`,推 `v*` tag 自動發 Release。
4. P3 群組;之後:設定檔匯入/匯出、`ssh://` URI 分享、Jump host。網域規則暫緩(user 決定先只做 per-app)。

## 里程碑

- 2026-10-02:Go core + 測試完成;Android app 初版程式碼;改走 GitHub Actions 建置(user 指示不在本地建置環境),清除本地 NDK/platform 36/build-tools 36/Gradle 9.8。
- 2026-10-02:CI 修正(sdkmanager SIGPIPE、platform 套件名 `android-37.0`、M3 experimental opt-in、gomobile getter 名稱、CidrTest 最小路由數 77、abiFilters),首次 CI 全綠。
- 2026-10-03:多伺服器路由設計定案(ipinfo.io、先只做 per-app、系統 DNS 獨立呈現);P1 完成,CI 綠。
- 2026-10-03:建置流程調整——debug 手動觸發 + 固定 debug 金鑰(GitHub runner 的 Android user home 不是 `~/.android`,改為 Gradle 明確指定 signingConfig)、release 只在 tag、versionCode 自動遞增。
- 2026-10-04:實機回報修正——Android 13+ `excludeRoute(127.0.0.0/8)` 觸發 Builder「Bad address」導致無法連線(路由規劃抽成 `Routes.plan` 並加回歸測試);首頁/設定先取得 VPN 授權以便出現在系統 VPN 清單;`×N` 改為「SSH 連線 ×N」;workflow 拆成 test/debug/release。
