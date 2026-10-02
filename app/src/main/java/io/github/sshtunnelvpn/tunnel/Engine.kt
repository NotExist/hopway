package io.github.sshtunnelvpn.tunnel

import io.github.sshtunnelvpn.data.AppSettings
import io.github.sshtunnelvpn.data.AuthType
import io.github.sshtunnelvpn.data.HostKeyVerdict
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
    fun json(p: Profile, s: AppSettings?, virtualDns: String?): String = buildJsonObject {
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
        // 優先 IPv4:行動網路的 IPv6 常有 NAT64/防火牆怪癖
        InetAddress.getAllByName(host)
            .sortedBy { if (it.address.size == 4) 0 else 1 }
            .joinToString(",") { it.hostAddress.orEmpty() }
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

    override fun log(level: Long, message: String) {
        logs?.add(level.toInt(), message)
    }
}

object EngineOps {
    suspend fun test(p: Profile, platform: Platform): Result<TestResult> = withContext(Dispatchers.IO) {
        runCatching { Sshvpn.testConnection(EngineConfig.json(p, null, null), platform) }
    }
}
