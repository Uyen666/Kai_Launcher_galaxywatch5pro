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

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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

        val isDrawerOpen by AppDrawerManager.isDrawerOpen.collectAsState()

        // 3D 景深階層過渡（Home <-> App Drawer 絲滑縮放與背景微暗）
        val homeScale by animateFloatAsState(
            targetValue = if (isDrawerOpen) 0.90f else 1.0f,
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "homeScaleTransition"
        )
        val homeAlpha by animateFloatAsState(
            targetValue = if (isDrawerOpen) 0.45f else 1.0f,
            animationSpec = tween(durationMillis = 200),
            label = "homeAlphaTransition"
        )

        // Instant snap to center WatchFace on wake reset
        LaunchedEffect(resetToWatchFaceTrigger) {
            if (resetToWatchFaceTrigger > 0L && pagerState.currentPage != 1) {
                pagerState.scrollToPage(1)
            }
        }

        if (isAmbient) {
            // In ambient mode, lock strictly to the minimalist HUD watchface
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.Black)
            ) {
                HudWatchFaceScreen(
                    isAmbient = true,
                    ambientUpdateTrigger = ambientUpdateTrigger
                )
            }
        } else {
            // Hierarchical Launcher BackHandler:
            // 1. If AppDrawer is open, close AppDrawer.
            // 2. If on PC Remote (page 0), return to center WatchFace (page 1).
            // 3. If already on center WatchFace, consume back key so the app NEVER exits!
            BackHandler(enabled = true) {
                when {
                    isDrawerOpen -> AppDrawerManager.setDrawerOpen(false)
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
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = homeScale
                            scaleY = homeScale
                            alpha = homeAlpha
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
                                onOpenAppDrawer = { AppDrawerManager.setDrawerOpen(true) }
                            )
                        }
                    }
                }

                // Wear OS Pager Indicator (dots at bottom)
                HorizontalPageIndicator(
                    pageIndicatorState = pageIndicatorState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { alpha = if (isDrawerOpen) 0f else 1f }
                )

                // Floating Timer Badge (if active)
                TimerBadge(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp)
                )

                // App Drawer Overlay (Swipe up from WatchFace)
                AppDrawerOverlay(
                    isOpen = isDrawerOpen,
                    onDismiss = { AppDrawerManager.setDrawerOpen(false) }
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
