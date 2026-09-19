package com.wristhub.launcher.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
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

    // 返回防護旗標：當 Launcher 在螢幕仍為亮起活躍時暫停（如開啟第三方 App、系統設定），此旗標設為 true
    // 當使用者關閉該 App 退回首頁時，螢幕本來就是亮的，藉此 100% 杜絕誤觸開麥
    private var isCoveredByOtherApp = false

    // 螢幕硬體點亮喚醒等待旗標：僅在 ACTION_SCREEN_ON 或 onExitAmbient 時設為 true
    private var pendingScreenWakeTrigger = false

    private var isActivityResumed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val AMBIENT_RESET_TIMEOUT_MS = 30_000L
    }

    /**
     * 監聽真實螢幕硬體開關廣播 (ACTION_SCREEN_ON / ACTION_SCREEN_OFF)
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Hardware screen turned ON (ACTION_SCREEN_ON)")
                    onHardwareScreenOn()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Hardware screen turned OFF (ACTION_SCREEN_OFF)")
                    onHardwareScreenOff()
                }
            }
        }
    }

    private fun onHardwareScreenOn() {
        // 若當前是被第三方應用遮蔽（在別的 App 裡休眠後抬腕），不在此觸發 Launcher 的語音喚醒
        if (isCoveredByOtherApp) {
            Log.d(TAG, "ACTION_SCREEN_ON: Ignored because launcher is covered by other app")
            return
        }
        pendingScreenWakeTrigger = true
        tryTriggerWakeAssistant()
        // 延時重試防護：預防 onResume() 執行排程微幅落後於系統廣播
        mainHandler.postDelayed({
            if (pendingScreenWakeTrigger && isActivityResumed) {
                tryTriggerWakeAssistant()
            }
        }, 150)
    }

    private fun onHardwareScreenOff() {
        // 若螢幕關閉時 Launcher 位於前景，則重置遮蔽狀態
        if (isActivityResumed) {
            isCoveredByOtherApp = false
        }
        pendingScreenWakeTrigger = false
        lastInactiveTimestamp = System.currentTimeMillis()
        WakeAssistantManager.onScreenSleep()
    }

    private fun tryTriggerWakeAssistant() {
        if (!pendingScreenWakeTrigger) return
        if (!isActivityResumed) return
        if (isAmbient) return
        if (isCoveredByOtherApp) {
            pendingScreenWakeTrigger = false
            return
        }

        val isEligible = LauncherStateManager.isHudWatchFaceEligible()
        Log.d(TAG, "tryTriggerWakeAssistant: isEligible=$isEligible, isResumed=$isActivityResumed")
        if (isEligible) {
            pendingScreenWakeTrigger = false
            WakeAssistantManager.onScreenInteractive()
        } else {
            pendingScreenWakeTrigger = false
        }
    }

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient = true
            lastInactiveTimestamp = System.currentTimeMillis()
            pendingScreenWakeTrigger = false
            WakeAssistantManager.onScreenSleep()
        }

        override fun onExitAmbient() {
            isAmbient = false
            checkAndTriggerWakeReset()
            // 退出微光 AOD 模式（抬腕亮起）
            if (!isCoveredByOtherApp) {
                pendingScreenWakeTrigger = true
                tryTriggerWakeAssistant()
                mainHandler.postDelayed({
                    if (pendingScreenWakeTrigger && isActivityResumed) {
                        tryTriggerWakeAssistant()
                    }
                }, 150)
            }
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
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isInteractive = powerManager?.isInteractive ?: true
        if (isInteractive && !isAmbient) {
            // 螢幕仍處於亮屏與互動狀態時 Launcher 被暫停 -> 代表使用者開啟了第三方 App 或系統設定
            isCoveredByOtherApp = true
            Log.d(TAG, "onPause: Launcher covered by other app -> isCoveredByOtherApp = true")
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

        // 若是由螢幕剛點亮 (ACTION_SCREEN_ON) 產生的待處理喚醒
        if (pendingScreenWakeTrigger) {
            tryTriggerWakeAssistant()
        }

        // 處理完畢後，若先前曾被其他 App 遮蔽，在此消耗並重置防護旗標（退回首頁絕不誤觸）
        if (isCoveredByOtherApp) {
            Log.d(TAG, "onResume: Returned from other app -> isCoveredByOtherApp consumed, no wake")
            isCoveredByOtherApp = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
