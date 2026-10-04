package io.github.sshtunnelvpn.ui.home

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sshtunnelvpn.ui.components.LatencyBadge
import io.github.sshtunnelvpn.R
import io.github.sshtunnelvpn.container
import io.github.sshtunnelvpn.data.AppSettings
import io.github.sshtunnelvpn.data.Profile
import io.github.sshtunnelvpn.tunnel.HostKeyMismatch
import io.github.sshtunnelvpn.tunnel.TunnelController
import io.github.sshtunnelvpn.tunnel.TunnelState
import io.github.sshtunnelvpn.tunnel.TunnelStatus
import io.github.sshtunnelvpn.ui.Route
import io.github.sshtunnelvpn.ui.components.SpeedChart
import io.github.sshtunnelvpn.ui.theme.trafficColors
import io.github.sshtunnelvpn.ui.profiles.MissingCredentialsWarning
import io.github.sshtunnelvpn.ui.formatBytes
import io.github.sshtunnelvpn.ui.formatDuration
import io.github.sshtunnelvpn.ui.formatRate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navigate: (Route) -> Unit, connectRequest: Int) {
    val ctx = LocalContext.current
    val c = ctx.container
    val status by c.tunnel.status.collectAsStateWithLifecycle()
    val history by c.tunnel.history.collectAsStateWithLifecycle()
    val mismatch by c.tunnel.hostKeyMismatch.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val profiles by c.profiles.profiles.collectAsStateWithLifecycle(emptyList())
    val addrVm: AddressViewModel = viewModel()
    val connected = status.state == TunnelState.CONNECTED
    // 對外位址一律當下查詢:進入首頁、連上/斷線、IPv6 經通道與否改變時都重查
    LaunchedEffect(connected, status.ipv6Routed) { addrVm.refresh(connected, status.ipv6Routed) }
    val selected = profiles.find { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()
    val scope = rememberCoroutineScope()

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) selected?.let { p -> c.tunnel.connect(p.id) }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun connect() {
        val p = selected ?: return navigate(Route.EditProfile())
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val intent = c.tunnel.prepare()
        if (intent != null) vpnPermission.launch(intent) else c.tunnel.connect(p.id)
    }

    // 回到前景時重新檢查授權(使用者可能在系統設定撤銷或授權)
    var authorized by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        authorized = c.tunnel.prepare() == null
        onPauseOrDispose {}
    }
    val authorize = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        authorized = c.tunnel.prepare() == null
    }

    LaunchedEffect(connectRequest) {
        if (connectRequest > 0 && !c.tunnel.isActive) connect()
    }

    Scaffold(
        topBar = {
            // 固定一行標題,不隨捲動放大/縮小
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { navigate(Route.Logs) }) {
                        Icon(Icons.AutoMirrored.Outlined.Article, stringResource(R.string.title_logs))
                    }
                    IconButton(onClick = { navigate(Route.Settings) }) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.title_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!authorized) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.vpn_auth_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.vpn_auth_body), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(
                            onClick = { c.tunnel.prepare()?.let(authorize::launch) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text(stringResource(R.string.vpn_auth_action)) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ConnectButton(status.state, onClick = { if (c.tunnel.isActive) c.tunnel.disconnect() else connect() })
            Spacer(Modifier.height(16.dp))
            StatusLine(status)
            Spacer(Modifier.height(24.dp))
            ProfileCard(
                selected,
                liveRtt = status.stats.rttMillis.takeIf { connected && selected?.id == status.profileId && it > 0 },
                onClick = { navigate(if (profiles.isEmpty()) Route.EditProfile() else Route.Profiles) },
            )
            Spacer(Modifier.height(12.dp))
            AddressCard(
                addrVm,
                connected = connected,
                ipv6Routed = status.ipv6Routed,
                serverIpv6 = status.stats.serverIpv6,
                ipv6Mode = settings.ipv6Mode,
                onRefresh = { addrVm.refresh(connected, status.ipv6Routed) },
            )
            AnimatedVisibility(status.state == TunnelState.CONNECTED || status.state == TunnelState.RECONNECTING) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    TrafficCard(status, history)
                    Spacer(Modifier.height(12.dp))
                    StatsGrid(status)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    mismatch?.let { m ->
        HostKeyMismatchDialog(
            m,
            onDismiss = { c.tunnel.dismissHostKeyMismatch() },
            onTrust = {
                scope.launch {
                    c.knownHosts.trust(m.host, m.port, m.keyType, m.newFingerprint)
                    c.tunnel.dismissHostKeyMismatch()
                    connect()
                }
            },
        )
    }
}

@Composable
private fun ConnectButton(state: TunnelState, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val busy = state == TunnelState.CONNECTING || state == TunnelState.RECONNECTING || state == TunnelState.STOPPING
    val container by animateColorAsState(
        when (state) {
            TunnelState.CONNECTED -> scheme.primary
            TunnelState.ERROR -> scheme.errorContainer
            TunnelState.CONNECTING, TunnelState.RECONNECTING, TunnelState.STOPPING -> scheme.tertiaryContainer
            TunnelState.IDLE -> scheme.surfaceContainerHigh
        },
        tween(400),
        label = "btn",
    )
    val content by animateColorAsState(
        when (state) {
            TunnelState.CONNECTED -> scheme.onPrimary
            TunnelState.ERROR -> scheme.onErrorContainer
            TunnelState.CONNECTING, TunnelState.RECONNECTING, TunnelState.STOPPING -> scheme.onTertiaryContainer
            TunnelState.IDLE -> scheme.onSurfaceVariant
        },
        tween(400),
        label = "btnContent",
    )
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        if (busy || state == TunnelState.CONNECTED) {
            Box(
                Modifier
                    .size(176.dp)
                    .scale(if (busy) ring else 1.12f)
                    .clip(CircleShape)
                    .background(container.copy(alpha = 0.22f)),
            )
        }
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            shadowElevation = if (state == TunnelState.CONNECTED) 8.dp else 2.dp,
            modifier = Modifier.size(168.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PowerSettingsNew, stringResource(R.string.action_toggle), Modifier.size(72.dp))
            }
        }
    }
}

@Composable
private fun StatusLine(s: TunnelStatus) {
    val text = when (s.state) {
        TunnelState.IDLE -> stringResource(R.string.state_idle)
        TunnelState.CONNECTING -> stringResource(R.string.state_connecting)
        TunnelState.CONNECTED -> stringResource(R.string.state_connected)
        TunnelState.RECONNECTING -> stringResource(R.string.state_reconnecting)
        TunnelState.STOPPING -> stringResource(R.string.state_stopping)
        TunnelState.ERROR -> stringResource(R.string.state_error)
    }
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    if (s.state == TunnelState.CONNECTED) {
        Text(
            formatDuration(s.stats.uptimeMillis),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (s.state == TunnelState.ERROR && !s.error.isNullOrBlank()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(12.dp))
                SelectionContainer {
                    Text(s.error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(p: Profile?, liveRtt: Long?, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Storage, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (p == null) {
                    Text(stringResource(R.string.home_no_profile), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.home_no_profile_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(p.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        p.endpoint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MissingCredentialsWarning(p.missingCredentials)
                }
            }
            // 延遲是「手機到這台伺服器」的屬性,跟伺服器放在一起(與伺服器清單同一個標籤)
            if (liveRtt != null) {
                LatencyBadge(ms = liveRtt, failed = false, measuring = false, live = true, modifier = Modifier.padding(end = 8.dp))
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrafficCard(s: TunnelStatus, history: List<io.github.sshtunnelvpn.tunnel.TrafficSample>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth()) {
                val tc = trafficColors()
                RateColumn(Icons.Filled.ArrowDownward, stringResource(R.string.stat_download), s.stats.rxRate,
                    s.stats.rxBytes, tc.download, dashed = false, Modifier.weight(1f))
                RateColumn(Icons.Filled.ArrowUpward, stringResource(R.string.stat_upload), s.stats.txRate,
                    s.stats.txBytes, tc.upload, dashed = true, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            SpeedChart(history, TunnelController.HISTORY, Modifier.fillMaxWidth().height(120.dp))
        }
    }
}

@Composable
private fun RateColumn(icon: ImageVector, label: String, rate: Long, total: Long, color: Color, dashed: Boolean, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            // 圖例與線條同色,並附一小段線型樣本(實線/虛線)
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            Spacer(Modifier.width(6.dp))
            LegendLine(color, dashed)
        }
        Text(formatRate(rate), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = color)
        Text(
            stringResource(R.string.stat_total, formatBytes(total)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class StatItem(val icon: ImageVector, val label: String, val value: String, val detail: String, val help: String)

/** 首頁只留需要一眼看到的 4 項;點格子看完整數值與說明。 */
@Composable
private fun StatsGrid(s: TunnelStatus) {
    val st = s.stats
    val items = listOf(
        StatItem(
            Icons.Outlined.Hub, stringResource(R.string.stat_ssh_links), "${st.sshLive} / ${st.sshTotal}",
            stringResource(R.string.stat_ssh_links_detail, st.sshLive, st.sshTotal), stringResource(R.string.stat_ssh_links_help),
        ),
        StatItem(
            Icons.Outlined.ErrorOutline, stringResource(R.string.stat_dial_failures), "${st.dialFailures}",
            "${st.dialFailures}", stringResource(R.string.stat_dial_failures_help),
        ),
        StatItem(
            Icons.Outlined.Timer, stringResource(R.string.stat_udp_short), "${st.udpActive} / ${st.udpDropped}",
            stringResource(R.string.stat_udp_value, st.udpActive, st.udpDropped), stringResource(R.string.stat_udp_help),
        ),
        StatItem(
            Icons.Outlined.Link, stringResource(R.string.stat_tcp_active), "${st.tcpActive}",
            stringResource(R.string.stat_tcp_detail, st.tcpActive, st.tcpTotal), stringResource(R.string.stat_tcp_help),
        ),
    )
    var open by remember { mutableStateOf<StatItem?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item -> StatTile(item, Modifier.weight(1f)) { open = item } }
            }
        }
    }
    open?.let { item ->
        AlertDialog(
            onDismissRequest = { open = null },
            icon = { Icon(item.icon, null) },
            title = { Text(item.label) },
            text = {
                Column {
                    SelectionContainer { Text(item.detail, style = MaterialTheme.typography.titleMedium) }
                    Spacer(Modifier.height(12.dp))
                    Text(item.help, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun StatTile(item: StatItem, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CardDefaults.shape),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(item.icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(item.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Text(item.value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HostKeyMismatchDialog(m: HostKeyMismatch, onDismiss: () -> Unit, onTrust: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.hostkey_changed_title)) },
        text = {
            Column {
                Text(stringResource(R.string.hostkey_changed_body, "${m.host}:${m.port}"))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.hostkey_known), style = MaterialTheme.typography.labelMedium)
                Text(m.knownFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.hostkey_new, m.keyType), style = MaterialTheme.typography.labelMedium)
                Text(m.newFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text(stringResource(R.string.hostkey_trust)) } },
        dismissButton = { FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun LegendLine(color: Color, dashed: Boolean) {
    androidx.compose.foundation.Canvas(Modifier.size(width = 20.dp, height = 8.dp)) {
        val y = size.height / 2
        drawLine(
            color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y),
            strokeWidth = 2.dp.toPx(),
            pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
        )
    }
}
