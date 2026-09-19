package com.wristhub.launcher.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.PageIndicatorState
import androidx.compose.foundation.layout.padding
import com.wristhub.launcher.network.PcWebSocketManager
import com.wristhub.launcher.presentation.components.FlashlightOverlay
import com.wristhub.launcher.presentation.components.FloatingReplyCard
import com.wristhub.launcher.presentation.components.GeminiAuraOverlay
import com.wristhub.launcher.presentation.components.TimerBadge
import com.wristhub.launcher.presentation.screens.HudWatchFaceScreen
import com.wristhub.launcher.presentation.screens.PcRemoteScreen
import com.wristhub.launcher.presentation.theme.WristHubTheme

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import com.wristhub.launcher.manager.AppDrawerManager
import com.wristhub.launcher.presentation.components.AppDrawerOverlay
import kotlinx.coroutines.launch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun WristHubApp(
    isAmbient: Boolean,
    ambientUpdateTrigger: Long = 0L,
    resetToWatchFaceTrigger: Long = 0L
) {
    WristHubTheme {
        // Automatically attempt connection to PC on app launch
        LaunchedEffect(Unit) {
            PcWebSocketManager.connect()
        }

        // Interactive 2-page horizontal pager: Left = PC Remote, Center = HUD WatchFace
        val pagerState = rememberPagerState(initialPage = 1, pageCount = { 2 })
        val coroutineScope = rememberCoroutineScope()

        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val screenHeightPx = remember(density, configuration) {
            with(density) { configuration.screenHeightDp.dp.toPx() }
        }

        val drawerOffsetY = remember { Animatable(screenHeightPx) }
        val isDrawerOpenState by AppDrawerManager.isDrawerOpen.collectAsState()

        // 程式化開關狀態同步（微光逾時歸位或外部調用）
        LaunchedEffect(isDrawerOpenState) {
            if (!isDrawerOpenState && drawerOffsetY.value < screenHeightPx - 0.5f) {
                drawerOffsetY.animateTo(
                    targetValue = screenHeightPx,
                    animationSpec = spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                )
            } else if (isDrawerOpenState && drawerOffsetY.value > 0.5f) {
                drawerOffsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                )
            }
        }

        LaunchedEffect(pagerState.currentPage) {
            com.wristhub.launcher.manager.LauncherStateManager.setCurrentPage(pagerState.currentPage)
        }

        LaunchedEffect(drawerOffsetY.value, screenHeightPx) {
            val isClosed = drawerOffsetY.value >= screenHeightPx - 1f
            com.wristhub.launcher.manager.LauncherStateManager.setDrawerClosed(isClosed)
        }

        // Instant snap to center WatchFace on wake reset
        LaunchedEffect(resetToWatchFaceTrigger) {
            if (resetToWatchFaceTrigger > 0L && pagerState.currentPage != 1) {
                pagerState.scrollToPage(1)
            }
            if (resetToWatchFaceTrigger > 0L && drawerOffsetY.value < screenHeightPx - 0.5f) {
                drawerOffsetY.snapTo(screenHeightPx)
                AppDrawerManager.setDrawerOpen(false)
            }
            com.wristhub.launcher.manager.LauncherStateManager.setCurrentPage(1)
            com.wristhub.launcher.manager.LauncherStateManager.setDrawerClosed(true)
        }

        if (isAmbient) {
            // 微光模式：純黑畫布（AMOLED 像素全滅達到零發光耗電與防烙印，同時維持前台狀態不中斷與電腦的連線）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        } else {
            // Hierarchical Launcher BackHandler:
            // 1. If AppDrawer is open or partially open, smoothly close AppDrawer.
            // 2. If on PC Remote (page 0), return to center WatchFace (page 1).
            // 3. If already on center WatchFace, consume back key so the app NEVER exits!
            BackHandler(enabled = true) {
                when {
                    drawerOffsetY.value < screenHeightPx - 0.5f -> {
                        coroutineScope.launch {
                            drawerOffsetY.animateTo(
                                targetValue = screenHeightPx,
                                animationSpec = spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                            )
                            AppDrawerManager.setDrawerOpen(false)
                        }
                    }
                    pagerState.currentPage != 1 -> {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    }
                }
            }

            val pageIndicatorState = remember(pagerState) {
                object : PageIndicatorState {
                    override val pageOffset: Float
                        get() = pagerState.currentPageOffsetFraction
                    override val selectedPage: Int
                        get() = pagerState.currentPage
                    override val pageCount: Int
                        get() = 2
                }
            }

            val flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.Black)
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = drawerOffsetY.value >= screenHeightPx - 0.5f,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 100% GPU RenderNode 零重組縮放與微暗
                            val progress = (1f - (drawerOffsetY.value / screenHeightPx)).coerceIn(0f, 1f)
                            val scale = 1.0f - (0.10f * progress)
                            scaleX = scale
                            scaleY = scale
                            alpha = 1.0f - (0.55f * progress)
                        },
                    beyondViewportPageCount = 1,
                    flingBehavior = flingBehavior,
                    pageSpacing = 16.dp
                ) { page ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val pageOffset = Math.abs(
                                    (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                                )
                                val clampedOffset = pageOffset.coerceIn(0f, 1f)

                                // Smooth circular scale transition (1.0 -> 0.85) without expensive alpha saveLayer
                                val scale = 1f - 0.15f * clampedOffset
                                scaleX = scale
                                scaleY = scale

                                // Enforce strict circular disc outline so sliding preserves round watch face aesthetics
                                clip = true
                                shape = CircleShape
                            }
                    ) {
                        when (page) {
                            0 -> PcRemoteScreen(isFocused = pagerState.currentPage == 0)
                            1 -> HudWatchFaceScreen(
                                isAmbient = false,
                                onOpenAppDrawer = {
                                    coroutineScope.launch {
                                        drawerOffsetY.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                                        )
                                        AppDrawerManager.setDrawerOpen(true)
                                    }
                                },
                                onVerticalDrag = { deltaY ->
                                    coroutineScope.launch {
                                        val newOffset = (drawerOffsetY.value + deltaY).coerceIn(0f, screenHeightPx)
                                        drawerOffsetY.snapTo(newOffset)
                                    }
                                },
                                onDragEnd = { velocityY ->
                                    coroutineScope.launch {
                                        val shouldOpen = if (Math.abs(velocityY) > 350f) {
                                            velocityY < 0f
                                        } else {
                                            drawerOffsetY.value < screenHeightPx * 0.6f
                                        }
                                        drawerOffsetY.animateTo(
                                            targetValue = if (shouldOpen) 0f else screenHeightPx,
                                            animationSpec = spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                                        )
                                        AppDrawerManager.setDrawerOpen(shouldOpen)
                                    }
                                }
                            )
                        }
                    }
                }

                // Wear OS Pager Indicator (dots at bottom)
                HorizontalPageIndicator(
                    pageIndicatorState = pageIndicatorState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer {
                            val progress = (1f - (drawerOffsetY.value / screenHeightPx)).coerceIn(0f, 1f)
                            alpha = (1f - progress * 2.5f).coerceIn(0f, 1f)
                        }
                )

                // Floating Timer Badge (if active)
                TimerBadge(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp)
                )

                // App Drawer Overlay (1:1 垂直手指即時跟隨)
                AppDrawerOverlay(
                    drawerOffsetY = drawerOffsetY.value,
                    screenHeightPx = screenHeightPx,
                    onDismiss = {
                        coroutineScope.launch {
                            drawerOffsetY.animateTo(
                                targetValue = screenHeightPx,
                                animationSpec = spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                            )
                            AppDrawerManager.setDrawerOpen(false)
                        }
                    },
                    onDragDelta = { deltaY ->
                        coroutineScope.launch {
                            val newOffset = (drawerOffsetY.value + deltaY).coerceIn(0f, screenHeightPx)
                            drawerOffsetY.snapTo(newOffset)
                        }
                    },
                    onFling = { velocityY ->
                        coroutineScope.launch {
                            val shouldOpen = if (Math.abs(velocityY) > 350f) {
                                velocityY < 0f
                            } else {
                                drawerOffsetY.value < screenHeightPx * 0.5f
                            }
                            drawerOffsetY.animateTo(
                                targetValue = if (shouldOpen) 0f else screenHeightPx,
                                animationSpec = spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)
                            )
                            AppDrawerManager.setDrawerOpen(shouldOpen)
                        }
                    }
                )

                // Siri / Apple Intelligence Bezel Aura Overlay
                GeminiAuraOverlay()

                // Floating glass response card
                FloatingReplyCard()
            }
        }

        // Full-screen pure white Flashlight Overlay (highest z-index, covers ambient & active)
        FlashlightOverlay()
    }
}
