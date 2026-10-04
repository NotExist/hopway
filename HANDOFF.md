# HANDOFF — sshtunnelvpn_android

## 當前狀態(2026-10-03)

- Repo:https://github.com/NotExist/sshtunnelvpn_android(public,branch `main`)
- **建置一律走 GitHub Actions**(`.github/workflows/build.yml`),本地不裝 Android SDK/NDK、不跑 Gradle。
  - 三個 workflow 各司其職,以觸發條件區分:`test.yml`「Run tests」(push main/PR,文件變更不觸發;Go + Kotlin 單元測試)、`build-debug.yml`「Build debug APK」(只手動:`gh workflow run build-debug.yml`)、`publish-release.yml`「Publish release」(只在 `v*` tag)。共用環境在 `.github/actions/android-setup`。舊 `build.yml` 的執行紀錄已刪除。
  - `versionName` 固定 0.1.0,`versionCode` = 分鐘級 Unix timestamp(`date +%s / 60`,約 2,940 萬;與 workflow 名稱、run number 無關)。APK/artifact 檔名為 `SSHTunnelVPN-0.1.0-debug-<yyyyMMdd-HHmm 台灣時間>-<commit>`;versionCode、建置時間、commit 顯示在「設定 → 關於 → 版本」。三者取自同一時間點。
  - debug 以固定金鑰簽章:secret `DEBUG_KEYSTORE_BASE64`(本機備份 `~/.android/sshtunnelvpn-debug.keystore`,`android`/`androiddebugkey`),build-debug.yml 會驗證簽章。
  - release keystore secrets 尚未建立 → 推 tag 會失敗(刻意,避免發出 debug 簽章的 release)。
- Go core(`core/`)完成,本地 `go test -race` 6 項端到端測試全過(TCP 8×2MB 雙向、平行 SSH、DNS pipelining+快取、udpgw、SOCKS5、斷線重連、認證失敗、host key 拒絕、加密私鑰)。
- Android app(`app/`)**CI 建置通過**,debug APK 約 27 MB。
- ABI 限 arm64-v8a / x86_64(與 gomobile target 一致)。
- **P1 完成**(多伺服器設計見 `docs/design-multi-server-routing.md`):伺服器清單延遲(TCP 探測/連線中顯示 keepalive 即時值)、入口/出口 IP 與國家(ipinfo.io;出口經通道查)、依延遲排序、下拉刷新、檢查出口 IP。CI 綠。
- **實機已成功連線**(2026-10-04,修正 Bad address 後)。其餘項目(網路切換重連、分 App、QS 磚、永久連線、host key 變更、P1 延遲/出口顯示)尚待實測。

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
- 2026-10-04:實機首次成功連線。
- 2026-10-04:Google App 新聞/縮圖載不出來 → 伺服器無 IPv6 所致。修正:先連遠端再握手(失敗回 RST,Happy Eyeballs 可退 IPv4)、自建 gVisor stack(forwarder 在 NIC 前安裝)、IPv6 自動偵測(按伺服器記住,三模式:自動/經通道/封鎖,IPv6 路由一律接管不洩漏)、無 IPv6 時 AAAA 回空、丟棄 UDP 回 ICMP unreachable、首頁顯示連線失敗/UDP 丟棄/IPv6 狀態。測試時曾觀察到一次 race 下 TCP 傳輸停滯原因未定,已在測試加診斷輸出。
- 2026-10-04:伺服器清單顯示 IPv6 能力(✓/✗/?,測試連線與檢查出口也會測 IPv6);伺服器清單匯出/匯入(含帳密時以密語 PBKDF2+AES-GCM 加密,合併時保留本機帳密與主機金鑰信任)。測試停滯根因確認為 socketpair 代替 TUN 時 buffer(上限 208 KB)寫滿 EAGAIN 丟包、TCP 重傳逾時退避;測試 MTU 改 1500 後連續通過。
- 2026-10-04:relay half-close 改為閒置逾時(原本一方 half-close 後另一方向套固定 60 秒期限,長下載會被截斷),加回歸測試。
