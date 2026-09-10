package app.consolepocket.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.consolepocket.model.DarkMode

private val Brand = Color(0xFF0B5C63)
private val BrandLight = Color(0xFF7FD1D8)

private val LightColors = lightColorScheme(
    primary = Brand,
    secondary = Brand,
    tertiary = Color(0xFF3C7C84),
)

private val DarkColors = darkColorScheme(
    primary = BrandLight,
    secondary = BrandLight,
    tertiary = Color(0xFF9FE0E6),
)

private val AppTypography = Typography()

@Composable
fun ConsolePocketTheme(
    darkMode: DarkMode = DarkMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (darkMode) {
        DarkMode.SYSTEM -> systemDark
        DarkMode.ON -> true
        DarkMode.OFF -> false
    }

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
