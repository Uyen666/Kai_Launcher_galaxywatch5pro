package com.wristhub.launcher.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.wristhub.launcher.network.PcWebSocketManager
import com.wristhub.launcher.presentation.theme.*

@Composable
fun PcRemoteScreen(
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val isConnected by PcWebSocketManager.isConnected.collectAsState()
    val buttonList by PcWebSocketManager.buttonList.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun triggerButton(btnId: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        PcWebSocketManager.sendCommand("BUTTON_CLICK", mapOf("id" to btnId))
    }

    fun triggerRotary(delta: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        PcWebSocketManager.sendCommand("ROTARY_SCROLL", mapOf("delta" to delta))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onRotaryScrollEvent { event ->
                if (event.verticalScrollPixels > 0) {
                    triggerRotary(1)
                } else if (event.verticalScrollPixels < 0) {
                    triggerRotary(-1)
                }
                true
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Top Row: PC Remote Title + Status Indicator + Lock Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.wristhub.launcher.R.string.pc_remote_title),
                    color = CyanNeon,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                // Status indicator
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (isConnected) GreenNeon else RedNeon,
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                // Lock PC button
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        PcWebSocketManager.sendCommand("BUTTON_CLICK", mapOf("id" to "LOCK_PC", "actionId" to "LOCK_PC"))
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = SurfaceVariant),
                    modifier = Modifier.size(26.dp)
                ) {
                    Text("🔒", fontSize = 11.sp)
                }
            }

            // Dynamic Row 1 (Buttons 0..2)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val row1 = buttonList.take(3)
                row1.forEach { btn ->
                    val btnBg = remember(btn.colorHex) {
                        try {
                            Color(android.graphics.Color.parseColor(btn.colorHex))
                        } catch (_: Exception) {
                            SurfaceVariant
                        }
                    }
                    Button(
                        onClick = { triggerButton(btn.id) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = btnBg),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Text(btn.icon, fontSize = 16.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Dynamic Row 2 (Buttons 3..5)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val row2 = buttonList.drop(3).take(3)
                row2.forEach { btn ->
                    val btnBg = remember(btn.colorHex) {
                        try {
                            Color(android.graphics.Color.parseColor(btn.colorHex))
                        } catch (_: Exception) {
                            SurfaceVariant
                        }
                    }
                    Button(
                        onClick = { triggerButton(btn.id) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = btnBg),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Text(btn.icon, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
