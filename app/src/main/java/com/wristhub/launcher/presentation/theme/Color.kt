package com.wristhub.launcher.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors

val CyanNeon = Color(0xFF00E5FF)
val CyanNeonDark = Color(0xFF00838F)
val GreenNeon = Color(0xFF00E676)
val OrangeNeon = Color(0xFFFF9100)
val RedNeon = Color(0xFFFF5252)

val BackgroundBlack = Color(0xFF000000)
val SurfaceDark = Color(0xFF14181D)
val SurfaceVariant = Color(0xFF222933)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF9EABB8)

val WristHubColorPalette = Colors(
    primary = CyanNeon,
    primaryVariant = CyanNeonDark,
    secondary = GreenNeon,
    secondaryVariant = OrangeNeon,
    error = RedNeon,
    background = BackgroundBlack,
    surface = SurfaceDark,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onError = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)
