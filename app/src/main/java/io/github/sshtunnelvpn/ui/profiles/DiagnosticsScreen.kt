package io.github.sshtunnelvpn.ui.profiles

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sshtunnelvpn.AppContainer
import io.github.sshtunnelvpn.R
import io.github.sshtunnelvpn.container
import io.github.sshtunnelvpn.data.IpInfoRepository
import io.github.sshtunnelvpn.data.Profile
import io.github.sshtunnelvpn.sshvpn.TestResult
import io.github.sshtunnelvpn.tunnel.BasePlatform
import io.github.sshtunnelvpn.tunnel.EngineOps
import io.github.sshtunnelvpn.tunnel.TunnelState
import io.github.sshtunnelvpn.tunnel.toProbe
import io.github.sshtunnelvpn.ui.components.BackTopBar
import io.github.sshtunnelvpn.ui.formatDuration
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class DiagnosticsViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    var profile by mutableStateOf<Profile?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var result by mutableStateOf<TestResult?>(null)
        private set
    var probe by mutableStateOf<IpInfoRepository.Probe?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var checkedAt by mutableStateOf<Long?>(null)
        private set

    init {
        viewModelScope.launch {
            profile = c.profiles.get(id)
            run()
        }
    }

    /** 短暫登入 SSH:測交握/RTT/主機金鑰、IPv4/IPv6 對外能力,並經通道分別查兩份 ipinfo。 */
    fun run() {
        val p = profile ?: return
        if (running) return
        running = true
        error = null
        viewModelScope.launch {
            val platform = BasePlatform(c.knownHosts, persistHostKeys = false, onMismatch = {}, logs = c.logs)
            EngineOps.test(p, platform, withExit = true).fold(
                onSuccess = {
                    result = it
                    probe = it.toProbe().also { pr -> c.ipInfo.record(p.id, pr) }
                },
                onFailure = { error = it.message ?: it.toString() },
            )
            checkedAt = System.currentTimeMillis()
            running = false
        }
    }

    /** 純文字報告(複製全部用)。 */
    fun report(live: String?): String = buildString {
        val p = profile ?: return@buildString
        appendLine("${p.displayName} (${p.endpoint})")
        checkedAt?.let { appendLine(DateFormat.getDateTimeInstance().format(Date(it))) }
        error?.let { appendLine("ERROR: $it") }
        result?.let { r ->
            appendLine("SSH: ${r.serverVersion}  handshake ${r.handshakeMs} ms  RTT ${r.rttMillis} ms")
            appendLine("Host key: ${r.hostKeyType} ${r.fingerprint}")
            appendLine("IPv4: ${famText(probe?.ipv4)}  IPv6: ${famText(probe?.ipv6)}")
            appendLine("--- ipinfo IPv4 ---")
            appendLine(r.exitInfo.ifEmpty { r.exitError })
            appendLine("--- ipinfo IPv6 ---")
            appendLine(r.exitInfo6.ifEmpty { r.exitError6 })
        }
        live?.let { appendLine("--- live ---").appendLine(it) }
    }

    private fun famText(v: Boolean?) = when (v) {
        true -> "yes"
        false -> "no"
        null -> "unknown"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(profileId: String, onBack: () -> Unit) {
    val c = LocalContext.current.container
    val vm: DiagnosticsViewModel = viewModel { DiagnosticsViewModel(c, profileId) }
    val status by c.tunnel.status.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun copy(text: String) = scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("diagnostics", text))) }

    // 這台伺服器正在連線時,附上引擎的即時統計
    val live = status.takeIf { it.profileId == profileId && it.state == TunnelState.CONNECTED }?.stats
    val hitRate = live?.let { if (it.dnsQueries > 0) it.dnsCacheHits * 100 / it.dnsQueries else 0 }
    val liveText = live?.let {
        listOf(
            stringResource(R.string.diag_live_uptime) + ": " + formatDuration(it.uptimeMillis),
            stringResource(R.string.stat_ssh_links) + ": ${it.sshLive} / ${it.sshTotal}",
            stringResource(R.string.diag_live_tcp_total) + ": ${it.tcpTotal}",
            stringResource(R.string.stat_dns) + ": ${it.dnsQueries} · $hitRate%",
            stringResource(R.string.stat_dial_failures) + ": ${it.dialFailures}",
        ).joinToString("\n")
    }

    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.title_diagnostics), onBack) {
                IconButton(onClick = vm::run, enabled = !vm.running) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.action_refresh))
                }
                IconButton(onClick = { copy(vm.report(liveText)) }, enabled = vm.result != null || vm.error != null) {
                    Icon(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy_all))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.profile?.let {
                Text(it.displayName, style = MaterialTheme.typography.titleLarge)
                Text(it.endpoint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (vm.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.checkedAt?.let {
                Text(
                    stringResource(R.string.diag_checked_at, DateFormat.getTimeInstance().format(Date(it))),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            vm.error?.let {
                Section(stringResource(R.string.state_error)) {
                    SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            vm.result?.let { r ->
                Section(stringResource(R.string.diag_ssh)) {
                    SelectionContainer {
                        Column {
                            KV(stringResource(R.string.stat_server), r.serverVersion)
                            KV(stringResource(R.string.diag_handshake), "${r.handshakeMs} ms")
                            KV(stringResource(R.string.stat_latency), "${r.rttMillis} ms")
                            KV(stringResource(R.string.diag_host_key), r.hostKeyType)
                            Text(r.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Section(stringResource(R.string.diag_families)) {
                    FamilyRow(v6 = false, capable = vm.probe?.ipv4, exit = vm.probe?.exit4, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    FamilyRow(v6 = true, capable = vm.probe?.ipv6, exit = vm.probe?.exit6, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.diag_families_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                RawJson("ipinfo.io (IPv4)", r.exitInfo, r.exitError, ::copy)
                RawJson("v6.ipinfo.io (IPv6)", r.exitInfo6, r.exitError6, ::copy)
            }
            liveText?.let {
                Section(stringResource(R.string.diag_live)) {
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}

/** ipinfo 原文:成功顯示 JSON(可選取/複製);失敗顯示原因(通常代表伺服器沒有該協定)。 */
@Composable
private fun RawJson(title: String, body: String, error: String, copy: (String) -> Unit) {
    Section(title) {
        if (body.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Top) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(body.trim(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { copy(body) }) {
                    Icon(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy), Modifier.size(20.dp))
                }
            }
        } else {
            Text(stringResource(R.string.diag_unavailable, error), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
