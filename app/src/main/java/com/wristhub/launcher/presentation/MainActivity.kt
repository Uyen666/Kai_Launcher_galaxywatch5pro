package com.wristhub.launcher.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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

    // 螢幕是否曾進入休眠/微光/熄滅狀態（待喚醒標記）
    private var wasDisplaySleeping = false

    private var isActivityResumed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // 防止 onExitAmbient + DisplayListener 在同一次抬腕重複觸發（debounce）
    private var lastWakeTriggerMs = 0L

    companion object {
        private const val AMBIENT_RESET_TIMEOUT_MS = 30_000L
        private const val WAKE_TRIGGER_DEBOUNCE_MS = 500L  // 500ms 內只觸發一次
    }


    /**
     * 嘗試觸發抬腕 Gemini 開麥：
     * 必須滿足：1. 來自真實休眠喚醒；2. Activity 處於 Resumed；3. 非 Ambient 微光；4. 處於 HUD 錶盤第一頁且抽屜收合
     */
    private fun tryTriggerWakeOnResume() {
        if (!wasDisplaySleeping) {
            Log.d(TAG, "tryTriggerWakeOnResume: Not sleeping before -> Ignore")
            return
        }
        if (!isActivityResumed) {
            Log.d(TAG, "tryTriggerWakeOnResume: Activity not resumed -> Ignore")
            return
        }
        if (isAmbient) {
            Log.d(TAG, "tryTriggerWakeOnResume: Currently ambient -> Ignore")
            return
        }

        // Debounce：onExitAmbient 和 DisplayListener STATE_ON 可能在同一次抬腕都觸發
        val now = System.currentTimeMillis()
        if (now - lastWakeTriggerMs < WAKE_TRIGGER_DEBOUNCE_MS) {
            Log.d(TAG, "tryTriggerWakeOnResume: Debounced (last trigger ${now - lastWakeTriggerMs}ms ago)")
            return
        }

        val isEligible = LauncherStateManager.isHudWatchFaceEligible()
        Log.d(TAG, "tryTriggerWakeOnResume: isEligible=$isEligible (page=${LauncherStateManager.currentPage.value}, drawerClosed=${LauncherStateManager.isDrawerClosed.value})")
        if (isEligible) {
            wasDisplaySleeping = false
            lastWakeTriggerMs = now
            Log.d(TAG, "tryTriggerWakeOnResume: Waking up on HUD WatchFace -> Triggering Gemini Assistant!")
            WakeAssistantManager.onScreenInteractive()
        }
    }


    /**
     * 監聽底層真實顯示面板狀態變化（STATE_ON, STATE_DOZE, STATE_OFF）
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
            val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val state = defaultDisplay.state
            Log.d(TAG, "DisplayListener: state=$state (wasSleeping=$wasDisplaySleeping)")

            if (state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND || state == Display.STATE_OFF) {
                wasDisplaySleeping = true
                WakeAssistantManager.onScreenSleep()
            } else if (state == Display.STATE_ON) {
                if (wasDisplaySleeping && isActivityResumed) {
                    tryTriggerWakeOnResume()
                }
            }
        }
    }

    /**
     * 監聽硬體螢幕開關廣播 (ACTION_SCREEN_ON / ACTION_SCREEN_OFF)
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "System Broadcast: ACTION_SCREEN_ON")
                    if (wasDisplaySleeping && isActivityResumed) {
                        tryTriggerWakeOnResume()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "System Broadcast: ACTION_SCREEN_OFF")
                    wasDisplaySleeping = true
                    lastInactiveTimestamp = System.currentTimeMillis()
                    WakeAssistantManager.onScreenSleep()
                }
            }
        }
    }

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient = true
            wasDisplaySleeping = true
            lastInactiveTimestamp = System.currentTimeMillis()
            WakeAssistantManager.onScreenSleep()
        }

        override fun onExitAmbient() {
            isAmbient = false
            checkAndTriggerWakeReset()
            if (wasDisplaySleeping) {
                tryTriggerWakeOnResume()
            }
        }

        override fun onUpdateAmbient() {
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

        val isAppLaunch = AppDrawerManager.isLaunchingApp
        AppDrawerManager.isLaunchingApp = false

        if (isAppLaunch) {
            // 使用者從抽屜點擊開啟了第三方 App -> 明確標記非休眠
            wasDisplaySleeping = false
            Log.d(TAG, "onPause: Launching app from drawer -> wasDisplaySleeping = false")
        } else {
            // 預設為手放下/螢幕超時休眠
            wasDisplaySleeping = true
            Log.d(TAG, "onPause: Screen sleep / wrist lowered candidate -> wasDisplaySleeping = true")

            // 防護：若 350ms 後螢幕仍然完全亮起且可互動，代表是開啟了系統快捷設定、Tiles 或多工介面
            mainHandler.postDelayed({
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm?.isInteractive == true && !isAmbient) {
                    wasDisplaySleeping = false
                    Log.d(TAG, "onPause delay-check: Screen still interactive -> wasDisplaySleeping = false")
                }
            }, 350)
        }

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

        // 當 Activity 恢復前景時，若是從手錶休眠/微光狀態喚醒，直接觸發開麥
        if (wasDisplaySleeping) {
            Log.d(TAG, "onResume: Resumed from wrist sleep!")
            tryTriggerWakeOnResume()
        } else {
            Log.d(TAG, "onResume: Returned from another app or settings -> No wake")
        }
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
