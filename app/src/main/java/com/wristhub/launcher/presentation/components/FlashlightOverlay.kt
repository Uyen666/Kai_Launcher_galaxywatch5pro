package com.wristhub.launcher.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wristhub.launcher.hardware.WatchHardwareManager

/**
 * 全螢幕純白極致增亮手電筒覆蓋層 (AMOLED 1.0f 亮度)
 * 輕觸螢幕任意處即可立即關閉手電筒並還原亮度
 */
@Composable
fun FlashlightOverlay(
    modifier: Modifier = Modifier
) {
    val isFlashlightOn by WatchHardwareManager.isFlashlightOn.collectAsState()

    AnimatedVisibility(
        visible = isFlashlightOn,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(200)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color.White)
                .clickable {
                    WatchHardwareManager.setFlashlight(false)
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "🔦",
                    fontSize = 28.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap anywhere to turn off",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0x77000000)
                )
            }
        }
    }
}
