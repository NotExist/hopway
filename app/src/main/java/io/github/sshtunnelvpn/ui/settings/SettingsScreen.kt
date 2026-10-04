package io.github.sshtunnelvpn.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sshtunnelvpn.BuildConfig
import io.github.sshtunnelvpn.R
import io.github.sshtunnelvpn.container
import io.github.sshtunnelvpn.data.AppMode
import io.github.sshtunnelvpn.data.AppSettings
import io.github.sshtunnelvpn.data.Ipv6Mode
import io.github.sshtunnelvpn.data.ThemeMode
import io.github.sshtunnelvpn.tunnel.Cidr
import io.github.sshtunnelvpn.ui.Route
import io.github.sshtunnelvpn.ui.components.BackTopBar
import io.github.sshtunnelvpn.ui.components.ChoiceDialog
import io.github.sshtunnelvpn.ui.components.PrefItem
import io.github.sshtunnelvpn.ui.components.SectionHeader
import io.github.sshtunnelvpn.ui.components.SwitchPref
import io.github.sshtunnelvpn.ui.components.TextInputDialog
import kotlinx.coroutines.launch

private enum class Dialog { DNS, EXCLUDED, MTU, APP_MODE, SOCKS_PORT, THEME, LOG_LEVEL, IPV6 }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navigate: (Route) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val s by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    var dialog by rememberSaveable { mutableStateOf<Dialog?>(null) }
    fun set(f: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(f) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    // 系統 VPN 清單只列出已授權的 App:先取得授權再開設定頁
    val openVpnSettings = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
    val authorizeThenOpen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) openVpnSettings()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { BackTopBar(stringResource(R.string.title_settings), onBack, scroll) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (c.tunnel.isActive) {
                Text(
                    stringResource(R.string.settings_apply_on_reconnect),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            SectionHeader(stringResource(R.string.section_dns))
            PrefItem(stringResource(R.string.pref_dns_upstream), s.dnsUpstream, Icons.Outlined.Dns) { dialog = Dialog.DNS }
            SwitchPref(stringResource(R.string.pref_dns_cache), stringResource(R.string.pref_dns_cache_summary),
                Icons.Outlined.Cached, s.dnsCache) { v -> set { it.copy(dnsCache = v) } }

            SectionHeader(stringResource(R.string.section_routing))
            PrefItem(
                stringResource(R.string.pref_ipv6),
                ipv6ModeLabel(s.ipv6Mode) + "\n" + ipv6ModeDescription(s.ipv6Mode),
                Icons.Outlined.Language,
            ) { dialog = Dialog.IPV6 }
            SwitchPref(stringResource(R.string.pref_bypass_lan), stringResource(R.string.pref_bypass_lan_summary),
                Icons.Outlined.Lan, s.bypassLan) { v -> set { it.copy(bypassLan = v) } }
            PrefItem(
                stringResource(R.string.pref_excluded_routes),
                Cidr.parseList(s.excludedRoutes).joinToString(", ").ifBlank { stringResource(R.string.pref_none) },
                Icons.Outlined.CallSplit,
            ) { dialog = Dialog.EXCLUDED }
            PrefItem(stringResource(R.string.pref_mtu), s.mtu.toString(), Icons.Outlined.Straighten) { dialog = Dialog.MTU }

            SectionHeader(stringResource(R.string.section_apps))
            PrefItem(stringResource(R.string.pref_app_mode), appModeLabel(s.appMode), Icons.Outlined.VpnLock) { dialog = Dialog.APP_MODE }
            PrefItem(
                stringResource(R.string.pref_select_apps),
                stringResource(R.string.pref_select_apps_summary, s.appPackages.size),
                Icons.Outlined.Apps,
                enabled = s.appMode != AppMode.ALL,
            ) { navigate(Route.AppPicker) }

            SectionHeader(stringResource(R.string.section_proxy))
            SwitchPref(stringResource(R.string.pref_socks), stringResource(R.string.pref_socks_summary),
                Icons.Outlined.Hub, s.socksEnabled) { v -> set { it.copy(socksEnabled = v) } }
            PrefItem(stringResource(R.string.pref_socks_port), s.socksPort.toString(), Icons.Outlined.Router,
                enabled = s.socksEnabled) { dialog = Dialog.SOCKS_PORT }
            SwitchPref(stringResource(R.string.pref_socks_lan), stringResource(R.string.pref_socks_lan_summary),
                Icons.Outlined.Wifi, s.socksAllowLan, enabled = s.socksEnabled) { v -> set { it.copy(socksAllowLan = v) } }

            SectionHeader(stringResource(R.string.section_security))
            PrefItem(stringResource(R.string.title_known_hosts), stringResource(R.string.pref_known_hosts_summary),
                Icons.Outlined.Fingerprint) { navigate(Route.KnownHosts) }
            PrefItem(stringResource(R.string.pref_always_on), stringResource(R.string.pref_always_on_summary),
                Icons.Outlined.VpnLock) {
                c.tunnel.prepare()?.let(authorizeThenOpen::launch) ?: openVpnSettings()
            }

            SectionHeader(stringResource(R.string.section_appearance))
            PrefItem(stringResource(R.string.pref_theme), themeLabel(s.theme), Icons.Outlined.DarkMode) { dialog = Dialog.THEME }
            if (Build.VERSION.SDK_INT >= 31) {
                SwitchPref(stringResource(R.string.pref_dynamic_color), stringResource(R.string.pref_dynamic_color_summary),
                    Icons.Outlined.Palette, s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
            }

            SectionHeader(stringResource(R.string.section_about))
            PrefItem(stringResource(R.string.pref_log_level), logLevelLabel(s.logLevel), Icons.Outlined.BugReport) {
                dialog = Dialog.LOG_LEVEL
            }
            SwitchPref(stringResource(R.string.pref_log_owners), stringResource(R.string.pref_log_owners_summary),
                Icons.Outlined.Fingerprint, s.logConnectionOwners) { v -> set { it.copy(logConnectionOwners = v) } }
            PrefItem(
                stringResource(R.string.pref_version),
                buildString {
                    append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")")
                    append('\n').append(BuildConfig.BUILD_TIME)
                    if (BuildConfig.BUILD_COMMIT.isNotEmpty()) append(" · ").append(BuildConfig.BUILD_COMMIT)
                },
                Icons.Outlined.Info,
            )
            PrefItem(stringResource(R.string.pref_ipinfo), stringResource(R.string.pref_ipinfo_summary), Icons.Outlined.Public) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ipinfo.io")))
            }
            PrefItem(stringResource(R.string.pref_inspired), "github.com/Anton2319/VPNoverSSH", Icons.Outlined.Code) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Anton2319/VPNoverSSH")))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(16.dp))
        }
    }

    when (dialog) {
        Dialog.DNS -> DnsDialog(s.dnsUpstream, onDismiss = { dialog = null }) { v -> set { it.copy(dnsUpstream = v) } }
        Dialog.EXCLUDED -> TextInputDialog(
            title = stringResource(R.string.pref_excluded_routes),
            initial = s.excludedRoutes,
            supporting = stringResource(R.string.pref_excluded_routes_hint),
            singleLine = false,
            validate = { v -> v.split(',', '\n', ' ', ';').filter { it.isNotBlank() }.all { Cidr.parse(it) != null } },
            onDismiss = { dialog = null },
        ) { v -> set { it.copy(excludedRoutes = v) } }
        Dialog.MTU -> TextInputDialog(
            title = stringResource(R.string.pref_mtu),
            initial = s.mtu.toString(),
            supporting = stringResource(R.string.pref_mtu_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            validate = { it.toIntOrNull() in 1280..16000 },
            onDismiss = { dialog = null },
        ) { v -> set { it.copy(mtu = v.toInt()) } }
        Dialog.SOCKS_PORT -> TextInputDialog(
            title = stringResource(R.string.pref_socks_port),
            initial = s.socksPort.toString(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            validate = { it.toIntOrNull() in 1024..65535 },
            onDismiss = { dialog = null },
        ) { v -> set { it.copy(socksPort = v.toInt()) } }
        Dialog.APP_MODE -> ChoiceDialog(
            stringResource(R.string.pref_app_mode),
            AppMode.entries.map { it to appModeLabel(it) },
            s.appMode, onDismiss = { dialog = null },
        ) { v -> set { it.copy(appMode = v) } }
        Dialog.THEME -> ChoiceDialog(
            stringResource(R.string.pref_theme),
            ThemeMode.entries.map { it to themeLabel(it) },
            s.theme, onDismiss = { dialog = null },
        ) { v -> set { it.copy(theme = v) } }
        Dialog.IPV6 -> ChoiceDialog(
            stringResource(R.string.pref_ipv6),
            Ipv6Mode.entries.map { it to ipv6ModeLabel(it) },
            s.ipv6Mode, onDismiss = { dialog = null },
            descriptions = Ipv6Mode.entries.associateWith { ipv6ModeDescription(it) },
            header = stringResource(R.string.ipv6_dialog_header),
        ) { v -> set { it.copy(ipv6Mode = v) } }
        Dialog.LOG_LEVEL -> ChoiceDialog(
            stringResource(R.string.pref_log_level),
            (0..3).map { it to logLevelLabel(it) },
            s.logLevel, onDismiss = { dialog = null },
        ) { v -> set { it.copy(logLevel = v) } }
        null -> {}
    }
}

@Composable
private fun DnsDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val presets = listOf(
        "1.1.1.1" to "Cloudflare",
        "8.8.8.8" to "Google",
        "9.9.9.9" to "Quad9",
        "208.67.222.222" to "OpenDNS",
    )
    var custom by rememberSaveable { mutableStateOf(false) }
    if (custom) {
        TextInputDialog(
            title = stringResource(R.string.pref_dns_upstream),
            initial = current,
            supporting = stringResource(R.string.pref_dns_upstream_hint),
            // 可填多個(逗號分隔);每一項須是 host 或 host:port
            validate = { v -> v.split(',').map { it.trim() }.let { l -> l.isNotEmpty() && l.all { it.isNotEmpty() && ' ' !in it } } },
            onDismiss = onDismiss,
            onConfirm = onConfirm,
        )
        return
    }
    val pickedCustom = remember { booleanArrayOf(false) }
    val options = presets.map { (ip, name) -> ip to "$name ($ip)" } + ("" to stringResource(R.string.pref_custom))
    ChoiceDialog(
        stringResource(R.string.pref_dns_upstream),
        options,
        if (presets.any { it.first == current }) current else "",
        onDismiss = { if (!pickedCustom[0]) onDismiss() },
    ) { v ->
        if (v.isEmpty()) {
            pickedCustom[0] = true
            custom = true
        } else {
            onConfirm(v)
        }
    }
}

@Composable
private fun appModeLabel(m: AppMode) = stringResource(
    when (m) {
        AppMode.ALL -> R.string.app_mode_all
        AppMode.ALLOW -> R.string.app_mode_allow
        AppMode.DISALLOW -> R.string.app_mode_disallow
    },
)

@Composable
private fun ipv6ModeLabel(m: Ipv6Mode) = stringResource(
    when (m) {
        Ipv6Mode.AUTO -> R.string.ipv6_mode_auto
        Ipv6Mode.TUNNEL -> R.string.ipv6_mode_tunnel
        Ipv6Mode.BLOCK -> R.string.ipv6_mode_block
    },
)

@Composable
private fun ipv6ModeDescription(m: Ipv6Mode) = stringResource(
    when (m) {
        Ipv6Mode.AUTO -> R.string.ipv6_mode_auto_desc
        Ipv6Mode.TUNNEL -> R.string.ipv6_mode_tunnel_desc
        Ipv6Mode.BLOCK -> R.string.ipv6_mode_block_desc
    },
)

@Composable
private fun themeLabel(t: ThemeMode) = stringResource(
    when (t) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)

@Composable
private fun logLevelLabel(l: Int) = stringResource(
    when (l) {
        0 -> R.string.log_debug
        1 -> R.string.log_info
        2 -> R.string.log_warn
        else -> R.string.log_error
    },
)
