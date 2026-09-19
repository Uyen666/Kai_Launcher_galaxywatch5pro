package com.wristhub.launcher.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.items
import androidx.wear.compose.material.rememberScalingLazyListState
import com.wristhub.launcher.data.AppItem
import com.wristhub.launcher.manager.AppDrawerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 1:1 垂直手勢追隨、零重組 GPU 渲染與賽博微光美學 App Drawer
 * 1. 1:1 手指即時位移追隨（淘汰閾值分離動畫，手推多少畫面跟多少）
 * 2. 移除所有生硬水平拉條，加入頂底黑階漸變遮罩（Vignette）
 * 3. 賽博深空微光膠囊（Pill Capsule + 微光青邊）
 * 4. 懸浮三點微光陣列常用推薦 + 髮絲漸變分界線
 * 5. 數位邊框滾動（Rotary）+ 非線性加速 + 貼合圓弧 Alphabet 軌道指示器
 */
@Composable
fun AppDrawerOverlay(
    drawerOffsetY: Float,
    screenHeightPx: Float,
    onDismiss: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onFling: (Float) -> Unit
) {
    val context = LocalContext.current
    val installedApps by AppDrawerManager.installedApps.collectAsState()
    val recentApps by AppDrawerManager.recentApps.collectAsState()
    val isLoading by AppDrawerManager.isLoading.collectAsState()

    // 抽屜未完全收合時攔截返回鍵，優先關閉抽屜
    val isVisible = drawerOffsetY < screenHeightPx - 0.5f
    BackHandler(enabled = isVisible) {
        onDismiss()
    }

    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var lastRotaryTime by remember { mutableLongStateOf(0L) }
    var accumulatedRotaryPixels by remember { mutableFloatStateOf(0f) }

    // 當抽屜完全升起時（offset <= 5f），主動請求 Rotary 焦點
    LaunchedEffect(drawerOffsetY <= 5f) {
        if (drawerOffsetY <= 5f) {
            focusRequester.requestFocus()
        }
    }

    // 當前首字母指示器開關
    var showAlphabetIndicator by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            showAlphabetIndicator = true
        } else {
            delay(650L)
            showAlphabetIndicator = false
        }
    }

    val currentLetter by remember(listState, installedApps, recentApps) {
        derivedStateOf {
            val centerIndex = listState.centerItemIndex
            val recentHeaderOffset = if (recentApps.isNotEmpty()) 2 else 1
            if (centerIndex < recentHeaderOffset) {
                "⭐"
            } else {
                val appIdx = (centerIndex - recentHeaderOffset).coerceIn(0, installedApps.lastIndex.coerceAtLeast(0))
                val app = installedApps.getOrNull(appIdx)
                app?.label?.firstOrNull()?.uppercaseChar()?.toString() ?: ""
            }
        }
    }

    // 1:1 巢狀滑動接管（清單滑至頂部繼續下拉時，無縫 1:1 接管帶動抽屜下滑）
    val nestedScrollConnection = remember(screenHeightPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val currentOffset = drawerOffsetY
                if (currentOffset > 0f && currentOffset < screenHeightPx) {
                    onDragDelta(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 清單頂部下拉時（available.y > 0），將剩餘位移接管給抽屜下滑
                if (available.y > 0f) {
                    onDragDelta(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drawerOffsetY > 0f) {
                    onFling(available.y)
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (drawerOffsetY > 0f) {
                    onFling(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // 100% GPU RenderNode 位移，零 Recomposition / Layout 掉幀
                translationY = drawerOffsetY.coerceIn(0f, screenHeightPx)
                alpha = if (drawerOffsetY >= screenHeightPx - 0.5f) 0f else 1f
            }
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xF8050810),
                        Color(0xFA0A0F1A),
                        Color(0xFF000000)
                    )
                )
            )
            .nestedScroll(nestedScrollConnection)
            .focusRequester(focusRequester)
            .focusable()
            .onRotaryScrollEvent { event ->
                val now = System.currentTimeMillis()
                val dt = (now - lastRotaryTime).coerceAtLeast(1L)

                // 1. 角速度非線性加速：轉動越快，位移倍率指數級放大（1.0x ~ 3.8x）
                val speedMultiplier = if (dt < 45L) {
                    1.0f + ((50f - dt) / 8f).coerceIn(0f, 2.8f)
                } else {
                    1.0f
                }

                val scrollAmount = event.verticalScrollPixels * speedMultiplier
                coroutineScope.launch {
                    listState.scrollBy(scrollAmount)
                }

                // 2. 階梯微觸覺震動反饋 (Stepped Haptic Ticks)
                accumulatedRotaryPixels += Math.abs(scrollAmount)
                if (accumulatedRotaryPixels >= 26f) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    accumulatedRotaryPixels = 0f
                }

                lastRotaryTime = now
                showAlphabetIndicator = true
                true
            }
            // 支援在抽屜非滾動區域直接向下拖曳手勢
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { onFling(0f) },
                    onVerticalDrag = { _, dragAmount ->
                        onDragDelta(dragAmount)
                    }
                )
            }
    ) {
        Scaffold(
            positionIndicator = {
                PositionIndicator(scalingLazyListState = listState)
            }
        ) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. 極簡科技感 APPS 標題徽章（徹底告別白色小橫條）
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 10.dp)
                    ) {
                        Text(
                            text = "APPS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp,
                            color = Color(0xFF00E5FF)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFF00E5FF).copy(alpha = 0.12f))
                                .border(0.5.dp, Color(0xFF00E5FF).copy(alpha = 0.35f), CircleShape)
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "${installedApps.size}",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF)
                            )
                        }
                    }
                }

                // 2. 常用推薦分區 (精緻三點陣列 + 髮絲漸變線，不佔用大字標題)
                if (recentApps.isNotEmpty()) {
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                for (app in recentApps) {
                                    RecentAppCircleItem(app = app) {
                                        onDismiss()
                                        AppDrawerManager.launchApp(context, app)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            // 霓虹微光漸變髮絲線 (Transparent -> Cyan 0.35 -> Transparent)
                            Box(
                                modifier = Modifier
                                    .width(130.dp)
                                    .height(0.8.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                Color(0x6600E5FF),
                                                Color.Transparent
                                            )
                                        )
                                    )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }

                // 載入狀態指示
                if (isLoading && installedApps.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                indicatorColor = Color(0xFF00E5FF)
                            )
                        }
                    }
                }

                // 3. 所有應用清單 (A~Z 排序 - 賽博深空微光膠囊卡片)
                items(installedApps, key = { it.packageName }) { app ->
                    AppRowChip(app = app) {
                        onDismiss()
                        AppDrawerManager.launchApp(context, app)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // 底部安全間距
                item {
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }

        // 4. 頂部純黑漸變遮罩 (Top Vignette - 項目融於頂部鈦金屬黑框)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black,
                            Color.Black.copy(alpha = 0.65f),
                            Color.Transparent
                        )
                    )
                )
        )

        // 5. 底部純黑漸變遮罩 (Bottom Vignette - 項目融於底部鈦金屬黑框)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.65f),
                            Color.Black
                        )
                    )
                )
        )

        // 6. 貼合外圈圓弧軌道之 Alphabet Indicator
        val indicatorAlpha by animateFloatAsState(
            targetValue = if (showAlphabetIndicator && currentLetter.isNotBlank()) 1f else 0f,
            animationSpec = tween(200),
            label = "alphabetIndicatorAlpha"
        )
        if (indicatorAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .graphicsLayer { alpha = indicatorAlpha }
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xE6080E1A))
                .border(1.2.dp, Color(0xFF00E5FF).copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentLetter,
                    fontSize = if (currentLetter == "⭐") 14.sp else 15.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF00E5FF),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 常用推薦微縮圓形按鈕（帶微光青圈）
 */
@Composable
private fun RecentAppCircleItem(
    app: AppItem,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFF0D1420))
                .border(0.6.dp, Color(0x5500E5FF), CircleShape)
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (app.iconBitmap != null) {
                Image(
                    bitmap = app.iconBitmap.asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = app.label.take(1),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = app.label,
            fontSize = 9.sp,
            color = Color(0xFFCAD1DC),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(46.dp),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 賽博深空微光膠囊卡片 (Pill Capsule + 0.5dp 微光青邊)
 */
@Composable
private fun AppRowChip(
    app: AppItem,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0xCC0D131F))
            .border(0.6.dp, Color(0x3800E5FF), RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize()
        ) {
            // 圓形圖標井
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF070B12))
                    .border(0.5.dp, Color(0x3300E5FF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (app.iconBitmap != null) {
                    Image(
                        bitmap = app.iconBitmap.asImageBitmap(),
                        contentDescription = app.label,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Text(
                        text = app.label.take(1),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF0F6FC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (app.isSystemApp) {
                    Text(
                        text = "SYSTEM",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.8.sp,
                        color = Color(0xFF6E7681)
                    )
                }
            }
        }
    }
}
