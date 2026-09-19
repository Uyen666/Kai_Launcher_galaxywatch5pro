package com.wristhub.launcher.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.items
import androidx.wear.compose.material.rememberScalingLazyListState
import com.wristhub.launcher.data.AppItem
import com.wristhub.launcher.manager.AppDrawerManager
import com.wristhub.launcher.manager.TaskManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手錶多工任務管理器 (Task Manager Overlay)
 * 1. 連按兩下實體返回鍵呼叫
 * 2. 展示近期啟動的背景任務卡片
 * 3. 單個任務卡片關閉或頂部「一鍵釋放記憶體」
 * 4. 嚴格白名單永遠保護 WristHub Launcher，絕不誤殺
 */
@Composable
fun TaskManagerOverlay(
    isOpen: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val recentTasks by TaskManager.recentTasks.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var freedMemToast by remember { mutableStateOf<String?>(null) }

    // 開啟時攔截返回鍵
    BackHandler(enabled = isOpen) {
        onDismiss()
    }

    AnimatedVisibility(
        visible = isOpen,
        enter = scaleIn(
            initialScale = 0.85f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeIn(animationSpec = tween(150)),
        exit = scaleOut(
            targetScale = 0.85f,
            animationSpec = tween(180)
        ) + fadeOut(animationSpec = tween(150))
    ) {
        val listState = rememberScalingLazyListState()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFA080C14),
                            Color(0xFD0D121F),
                            Color(0xFF000000)
                        )
                    )
                )
        ) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. 頂部關閉手柄
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDismiss() }
                            .padding(top = 4.dp, bottom = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp, 4.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4A5568))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Canvas(modifier = Modifier.size(16.dp, 8.dp)) {
                            val w = size.width
                            val h = size.height
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(w * 0.15f, h * 0.25f)
                                lineTo(w * 0.5f, h * 0.75f)
                                lineTo(w * 0.85f, h * 0.25f)
                            }
                            drawPath(
                                path = path,
                                color = Color(0xFF00E5FF),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 2.dp.toPx(),
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                                )
                            )
                        }
                    }
                }

                // 2. 標題與任務數量
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = "多工背景清理",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFFFF5252).copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${recentTasks.size}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF5252)
                            )
                        }
                    }
                }

                // 3. 一鍵清理大按鈕 (若有任務可清理)
                if (recentTasks.isNotEmpty()) {
                    item {
                        Button(
                            onClick = {
                                val freedMb = TaskManager.killAllBackgroundTasks(context)
                                freedMemToast = "已深度釋放 ${freedMb} MB 記憶體！"
                                coroutineScope.launch {
                                    delay(2000)
                                    freedMemToast = null
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(40.dp)
                                .padding(bottom = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = Color(0xFFE53935),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "🧹",
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "一鍵清理所有背景",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // 4. 清空狀態展示
                if (recentTasks.isEmpty()) {
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        ) {
                            Text(
                                text = "✨",
                                fontSize = 32.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "目前無殘留背景應用",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE2E8F0)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "記憶體充裕，手錶運作順暢！",
                                fontSize = 10.sp,
                                color = Color(0xFF718096)
                            )
                        }
                    }
                }

                // 5. 各任務卡片列表
                items(recentTasks, key = { it.packageName }) { app ->
                    TaskCardItem(
                        app = app,
                        onOpen = {
                            onDismiss()
                            AppDrawerManager.launchApp(context, app)
                        },
                        onClose = {
                            TaskManager.removeTask(context, app)
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // 底部邊距
                item {
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }

            // 浮動清理成功提示
            if (freedMemToast != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF00E5FF).copy(alpha = 0.9f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = freedMemToast ?: "",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

/**
 * 單個多工任務卡片
 */
@Composable
private fun TaskCardItem(
    app: AppItem,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E2533))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // App Icon
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0F141C)),
                contentAlignment = Alignment.Center
            ) {
                if (app.iconBitmap != null) {
                    Image(
                        bitmap = app.iconBitmap.asImageBitmap(),
                        contentDescription = app.label,
                        modifier = Modifier.size(26.dp)
                    )
                } else {
                    Text(
                        text = app.label.take(1),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // App 資訊
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = app.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "點擊切換 • 點✕關閉",
                    fontSize = 9.sp,
                    color = Color(0xFFA0AEC0)
                )
            }

            // 關閉按鈕
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2D3748))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✕",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF5252)
                )
            }
        }
    }
}
