package io.github.sshtunnelvpn.ui.logs

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sshtunnelvpn.R
import io.github.sshtunnelvpn.container
import io.github.sshtunnelvpn.tunnel.LogEntry
import io.github.sshtunnelvpn.ui.components.BackTopBar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
private val levelTag = arrayOf("D", "I", "W", "E")

private fun LogEntry.format() = "${timeFmt.format(Date(time))} ${levelTag.getOrElse(level) { "?" }} $message"

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val logs = ctx.container.logs
    val entries by logs.entries.collectAsStateWithLifecycle()
    var minLevel by rememberSaveable { mutableIntStateOf(0) }
    var filterMenu by remember { mutableStateOf(false) }
    val visible = entries.filter { it.level >= minLevel }
    val list = rememberLazyListState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // 停在底部時自動跟隨新日誌
    LaunchedEffect(visible.size) {
        val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (visible.isNotEmpty() && last >= visible.size - 3) list.scrollToItem(visible.lastIndex)
    }

    fun allText() = visible.joinToString("\n") { it.format() }

    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.title_logs), onBack) {
                Box {
                    IconButton(onClick = { filterMenu = true }) { Icon(Icons.Outlined.FilterList, stringResource(R.string.logs_filter)) }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        listOf(R.string.log_debug, R.string.log_info, R.string.log_warn, R.string.log_error).forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                leadingIcon = { RadioButton(selected = minLevel == i, onClick = null) },
                                onClick = { minLevel = i; filterMenu = false },
                            )
                        }
                    }
                }
                IconButton(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("logs", allText()))) }
                }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy)) }
                IconButton(onClick = {
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, allText()), null,
                        ),
                    )
                }) { Icon(Icons.Outlined.Share, stringResource(R.string.action_share)) }
                IconButton(onClick = { logs.clear() }) { Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.action_clear)) }
            }
        },
    ) { padding ->
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        SelectionContainer {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
            ) {
                items(visible, key = { it.seq }) { e ->
                    val color = when (e.level) {
                        3 -> MaterialTheme.colorScheme.error
                        2 -> MaterialTheme.colorScheme.tertiary
                        0 -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(
                            e.format(),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                        )
                    }
                }
            }
        }
    }
}
