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
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun trigger(action: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        PcWebSocketManager.sendCommand(action)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onRotaryScrollEvent { event ->
                if (event.verticalScrollPixels > 0) {
                    trigger("VOLUME_UP")
                } else if (event.verticalScrollPixels < 0) {
                    trigger("VOLUME_DOWN")
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
            // Top Row: PC Remote Title + Status Chip + Lock Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Text(
                    text = "PC遙控",
                    color = CyanNeon,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                // Status chip
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
                    onClick = { trigger("LOCK_PC") },
                    colors = ButtonDefaults.buttonColors(backgroundColor = SurfaceVariant),
                    modifier = Modifier.size(26.dp)
                ) {
                    Text("🔒", fontSize = 11.sp)
                }
            }

            // Middle Row: Volume Control
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { trigger("VOLUME_DOWN") },
                    colors = ButtonDefaults.buttonColors(backgroundColor = SurfaceVariant),
                    modifier = Modifier.size(42.dp)
                ) {
                    Text("🔉", fontSize = 15.sp)
                }
                Button(
                    onClick = { trigger("MUTE_TOGGLE") },
                    colors = ButtonDefaults.buttonColors(backgroundColor = OrangeNeon),
                    modifier = Modifier.size(44.dp)
                ) {
                    Text("🔇", fontSize = 16.sp)
                }
                Button(
                    onClick = { trigger("VOLUME_UP") },
                    colors = ButtonDefaults.buttonColors(backgroundColor = SurfaceVariant),
                    modifier = Modifier.size(42.dp)
                ) {
                    Text("🔊", fontSize = 15.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Row: Media & PPT Control
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { trigger("PPT_PREV") },
                    colors = ButtonDefaults.buttonColors(backgroundColor = SurfaceDark),
                    modifier = Modifier.size(38.dp)
                ) {
                    Text("◀", fontSize = 12.sp, color = TextPrimary)
                }
                Button(
                    onClick = { trigger("PLAY_PAUSE") },
                    colors = ButtonDefaults.buttonColors(backgroundColor = CyanNeon),
                    modifier = Modifier.size(44.dp)
                ) {
                    Text("⏯", fontSize = 16.sp, color = Color.Black)
                }
                Button(
                    onClick = { trigger("PPT_NEXT") },
                    colors = ButtonDefaults.buttonColors(backgroundColor = SurfaceDark),
                    modifier = Modifier.size(38.dp)
                ) {
                    Text("▶", fontSize = 12.sp, color = TextPrimary)
                }
            }
        }
    }
}
