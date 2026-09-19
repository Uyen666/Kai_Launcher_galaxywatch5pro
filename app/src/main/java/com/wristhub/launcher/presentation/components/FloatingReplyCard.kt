package com.wristhub.launcher.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wristhub.launcher.audio.AssistantUiState
import com.wristhub.launcher.audio.WakeAssistantManager

/**
 * 輕量級毛玻璃懸浮卡片
 * 在手錶上方或中央偏上彈出，顯示使用者語音辨識內容、Gemini 智慧回覆與硬體動作反饋
 * 不干擾手錶主畫面，幾秒後自動收回或點擊立即關閉
 */
@Composable
fun FloatingReplyCard(
    modifier: Modifier = Modifier
) {
    val uiState by WakeAssistantManager.uiState.collectAsState()
    val transcript by WakeAssistantManager.currentTranscript.collectAsState()
    val reply by WakeAssistantManager.currentReply.collectAsState()
    val action by WakeAssistantManager.currentAction.collectAsState()

    val isVisible = uiState == AssistantUiState.PROCESSING || uiState == AssistantUiState.REPLY_SHOWING

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(250)) + slideInVertically(
            initialOffsetY = { -it / 2 },
            animationSpec = tween(300)
        ),
        exit = fadeOut(tween(200)) + slideOutVertically(
            targetOffsetY = { -it / 2 },
            animationSpec = tween(250)
        ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 24.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            if (uiState == AssistantUiState.PROCESSING) {
                // 思考處理中的輕巧浮動膠囊 (Thinking Pill)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xEE141416))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF24D8A7), Color(0xFF9B51E0))
                                )
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Gemini 分析中...",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            } else if (uiState == AssistantUiState.REPLY_SHOWING) {
                // 回答完成的精緻毛玻璃對話卡片
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xF0121316))
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                listOf(Color(0x554285F4), Color(0x229B51E0))
                            ),
                            shape = RoundedCornerShape(18.dp)
                        )
                        .clickable { WakeAssistantManager.dismissReply() }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (transcript.isNotBlank()) {
                        Text(
                            text = "“$transcript”",
                            fontSize = 10.5.sp,
                            color = Color(0xFFAAAAAA),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Text(
                        text = reply,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color.White,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )

                    if (!action.isNullOrBlank() && action != "NONE") {
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x3334A853))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "✓ 硬體指令已執行",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF81C995)
                            )
                        }
                    }
                }
            }
        }
    }
}
