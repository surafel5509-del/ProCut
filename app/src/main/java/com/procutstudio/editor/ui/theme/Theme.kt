package com.procutstudio.editor.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val ProCutColorScheme = darkColorScheme(
    primary = ProCutPrimary,
    onPrimary = Color.White,
    primaryContainer = ProCutPrimaryDeep,
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = ProCutSecondary,
    onSecondary = Color(0xFF04212A),
    tertiary = ProCutTertiary,
    onTertiary = Color(0xFF2A0A18),
    background = ProCutBackground,
    onBackground = ProCutOnSurface,
    surface = ProCutSurface,
    onSurface = ProCutOnSurface,
    surfaceVariant = ProCutSurfaceElevated,
    onSurfaceVariant = ProCutOnSurfaceMuted,
    surfaceContainer = ProCutSurface,
    surfaceContainerHigh = ProCutSurfaceElevated,
    surfaceContainerHighest = ProCutSurfaceHigh,
    surfaceContainerLow = ProCutBackground,
    surfaceContainerLowest = ProCutBackground,
    outline = ProCutOutline,
    outlineVariant = ProCutOutlineSoft,
    error = ProCutError,
    onError = Color(0xFF2A0710),
    scrim = Color.Black,
)

/**
 * The app is dark-only by design: colour grading and video preview need a neutral, non-reflective
 * canvas, and every competitor ships the same way.
 */
@Composable
fun ProCutTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            window.statusBarColor = ProCutBackground.toArgb()
            window.navigationBarColor = ProCutBackground.toArgb()
        }
    }
    MaterialTheme(
        colorScheme = ProCutColorScheme,
        typography = ProCutTypography,
        content = content,
    )
}
