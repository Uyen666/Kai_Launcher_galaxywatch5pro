package com.wristhub.launcher.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wristhub.launcher.hardware.WatchTimerManager

/**
 * 懸浮於錶面上方的倒數計時小膠囊
 * 顯示剩餘分秒 (如 ⏱️ 02:45)，點擊可取消計時
 */
@Composable
fun TimerBadge(
    modifier: Modifier = Modifier
) {
    val isTimerActive by WatchTimerManager.isTimerActive.collectAsState()
    val remainingSecs by WatchTimerManager.remainingSeconds.collectAsState()

    AnimatedVisibility(
        visible = isTimerActive && remainingSecs > 0,
        enter = fadeIn(tween(200)) + expandVertically(tween(250)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(200)),
        modifier = modifier
    ) {
        val formattedTime = remember(remainingSecs) {
            val m = remainingSecs / 60
            val s = remainingSecs % 60
            String.format("%02d:%02d", m, s)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xDDFF8C00))
                .clickable {
                    WatchTimerManager.cancelTimer()
                }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = "⏱️",
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = formattedTime,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.White
            )
        }
    }
}
