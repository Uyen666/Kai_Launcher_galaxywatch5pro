package com.wristhub.launcher.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.wristhub.launcher.network.PcWebSocketManager
import com.wristhub.launcher.presentation.theme.*

@Composable
fun AiAssistantScreen(
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var isListening by remember { mutableStateOf(false) }
    var aiStatus by remember { mutableStateOf("點擊開始對話") }
    var responseText by remember { mutableStateOf("隨時向 Gemini 提問或下達電腦指令") }

    val lastPcMsg by PcWebSocketManager.lastMessage.collectAsState()

    LaunchedEffect(lastPcMsg) {
        if (lastPcMsg.isNotEmpty()) {
            responseText = lastPcMsg
            aiStatus = "收到 AI 回應"
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Gemini AI 助理",
                color = GreenNeon,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Big Mic Button
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isListening = !isListening
                    if (isListening) {
                        aiStatus = "正在聆聽語音..."
                        PcWebSocketManager.sendCommand("AI_VOICE_START")
                    } else {
                        aiStatus = "Gemini 分析中..."
                        PcWebSocketManager.sendCommand("AI_VOICE_STOP")
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isListening) RedNeon else CyanNeon
                ),
                modifier = Modifier.size(54.dp),
                shape = CircleShape
            ) {
                Text(
                    text = if (isListening) "⏹" else "🎙️",
                    fontSize = 22.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = aiStatus,
                color = if (isListening) RedNeon else TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Response snippet
            Text(
                text = responseText,
                color = TextPrimary,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}
