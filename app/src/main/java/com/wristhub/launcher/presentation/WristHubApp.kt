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
import com.wristhub.launcher.network.PcWebSocketManager
import com.wristhub.launcher.presentation.screens.AiAssistantScreen
import com.wristhub.launcher.presentation.screens.HudWatchFaceScreen
import com.wristhub.launcher.presentation.screens.PcRemoteScreen
import com.wristhub.launcher.presentation.theme.WristHubTheme

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun WristHubApp(
    isAmbient: Boolean,
    ambientUpdateTrigger: Long = 0L
) {
    WristHubTheme {
        // Automatically attempt connection to PC on app launch
        LaunchedEffect(Unit) {
            PcWebSocketManager.connect()
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
            // Interactive 3-page horizontal pager: Left = PC Remote, Center = HUD WatchFace, Right = AI
            val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
            val coroutineScope = rememberCoroutineScope()

            // Safe Launcher BackHandler: If on Remote or AI, return to center WatchFace.
            // If already on center WatchFace, consume back key so the app NEVER exits!
            BackHandler(enabled = true) {
                if (pagerState.currentPage != 1) {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(1)
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
                        get() = 3
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
                    modifier = Modifier.fillMaxSize(),
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
                            1 -> HudWatchFaceScreen(isAmbient = false)
                            2 -> AiAssistantScreen()
                        }
                    }
                }

                // Wear OS Pager Indicator (dots at bottom)
                HorizontalPageIndicator(
                    pageIndicatorState = pageIndicatorState,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}
