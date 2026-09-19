package com.wristhub.launcher.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.wristhub.launcher.audio.WakeAssistantManager

/**
 * 專屬圓形手錶外框的 Apple Intelligence / Siri 風格 Gemini 霓虹光環
 * 當抬腕偵測到說話或喚醒助理時，在手錶圓形錶框邊緣流轉動態漸層光暈
 */
@Composable
fun GeminiAuraOverlay(
    modifier: Modifier = Modifier
) {
    val isVisible by WakeAssistantManager.isAuraVisible.collectAsState()
    val audioEnergy by WakeAssistantManager.audioEnergy.collectAsState()

    // 平滑淡入 (250ms) 與淡出 (500ms，依使用者規範 0.5 秒消失)
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isVisible) 250 else 500,
            easing = FastOutSlowInEasing
        ),
        label = "AuraAlpha"
    )

    if (alpha <= 0.01f) return

    // 順暢旋轉動畫
    val infiniteTransition = rememberInfiniteTransition(label = "AuraRingRotation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "AuraAngle"
    )

    // 呼吸縮放 (根據音訊能量擴張)
    val energyBonus = (audioEnergy * 3.5f).coerceIn(0f, 4f)

    // 頂級科技感色彩配置 (Google Cyan -> Blue -> Gemini Purple -> Neon Coral -> Amber)
    val auraColors = remember {
        listOf(
            Color(0xFF24D8A7), // Neon Cyan
            Color(0xFF4285F4), // Google Blue
            Color(0xFF9B51E0), // Gemini Violet
            Color(0xFFFF2A85), // Neon Pink/Magenta
            Color(0xFFFFB800), // Electric Amber
            Color(0xFF24D8A7)  // Loop closure
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(CircleShape)
            .graphicsLayer {
                this.alpha = alpha
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f

            val brush = Brush.sweepGradient(
                colors = auraColors,
                center = center
            )

            // 外層高亮邊緣主環 (4dp + 音量爆發)
            val strokeWidth = (4.dp.toPx() + energyBonus * 2.5f)
            val drawRadius = radius - strokeWidth / 2f

            // 旋轉動態
            drawContext.transform.rotate(rotationAngle, center)

            // 內外雙重光暈：第一層主邊框環
            drawCircle(
                brush = brush,
                radius = drawRadius,
                center = center,
                style = Stroke(width = strokeWidth)
            )

            // 第二層內部半透明微光擴散環 (柔和彌散效果)
            val innerGlowWidth = strokeWidth * 1.8f
            drawCircle(
                brush = brush,
                radius = drawRadius - strokeWidth * 0.4f,
                center = center,
                alpha = 0.35f,
                style = Stroke(width = innerGlowWidth)
            )
        }
    }
}
