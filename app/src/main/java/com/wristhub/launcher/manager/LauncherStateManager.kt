package com.wristhub.launcher.manager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 集中管理 Launcher 當前畫面狀態
 * 追蹤水平分頁（0: PC 遙控, 1: HUD 錶盤）與 App Drawer 展開狀態
 * 用於精確判定抬腕亮螢幕時是否符合喚醒 Gemini 助理條件
 */
object LauncherStateManager {
    // 0: PcRemoteScreen, 1: HudWatchFaceScreen
    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _isDrawerClosed = MutableStateFlow(true)
    val isDrawerClosed: StateFlow<Boolean> = _isDrawerClosed.asStateFlow()

    fun setCurrentPage(page: Int) {
        _currentPage.value = page
    }

    fun setDrawerClosed(closed: Boolean) {
        _isDrawerClosed.value = closed
    }

    /**
     * 當前是否處於 HUD 錶盤第一頁且 App Drawer 完全收合
     */
    fun isHudWatchFaceEligible(): Boolean {
        return _currentPage.value == 1 && _isDrawerClosed.value
    }
}
