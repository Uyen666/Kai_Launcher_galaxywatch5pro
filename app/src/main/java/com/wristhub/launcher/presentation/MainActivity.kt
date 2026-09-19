package com.wristhub.launcher.presentation

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.ambient.AmbientLifecycleObserver

import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.mutableLongStateOf

class MainActivity : ComponentActivity() {

    private var isAmbient by mutableStateOf(false)
    private var ambientUpdateTrigger by mutableLongStateOf(0L)
    private var resetToWatchFaceTrigger by mutableLongStateOf(0L)
    private var lastInactiveTimestamp = 0L

    companion object {
        private const val AMBIENT_RESET_TIMEOUT_MS = 30_000L
    }

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient = true
            lastInactiveTimestamp = System.currentTimeMillis()
            com.wristhub.launcher.audio.WakeAssistantManager.onScreenSleep()
        }

        override fun onExitAmbient() {
            isAmbient = false
            checkAndTriggerWakeReset()
            com.wristhub.launcher.audio.WakeAssistantManager.onScreenInteractive()
        }

        override fun onUpdateAmbient() {
            // Periodic update in ambient mode (called ~1/min by system RTC)
            ambientUpdateTrigger = System.currentTimeMillis()
        }
    }

    private fun checkAndTriggerWakeReset() {
        if (lastInactiveTimestamp > 0L) {
            val elapsed = System.currentTimeMillis() - lastInactiveTimestamp
            val isAiBusy = com.wristhub.launcher.network.AiSyncManager.isAiTaskActive()
            if (elapsed >= AMBIENT_RESET_TIMEOUT_MS && !isAiBusy) {
                resetToWatchFaceTrigger = System.currentTimeMillis()
                com.wristhub.launcher.manager.AppDrawerManager.setDrawerOpen(false)
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
        com.wristhub.launcher.network.PcWebSocketManager.init(this)

        // Pre-warm TextToSpeech engine so first utterance is instant
        com.wristhub.launcher.audio.WatchTtsManager.init(this)

        // Initialize Raise-to-Speak Wake Assistant
        com.wristhub.launcher.audio.WakeAssistantManager.init(this)

        // Initialize Watch Hardware Controller & Timer Manager
        com.wristhub.launcher.hardware.WatchHardwareManager.init(this)
        com.wristhub.launcher.hardware.WatchTimerManager.setContext(this)

        // Initialize App Drawer Manager (scan apps & register package receiver)
        com.wristhub.launcher.manager.AppDrawerManager.init(this)

        // Ensure RECORD_AUDIO permission is granted
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
        }

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
        if (lastInactiveTimestamp == 0L) {
            lastInactiveTimestamp = System.currentTimeMillis()
        }
        com.wristhub.launcher.audio.WakeAssistantManager.onScreenSleep()
    }

    override fun onResume() {
        super.onResume()
        if (!isAmbient) {
            checkAndTriggerWakeReset()
            // NOTE: Do NOT call onScreenInteractive() here.
            // onScreenInteractive() is strictly for ambient wake-up (raise wrist),
            // not for resuming from another app/recents.
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
