package com.wristhub.launcher.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.ambient.AmbientLifecycleObserver
import com.wristhub.launcher.audio.WakeAssistantManager
import com.wristhub.launcher.audio.WatchTtsManager
import com.wristhub.launcher.hardware.WatchHardwareManager
import com.wristhub.launcher.hardware.WatchTimerManager
import com.wristhub.launcher.manager.AppDrawerManager
import com.wristhub.launcher.manager.LauncherStateManager
import com.wristhub.launcher.network.AiSyncManager
import com.wristhub.launcher.network.PcWebSocketManager

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private var isAmbient by mutableStateOf(false)
    private var ambientUpdateTrigger by mutableLongStateOf(0L)
    private var resetToWatchFaceTrigger by mutableLongStateOf(0L)
    private var lastInactiveTimestamp = 0L

    // 螢幕是否處於休眠/微光狀態（Doze / Off）
    private var wasDisplaySleeping = false

    // 記錄真實螢幕點亮（由休眠/Doze轉為活躍亮屏）的時間戳
    // 只有在剛點亮後的 1200ms 黃金視窗內，且 Launcher 位於 HUD 錶盤第一頁時才允許開麥
    // 從第三方 App 退回首頁時，螢幕始終為 STATE_ON，此時間戳為 0，100% 杜絕誤觸！
    private var lastWakeTimestamp = 0L

    private var isActivityResumed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val AMBIENT_RESET_TIMEOUT_MS = 30_000L
        private const val SCREEN_ON_WAKE_WINDOW_MS = 1200L
    }

    /**
     * 監聽底層真實顯示面板狀態變化（STATE_ON, STATE_DOZE, STATE_OFF）
     * 完美捕獲 Wear OS 系統級 AmbientDream（休眠微光屏保）的進入與退出！
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
            val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val state = defaultDisplay.state
            Log.d(TAG, "DisplayListener.onDisplayChanged: state=$state (wasSleeping=$wasDisplaySleeping)")

            if (state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND || state == Display.STATE_OFF) {
                wasDisplaySleeping = true
                WakeAssistantManager.onScreenSleep()
            } else if (state == Display.STATE_ON) {
                if (wasDisplaySleeping) {
                    wasDisplaySleeping = false
                    Log.d(TAG, "Display woke up from sleep/doze to STATE_ON! Starting wake window.")
                    lastWakeTimestamp = SystemClock.elapsedRealtime()
                    tryTriggerWakeAssistant()
                    mainHandler.postDelayed({
                        tryTriggerWakeAssistant()
                    }, 150)
                }
            }
        }
    }

    /**
     * 監聽真實硬體螢幕開關廣播 (ACTION_SCREEN_ON / ACTION_SCREEN_OFF)
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Hardware screen turned ON (ACTION_SCREEN_ON)")
                    wasDisplaySleeping = false
                    lastWakeTimestamp = SystemClock.elapsedRealtime()
                    tryTriggerWakeAssistant()
                    mainHandler.postDelayed({
                        tryTriggerWakeAssistant()
                    }, 150)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Hardware screen turned OFF (ACTION_SCREEN_OFF)")
                    wasDisplaySleeping = true
                    lastWakeTimestamp = 0L
                    lastInactiveTimestamp = System.currentTimeMillis()
                    WakeAssistantManager.onScreenSleep()
                }
            }
        }
    }

    private fun tryTriggerWakeAssistant() {
        if (lastWakeTimestamp == 0L) return
        if (!isActivityResumed) return
        if (isAmbient) return

        val elapsed = SystemClock.elapsedRealtime() - lastWakeTimestamp
        if (elapsed > SCREEN_ON_WAKE_WINDOW_MS) {
            // 超過 1.2 秒視窗，代表非剛亮屏事件（例如早已處於亮屏狀態）
            lastWakeTimestamp = 0L
            return
        }

        val isEligible = LauncherStateManager.isHudWatchFaceEligible()
        Log.d(TAG, "tryTriggerWakeAssistant: elapsed=${elapsed}ms, isEligible=$isEligible, isResumed=$isActivityResumed")
        if (isEligible) {
            // 成功消耗此亮屏喚醒事件，避免同一亮屏期間重複觸發
            lastWakeTimestamp = 0L
            WakeAssistantManager.onScreenInteractive()
        }
    }

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient = true
            wasDisplaySleeping = true
            lastWakeTimestamp = 0L
            lastInactiveTimestamp = System.currentTimeMillis()
            WakeAssistantManager.onScreenSleep()
        }

        override fun onExitAmbient() {
            isAmbient = false
            checkAndTriggerWakeReset()
            // 退出微光 AOD 模式（抬腕亮起）
            wasDisplaySleeping = false
            lastWakeTimestamp = SystemClock.elapsedRealtime()
            tryTriggerWakeAssistant()
            mainHandler.postDelayed({
                tryTriggerWakeAssistant()
            }, 150)
        }

        override fun onUpdateAmbient() {
            // Periodic update in ambient mode (called ~1/min by system RTC)
            ambientUpdateTrigger = System.currentTimeMillis()
        }
    }

    private fun checkAndTriggerWakeReset() {
        if (lastInactiveTimestamp > 0L) {
            val elapsed = System.currentTimeMillis() - lastInactiveTimestamp
            val isAiBusy = AiSyncManager.isAiTaskActive()
            if (elapsed >= AMBIENT_RESET_TIMEOUT_MS && !isAiBusy) {
                resetToWatchFaceTrigger = System.currentTimeMillis()
                AppDrawerManager.setDrawerOpen(false)
                LauncherStateManager.setCurrentPage(1)
                LauncherStateManager.setDrawerClosed(true)
            }
        }
        lastInactiveTimestamp = 0L
    }

    private val ambientObserver by lazy {
        AmbientLifecycleObserver(this, ambientCallback)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize WebSocket & Local WatchFace Cache
        PcWebSocketManager.init(this)

        // Pre-warm TextToSpeech engine so first utterance is instant
        WatchTtsManager.init(this)

        // Initialize Raise-to-Speak Wake Assistant
        WakeAssistantManager.init(this)

        // Initialize Watch Hardware Controller & Timer Manager
        WatchHardwareManager.init(this)
        WatchTimerManager.setContext(this)

        // Initialize App Drawer Manager (scan apps & register package receiver)
        AppDrawerManager.init(this)

        // Ensure RECORD_AUDIO permission is granted
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
        }

        // Register hardware display listener to detect AmbientDream sleep and wake
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayManager?.registerDisplayListener(displayListener, mainHandler)

        // Register hardware screen on/off receiver
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, screenFilter)

        // Register ambient observer for natural AOD behavior
        lifecycle.addObserver(ambientObserver)

        // Intercept system back key to guarantee launcher never exits/finishes
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Consumed. Pager navigation is handled by BackHandler in WristHubApp.
            }
        })

        setContent {
            WristHubApp(
                isAmbient = isAmbient,
                ambientUpdateTrigger = ambientUpdateTrigger,
                resetToWatchFaceTrigger = resetToWatchFaceTrigger
            )
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        // 當使用者切換到其他 App，取消任何待處理的喚醒戳記
        lastWakeTimestamp = 0L
        if (lastInactiveTimestamp == 0L) {
            lastInactiveTimestamp = System.currentTimeMillis()
        }
        WakeAssistantManager.onScreenSleep()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        if (!isAmbient) {
            checkAndTriggerWakeReset()
        }

        // 檢查是否是由剛亮屏（1200ms 內由休眠/Doze轉為亮起）觸發的 resume
        tryTriggerWakeAssistant()
    }

    override fun onDestroy() {
        super.onDestroy()
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
