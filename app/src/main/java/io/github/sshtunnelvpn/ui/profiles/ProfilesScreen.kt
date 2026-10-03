package io.github.sshtunnelvpn.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sshtunnelvpn.R
import io.github.sshtunnelvpn.container
import io.github.sshtunnelvpn.data.AppSettings
import io.github.sshtunnelvpn.data.AuthType
import io.github.sshtunnelvpn.data.IpInfo
import io.github.sshtunnelvpn.data.Profile
import io.github.sshtunnelvpn.data.flagEmoji
import io.github.sshtunnelvpn.tunnel.TunnelState
import io.github.sshtunnelvpn.ui.Route
import io.github.sshtunnelvpn.ui.components.BackTopBar
import io.github.sshtunnelvpn.ui.components.LatencyBadge
import kotlinx.coroutines.launch
import java.util.UUID

private enum class SortBy { NAME, LATENCY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(navigate: (Route) -> Unit, onBack: () -> Unit) {
    val c = LocalContext.current.container
    val vm: ProfilesViewModel = viewModel { ProfilesViewModel(c) }
    val profiles by c.profiles.profiles.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val status by c.tunnel.status.collectAsStateWithLifecycle()
    val exits by c.ipInfo.exits.collectAsStateWithLifecycle(emptyMap())
    val probes by vm.probes.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Profile?>(null) }
    var sortBy by rememberSaveable { mutableStateOf(SortBy.NAME) }
    var sortMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    // 只在畫面可見(STARTED)時探測,切到背景或離開即停
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.probeLoop() } }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    fun liveRtt(p: Profile): Long? =
        status.stats.rttMillis.takeIf { status.profileId == p.id && status.state == TunnelState.CONNECTED && it > 0 }

    fun select(p: Profile) = scope.launch {
        c.settings.update { it.copy(selectedProfileId = p.id) }
        // 連線中切換伺服器:直接改連新的
        if (c.tunnel.isActive && status.profileId != p.id) c.tunnel.connect(p.id)
    }

    val sorted = when (sortBy) {
        SortBy.NAME -> profiles
        SortBy.LATENCY -> profiles.sortedBy { p -> liveRtt(p) ?: probes[p.id]?.rttMs ?: Long.MAX_VALUE }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            BackTopBar(stringResource(R.string.title_profiles), onBack, scroll) {
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.action_sort))
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf(SortBy.NAME to R.string.sort_name, SortBy.LATENCY to R.string.sort_latency).forEach { (k, l) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(l)) },
                                leadingIcon = { RadioButton(selected = sortBy == k, onClick = null) },
                                onClick = { sortBy = k; sortMenu = false },
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { navigate(Route.EditProfile()) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.action_add_profile)) },
            )
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Outlined.Storage, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.profiles_empty), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }
        val selectedId = settings.selectedProfileId ?: profiles.first().id
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(sorted, key = { it.id }) { p ->
                    val probe = probes[p.id] ?: Probe()
                    val live = liveRtt(p)
                    ProfileRow(
                        p,
                        selected = p.id == selectedId,
                        probe = probe,
                        liveRtt = live,
                        exit = exits[p.id],
                        onSelect = { select(p) },
                        onEdit = { navigate(Route.EditProfile(p.id)) },
                        onCheckExit = { vm.checkExit(p) },
                        onDuplicate = {
                            scope.launch { c.profiles.upsert(p.copy(id = UUID.randomUUID().toString(), name = p.displayName + " (copy)")) }
                        },
                        onDelete = { deleting = p },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = { Text(stringResource(R.string.profile_delete_body, p.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        c.profiles.delete(p.id)
                        c.ipInfo.forget(p.id)
                        if (settings.selectedProfileId == p.id) c.settings.update { it.copy(selectedProfileId = null) }
                    }
                    deleting = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** 「🇯🇵 JP · Tokyo」;私有位址顯示「區網」。 */
@Composable
fun placeLabel(info: IpInfo?): String? {
    info ?: return null
    if (info.bogon) return stringResource(R.string.place_private)
    val cc = info.country ?: return null
    return listOfNotNull("${flagEmoji(cc)} $cc", info.city).joinToString(" · ")
}

@Composable
private fun ProfileRow(
    p: Profile,
    selected: Boolean,
    probe: Probe,
    liveRtt: Long?,
    exit: IpInfo?,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onCheckExit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(p.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.endpoint, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // 入口:實際連線用的 IP 與其國家
                Text(
                    listOfNotNull(
                        stringResource(R.string.label_entry),
                        placeLabel(probe.entry),
                        probe.ip,
                    ).joinToString("  "),
                    style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // 出口:經該伺服器查到的、網站實際看到的 IP
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Public, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    when {
                        probe.checkingExit -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        exit != null -> Text(
                            listOfNotNull(stringResource(R.string.label_exit), placeLabel(exit), exit.ip).joinToString("  "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        else -> Text(
                            stringResource(R.string.label_exit_unknown),
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(
                        if (p.authType == AuthType.PASSWORD) Icons.Outlined.Password else Icons.Outlined.Key,
                        null, Modifier.size(14.dp), tint = muted,
                    )
                    Text(
                        buildString {
                            append(
                                when (p.authType) {
                                    AuthType.PASSWORD -> stringResource(R.string.auth_password)
                                    AuthType.KEY -> stringResource(R.string.auth_key)
                                    AuthType.KEY_AND_PASSWORD -> stringResource(R.string.auth_key_password)
                                },
                            )
                            append(" · ×").append(p.connections)
                            if (p.udpgwEnabled) append(" · UDP")
                        },
                        style = MaterialTheme.typography.labelSmall, color = muted,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                LatencyBadge(
                    ms = liveRtt ?: probe.rttMs,
                    failed = liveRtt == null && probe.failed,
                    measuring = probe.measuring && probe.rttMs == null,
                    live = liveRtt != null,
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_check_exit)) },
                            leadingIcon = { Icon(Icons.Outlined.Public, null) },
                            enabled = !probe.checkingExit,
                            onClick = { menu = false; onCheckExit() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_duplicate)) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}
