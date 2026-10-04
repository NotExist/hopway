package not.exist.hopway.ui.profiles

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import not.exist.hopway.R
import not.exist.hopway.data.IpInfo

/** ✓ / ✗ / ? 與顏色、讀螢幕描述。 */
@Composable
fun familyMark(capable: Boolean?, v6: Boolean): Triple<String, Color, String> {
    val name = if (v6) "IPv6" else "IPv4"
    return when (capable) {
        true -> Triple("✓", MaterialTheme.colorScheme.primary, stringResource(R.string.family_yes_desc, name))
        false -> Triple("✗", MaterialTheme.colorScheme.error, stringResource(R.string.family_no_desc, name))
        null -> Triple("?", MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.family_unknown_desc, name))
    }
}

/**
 * 一個協定的一行:「IPv4 ✓  出口 🇯🇵 JP · Tokyo  203.0.113.10」或「IPv6 ✗  伺服器無 IPv6」。
 * 伺服器能力未測時顯示「?」。
 */
@Composable
fun FamilyRow(v6: Boolean, capable: Boolean?, exit: IpInfo?, style: TextStyle = MaterialTheme.typography.bodySmall) {
    val (mark, color, desc) = familyMark(capable, v6)
    val name = if (v6) "IPv6" else "IPv4"
    val detail = when {
        exit != null -> listOfNotNull(stringResource(R.string.label_exit), placeLabel(exit), exit.ip).joinToString("  ")
        capable == false -> stringResource(R.string.family_server_lacks, name)
        capable == true -> stringResource(R.string.label_exit_unknown_short)
        else -> stringResource(R.string.family_untested)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$name $mark", style = style, color = color, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clearAndSetSemantics { contentDescription = desc },
        )
        Spacer(Modifier.width(8.dp))
        Text(detail, style = style, color = if (exit != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
