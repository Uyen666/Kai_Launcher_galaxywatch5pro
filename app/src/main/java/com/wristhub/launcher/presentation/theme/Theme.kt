package com.wristhub.launcher.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme

@Composable
fun WristHubTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colors = WristHubColorPalette,
        content = content
    )
}
