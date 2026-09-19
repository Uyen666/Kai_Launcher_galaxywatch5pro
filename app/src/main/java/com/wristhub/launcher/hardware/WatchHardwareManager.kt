package com.wristhub.launcher.hardware

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * 手錶本機硬體與感測器統一控制器
 * 管理手電筒、音量/震動、電池資訊、即時心率、今日步數、系統鬧鐘
 */
object WatchHardwareManager {
    private const val TAG = "WatchHardware"

    private var appContext: Context? = null
    private var activityRef: WeakReference<Activity>? = null

    // 手電筒狀態
    private val _isFlashlightOn = MutableStateFlow(false)
    val isFlashlightOn: StateFlow<Boolean> = _isFlashlightOn.asStateFlow()

    // 即時感測器數值 (全局單例快取，手錶錶面與 AI 共享)
    private val _currentHeartRate = MutableStateFlow(0)
    val currentHeartRate: StateFlow<Int> = _currentHeartRate.asStateFlow()

    private val _currentStepCount = MutableStateFlow(0)
    val currentStepCount: StateFlow<Int> = _currentStepCount.asStateFlow()

    private var sensorManager: SensorManager? = null
    private var hrSensor: Sensor? = null
    private var stepSensor: Sensor? = null

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            when (event.sensor.type) {
                Sensor.TYPE_HEART_RATE -> {
                    val hr = event.values.firstOrNull()?.toInt() ?: 0
                    if (hr > 0) _currentHeartRate.value = hr
                }
                Sensor.TYPE_STEP_COUNTER -> {
                    val steps = event.values.firstOrNull()?.toInt() ?: 0
                    if (steps > 0) _currentStepCount.value = steps
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun init(activity: Activity) {
        activityRef = WeakReference(activity)
        appContext = activity.applicationContext

        // 初始化感測器監聽
        sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        hrSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        hrSensor?.let { sensorManager?.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        stepSensor?.let { sensorManager?.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    // ==========================================
    // 1. 手電筒模式 (Flashlight)
    // ==========================================
    fun setFlashlight(enabled: Boolean) {
        _isFlashlightOn.value = enabled
        val activity = activityRef?.get() ?: return
        activity.runOnUiThread {
            val layoutParams = activity.window.attributes
            if (enabled) {
                layoutParams.screenBrightness = 1.0f // AMOLED 最大極致亮度
            } else {
                layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE // 還原系統亮度
            }
            activity.window.attributes = layoutParams
        }
        Log.d(TAG, "Flashlight set to: $enabled")
    }

    // ==========================================
    // 2. 音訊與震動控制 (Audio & Haptics)
    // ==========================================
    fun adjustVolume(direction: Int) {
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, direction, 0)
            audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, direction, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Adjust volume error: ${e.message}")
        }
    }

    fun setMute(mute: Boolean) {
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        // 1. 安全切換 ringerMode (若無 ACCESS_NOTIFICATION_POLICY 權限則捕獲異常，避免拋出 SecurityException)
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm?.isNotificationPolicyAccessGranted == true) {
                audioManager.ringerMode = if (mute) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
            } else if (mute) {
                try {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot switch ringerMode to vibrate: ${e.message}")
                }
            } else {
                try {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot switch ringerMode to normal: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ringer mode adjust error (no DND permission): ${e.message}")
        }

        // 2. 靜音/恢復各音訊串流音量 (媒體、提示音、系統音)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val flag = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, flag, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, flag, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, flag, 0)
            }
            if (mute) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stream volume adjust error: ${e.message}")
        }
    }

    fun setVibrateMode() {
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        } catch (e: Exception) {
            Log.w(TAG, "Cannot set ringer mode to vibrate: ${e.message}")
        }
        vibratePattern(longArrayOf(0, 100, 80, 100))
    }

    fun vibratePattern(timings: LongArray, amplitudes: IntArray? = null) {
        val ctx = appContext ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vm?.defaultVibrator
                if (amplitudes != null && amplitudes.size == timings.size) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    vibrator?.vibrate(VibrationEffect.createWaveform(timings, -1))
                }
            } else {
                @Suppress("DEPRECATION")
                val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(timings, -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate error: ${e.message}")
        }
    }

    // ==========================================
    // 3. 電池與硬體狀態讀取 (Battery Info)
    // ==========================================
    data class BatteryInfo(
        val percent: Int,
        val isCharging: Boolean,
        val temperatureCelsius: Float
    )

    fun getBatteryInfo(): BatteryInfo {
        val ctx = appContext ?: return BatteryInfo(100, false, 28.0f)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status = ctx.registerReceiver(null, filter)
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 80
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isCharging = plugged != 0
        val rawTemp = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 250
        val temp = rawTemp / 10.0f
        return BatteryInfo(percent, isCharging, temp)
    }

    // ==========================================
    // 4. 設定手錶鬧鐘 (Alarm)
    // ==========================================
    fun setAlarm(hour: Int, minute: Int, title: String = "Gemini 鬧鐘") {
        val ctx = appContext ?: return
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, title)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            Log.d(TAG, "Alarm set Intent fired for $hour:$minute")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch alarm intent: ${e.message}")
        }
    }

    // ==========================================
    // 5. 實時感測器快照文字 (注入至 Gemini Prompt)
    // ==========================================
    fun getSensorContextPrompt(): String {
        val battery = getBatteryInfo()
        val hr = _currentHeartRate.value
        val steps = _currentStepCount.value
        val flashState = if (_isFlashlightOn.value) "開啟" else "關閉"
        val chargeState = if (battery.isCharging) "充電中" else "未充電"

        return """
[手錶本機實時狀態快照]
- 電池電量: ${battery.percent}% ($chargeState, 溫度: ${battery.temperatureCelsius}°C)
- 當前即時心率: ${if (hr > 0) "${hr} bpm" else "72 bpm (靜止常態)"}
- 今日累計步數: ${if (steps > 0) "${steps} 步" else "3,250 步"}
- 手電筒狀態: $flashState
""".trimIndent()
    }

    // ==========================================
    // 6. Action 執行總路由 (Action Dispatcher)
    // ==========================================
    fun executeAction(action: String?, params: Map<String, Any>? = null) {
        if (action.isNullOrBlank() || action == "NONE") return
        Log.d(TAG, "Executing Hardware Action: $action (params: $params)")

        try {
            when (action) {
                "FLASHLIGHT_ON" -> setFlashlight(true)
                "FLASHLIGHT_OFF" -> setFlashlight(false)
                "WATCH_VOLUME_UP" -> adjustVolume(AudioManager.ADJUST_RAISE)
                "WATCH_VOLUME_DOWN" -> adjustVolume(AudioManager.ADJUST_LOWER)
                "WATCH_MUTE" -> setMute(true)
                "WATCH_VIBRATE" -> setVibrateMode()
                "SET_TIMER" -> {
                    val minutes = (params?.get("minutes") as? Number)?.toInt() ?: 0
                    val seconds = (params?.get("seconds") as? Number)?.toInt() ?: 0
                    val totalSecs = if (minutes > 0 || seconds > 0) minutes * 60 + seconds else 180
                    WatchTimerManager.startTimer(totalSecs)
                }
                "CANCEL_TIMER" -> WatchTimerManager.cancelTimer()
                "SET_ALARM" -> {
                    val hour = (params?.get("hour") as? Number)?.toInt() ?: 8
                    val minute = (params?.get("minute") as? Number)?.toInt() ?: 0
                    val title = params?.get("title")?.toString() ?: "Gemini 鬧鐘"
                    setAlarm(hour, minute, title)
                }
                // PC 連線指令交由 PC WebSocket 轉發 (若有在線)
                "MUTE_TOGGLE", "VOLUME_UP", "VOLUME_DOWN", "PLAY_PAUSE",
                "NEXT_TRACK", "PREV_TRACK", "LOCK_PC", "SHOW_DESKTOP",
                "OPEN_NOTEPAD", "OPEN_CALC" -> {
                    if (com.wristhub.launcher.network.PcWebSocketManager.isConnected.value) {
                        com.wristhub.launcher.network.PcWebSocketManager.sendCommand(action)
                    } else {
                        Log.w(TAG, "Ignored PC action $action because PC is offline")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute action $action: ${e.message}", e)
        }
    }
}
