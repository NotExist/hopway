package io.github.sshtunnelvpn.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sshtunnelvpn.AppContainer
import io.github.sshtunnelvpn.data.IpInfo
import io.github.sshtunnelvpn.data.IpInfoRepository
import io.github.sshtunnelvpn.data.Profile
import io.github.sshtunnelvpn.tunnel.BasePlatform
import io.github.sshtunnelvpn.tunnel.EngineOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** 單台伺服器的探測結果(只存在記憶體,畫面離開即停止更新)。 */
data class Probe(
    /** 實際連線用的入口 IP。 */
    val ip: String? = null,
    /** TCP connect 往返時間;null = 尚未量測。 */
    val rttMs: Long? = null,
    val failed: Boolean = false,
    val measuring: Boolean = false,
    val entry: IpInfo? = null,
    val checkingExit: Boolean = false,
)

class ProfilesViewModel(private val c: AppContainer) : ViewModel() {
    private val _probes = MutableStateFlow<Map<String, Probe>>(emptyMap())
    val probes: StateFlow<Map<String, Probe>> = _probes.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** 一次性訊息(檢查出口失敗等),由畫面以 Snackbar 顯示後清除。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val limit = Semaphore(4)

    private fun edit(id: String, f: (Probe) -> Probe) = _probes.update { it + (id to f(it[id] ?: Probe())) }

    /** 畫面可見期間反覆探測;由畫面以 repeatOnLifecycle(STARTED) 呼叫,離開即取消。 */
    suspend fun probeLoop() {
        while (true) {
            probeAll()
            delay(INTERVAL_MS)
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            probeAll()
            _refreshing.value = false
        }
    }

    private suspend fun probeAll() = coroutineScope {
        val list = c.profiles.profiles.first()
        _probes.update { m -> m.filterKeys { id -> list.any { it.id == id } } }
        list.forEach { p -> launch { limit.withPermit { probe(p) } } }
    }

    private suspend fun probe(p: Profile) {
        edit(p.id) { it.copy(measuring = true) }
        val r = withContext(Dispatchers.IO) { tcpProbe(p.host.trim(), p.port) }
        edit(p.id) { it.copy(ip = r.first ?: it.ip, rttMs = r.second, failed = r.second == null, measuring = false) }
        val ip = r.first ?: return
        c.ipInfo.lookup(ip)?.let { info -> edit(p.id) { it.copy(entry = info) } }
    }

    /** 回傳 (IP, RTT ms);解析失敗則 IP 為 null,連不上則 RTT 為 null。DNS 解析時間不計入 RTT。 */
    private fun tcpProbe(host: String, port: Int): Pair<String?, Long?> {
        val addr = runCatching {
            InetAddress.getAllByName(host).sortedBy { if (it.address.size == 4) 0 else 1 }.first()
        }.getOrNull() ?: return null to null
        val ip = addr.hostAddress
        return try {
            Socket().use { s ->
                val t = System.nanoTime()
                s.connect(InetSocketAddress(addr, port), TIMEOUT_MS)
                ip to ((System.nanoTime() - t) / 1_000_000).coerceAtLeast(1)
            }
        } catch (_: Exception) {
            ip to null
        }
    }

    /** 短暫 SSH 登入後經通道查出口 IP,結果寫入出口快取。 */
    fun checkExit(p: Profile) {
        edit(p.id) { it.copy(checkingExit = true) }
        viewModelScope.launch {
            val platform = BasePlatform(c.knownHosts, persistHostKeys = false, onMismatch = {}, logs = c.logs)
            val r = EngineOps.test(p, platform, IpInfoRepository.EXIT_URL)
            r.onSuccess { res ->
                val info = res.exitInfo.takeIf { it.isNotEmpty() }?.let(IpInfoRepository::parse)
                if (info != null) c.ipInfo.recordExit(p.id, info) else _message.value = res.exitError.ifBlank { "unexpected response" }
            }.onFailure { _message.value = it.message ?: it.toString() }
            edit(p.id) { it.copy(checkingExit = false) }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        private const val INTERVAL_MS = 30_000L
        private const val TIMEOUT_MS = 3000
    }
}
