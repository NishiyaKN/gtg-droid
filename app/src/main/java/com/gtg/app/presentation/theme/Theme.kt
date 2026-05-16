package com.gtg.app.presentation.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val GtgDarkColorScheme = darkColorScheme(
    primary = GtgPrimary,
    onPrimary = GtgOnPrimary,
    primaryContainer = GtgPrimaryContainer,
    onPrimaryContainer = GtgOnPrimary,
    secondary = GtgPrimary,
    onSecondary = GtgOnPrimary,
    background = GtgBackground,
    onBackground = GtgOnBackground,
    surface = GtgSurface,
    onSurface = GtgOnSurface,
    surfaceVariant = GtgSurfaceVariant,
    onSurfaceVariant = GtgOnSurfaceVariant,
    error = GtgError,
    onError = GtgOnPrimary,
)

/**
 * Tema forçado dark para o app GtG.
 * Não oferece light theme — dark by design.
 */
@Composable
fun GtgTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = GtgDarkColorScheme,
        content = content,
    )
}
