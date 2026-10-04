package io.github.sshtunnelvpn.tunnel

import io.github.sshtunnelvpn.data.AppSettings
import io.github.sshtunnelvpn.data.AuthType
import io.github.sshtunnelvpn.data.HostKeyVerdict
import io.github.sshtunnelvpn.data.IpInfoRepository
import io.github.sshtunnelvpn.data.KnownHostsRepository
import io.github.sshtunnelvpn.data.Profile
import io.github.sshtunnelvpn.sshvpn.Platform
import io.github.sshtunnelvpn.sshvpn.Sshvpn
import io.github.sshtunnelvpn.sshvpn.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress

/** VPN 介面位址:選在 198.18.0.0/15(benchmark 保留段),不會與「略過區域網路」衝突。 */
object TunAddress {
    const val V4 = "198.18.0.1"
    const val V4_PREFIX = 30
    const val DNS_V4 = "198.18.0.2"
    const val V6 = "fdfe:dcba:9876::1"
    const val V6_PREFIX = 126
}

object EngineConfig {
    fun json(
        p: Profile,
        s: AppSettings?,
        virtualDns: String?,
        exitCheckUrl: String? = null,
        exitCheckUrl6: String? = null,
    ): String = buildJsonObject {
        put("host", p.host.trim())
        put("port", p.port)
        put("user", p.username.trim())
        if (p.authType != AuthType.KEY) put("password", p.password)
        if (p.authType != AuthType.PASSWORD) {
            put("privateKey", p.privateKey)
            put("passphrase", p.passphrase)
        }
        put("connections", p.connections)
        put("keepaliveSec", p.keepaliveSec)
        if (p.udpgwEnabled && p.udpgwAddress.isNotBlank()) put("udpgw", p.udpgwAddress.trim())
        if (s != null) {
            put("mtu", s.mtu)
            put("dnsUpstream", s.dnsUpstream.trim())
            put("dnsCache", s.dnsCache)
            put("logLevel", s.logLevel)
            if (s.socksEnabled) {
                put("socksListen", "${if (s.socksAllowLan) "0.0.0.0" else "127.0.0.1"}:${s.socksPort}")
            }
        }
        if (virtualDns != null) put("virtualDns", virtualDns)
        if (exitCheckUrl != null) put("exitCheckUrl", exitCheckUrl)
        if (exitCheckUrl6 != null) put("exitCheckUrl6", exitCheckUrl6)
    }.toString()
}

/**
 * gomobile Platform 的共用實作。Go 端會從任意 goroutine 呼叫,這裡的方法都需 thread-safe。
 */
open class BasePlatform(
    private val knownHosts: KnownHostsRepository,
    private val persistHostKeys: Boolean,
    private val onMismatch: (HostKeyMismatch) -> Unit,
    private val logs: LogBuffer?,
) : Platform {
    override fun protect(fd: Long): Boolean = true

    override fun resolveHost(host: String): String = try {
        // 保留系統排序(已依目前網路的 IPv4/IPv6 可用性排好),Go 端以 Happy Eyeballs 交錯嘗試;
        // 不假設 IPv4 一定存在
        InetAddress.getAllByName(host).joinToString(",") { it.hostAddress.orEmpty() }
    } catch (_: Exception) {
        ""
    }

    override fun verifyHostKey(host: String, port: Long, keyType: String, fingerprint: String, keyBase64: String): Boolean =
        runBlocking {
            when (val v = knownHosts.verify(host, port.toInt(), keyType, fingerprint, persistHostKeys)) {
                HostKeyVerdict.Trusted -> true
                HostKeyVerdict.NewlyTrusted -> {
                    logs?.add(LogBuffer.WARN, "Trusting new host key for $host:$port ($keyType $fingerprint)")
                    true
                }
                is HostKeyVerdict.Mismatch -> {
                    onMismatch(HostKeyMismatch(host, port.toInt(), keyType, v.known.fingerprint, fingerprint))
                    false
                }
            }
        }

    override fun onState(state: Long, message: String) {}

    override fun onServerIP(ipv4: Long, ipv6: Long) {}

    override fun log(level: Long, message: String) {
        logs?.add(level.toInt(), message)
    }
}

object EngineOps {
    /** [withExit] 時,測試連線後經通道分別查詢 IPv4 / IPv6 出口資訊。 */
    suspend fun test(p: Profile, platform: Platform, withExit: Boolean = false): Result<TestResult> =
        withContext(Dispatchers.IO) {
            val json = if (withExit) {
                EngineConfig.json(p, null, null, IpInfoRepository.EXIT_URL, IpInfoRepository.EXIT_URL6)
            } else {
                EngineConfig.json(p, null, null)
            }
            runCatching { Sshvpn.testConnection(json, platform) }
        }

    /** Cloudflare trace 解析結果;[error] 非空表示該協定查不到(通常代表不通)。 */
    data class Trace(val ip: String? = null, val country: String? = null, val error: String? = null)

    /** 查「此刻」對外位址:[viaTunnel] = 經 VPN(網站看到的你),否則本機直連。 */
    suspend fun trace(viaTunnel: Boolean, ipv6: Boolean): Trace = withContext(Dispatchers.IO) {
        runCatching { Sshvpn.fetchTrace(viaTunnel, ipv6, 6000) }.fold(
            onSuccess = { body ->
                val kv = body.lineSequence().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 } }
                    .associate { (k, v) -> k.trim() to v.trim() }
                Trace(ip = kv["ip"], country = kv["loc"]?.takeIf { it.length == 2 && it != "XX" })
            },
            onFailure = { Trace(error = it.message ?: it.toString()) },
        )
    }
}

/** TestResult → 兩協定出口與伺服器能力。 */
fun TestResult.toProbe() = IpInfoRepository.Probe(
    exit4 = exitInfo.takeIf { it.isNotEmpty() }?.let(IpInfoRepository::parse),
    exit6 = exitInfo6.takeIf { it.isNotEmpty() }?.let(IpInfoRepository::parse),
    ipv4 = familyOf(getIPv4()),
    ipv6 = familyOf(getIPv6()),
)
