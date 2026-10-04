package not.exist.hopway.ui.apps

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import not.exist.hopway.R
import not.exist.hopway.container
import not.exist.hopway.data.AppMode
import not.exist.hopway.data.AppSettings
import not.exist.hopway.ui.components.BackTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String, val system: Boolean, val info: ApplicationInfo)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val pm = ctx.packageManager
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != ctx.packageName }
                .map {
                    AppEntry(
                        it.packageName, it.loadLabel(pm).toString(),
                        it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0, it,
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    val selected = settings.appPackages
    // 已勾選的排前面;排序只在進入畫面時決定,避免勾選時列表跳動
    val initialSelected = remember(apps) { selected }
    val visible = apps.orEmpty()
        .filter { showSystem || !it.system || it.pkg in selected }
        .filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
        .sortedByDescending { it.pkg in initialSelected }

    fun setSelection(pkgs: Set<String>) = scope.launch { c.settings.update { it.copy(appPackages = pkgs) } }

    Scaffold(
        topBar = {
            BackTopBar(
                stringResource(
                    if (settings.appMode == AppMode.DISALLOW) R.string.title_apps_bypass else R.string.title_apps_proxy,
                ),
                onBack,
            ) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (showSystem) R.string.apps_hide_system else R.string.apps_show_system)) },
                            onClick = { showSystem = !showSystem; menu = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.apps_select_visible)) },
                            onClick = { setSelection(selected + visible.map { it.pkg }); menu = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.apps_clear)) },
                            onClick = { setSelection(emptySet()); menu = false },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, null) }
                },
                placeholder = { Text(stringResource(R.string.apps_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                stringResource(R.string.pref_select_apps_summary, selected.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (apps == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.pkg }) { app ->
                    val checked = app.pkg in selected
                    ListItem(
                        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(app.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        },
                        leadingContent = { AppIcon(app.info, pm) },
                        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        modifier = Modifier.clickable {
                            setSelection(if (checked) selected - app.pkg else selected + app.pkg)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIcon(info: ApplicationInfo, pm: PackageManager) {
    val icon by produceState<ImageBitmap?>(null, info.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { info.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    val bmp = icon
    if (bmp != null) {
        Image(bmp, null, Modifier.size(40.dp))
    } else {
        Icon(Icons.Outlined.Android, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
    }
}
