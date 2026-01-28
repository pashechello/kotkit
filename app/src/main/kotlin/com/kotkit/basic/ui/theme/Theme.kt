package com.kotkit.basic.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Dark Theme - основная тема (как на landing)
private val DarkColorScheme = darkColorScheme(
    // Primary - Brand Pink
    primary = BrandPink,
    onPrimary = Color.White,
    primaryContainer = BrandPinkDark,
    onPrimaryContainer = Color.White,

    // Secondary - Brand Cyan
    secondary = BrandCyan,
    onSecondary = SurfaceBase,
    secondaryContainer = BrandCyanDark,
    onSecondaryContainer = Color.White,

    // Tertiary
    tertiary = GradientPurple,
    onTertiary = Color.White,

    // Background & Surface - Black base
    background = SurfaceBase,
    onBackground = TextPrimary,
    surface = SurfaceElevated1,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceGlassMedium,
    onSurfaceVariant = TextSecondary,
    surfaceTint = BrandCyan.copy(alpha = 0.1f),

    // Inverse
    inverseSurface = Color.White,
    inverseOnSurface = SurfaceBase,
    inversePrimary = BrandPinkDark,

    // Outline & Borders
    outline = BorderDefault,
    outlineVariant = BorderSubtle,

    // Error
    error = Error,
    onError = Color.White,
    errorContainer = Error.copy(alpha = 0.2f),
    onErrorContainer = ErrorLight,

    // Scrim
    scrim = SurfaceBase.copy(alpha = 0.8f)
)

// Light Theme - запасная
private val LightColorScheme = lightColorScheme(
    primary = BrandPink,
    onPrimary = Color.White,
    primaryContainer = BrandPinkLight,
    secondary = BrandCyan,
    onSecondary = SurfaceBase,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = TextSecondaryLight,
    outline = Color(0xFFE0E0E0),
    error = Error,
    onError = Color.White
)

@Composable
fun KotKitTheme(
    darkTheme: Boolean = true, // Темная тема по умолчанию!
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Чёрный статус бар и навигация
            window.statusBarColor = SurfaceBase.toArgb()
            window.navigationBarColor = SurfaceBase.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
