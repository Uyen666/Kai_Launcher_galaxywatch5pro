package com.wristhub.launcher.hardware

import android.content.Context
import com.wristhub.launcher.audio.WatchTtsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 手錶本機背景倒數計時器
 * 提供即時剩餘時間流、時間到震動警報與語音提醒
 */
object WatchTimerManager {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var timerJob: Job? = null

    private val _isTimerActive = MutableStateFlow(false)
    val isTimerActive: StateFlow<Boolean> = _isTimerActive.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

    private val _originalSeconds = MutableStateFlow(0)
    val originalSeconds: StateFlow<Int> = _originalSeconds.asStateFlow()

    fun startTimer(seconds: Int) {
        if (seconds <= 0) return
        cancelTimer()

        _originalSeconds.value = seconds
        _remainingSeconds.value = seconds
        _isTimerActive.value = true

        // 短震提示計時開始
        WatchHardwareManager.vibratePattern(longArrayOf(0, 80))

        timerJob = scope.launch {
            while (isActive && _remainingSeconds.value > 0) {
                delay(1000L)
                _remainingSeconds.value -= 1
            }

            if (_remainingSeconds.value <= 0 && _isTimerActive.value) {
                onTimerFinished()
            }
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _isTimerActive.value = false
        _remainingSeconds.value = 0
    }

    private fun onTimerFinished() {
        _isTimerActive.value = false
        _remainingSeconds.value = 0

        // 連續強烈節奏脈衝震動 (持續 3 秒)
        val timings = longArrayOf(0, 350, 150, 350, 150, 350, 150, 500)
        WatchHardwareManager.vibratePattern(timings)

        // 揚聲器語音通知
        val mins = _originalSeconds.value / 60
        val secs = _originalSeconds.value % 60
        val text = if (mins > 0 && secs > 0) {
            "倒數 ${mins} 分 ${secs} 秒時間到囉！"
        } else if (mins > 0) {
            "倒數 ${mins} 分鐘時間到囉！"
        } else {
            "倒數 ${_originalSeconds.value} 秒時間到囉！"
        }
        
        // 透過全局 TTS 播報
        appContextInstance?.let {
            WatchTtsManager.getInstance(it).speak(text)
        }
    }

    private var appContextInstance: Context? = null
    fun setContext(context: Context) {
        appContextInstance = context.applicationContext
    }

    fun getFormattedRemaining(): String {
        val total = _remainingSeconds.value
        val m = total / 60
        val s = total % 60
        return String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}
