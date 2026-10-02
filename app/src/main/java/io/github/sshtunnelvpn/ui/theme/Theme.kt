package io.github.sshtunnelvpn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.sshtunnelvpn.data.ThemeMode

// 靜態配色(無 Material You 時):以青綠為主色,呼應「安全通道」意象
private val Light = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2E4),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    secondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFF456179),
    tertiaryContainer = Color(0xFFCCE5FF),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    background = Color(0xFFF4FBF8),
    surface = Color(0xFFF4FBF8),
    surfaceVariant = Color(0xFFDAE5E1),
    surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3EAE7),
    surfaceContainerLow = Color(0xFFEFF5F2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF82D5C8),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF9EF2E4),
    secondary = Color(0xFFB1CCC6),
    secondaryContainer = Color(0xFF334B47),
    tertiary = Color(0xFFADCAE5),
    tertiaryContainer = Color(0xFF2D4960),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    background = Color(0xFF0E1513),
    surface = Color(0xFF0E1513),
    surfaceVariant = Color(0xFF3F4946),
    surfaceContainer = Color(0xFF1A2120),
    surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerLow = Color(0xFF161D1C),
)

@Composable
fun AppTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
