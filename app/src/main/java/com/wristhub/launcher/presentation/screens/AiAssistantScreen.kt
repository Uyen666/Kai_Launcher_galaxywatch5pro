package com.wristhub.launcher.presentation.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.wristhub.launcher.audio.AudioRecorderManager
import com.wristhub.launcher.audio.WatchTtsManager
import com.wristhub.launcher.data.AiConversation
import com.wristhub.launcher.network.AiSyncManager
import com.wristhub.launcher.presentation.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AiAssistantScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Audio & TTS Managers
    val recorder = remember { AudioRecorderManager(context) }
    val tts = remember { WatchTtsManager(context) }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancelRecording()
            tts.shutdown()
        }
    }

    var isRecording by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("點擊按鈕說出指令或問題") }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
        if (!isGranted) {
            statusText = "請至系統允許手錶錄音權限"
        }
    }

    val isProcessing by AiSyncManager.isProcessing.collectAsState()
    val conversations by AiSyncManager.conversations.collectAsState()
    val isSpeaking by tts.isSpeaking.collectAsState()

    // Pulsing Animation for Siri / Gemini Glowing Aura
    val infiniteTransition = rememberInfiniteTransition(label = "SiriAura")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    fun startListening() {
        if (!hasAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        tts.stop()
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val started = recorder.startRecording()
        if (started) {
            isRecording = true
            statusText = "正在聆聽中... 請說話"
        } else {
            statusText = "無法啟動錄音麥克風"
        }
    }

    fun stopAndProcess() {
        if (!isRecording) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        isRecording = false
        statusText = "Gemini 正在分析語音..."
        val recordedFile = recorder.stopRecording()

        if (recordedFile != null && recordedFile.exists()) {
            AiSyncManager.uploadAudio(
                audioFile = recordedFile,
                onSuccess = { conv ->
                    statusText = "回答完成"
                    // Voice report through watch speaker!
                    tts.speak(conv.aiReply)
                },
                onError = { err ->
                    statusText = err
                }
            )
        } else {
            statusText = "錄音時間太短，請再試一次"
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (conversations.isEmpty() && !isProcessing) {
            // ========================================================
            // 1. IDLE / WELCOME SCREEN (Siri / Gemini Intelligence Ring)
            // ========================================================
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
            ) {
                // Top Header Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (isRecording) RedNeon else CyanNeon, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "GEMINI INTELLIGENCE",
                        color = if (isRecording) RedNeon else CyanNeon,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Siri / Intelligence Glowing Orb & Mic Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(76.dp)
                ) {
                    // Outer Radiant Aura
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .scale(pulseScale)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(RedNeon.copy(alpha = 0.5f), Color.Transparent)
                                    ),
                                    shape = CircleShape
                                )
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(CyanNeon.copy(alpha = 0.25f), Color.Transparent)
                                    ),
                                    shape = CircleShape
                                )
                        )
                    }

                    // Main Mic / Orb Button
                    Button(
                        onClick = {
                            if (isRecording) {
                                stopAndProcess()
                            } else {
                                startListening()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (isRecording) RedNeon else Color(0xFF14181D)
                        ),
                        modifier = Modifier
                            .size(56.dp)
                            .border(
                                width = 2.dp,
                                brush = if (isRecording) {
                                    Brush.linearGradient(listOf(RedNeon, OrangeNeon))
                                } else {
                                    Brush.linearGradient(listOf(CyanNeon, GreenNeon))
                                },
                                shape = CircleShape
                            ),
                        shape = CircleShape
                    ) {
                        Text(
                            text = if (isRecording) "⏹" else "🎙️",
                            fontSize = 24.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Status & Hint Text
                Text(
                    text = statusText,
                    color = if (isRecording) RedNeon else TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Sample query badge
                Text(
                    text = "💡「把電腦靜音」或「查台北天氣」",
                    color = Color.DarkGray,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // ========================================================
            // 2. CONVERSATION CARDS & INTERACTIVE CHAT STREAM
            // ========================================================
            val listState = rememberScalingLazyListState()

            ScalingLazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Mini Header & New Voice Command Bar
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 4.dp)
                    ) {
                        Text(
                            text = "🤖 GEMINI INTELLIGENCE",
                            color = CyanNeon,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Processing indicator if currently analyzing
                if (isProcessing) {
                    item {
                        Card(
                            onClick = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(14.dp),
                            backgroundPainter = CardDefaults.cardBackgroundPainter(
                                startBackgroundColor = Color(0xFF14181D),
                                endBackgroundColor = Color(0xFF1B232D)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    indicatorColor = CyanNeon
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Gemini 思考中...",
                                    color = CyanNeon,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // Conversation Cards
                items(conversations) { item ->
                    ConversationCard(
                        conversation = item,
                        isSpeaking = isSpeaking,
                        onReplay = { tts.speak(item.aiReply) },
                        onStopSpeak = { tts.stop() }
                    )
                }

                // Bottom Floating Control: Record Again or Clear
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isRecording) {
                                    stopAndProcess()
                                } else {
                                    startListening()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (isRecording) RedNeon else CyanNeon
                            ),
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .height(36.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(
                                text = if (isRecording) "⏹ 結束錄音" else "🎙️ 再次提問",
                                color = Color.Black,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "清空對話記錄",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .clickable {
                                    tts.stop()
                                    AiSyncManager.clearHistory()
                                }
                                .padding(4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationCard(
    conversation: AiConversation,
    isSpeaking: Boolean,
    onReplay: () -> Unit,
    onStopSpeak: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeStr = remember(conversation.timestamp) { timeFormat.format(Date(conversation.timestamp)) }

    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = Color(0xFF14181D),
            endBackgroundColor = Color(0xFF1A222B)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            // 1. User Voice Prompt
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "🗣️", fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = conversation.userText,
                        color = CyanNeon,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2
                    )
                }
                Text(
                    text = timeStr,
                    color = Color.Gray,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 2. Gemini Answer Text
            Text(
                text = conversation.aiReply,
                color = Color.White,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Normal
            )

            // 3. Executed Action Badge (if any)
            if (conversation.action != "NONE") {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xFF003820), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "⚡ 電腦已執行: ${conversation.actionResult ?: conversation.action}",
                        color = GreenNeon,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 4. TTS Voice Speaker Control
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        if (isSpeaking) onStopSpeak() else onReplay()
                    },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (isSpeaking) RedNeon else SurfaceVariant
                    ),
                    modifier = Modifier.size(26.dp),
                    shape = CircleShape
                ) {
                    Text(
                        text = if (isSpeaking) "⏹" else "🔊",
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
