package com.wristhub.launcher.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    // 螢幕由暗轉亮的真實時間戳記（僅由 ACTION_SCREEN_ON 或 onExitAmbient 賦值）
    // 用於精確判定是否為「真實抬腕/點亮螢幕喚醒」；從第三方 App 退回首頁時螢幕本為亮起，時間戳過期，100% 絕不誤觸
    private var lastScreenOnTimestamp = 0L

    private var isActivityResumed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val AMBIENT_RESET_TIMEOUT_MS = 30_000L
        private const val SCREEN_ON_FRESHNESS_WINDOW_MS = 1500L
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
        lastScreenOnTimestamp = System.currentTimeMillis()
        tryTriggerWakeAssistant()
        // 延時排程防護：若廣播抵達時 onResume 仍在排程中，150ms 後重試
        mainHandler.postDelayed({
            tryTriggerWakeAssistant()
        }, 150)
    }

    private fun onHardwareScreenOff() {
        lastScreenOnTimestamp = 0L
        lastInactiveTimestamp = System.currentTimeMillis()
        WakeAssistantManager.onScreenSleep()
    }

    private fun tryTriggerWakeAssistant() {
        if (lastScreenOnTimestamp == 0L) return
        val elapsed = System.currentTimeMillis() - lastScreenOnTimestamp
        if (elapsed > SCREEN_ON_FRESHNESS_WINDOW_MS) {
            // 螢幕亮起已超過 1.5 秒（非新鮮亮屏事件，例如從別的 App 返回），不予觸發
            return
        }
        if (!isActivityResumed) return
        if (isAmbient) return

        val isEligible = LauncherStateManager.isHudWatchFaceEligible()
        Log.d(TAG, "tryTriggerWakeAssistant: elapsed=${elapsed}ms, isEligible=$isEligible, isResumed=$isActivityResumed")
        if (isEligible) {
            lastScreenOnTimestamp = 0L // 成功觸發，消費時間戳防重複
            WakeAssistantManager.onScreenInteractive()
        }
    }

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient = true
            lastInactiveTimestamp = System.currentTimeMillis()
            lastScreenOnTimestamp = 0L
            WakeAssistantManager.onScreenSleep()
        }

        override fun onExitAmbient() {
            isAmbient = false
            checkAndTriggerWakeReset()
            // 退出微光 AOD 模式（抬腕亮起）
            lastScreenOnTimestamp = System.currentTimeMillis()
            tryTriggerWakeAssistant()
            mainHandler.postDelayed({
                tryTriggerWakeAssistant()
            }, 150)
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

        // 若是新鮮亮屏喚醒（剛抬腕亮起），在此觸發
        tryTriggerWakeAssistant()
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
