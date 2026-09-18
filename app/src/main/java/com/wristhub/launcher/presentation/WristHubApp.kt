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

@Composable
fun WristHubApp(
    isAmbient: Boolean
) {
    WristHubTheme {
        // Automatically attempt connection to PC on app launch
        LaunchedEffect(Unit) {
            PcWebSocketManager.connect()
        }

        if (isAmbient) {
            // In ambient mode, lock strictly to the minimalist HUD watchface
            HudWatchFaceScreen(isAmbient = true)
        } else {
            // Interactive 3-page horizontal pager: Left = PC Remote, Center = HUD WatchFace, Right = AI
            val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })

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

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> PcRemoteScreen()
                        1 -> HudWatchFaceScreen(isAmbient = false)
                        2 -> AiAssistantScreen()
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
