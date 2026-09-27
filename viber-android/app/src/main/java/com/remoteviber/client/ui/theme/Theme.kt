package com.remoteviber.client.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = ViberCyan,
    onPrimary = ViberBg,
    primaryContainer = ViberSurface,
    onPrimaryContainer = ViberCyan,
    secondary = ViberIndigo,
    onSecondary = TextPrimary,
    background = ViberBg,
    onBackground = TextPrimary,
    surface = ViberSurface,
    onSurface = TextPrimary,
    surfaceVariant = ViberCard,
    onSurfaceVariant = TextSecondary,
    outline = ViberBorder
)

@Composable
fun RemoteViberTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = ViberBg.toArgb()
            window.navigationBarColor = ViberBg.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
