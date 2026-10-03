package io.github.sshtunnelvpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sshtunnelvpn.R
import io.github.sshtunnelvpn.tunnel.TrafficSample

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(
    title: String,
    onBack: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun PrefItem(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.38f
    ListItem(
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)) },
        supportingContent = summary?.let {
            { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)) }
        },
        leadingContent = icon?.let {
            { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)) }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
fun SwitchPref(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    PrefItem(
        title = title, summary = summary, icon = icon, enabled = enabled,
        trailing = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        onClick = { onChange(!checked) },
    )
}

@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == selected, role = Role.RadioButton) {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String? = null,
    supporting: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    validate: (String) -> Boolean = { true },
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val valid = validate(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = label?.let { { Text(it) } },
                supportingText = supporting?.let { { Text(it) } },
                isError = !valid,
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 4,
                keyboardOptions = keyboardOptions,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(value.trim()); onDismiss() }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 即時流量曲線:下載實線+漸層填色,上傳細線。 */
@Composable
fun SpeedChart(samples: List<TrafficSample>, capacity: Int, modifier: Modifier = Modifier) {
    val rxColor = MaterialTheme.colorScheme.primary
    val txColor = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 1..3) {
            val y = h * i / 4
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (samples.size < 2) return@Canvas
        val maxV = (samples.maxOf { maxOf(it.rxRate, it.txRate) }.coerceAtLeast(1024)).toFloat() * 1.15f
        val step = w / (capacity - 1)
        val offset = (capacity - samples.size) * step
        fun path(sel: (TrafficSample) -> Long): Path = Path().apply {
            samples.forEachIndexed { i, s ->
                val x = offset + i * step
                val y = h - sel(s) / maxV * h
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        val rx = path { it.rxRate }
        val fill = Path().apply {
            addPath(rx)
            lineTo(offset + (samples.size - 1) * step, h)
            lineTo(offset, h)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(rxColor.copy(alpha = 0.35f), rxColor.copy(alpha = 0f))))
        drawPath(rx, rxColor, style = Stroke(width = 2.5.dp.toPx()))
        drawPath(path { it.txRate }, txColor, style = Stroke(width = 1.5.dp.toPx()))
    }
}

/** 延遲標籤:<80 ms 綠、<200 ms 黃、其餘紅;失敗灰色「—」;live = 來自 SSH keepalive 的即時值。 */
@Composable
fun LatencyBadge(ms: Long?, failed: Boolean, measuring: Boolean, live: Boolean, modifier: Modifier = Modifier) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val (bg, fg) = when {
        failed -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        ms == null -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        ms < 80 -> (if (dark) Color(0xFF1B5E20) else Color(0xFFC8E6C9)) to (if (dark) Color(0xFFC8E6C9) else Color(0xFF1B5E20))
        ms < 200 -> (if (dark) Color(0xFF5D4300) else Color(0xFFFFE082)) to (if (dark) Color(0xFFFFE082) else Color(0xFF5D4300))
        else -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    androidx.compose.material3.Surface(color = bg, contentColor = fg, shape = androidx.compose.foundation.shape.RoundedCornerShape(50), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            if (live) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .padding(end = 6.dp)
                        .size(6.dp)
                        .background(fg, androidx.compose.foundation.shape.CircleShape),
                )
            }
            when {
                ms != null && !failed -> Text("$ms ms", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                measuring -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = fg)
                else -> Text(if (failed) stringResource(R.string.latency_timeout) else "—", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
