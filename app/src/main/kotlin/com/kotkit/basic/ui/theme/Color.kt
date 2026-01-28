package com.kotkit.basic.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ============================================
// Brand Colors - основные цвета бренда
// ============================================
val BrandCyan = Color(0xFF00F0FF)
val BrandCyanLight = Color(0xFF33F3FF)
val BrandCyanDark = Color(0xFF00C4D1)

val BrandPink = Color(0xFFFE2C55)
val BrandPinkLight = Color(0xFFFF5C7C)
val BrandPinkDark = Color(0xFFD91A3F)

// Legacy aliases
val TikTokPink = BrandPink
val TikTokCyan = BrandCyan
val TikTokBlack = Color(0xFF000000)
val Primary = BrandPink
val PrimaryVariant = BrandPinkDark
val Secondary = BrandCyan

// ============================================
// Surface Colors - поверхности и фоны (Dark Theme)
// ============================================
val SurfaceBase = Color(0xFF000000)
val SurfaceElevated1 = Color(0x0DFFFFFF) // 5%
val SurfaceElevated2 = Color(0x1AFFFFFF) // 10%
val SurfaceElevated3 = Color(0x26FFFFFF) // 15%

// Glass surfaces
val SurfaceGlassLight = Color(0x14FFFFFF) // 8%
val SurfaceGlassMedium = Color(0x1FFFFFFF) // 12%
val SurfaceGlassHeavy = Color(0x33FFFFFF) // 20%

// Legacy
val BackgroundDark = SurfaceBase
val SurfaceDark = SurfaceElevated1
val SurfaceElevatedDark = SurfaceElevated2

// Light theme (rarely used)
val BackgroundLight = Color(0xFFFAFAFA)
val SurfaceLight = Color(0xFFFFFFFF)

// ============================================
// Border Colors - границы
// ============================================
val BorderSubtle = Color(0x14FFFFFF) // 8%
val BorderDefault = Color(0x26FFFFFF) // 15%
val BorderStrong = Color(0x40FFFFFF) // 25%
val BorderAccentCyan = Color(0x4D00F0FF) // 30%
val BorderAccentPink = Color(0x4DFE2C55) // 30%

// ============================================
// Text Colors
// ============================================
val TextPrimary = Color(0xFFFFFFFF) // 100%
val TextSecondary = Color(0xD9FFFFFF) // 85%
val TextTertiary = Color(0x99FFFFFF) // 60%
val TextMuted = Color(0x66FFFFFF) // 40%
val TextDisabled = Color(0x4DFFFFFF) // 30%

// Legacy
val TextPrimaryDark = TextPrimary
val TextSecondaryDark = TextSecondary
val TextPrimaryLight = Color(0xFF1A1A1A)
val TextSecondaryLight = Color(0xFF757575)

// ============================================
// Status Colors
// ============================================
val Success = Color(0xFF00D26A)
val SuccessLight = Color(0xFF00B894)
val Error = Color(0xFFFF4757)
val ErrorLight = Color(0xFFFF6B81)
val Warning = Color(0xFFFFB800)
val WarningLight = Color(0xFFFF9500)
val Info = Color(0xFF3B82F6)
val InfoLight = Color(0xFF60A5FA)

// Status badge colors
val StatusScheduled = Info
val StatusPosting = Warning
val StatusCompleted = Success
val StatusFailed = Error
val StatusNeedsAction = BrandPink
val StatusCancelled = Color(0xFF6B7280)

// ============================================
// Gradient Colors
// ============================================
val GradientPink = BrandPink
val GradientCyan = BrandCyan
val GradientPurple = Color(0xFF8B5CF6)
val GradientBlue = Color(0xFF3B82F6)
val GradientOrange = Color(0xFFFF6B35)

// ============================================
// Gradients - готовые градиенты
// ============================================
val BrandGradient = Brush.linearGradient(
    colors = listOf(BrandCyan, BrandPink)
)

val BrandGradientReverse = Brush.linearGradient(
    colors = listOf(BrandPink, BrandCyan)
)

val TikTokGradient = BrandGradient

val PrimaryGradient = Brush.linearGradient(
    colors = listOf(BrandPink, GradientOrange)
)

val AccentGradient = Brush.linearGradient(
    colors = listOf(BrandCyan, GradientBlue)
)

val PurpleGradient = Brush.linearGradient(
    colors = listOf(GradientPurple, BrandPink)
)

val SuccessGradient = Brush.linearGradient(
    colors = listOf(Success, SuccessLight)
)

val WarningGradient = Brush.linearGradient(
    colors = listOf(Warning, WarningLight)
)

val ErrorGradient = Brush.linearGradient(
    colors = listOf(Error, ErrorLight)
)

val InfoGradient = Brush.linearGradient(
    colors = listOf(Info, InfoLight)
)

// Glass gradient for cards
val GlassGradient = Brush.linearGradient(
    colors = listOf(
        BrandCyan.copy(alpha = 0.1f),
        BrandPink.copy(alpha = 0.1f)
    )
)

val GlowGradient = Brush.linearGradient(
    colors = listOf(
        BrandCyan.copy(alpha = 0.2f),
        BrandPink.copy(alpha = 0.2f)
    )
)

// Card gradients
val CardGradientDark = Brush.linearGradient(
    colors = listOf(
        SurfaceElevated1,
        SurfaceElevated2
    )
)

val CardGradientLight = Brush.linearGradient(
    colors = listOf(
        Color(0xFFFFFFFF),
        Color(0xFFF8F8F8)
    )
)

// ============================================
// Glow Colors (for shadows)
// ============================================
val GlowCyan = BrandCyan.copy(alpha = 0.3f)
val GlowPink = BrandPink.copy(alpha = 0.3f)
val GlowBrand = BrandCyan.copy(alpha = 0.25f)

// Legacy
val GlassLight = SurfaceGlassLight
val GlassDark = SurfaceGlassMedium
val GlassBorderLight = BorderDefault
val GlassBorderDark = BorderSubtle

// Accent colors (aliases for WorkerDashboard)
val AccentGreen = Success
val AccentOrange = GradientOrange
val AccentBlue = Info
val SurfaceElevated = SurfaceElevated2
