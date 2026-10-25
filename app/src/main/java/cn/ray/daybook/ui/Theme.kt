package cn.ray.daybook.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import cn.ray.daybook.R
import cn.ray.daybook.data.AppSettings

val BookFont = FontFamily(Font(R.font.noto_serif_sc))
private val Light = lightColorScheme(
    primary = Color(0xFFA44735), onPrimary = Color(0xFFFFF8EF),
    secondary = Color(0xFF6A7260), onSecondary = Color.White,
    background = Color(0xFFF8F3E8), onBackground = Color(0xFF342F28),
    surface = Color(0xFFF8F3E8), onSurface = Color(0xFF342F28),
    surfaceVariant = Color(0xFFEFE7D8), onSurfaceVariant = Color(0xFF776B5C),
    outline = Color(0xFF9B8F7C), outlineVariant = Color(0xFFD9CEBA),
    surfaceContainer = Color(0xFFF0E8D9), surfaceContainerHigh = Color(0xFFF2EBDD),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFE2997F), onPrimary = Color(0xFF302015),
    secondary = Color(0xFFBAC4A7), onSecondary = Color(0xFF22291E),
    background = Color(0xFF24221D), onBackground = Color(0xFFEEE5D6),
    surface = Color(0xFF24221D), onSurface = Color(0xFFEEE5D6),
    surfaceVariant = Color(0xFF332E25), onSurfaceVariant = Color(0xFFB8AA96),
    outline = Color(0xFF9E8E78), outlineVariant = Color(0xFF554B3D),
    surfaceContainer = Color(0xFF2C2821), surfaceContainerHigh = Color(0xFF383127),
)

@Composable
fun DaybookTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.appearance) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val density = LocalDensity.current
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * settings.textScale)) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
