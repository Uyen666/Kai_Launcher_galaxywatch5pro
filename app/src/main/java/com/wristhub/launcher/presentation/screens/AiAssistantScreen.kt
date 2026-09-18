package com.wristhub.launcher.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
    val defaultIdleStatus = androidx.compose.ui.res.stringResource(com.wristhub.launcher.R.string.ai_status_idle)
    val defaultPromptText = androidx.compose.ui.res.stringResource(com.wristhub.launcher.R.string.ai_response_default)
    val receivedMsgStatus = androidx.compose.ui.res.stringResource(com.wristhub.launcher.R.string.ai_status_received)
    val listeningMsgStatus = androidx.compose.ui.res.stringResource(com.wristhub.launcher.R.string.ai_status_listening)
    val analyzingMsgStatus = androidx.compose.ui.res.stringResource(com.wristhub.launcher.R.string.ai_status_analyzing)

    var isListening by remember { mutableStateOf(false) }
    var aiStatus by remember(defaultIdleStatus) { mutableStateOf(defaultIdleStatus) }
    var responseText by remember(defaultPromptText) { mutableStateOf(defaultPromptText) }

    val lastPcMsg by PcWebSocketManager.lastMessage.collectAsState()

    LaunchedEffect(lastPcMsg) {
        if (lastPcMsg.isNotEmpty()) {
            responseText = lastPcMsg
            aiStatus = receivedMsgStatus
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(com.wristhub.launcher.R.string.ai_assistant_title),
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
                        aiStatus = listeningMsgStatus
                        PcWebSocketManager.sendCommand("AI_VOICE_START")
                    } else {
                        aiStatus = analyzingMsgStatus
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
