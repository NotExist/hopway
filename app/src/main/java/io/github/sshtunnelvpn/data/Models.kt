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

@Serializable
data class AppSettings(
    val selectedProfileId: String? = null,
    val dnsUpstream: String = "1.1.1.1",
    val dnsCache: Boolean = true,
    val ipv6: Boolean = true,
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
