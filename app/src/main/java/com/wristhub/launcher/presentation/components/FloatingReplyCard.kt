package com.wristhub.launcher.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.wristhub.launcher.presentation.theme.CyanNeon

/**
 * 輕量級毛玻璃懸浮卡片 / 全文展開閱讀器
 * 1. 支援在卡片上滑動瀏覽長篇回覆（垂直滾動）
 * 2. 點擊卡片一鍵展開「全螢幕舒適閱讀模式」，並延長自動關閉時間
 * 3. 獨立關閉按鈕，不再因誤觸螢幕滑動而意外把卡片按掉
 */
@Composable
fun FloatingReplyCard(
    modifier: Modifier = Modifier
) {
    val uiState by WakeAssistantManager.uiState.collectAsState()
    val transcript by WakeAssistantManager.currentTranscript.collectAsState()
    val reply by WakeAssistantManager.currentReply.collectAsState()
    val action by WakeAssistantManager.currentAction.collectAsState()

    var isExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        if (uiState != AssistantUiState.REPLY_SHOWING) {
            isExpanded = false
        }
    }

    val isVisible = uiState == AssistantUiState.RECORDING_SPEECH ||
                    uiState == AssistantUiState.PROCESSING ||
                    uiState == AssistantUiState.REPLY_SHOWING

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
                .padding(
                    horizontal = if (isExpanded) 12.dp else 16.dp,
                    vertical = if (isExpanded) 16.dp else 22.dp
                ),
            contentAlignment = if (isExpanded) Alignment.Center else Alignment.TopCenter
        ) {
            if (uiState == AssistantUiState.RECORDING_SPEECH) {
                // 說話收音中的互動膠囊 (支援直接點擊提早結束收音開始思考)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xEE141416))
                        .border(1.dp, Color(0x66FF5252), RoundedCornerShape(20.dp))
                        .clickable {
                            WakeAssistantManager.manualStopAndProcess()
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF5252))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Listening • Tap to stop ⚡",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            } else if (uiState == AssistantUiState.PROCESSING) {
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
                        text = "Gemini is thinking...",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            } else if (uiState == AssistantUiState.REPLY_SHOWING) {
                val scrollState = rememberScrollState()

                // 當使用者正在滑動閱讀時，自動將收回時間延長至 25 秒
                LaunchedEffect(scrollState.value) {
                    if (scrollState.value > 0) {
                        WakeAssistantManager.extendAutoDismiss(25000L)
                    }
                }

                val hasLongContent = reply.length > 55 || reply.lines().size > 3

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth(if (isExpanded) 0.98f else 0.94f)
                        .then(
                            if (isExpanded) {
                                Modifier.fillMaxHeight(0.88f)
                            } else {
                                Modifier.wrapContentHeight()
                            }
                        )
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xF5111317))
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                listOf(
                                    if (isExpanded) Color(0x8800E5FF) else Color(0x664285F4),
                                    Color(0x339B51E0)
                                )
                            ),
                            shape = RoundedCornerShape(18.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    // 頂部列：提問摘要 + 獨立關閉按鈕 ✕
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (transcript.isNotBlank()) {
                            Text(
                                text = "“$transcript”",
                                fontSize = 10.sp,
                                color = Color(0xFF9E9E9E),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        // 獨立關閉按鈕（點擊直接收回）
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(0x33FFFFFF))
                                .clickable { WakeAssistantManager.dismissReply() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✕",
                                fontSize = 11.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 回覆內容本體（支援可捲動閱讀 + 點擊展開全文）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isExpanded) {
                                    Modifier.weight(1f, fill = false)
                                } else {
                                    Modifier
                                        .heightIn(max = 80.dp)
                                        .clickable {
                                            if (hasLongContent) {
                                                isExpanded = true
                                                WakeAssistantManager.extendAutoDismiss(35000L)
                                            }
                                        }
                                }
                            )
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = reply,
                            fontSize = if (isExpanded) 13.sp else 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.White,
                            textAlign = if (isExpanded) TextAlign.Start else TextAlign.Center,
                            lineHeight = if (isExpanded) 17.sp else 16.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 長文提示與操作列
                    if (!isExpanded && hasLongContent) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x2200E5FF))
                                .clickable {
                                    isExpanded = true
                                    WakeAssistantManager.extendAutoDismiss(35000L)
                                }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "👆 Tap for full text / Scroll",
                                fontSize = 9.5.sp,
                                color = CyanNeon,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // 動作反饋徽章（如 TYPE_TEXT 電腦打字或硬體執行）
                    if (!action.isNullOrBlank() && action != "NONE") {
                        Spacer(modifier = Modifier.height(6.dp))
                        val isTypeAction = action == "TYPE_TEXT"
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isTypeAction) Color(0x3300E5FF) else Color(0x3334A853))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isTypeAction) "⌨️ Typed at PC cursor" else "✓ Command executed",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isTypeAction) Color(0xFF00E5FF) else Color(0xFF81C995)
                            )
                        }
                    }

                    // 全螢幕展開模式下的底部關閉條
                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x33FFFFFF))
                                .clickable { WakeAssistantManager.dismissReply() }
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✓ Dismiss",
                                fontSize = 10.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
