package io.github.sshtunnelvpn.tunnel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TunnelState { IDLE, CONNECTING, CONNECTED, RECONNECTING, ERROR, STOPPING }

data class TrafficSample(val time: Long, val rxRate: Long, val txRate: Long)

data class TrafficStats(
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val rxRate: Long = 0,
    val txRate: Long = 0,
    val tcpActive: Long = 0,
    val tcpTotal: Long = 0,
    val udpActive: Long = 0,
    /** 沒有 udpgw 而回 ICMP unreachable 的 UDP 流(QUIC 會因此改走 TCP)。 */
    val udpDropped: Long = 0,
    /** 伺服器端連不到目的地、或因伺服器無 IPv6 而直接拒絕的 TCP 連線。 */
    val dialFailures: Long = 0,
    /** 伺服器 IPv6 能力:0 偵測中 / 1 有 / 2 沒有。 */
    val serverIpv6: Int = 0,
    val dnsQueries: Long = 0,
    val dnsCacheHits: Long = 0,
    val rttMillis: Long = 0,
    val sshLive: Long = 0,
    val sshTotal: Long = 0,
    val uptimeMillis: Long = 0,
    val serverVersion: String = "",
)

data class TunnelStatus(
    val state: TunnelState = TunnelState.IDLE,
    val profileId: String? = null,
    val profileName: String? = null,
    val error: String? = null,
    val stats: TrafficStats = TrafficStats(),
    /** 目前 VPN 介面有沒有 IPv6 位址(true = IPv6 經通道,false = IPv6 被封鎖)。 */
    val ipv6Routed: Boolean = false,
)

data class HostKeyMismatch(
    val host: String,
    val port: Int,
    val keyType: String,
    val knownFingerprint: String,
    val newFingerprint: String,
)

/** Service 與 UI 共用的狀態中樞(同一 process)。 */
class TunnelController(private val context: Context) {
    private val _status = MutableStateFlow(TunnelStatus())
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    private val _history = MutableStateFlow<List<TrafficSample>>(emptyList())
    val history: StateFlow<List<TrafficSample>> = _history.asStateFlow()

    private val _hostKeyMismatch = MutableStateFlow<HostKeyMismatch?>(null)
    val hostKeyMismatch: StateFlow<HostKeyMismatch?> = _hostKeyMismatch.asStateFlow()

    val isActive: Boolean
        get() = _status.value.state in setOf(TunnelState.CONNECTING, TunnelState.CONNECTED, TunnelState.RECONNECTING)

    /** 回傳非 null 時需先以該 Intent 取得 VPN 授權。 */
    fun prepare(): Intent? = VpnService.prepare(context)

    fun connect(profileId: String? = null) {
        val i = Intent(context, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_START)
        if (profileId != null) i.putExtra(TunnelVpnService.EXTRA_PROFILE_ID, profileId)
        ContextCompat.startForegroundService(context, i)
    }

    fun disconnect() {
        if (_status.value.state == TunnelState.IDLE || _status.value.state == TunnelState.ERROR) return
        context.startService(Intent(context, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_STOP))
    }

    fun toggle() = if (isActive) disconnect() else connect()

    internal fun update(f: (TunnelStatus) -> TunnelStatus) = _status.update(f)

    internal fun pushSample(s: TrafficSample) = _history.update { (it + s).takeLast(HISTORY) }

    internal fun resetHistory() {
        _history.value = emptyList()
    }

    internal fun reportHostKeyMismatch(m: HostKeyMismatch) {
        _hostKeyMismatch.value = m
    }

    fun dismissHostKeyMismatch() {
        _hostKeyMismatch.value = null
    }

    companion object {
        const val HISTORY = 90
    }
}
