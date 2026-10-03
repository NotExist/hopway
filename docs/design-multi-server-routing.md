# 設計:多伺服器路由、分 App 出口、伺服器清單延遲/國家

狀態:已定案,P1 實作中 · 2026-10-03

## 1. 目標

1. 同時維持多台 SSH 伺服器的連線,**新連線**依規則選出口。
2. 路由可精確到 **per-app**:每個 App 指定走哪台伺服器、哪個群組、直連或封鎖。
3. 群組策略:最低延遲、故障切換(failover)、手動固定。
4. 伺服器清單即時顯示 **延遲** 與 **IP 所屬國家**。

非目標:把同一條 TCP 連線拆到多台伺服器;已建立的連線在伺服器之間遷移(TCP 做不到)。

## 2. 關鍵機制:VPN 內怎麼知道封包屬於哪個 App

TUN 收到的只是 IP 封包,不帶 UID。做法:

| Android 版本 | 方法 | 備註 |
|---|---|---|
| 10+(API 29+) | `ConnectivityManager.getConnectionOwnerUid(proto, local, remote)` | 只有目前作用中的 VPN app 能呼叫,只查得到經過本 VPN 的連線;每次查詢是一次 binder IPC(約 0.1–0.5 ms) |
| 8–9(API 26–28) | 解析 `/proc/net/tcp{,6}`、`/proc/net/udp{,6}` 的 uid 欄位 | Android 10 起被封鎖,所以才有上面的 API |

流程:gVisor 交出新的 TCP/UDP flow 時,4-tuple 已知(src = 198.18.0.1:應用的 port,dst = 目的地)→ Go 呼叫 `Platform.ConnectionOwner(proto, src, dst)` 取得 UID → 查路由表 → 選出口 → 開 SSH channel。

- 查詢只在**每條新 flow 一次**,不是每個封包,成本可忽略。現行流程本來就是先在 gVisor 完成握手再 dial,查 UID 不會多加往返。
- 一個 UID 可能對應多個 package(`sharedUserId`,例如部分系統 App)。路由表以 UID 為鍵,UI 上選任一 package 等於選整組 UID,畫面要標示這點。

### 已知風險(實機才能確認)

- **DNS 的歸屬**:多數 App 透過系統 DnsResolver 查 DNS。查詢由系統代發,`getConnectionOwnerUid` 可能回傳系統 UID 而非 App 的 UID。
  - 確認後二選一:(a) 能歸屬 → DNS 也照 App 規則走,CDN 依出口地區回答;(b) 不能 → DNS 一律走預設出口。後者 App 連線本身仍會正確走指定伺服器,只是 CDN 可能選到離「預設出口」較近的節點。
- **未 connect 的 UDP socket** 可能查不到 owner → 退回預設規則。

## 3. 路由模型

```
Outbound(出口)= 伺服器 profile | 群組 | DIRECT(直連)| BLOCK(封鎖)
Group(群組)    = 成員伺服器[] + 策略(LOWEST_LATENCY | FAILOVER | MANUAL)
AppRule        = UID → Outbound
Default        = Outbound(沒有規則的 App 走這裡)
```

### 直連分兩種實作

- **App 層級直連**:用 `VpnService.Builder.addDisallowedApplication` 讓系統直接繞過 VPN,零額外負擔,也就是現行的分 App 模式。變更時需要重新 `establish()` 介面,系統會無縫接手、不中斷。
- **引擎內直連**:預留給未來的網域/IP 規則。Go 用 `protect()` 過的 socket 直接連出,有 gVisor 一層的負擔。第一版不需要。

### 群組策略

- **LOWEST_LATENCY**:群組維持一個「目前成員」,新 flow 全部走它。只有當 (a) 目前成員斷線,或 (b) 其他成員連續 3 次量測都比它低 30% 且至少 20 ms,才切換(hysteresis,避免來回跳)。
  - 理由:同一個網站若一下從 A 國 IP、一下從 B 國 IP 來,登入狀態或風控會出問題。以群組為單位穩定出口,比每條連線各自挑最快的安全。
- **FAILOVER**:依排序取第一台活著的。前面的恢復後,**新 flow** 才回到它;既有 flow 不動。
- **MANUAL**:使用者在通知或主畫面切換成員。

切換時已建立的連線留在原伺服器直到自然結束;只有新連線走新出口。

## 4. 引擎改動(Go core)

- `pools map[serverID]*sshPool`:每台伺服器一個現有的 sshPool(含平行連線、keepalive、重連),整個邏輯原封不動重用。
- **Lazy 連線**:只有預設出口與「有 App 規則指向」的伺服器在啟動時連線,其餘在第一條 flow 需要時才連。
- **閒置關閉**:非預設伺服器 N 分鐘(預設 5)沒有任何 channel 就關閉 SSH,省電。群組成員若需要做延遲比較,改用輕量 TCP connect 探測,不維持 SSH。
- **DNS client 每個出口一份**(含快取),DNS 結果不跨出口共用,避免 CDN 答案錯配。
- 統計多一層:per-outbound、per-UID 的 bytes/連線數,UI 可以顯示「每個 App 用了多少流量、走哪台」。
- 系統代發的 DNS(歸屬到系統 UID)在統計中**獨立列為「系統 DNS」**,顯示查詢數、流量與出口,讓預設路徑的負擔看得見。
- 路由表可**熱更新**:`Sshvpn.UpdateRouting(json)`,改 App 規則不必重連。只有「App 層級直連」名單變動才需要重新 `establish()`。

### Config JSON(v2,向後相容:沒有 `outbounds` 時當成單伺服器)

```json
{
  "outbounds": [
    {"id": "tokyo", "host": "...", "port": 22, "user": "...", "connections": 2, "udpgw": ""},
    {"id": "frankfurt", "host": "...", "...": "..."}
  ],
  "groups": [
    {"id": "fast", "members": ["tokyo", "frankfurt"], "strategy": "lowest_latency"}
  ],
  "uidRules": {"10123": "frankfurt", "10200": "fast", "10301": "block"},
  "default": "tokyo",
  "idleCloseSec": 300
}
```

Kotlin 端負責 package → UID 轉換,並監聽 `PACKAGE_ADDED/REPLACED/REMOVED` 廣播,在 UID 變動時熱更新路由表。

## 5. 伺服器清單:延遲與國家

### 延遲

| 情境 | 量什麼 | 頻率 |
|---|---|---|
| 該伺服器已連線 | SSH keepalive RTT(現有機制) | 跟 keepalive 同頻,標示為「即時」 |
| 未連線 | TCP connect 到 host:port 的時間,約等於網路 RTT,不做 SSH 握手、不認證 | 清單畫面可見時每 30 秒一輪,最多 4 台平行,支援下拉刷新;離開畫面即停止 |

- 探測用的 socket 走實體網路(本 App 本來就排除在 VPN 外),量到的是手機到伺服器的直連路徑,正是 SSH 實際會走的路。
- 顯示:<80 ms 綠、<200 ms 黃、其餘紅、逾時灰色「—」。可以依延遲排序。

### IP 國家(定案:ipinfo.io)

| 情境 | 查詢 | 得到 |
|---|---|---|
| 該伺服器已連線 | 經 SSH 通道 `GET https://ipinfo.io/json`(Go `FetchViaTunnel`) | **出口 IP**,即網站實際看到的 IP。每次連上後查一次,同一伺服器 10 分鐘內不重查 |
| 未連線 | 直接 `GET https://ipinfo.io/<入口IP>/json` | **入口 IP** 的國家 |
| 測試連線 / 清單「檢查出口」 | 短暫 SSH 登入後經通道查詢(`TestConnection` 帶 `exitCheckUrl`) | 出口 IP |

- 只有主動經該伺服器連線,才能查到真正的出口;入口 IP 的國家只是參考。
- 查詢結果以 IP 為鍵快取 7 天,出口資訊以伺服器為鍵保存。私有 IP 會被 ipinfo 標成 `bogon`,畫面顯示「區網」。
- 伺服器 IP 會送到 ipinfo.io,在「關於」頁註明資料來源。

### 清單畫面示意

```
┌──────────────────────────────────────────────┐
│ 伺服器                       [依延遲 ▾] [+]   │
├──────────────────────────────────────────────┤
│ ● 🇯🇵 Tokyo           預設    ▮ 38 ms 即時    │
│   203.0.113.10 · ×2 · 12 個 App              │
│ ○ 🇩🇪 Frankfurt               ▮ 241 ms       │
│   198.51.100.7 · ×1 · 3 個 App · 群組 fast    │
│ ○ 🇺🇸 Home NAS                ▮ —  逾時      │
│   home.example.org → 192.0.2.4               │
├──────────────────────────────────────────────┤
│ 群組                                          │
│ ⚡ fast  最低延遲 · 目前:Tokyo (38 ms)        │
└──────────────────────────────────────────────┘
```

## 6. 分 App 設定 UI

現有的「選擇 App」改成「App 路由」。每個 App 一列,右側下拉選單:

```
┌──────────────────────────────────────────────┐
│ App 路由                    🔍  [只看已設定]   │
├──────────────────────────────────────────────┤
│ 預設出口:Tokyo ▾                              │
├──────────────────────────────────────────────┤
│ [icon] Netflix             [ 🇩🇪 Frankfurt ▾ ]│
│ [icon] LINE                [ 直連 ▾ ]         │
│ [icon] Chrome              [ ⚡ fast ▾ ]      │
│ [icon] 某廣告 SDK App       [ ⛔ 封鎖 ▾ ]      │
│ [icon] Google Play 服務     [ 預設 ▾ ]        │
│        ⚠ 與 3 個系統套件共用 UID               │
└──────────────────────────────────────────────┘
```

- 下拉選項:預設 / 各伺服器(含國旗與延遲)/ 各群組 / 直連 / 封鎖。
- 伺服器詳情頁反向列出「走這台的 App」。
- 主畫面統計卡可以展開成「各 App 流量與出口」。
- 原本的 ALL / ALLOW / DISALLOW 模式可以完全由此取代,需設計舊設定的遷移:ALLOW 名單 → 其餘 App 設為直連;DISALLOW 名單 → 那些 App 設為直連。

## 7. 分階段實作

| 階段 | 內容 | 風險 |
|---|---|---|
| P1 | 伺服器清單延遲(TCP 探測 + 即時 RTT)與國家(離線 GeoIP) | 低,與 VPN 引擎無關 |
| P2 | 引擎多出口 + per-app 指定單一伺服器 / 直連 / 封鎖、路由熱更新、per-app 流量統計 | 中:UID 歸屬需實機驗證(含 DNS 歸屬) |
| P3 | 群組(最低延遲含 hysteresis、failover、手動)與閒置關閉 | 中 |
| P4(暫緩) | 網域 / IP 規則 | 第一版只做 per-app |

P2 之前建議先用現有版本在實機驗證基本連線。

## 8. 決議(2026-10-03)

1. 國家/IP 資料用 ipinfo.io:經通道查出口,未連線時查入口。
2. 第一版只做 per-app,網域規則暫緩。
3. 閒置關閉 5 分鐘、群組切換門檻(連續 3 次、低 30% 且至少 20 ms)照提案。
4. 系統 DNS 在 P2 統計中獨立呈現。
