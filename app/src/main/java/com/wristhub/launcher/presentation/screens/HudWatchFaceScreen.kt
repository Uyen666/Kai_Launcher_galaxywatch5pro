package com.wristhub.launcher.presentation.screens

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.wristhub.launcher.network.PcWebSocketManager
import com.wristhub.launcher.network.WatchFaceSyncManager
import com.wristhub.launcher.presentation.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HudWatchFaceScreen(
    isAmbient: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf(Calendar.getInstance().time) }
    var batteryPercent by remember { mutableIntStateOf(100) }
    val isPcConnected by PcWebSocketManager.isConnected.collectAsState()

    val wfConfig by WatchFaceSyncManager.config.collectAsState()
    val cachedBgBitmap by WatchFaceSyncManager.cachedBgBitmap.collectAsState()

    val clockColor = remember(wfConfig.clockColorHex) {
        try {
            Color(android.graphics.Color.parseColor(wfConfig.clockColorHex))
        } catch (_: Exception) {
            CyanNeon
        }
    }

    // Clock update loop: 1 second in active, 30s in ambient
    LaunchedEffect(isAmbient) {
        while (true) {
            currentTime = Calendar.getInstance().time
            delay(if (isAmbient) 30000L else 1000L)
        }
    }

    // Battery level reading & reporting to PC
    LaunchedEffect(isPcConnected) {
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, batteryFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            batteryPercent = (level * 100) / scale
            if (isPcConnected) {
                PcWebSocketManager.sendCommand("BATTERY_UPDATE", mapOf("level" to batteryPercent))
            }
        }
    }

    val cal = Calendar.getInstance().apply { time = currentTime }
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val secFormat = SimpleDateFormat("ss", Locale.getDefault())
    val dateFormat = SimpleDateFormat("MM/dd EEE", Locale.getDefault())

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // 1. Background Layer: In ambient mode, keep black for OLED efficiency. In active mode, render cached bitmap!
        if (!isAmbient && wfConfig.hasCustomBg && cachedBgBitmap != null) {
            Image(
                bitmap = cachedBgBitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Dimming overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = wfConfig.dimPercent / 100f))
            )
        }

        // 2. Outer Battery Arc (if enabled & active)
        if (!isAmbient && wfConfig.showBattery) {
            Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                val strokeWidth = 5.dp.toPx()
                drawCircle(
                    color = SurfaceVariant.copy(alpha = 0.6f),
                    radius = (size.minDimension - strokeWidth) / 2,
                    style = Stroke(width = strokeWidth)
                )
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

        // 3. Clock Style: ANALOG vs DIGITAL
        if (wfConfig.clockStyle == "ANALOG") {
            // Analog Clock Canvas
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                val cx = size.width / 2
                val cy = size.height / 2
                val radius = size.minDimension / 2

                // Hour Ticks
                for (i in 0 until 12) {
                    val angle = (i * 30.0) * (PI / 180.0)
                    val r1 = radius - 14.dp.toPx()
                    val r2 = radius - 4.dp.toPx()
                    drawLine(
                        color = Color.LightGray.copy(alpha = 0.7f),
                        start = Offset(cx + cos(angle).toFloat() * r1, cy + sin(angle).toFloat() * r1),
                        end = Offset(cx + cos(angle).toFloat() * r2, cy + sin(angle).toFloat() * r2),
                        strokeWidth = if (i % 3 == 0) 3.dp.toPx() else 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                val hrs = cal.get(Calendar.HOUR)
                val mins = cal.get(Calendar.MINUTE)
                val secs = cal.get(Calendar.SECOND)

                // Hour Hand
                val hrAngle = ((hrs % 12 + mins / 60f) * 30.0 - 90.0) * (PI / 180.0)
                val hrLen = radius * 0.52f
                drawLine(
                    color = Color.White,
                    start = Offset(cx, cy),
                    end = Offset(cx + cos(hrAngle).toFloat() * hrLen, cy + sin(hrAngle).toFloat() * hrLen),
                    strokeWidth = 4.5.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Minute Hand
                val minAngle = ((mins + secs / 60f) * 6.0 - 90.0) * (PI / 180.0)
                val minLen = radius * 0.76f
                drawLine(
                    color = clockColor,
                    start = Offset(cx, cy),
                    end = Offset(cx + cos(minAngle).toFloat() * minLen, cy + sin(minAngle).toFloat() * minLen),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Second Hand (Ticks once per second! Hidden in ambient mode)
                if (!isAmbient) {
                    val secAngle = (secs * 6.0 - 90.0) * (PI / 180.0)
                    val secLen = radius * 0.88f
                    val tailLen = radius * 0.16f
                    drawLine(
                        color = GreenNeon,
                        start = Offset(cx - cos(secAngle).toFloat() * tailLen, cy - sin(secAngle).toFloat() * tailLen),
                        end = Offset(cx + cos(secAngle).toFloat() * secLen, cy + sin(secAngle).toFloat() * secLen),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Center Pin
                drawCircle(
                    color = if (isAmbient) Color.White else GreenNeon,
                    radius = 4.dp.toPx(),
                    center = Offset(cx, cy)
                )
            }

            // Analog Date & Battery Overlay Text
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 36.dp)
            ) {
                if (wfConfig.showDate) {
                    Text(
                        text = dateFormat.format(currentTime).uppercase(),
                        color = if (isAmbient) Color.Gray else TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (wfConfig.showBattery) {
                    Text(
                        text = "⚡ $batteryPercent%",
                        color = if (isAmbient) Color.Gray else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

        } else {
            // DIGITAL Clock Layout
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(16.dp)
            ) {
                // Top Status Bar: Date + PC Connection indicator
                if (wfConfig.showDate || wfConfig.showPcStatus) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        if (wfConfig.showDate) {
                            Text(
                                text = dateFormat.format(currentTime).uppercase(),
                                color = if (isAmbient) Color.Gray else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (wfConfig.showDate && wfConfig.showPcStatus && !isAmbient) {
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        if (wfConfig.showPcStatus && !isAmbient) {
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
                }

                // Big Digital Time
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = timeFormat.format(currentTime),
                        color = if (isAmbient) Color.White else clockColor,
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
                if (wfConfig.showBattery) {
                    Spacer(modifier = Modifier.height(6.dp))
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
}
