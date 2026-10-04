package io.github.sshtunnelvpn.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AuthType { PASSWORD, KEY, KEY_AND_PASSWORD }

@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    /** 平行 SSH 連線數。 */
    val connections: Int = 2,
    val keepaliveSec: Int = 15,
    val udpgwEnabled: Boolean = false,
    val udpgwAddress: String = "127.0.0.1:7300",
) {
    val displayName: String get() = name.ifBlank { "$username@$host" }
    val endpoint: String get() = if (port == 22) "$username@$host" else "$username@$host:$port"
}

@Serializable
enum class AppMode { ALL, ALLOW, DISALLOW }

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * IPv6 處理方式。三種模式都會接管 IPv6 路由(::/0),差別只在 VPN 介面有沒有 IPv6 位址:
 *
 * - 有位址:手機認為「有 IPv6 可用」,DNS 會查 AAAA,App 會優先用 IPv6 連線,流量經 SSH 伺服器送出。
 *   伺服器若沒有 IPv6 對外能力,這些連線會失敗(引擎會回 RST 讓 App 改走 IPv4,但仍多一輪嘗試)。
 * - 無位址:IPv6 路由仍指向 VPN,但沒有來源位址可用,App 的 IPv6 連線會立即失敗並改走 IPv4;
 *   系統 DNS 也不再查 AAAA。這樣既不會從實體網路洩漏 IPv6,也不會卡在伺服器不支援的 IPv6。
 *
 * 不提供「IPv6 不經 VPN」的選項:那等於讓 IPv6 流量直接從實體網路出去(洩漏真實位址)。
 */
@Serializable
enum class Ipv6Mode {
    /** 依伺服器實測結果:有 IPv6 → 經通道;沒有 → 封鎖。結果按伺服器記住,首次連線預設封鎖。 */
    AUTO,
    /** 一律給 IPv6 位址、經通道送出(確定伺服器有 IPv6 時使用)。 */
    TUNNEL,
    /** 一律封鎖 IPv6(只用 IPv4)。 */
    BLOCK,
}

@Serializable
data class AppSettings(
    val selectedProfileId: String? = null,
    val dnsUpstream: String = "1.1.1.1",
    val dnsCache: Boolean = true,
    val ipv6Mode: Ipv6Mode = Ipv6Mode.AUTO,
    val bypassLan: Boolean = true,
    /** 使用者自訂不走 VPN 的網段,以換行或逗號分隔的 CIDR。 */
    val excludedRoutes: String = "",
    val appMode: AppMode = AppMode.ALL,
    val appPackages: Set<String> = emptySet(),
    val mtu: Int = 8500,
    val socksEnabled: Boolean = false,
    val socksPort: Int = 1080,
    val socksAllowLan: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val logLevel: Int = 1,
)

@Serializable
data class KnownHost(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val key: String get() = keyOf(host, port)

    companion object {
        fun keyOf(host: String, port: Int) = "${host.lowercase()}:$port"
    }
}

@Serializable
data class ProfileStore(val profiles: List<Profile> = emptyList())

@Serializable
data class KnownHostStore(val hosts: Map<String, KnownHost> = emptyMap())
