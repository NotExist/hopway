# 參考:通用出口(SSH / SOCKS5 / HTTP 代理)

狀態:參考,未排入實作 · 2026-10-04

## 動機

目前只能以 SSH 伺服器當出口。希望也能直接用 SOCKS5 或 HTTP 代理當出口,讓 App 成為通用的「全機代理」。

## 與現有架構的契合點

資料平面只依賴一個介面:

```go
type dialer interface {
    Dial(ctx context.Context, addr string) (net.Conn, error)
}
```

SSH 連線池(`sshPool`)只是其中一種實作。gVisor、先連遠端再握手、IPv4/IPv6 探測與過濾、DNS-over-TCP 與快取、首頁統計,都只透過 `Dial` 運作,不知道底下是 SSH。所以新增出口類型,主要是補上新的 `dialer` 實作,外加各類型的 UDP 處理。

## 各出口類型的能力

| | SSH | SOCKS5 | HTTP CONNECT |
|---|---|---|---|
| TCP | direct-tcpip | CONNECT | CONNECT |
| 目的地名稱由出口端解析 | 是 | 是(ATYP=domain) | 是 |
| DNS | DNS-over-TCP 經出口 | 同左;或 UDP ASSOCIATE | DNS-over-TCP 經 CONNECT(代理需允許連 53) |
| 其他 UDP | udpgw(選用) | UDP ASSOCIATE(需代理支援) | 不支援 → ICMP unreachable,QUIC 退回 TCP |
| 認證 | 密碼/金鑰 | 帳密(RFC 1929) | Basic(Proxy-Authorization) |
| 加密 | SSH 本身 | **無**(明文經過網路) | **無**;除非用 HTTPS 代理(TLS 到代理) |
| 伺服器身分驗證 | 主機金鑰 TOFU | 無 | 僅 HTTPS 代理有(憑證) |
| 存活偵測 / RTT | keepalive@openssh.com | 需另做(例如定期 CONNECT 探測) | 同左 |
| 平行連線 | 多條 SSH 分攤 channel | 每條 TCP 本來就各自連線 | 同左 |
| IPv4/IPv6 能力探測 | 經出口連探測位址 | 同左 | 同左 |

**安全性提醒**:SOCKS5 與純 HTTP 代理沒有加密,手機到代理之間的流量可能被看見或竄改。UI 要明確標示,並建議只在可信網路或搭配 TLS 時使用。

## 需要調整的部分

- **設定模型**:`Profile` 變成 `Outbound`,多一個 `type`(ssh / socks5 / http / https),各類型有自己的欄位。編輯畫面依類型切換欄位。
- **診斷頁與統計**:SSH 專屬的項目(主機金鑰、交握、SSH 連線數)依類型顯示或隱藏;出口 IP、IPv4/IPv6 能力等共通項目照常。
- **匯出/匯入格式**:版本升為 2,保留讀 v1 的能力。
- **與 P2 的關係**:P2 的「多出口 + 分 App 指定」可以直接混用不同類型的出口,例如某些 App 走 SSH、某些走 SOCKS5。建議先完成 P2 的多出口骨架,再加入新的出口類型。

## 命名

功能擴展到 SSH 以外後,「SSH Tunnel VPN」名稱就不貼切了。要調整的層級,影響各不相同:

| 層級 | 影響 |
|---|---|
| App 顯示名稱 | 隨時可改,無副作用 |
| GitHub repo 名稱 | 可改;GitHub 會自動轉址舊網址 |
| Kotlin package / Go module 路徑 | 純內部重構,不影響使用者 |
| **applicationId**(`io.github.sshtunnelvpn`) | **改了就是另一個 App**:無法覆蓋安裝,設定不會沿用(資料以 Keystore 加密,也無法搬移)。建議發布正式版之前就決定,或永久保留現有 ID、只改顯示名稱 |

如果要改名,最好在發布第一個正式版之前定案。
