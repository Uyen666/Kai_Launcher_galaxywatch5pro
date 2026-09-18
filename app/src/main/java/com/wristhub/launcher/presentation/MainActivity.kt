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

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient = true
        }

        override fun onExitAmbient() {
            isAmbient = false
        }

        override fun onUpdateAmbient() {
            // Periodic update in ambient mode (called ~1/min by system RTC)
            ambientUpdateTrigger = System.currentTimeMillis()
        }
    }

    private val ambientObserver by lazy {
        AmbientLifecycleObserver(this, ambientCallback)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize WebSocket & Local WatchFace Cache
        com.wristhub.launcher.network.PcWebSocketManager.init(this)

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
                ambientUpdateTrigger = ambientUpdateTrigger
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure state is refreshed when waking up
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
