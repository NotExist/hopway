package io.github.sshtunnelvpn.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sshtunnelvpn.R
import io.github.sshtunnelvpn.data.Ipv6Mode
import io.github.sshtunnelvpn.data.flagEmoji
import io.github.sshtunnelvpn.tunnel.EngineOps
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** 一格的查詢狀態;trace 為 null 且未在查詢 = 不適用(例如未連線時的 VPN 欄)。 */
data class AddrCell(val loading: Boolean = false, val trace: EngineOps.Trace? = null)

/**
 * 「對外位址」:一律當下查詢,不用快取。
 * 經 VPN = 經 SSH 通道查(網站看到的你);本機直連 = 手機實體網路的對外位址(本 App 不經 VPN)。
 * IPv4、IPv6 分開查,查不到就代表該協定在這條路徑上不通——不假設 IPv4 一定存在。
 */
class AddressViewModel : ViewModel() {
    var vpn4 by mutableStateOf(AddrCell())
        private set
    var vpn6 by mutableStateOf(AddrCell())
        private set
    var direct4 by mutableStateOf(AddrCell())
        private set
    var direct6 by mutableStateOf(AddrCell())
        private set
    var checkedAt by mutableStateOf<Long?>(null)
        private set
    private var job: Job? = null

    /** [connected] 時才查經 VPN 欄;[vpnV6Routed] 為 false 時 IPv6 已被封鎖,不查(App 本來就用不到)。 */
    fun refresh(connected: Boolean, vpnV6Routed: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            vpn4 = AddrCell(loading = connected)
            vpn6 = AddrCell(loading = connected && vpnV6Routed)
            direct4 = AddrCell(loading = true)
            direct6 = AddrCell(loading = true)
            val v4 = if (connected) async { EngineOps.trace(viaTunnel = true, ipv6 = false) } else null
            val v6 = if (connected && vpnV6Routed) async { EngineOps.trace(viaTunnel = true, ipv6 = true) } else null
            val d4 = async { EngineOps.trace(viaTunnel = false, ipv6 = false) }
            val d6 = async { EngineOps.trace(viaTunnel = false, ipv6 = true) }
            v4?.let { vpn4 = AddrCell(trace = it.await()) }
            v6?.let { vpn6 = AddrCell(trace = it.await()) }
            direct4 = AddrCell(trace = d4.await())
            direct6 = AddrCell(trace = d6.await())
            checkedAt = System.currentTimeMillis()
        }
    }
}

@Composable
fun AddressCard(
    vm: AddressViewModel,
    connected: Boolean,
    ipv6Routed: Boolean,
    serverIpv6: Int,
    ipv6Mode: Ipv6Mode,
    onRefresh: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Public, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.addr_title), style = MaterialTheme.typography.titleSmall)
                    vm.checkedAt?.let {
                        Text(
                            stringResource(R.string.addr_checked_at, DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it))),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, stringResource(R.string.action_refresh)) }
            }
            SelectionContainer {
                Column {
                    if (connected) {
                        Group(stringResource(R.string.addr_via_vpn))
                        Line("IPv4", vm.vpn4, unavailable = R.string.addr_vpn_unavailable)
                        if (ipv6Routed) {
                            Line("IPv6", vm.vpn6, unavailable = R.string.addr_vpn_unavailable)
                        } else {
                            // IPv6 未經通道:App 的 IPv6 連線會被封鎖(不會繞出 VPN)
                            Fixed(
                                "IPv6",
                                stringResource(
                                    when {
                                        serverIpv6 == 2 -> R.string.addr_v6_blocked_no_server
                                        ipv6Mode == Ipv6Mode.BLOCK -> R.string.addr_v6_blocked_setting
                                        else -> R.string.addr_v6_blocked_probing
                                    },
                                ),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Group(stringResource(R.string.addr_direct))
                    Line("IPv4", vm.direct4, unavailable = R.string.addr_direct_unavailable)
                    Line("IPv6", vm.direct6, unavailable = R.string.addr_direct_unavailable)
                }
            }
        }
    }
}

@Composable
private fun Group(title: String) {
    Text(
        title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun Line(family: String, cell: AddrCell, unavailable: Int) {
    val t = cell.trace
    val text = when {
        cell.loading -> stringResource(R.string.addr_loading)
        t == null -> "—"
        t.ip != null -> listOfNotNull(t.country?.let { "${flagEmoji(it)} $it" }, t.ip).joinToString("  ")
        else -> stringResource(unavailable)
    }
    Fixed(family, text, dim = t?.ip == null)
}

@Composable
private fun Fixed(family: String, text: String, dim: Boolean = true) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(family, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(48.dp))
        Text(
            text, style = MaterialTheme.typography.bodyMedium,
            color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
