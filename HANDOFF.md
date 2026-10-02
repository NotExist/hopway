# HANDOFF — sshtunnelvpn_android

## 當前狀態(2026-10-02)

- Repo:https://github.com/NotExist/sshtunnelvpn_android(public,branch `main`)
- **建置一律走 GitHub Actions**(`.github/workflows/build.yml`),本地不裝 Android SDK/NDK、不跑 Gradle。
- Go core(`core/`)完成,本地 `go test -race` 6 項端到端測試全過(TCP 8×2MB 雙向、平行 SSH、DNS pipelining+快取、udpgw、SOCKS5、斷線重連、認證失敗、host key 拒絕、加密私鑰)。
- Android app(`app/`)**CI 建置通過**:debug + release(R8 minify)APK 由 Actions 產出,artifact 名 `apk`(release 約 19.7 MB,debug 簽章——尚未設 keystore secrets)。
- ABI 限 arm64-v8a / x86_64(與 gomobile target 一致)。
- **尚未在實機/模擬器跑過**——功能正確性只由 Go 端到端測試與 CidrTest 保證,UI 與 VpnService 流程未實測。

## 架構重點

- 資料平面全在 Go:TUN fd → gVisor netstack(借用 tun2socks v2 `core`)→ SSH direct-tcpip。無本機 SOCKS loopback。
- Go↔Kotlin 只有一份 JSON config + `Platform` callback interface(protect / resolveHost / verifyHostKey / onState / log)。
- 虛擬位址 198.18.0.1/30、虛擬 DNS 198.18.0.2(避開 LAN bypass 網段)。
- 版本:AGP 9.4.1、Gradle 9.8.0、Kotlin 2.4.20、Compose BOM 2026.09.00、Navigation 3 1.2.0 → **需 compileSdk 37**。

## 下一步

1. 實機測試:連線、切換網路自動重連、分 App 代理、QS 磚、永久連線 VPN、host key 變更對話框。
2. 正式簽章:設定 repo secrets `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`,推 `v*` tag 自動發 Release。
3. 可能的後續:設定檔匯入/匯出、`ssh://` URI 分享、Jump host、HTTP proxy、per-profile DNS。

## 里程碑

- 2026-10-02:Go core + 測試完成;Android app 初版程式碼;改走 GitHub Actions 建置(user 指示不在本地建置環境),清除本地 NDK/platform 36/build-tools 36/Gradle 9.8。
- 2026-10-02:CI 修正(sdkmanager SIGPIPE、platform 套件名 `android-37.0`、M3 experimental opt-in、gomobile getter 名稱、CidrTest 最小路由數 77、abiFilters),首次 CI 全綠。
