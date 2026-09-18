package com.wristhub.launcher.presentation.screens

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wristhub.launcher.network.PcWebSocketManager
import com.wristhub.launcher.presentation.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HudWatchFaceScreen(
    isAmbient: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf(Calendar.getInstance().time) }
    var batteryPercent by remember { mutableIntStateOf(100) }
    val isPcConnected by PcWebSocketManager.isConnected.collectAsState()

    // Clock update loop
    LaunchedEffect(isAmbient) {
        while (true) {
            currentTime = Calendar.getInstance().time
            // In ambient mode, update once every 20-60s to save power; active mode every second
            delay(if (isAmbient) 30000L else 1000L)
        }
    }

    // Battery level reading
    LaunchedEffect(Unit) {
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, batteryFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            batteryPercent = (level * 100) / scale
        }
    }

    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val secFormat = SimpleDateFormat("ss", Locale.getDefault())
    val dateFormat = SimpleDateFormat("MM/dd EEE", Locale.getDefault())

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (!isAmbient) {
            // Futuristic outer circular decorative ring (Battery gauge)
            Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                val strokeWidth = 5.dp.toPx()
                // Track background
                drawCircle(
                    color = SurfaceVariant,
                    radius = (size.minDimension - strokeWidth) / 2,
                    style = Stroke(width = strokeWidth)
                )
                // Battery sweep arc
                val sweepAngle = (batteryPercent / 100f) * 270f
                drawArc(
                    color = when {
                        batteryPercent > 50 -> GreenNeon
                        batteryPercent > 20 -> OrangeNeon
                        else -> RedNeon
                    },
                    startAngle = 135f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            // Top Status Bar: Date + PC Connection indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Text(
                    text = dateFormat.format(currentTime).uppercase(),
                    color = if (isAmbient) Color.Gray else TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (!isAmbient) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                color = if (isPcConnected) GreenNeon else RedNeon,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isPcConnected) "PC" else "OFF",
                        color = if (isPcConnected) GreenNeon else Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Big Digital Time
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = timeFormat.format(currentTime),
                    color = if (isAmbient) Color.White else CyanNeon,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = (-1).sp
                )
                if (!isAmbient) {
                    Text(
                        text = ":${secFormat.format(currentTime)}",
                        color = GreenNeon,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                    )
                }
            }

            // Bottom Status: Battery & Mode
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "⚡ $batteryPercent%",
                    color = if (isAmbient) Color.Gray else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (!isAmbient) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "◀ 遙控 | 助理 ▶",
                    color = Color.DarkGray,
                    fontSize = 10.sp
                )
            }
        }
    }
}
