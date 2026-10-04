package io.github.sshtunnelvpn.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class IpInfo(
    val ip: String,
    val country: String? = null,
    val city: String? = null,
    val region: String? = null,
    val org: String? = null,
    /** 私有/保留位址(ipinfo 的 bogon 標記)。 */
    val bogon: Boolean = false,
    val fetchedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class IpInfoStore(
    /** 入口 IP 查詢快取,以 IP 為鍵。 */
    val byIp: Map<String, IpInfo> = emptyMap(),
    /** 經通道查到的 IPv4 出口資訊(ipinfo.io),以 profile id 為鍵。 */
    val exits: Map<String, IpInfo> = emptyMap(),
    /** 經通道查到的 IPv6 出口資訊(v6.ipinfo.io),以 profile id 為鍵。 */
    val exits6: Map<String, IpInfo> = emptyMap(),
    /** 伺服器有沒有 IPv4 對外能力(實測),以 profile id 為鍵。不假設 IPv4 一定存在。 */
    val ipv4: Map<String, Boolean> = emptyMap(),
    /** 伺服器有沒有 IPv6 對外能力(引擎連線後實測),以 profile id 為鍵;供 IPv6「自動」模式使用。 */
    val ipv6: Map<String, Boolean> = emptyMap(),
)

/** ipinfo.io 查詢與快取。 */
class IpInfoRepository(private val store: DataStore<IpInfoStore>) {
    val data: Flow<IpInfoStore> = store.data
    val exits: Flow<Map<String, IpInfo>> = store.data.map { it.exits }
    val ipv6: Flow<Map<String, Boolean>> = store.data.map { it.ipv6 }
    val ipv4: Flow<Map<String, Boolean>> = store.data.map { it.ipv4 }
    val exits6: Flow<Map<String, IpInfo>> = store.data.map { it.exits6 }

    private val inflight = Mutex()

    /** 查入口 IP 的資訊;快取 7 天,失敗回傳 null。 */
    suspend fun lookup(ip: String): IpInfo? {
        store.data.first().byIp[ip]?.takeIf { fresh(it.fetchedAt, CACHE_MS) }?.let { return it }
        // 同一時間只有一個請求在飛,避免清單一次刷新就打出 N 個重複查詢
        return inflight.withLock {
            store.data.first().byIp[ip]?.takeIf { fresh(it.fetchedAt, CACHE_MS) }?.let { return@withLock it }
            val info = runCatching { fetch("$BASE/$ip/json") }.getOrNull()?.let(::parse) ?: return@withLock null
            store.updateData { s -> s.copy(byIp = s.byIp + (ip to info)) }
            info
        }
    }

    suspend fun recordExit(profileId: String, info: IpInfo) {
        store.updateData { s -> s.copy(exits = s.exits + (profileId to info)) }
    }

    suspend fun recordExit6(profileId: String, info: IpInfo) {
        store.updateData { s -> s.copy(exits6 = s.exits6 + (profileId to info)) }
    }

    /** 記錄伺服器對外能力;null 表示無法判定,不覆蓋既有結果。 */
    suspend fun recordFamilies(profileId: String, ipv4: Boolean?, ipv6: Boolean?) {
        store.updateData { s ->
            s.copy(
                ipv4 = if (ipv4 != null) s.ipv4 + (profileId to ipv4) else s.ipv4,
                ipv6 = if (ipv6 != null) s.ipv6 + (profileId to ipv6) else s.ipv6,
            )
        }
    }

    suspend fun exitCheckedRecently(profileId: String): Boolean =
        store.data.first().exits[profileId]?.let { fresh(it.fetchedAt, EXIT_RECHECK_MS) } == true

    suspend fun forget(profileId: String) {
        store.updateData { s ->
            s.copy(exits = s.exits - profileId, exits6 = s.exits6 - profileId, ipv4 = s.ipv4 - profileId, ipv6 = s.ipv6 - profileId)
        }
    }

    suspend fun ipv6Capable(profileId: String): Boolean? = store.data.first().ipv6[profileId]

    suspend fun recordIpv6(profileId: String, available: Boolean) {
        store.updateData { s -> s.copy(ipv6 = s.ipv6 + (profileId to available)) }
    }

    /** 測試連線/診斷的結果:兩個協定的出口與伺服器對外能力。 */
    data class Probe(val exit4: IpInfo?, val exit6: IpInfo?, val ipv4: Boolean?, val ipv6: Boolean?)

    /** 把一次 Probe 寫回(未取得的部分不覆蓋既有資料)。 */
    suspend fun record(profileId: String, p: Probe) {
        recordFamilies(profileId, p.ipv4, p.ipv6)
        p.exit4?.let { recordExit(profileId, it) }
        p.exit6?.let { recordExit6(profileId, it) }
    }

    private fun fresh(t: Long, ttl: Long) = System.currentTimeMillis() - t < ttl

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) error("http ${c.responseCode}")
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    @Serializable
    private data class Dto(
        val ip: String? = null,
        val country: String? = null,
        val city: String? = null,
        val region: String? = null,
        val org: String? = null,
        val bogon: Boolean = false,
    )

    companion object {
        const val BASE = "https://ipinfo.io"
        /**
         * 經通道查出口用的 URL:回傳「發出請求者」的 IP。
         * ipinfo.io 只有 IPv4(A 記錄)、v6.ipinfo.io 只有 IPv6(AAAA),分別查就能各自測出兩個協定的出口。
         */
        const val EXIT_URL = "$BASE/json"
        const val EXIT_URL6 = "https://v6.ipinfo.io/json"
        private const val CACHE_MS = 7L * 24 * 3600 * 1000
        private const val EXIT_RECHECK_MS = 10L * 60 * 1000

        fun parse(json: String): IpInfo? = runCatching {
            val d = AppJson.decodeFromString(Dto.serializer(), json)
            IpInfo(
                ip = d.ip ?: return null,
                country = d.country?.takeIf { it.length == 2 },
                city = d.city?.ifBlank { null },
                region = d.region?.ifBlank { null },
                org = d.org?.ifBlank { null },
                bogon = d.bogon,
            )
        }.getOrNull()
    }
}

/** ISO 3166 國碼轉國旗 emoji(regional indicator symbols)。 */
fun flagEmoji(country: String?): String {
    val cc = country?.uppercase() ?: return ""
    if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return ""
    return cc.map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
}
