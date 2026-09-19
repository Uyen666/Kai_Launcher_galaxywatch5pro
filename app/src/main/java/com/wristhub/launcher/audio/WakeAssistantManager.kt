package com.wristhub.launcher.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.wristhub.launcher.network.AiSyncManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File

enum class AssistantUiState {
    IDLE,
    LISTENING_WAKE,      // 抬腕 3.5s 快速人聲偵測視窗
    RECORDING_SPEECH,    // 偵測到說話，光環旋轉，持續錄音中
    PROCESSING,          // 靜音截斷，等待 Gemini 解析
    REPLY_SHOWING        // 顯示頂部/中央浮動卡片 + TTS 朗讀
}

object WakeAssistantManager {
    private const val TAG = "WakeAssistant"
    private const val SAMPLE_RATE = 16000
    private const val CHUNK_SIZE = 640 // 40ms @ 16kHz
    private const val WAKE_LISTEN_TIMEOUT_MS = 3500L
    private const val MAX_SPEECH_DURATION_MS = 15000L

    private var appContext: Context? = null
    private val vad = VoiceActivityDetector(
        speechStartThreshold = 1400f,
        speechEndThreshold = 850f,
        silenceDurationMs = 800L
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var recordingJob: Job? = null
    private var autoDismissJob: Job? = null

    private val _uiState = MutableStateFlow(AssistantUiState.IDLE)
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val _isAuraVisible = MutableStateFlow(false)
    val isAuraVisible: StateFlow<Boolean> = _isAuraVisible.asStateFlow()

    private val _audioEnergy = MutableStateFlow(0f)
    val audioEnergy: StateFlow<Float> = _audioEnergy.asStateFlow()

    private val _currentTranscript = MutableStateFlow("")
    val currentTranscript: StateFlow<String> = _currentTranscript.asStateFlow()

    private val _currentReply = MutableStateFlow("")
    val currentReply: StateFlow<String> = _currentReply.asStateFlow()

    private val _currentAction = MutableStateFlow<String?>("")
    val currentAction: StateFlow<String?> = _currentAction.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isRecordingOrProcessing(): Boolean {
        return _uiState.value == AssistantUiState.RECORDING_SPEECH ||
               _uiState.value == AssistantUiState.PROCESSING ||
               _uiState.value == AssistantUiState.REPLY_SHOWING
    }

    private fun vibrate(durationMs: Long = 35, amplitude: Int = 180) {
        val ctx = appContext ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, amplitude)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            }
        } catch (_: Exception) {}
    }

    /**
     * 當手錶抬腕或點亮螢幕進入活躍狀態時觸發：
     * 開啟 3.5 秒短暫人聲偵測視窗，超時未說話自動關閉釋放麥克風
     */
    fun onScreenInteractive() {
        val ctx = appContext ?: return
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // 僅在完全 IDLE 狀態下開啟抬腕偵測
        if (_uiState.value != AssistantUiState.IDLE) return

        startListeningSession(isManual = false)
    }

    /**
     * 手動點擊（如雙擊錶面）喚醒助理
     */
    fun startManualListening() {
        val ctx = appContext ?: return
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        startListeningSession(isManual = true)
    }

    /**
     * 當螢幕熄滅或進入微光模式時：立即釋放麥克風，零後台耗電
     */
    fun onScreenSleep() {
        if (_uiState.value == AssistantUiState.LISTENING_WAKE) {
            stopAudioRecordAndReset()
        }
    }

    private fun stopAudioRecordAndReset() {
        recordingJob?.cancel()
        recordingJob = null
        _isAuraVisible.value = false
        _audioEnergy.value = 0f
        _uiState.value = AssistantUiState.IDLE
    }

    private fun startListeningSession(isManual: Boolean) {
        recordingJob?.cancel()
        autoDismissJob?.cancel()
        vad.reset()

        recordingJob = scope.launch(Dispatchers.IO) {
            val ctx = appContext ?: return@launch
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_SIZE * 2)

            val record = try {
                @SuppressLint("MissingPermission")
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate AudioRecord: ${e.message}")
                return@launch
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                record.release()
                return@launch
            }

            try {
                record.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord start failed: ${e.message}")
                record.release()
                return@launch
            }

            if (isManual) {
                _uiState.value = AssistantUiState.RECORDING_SPEECH
                _isAuraVisible.value = true
                vibrate(35, 200)
            } else {
                _uiState.value = AssistantUiState.LISTENING_WAKE
            }

            val pcmStream = ByteArrayOutputStream()
            val shortBuf = ShortArray(CHUNK_SIZE)
            val byteBuf = ByteArray(CHUNK_SIZE * 2)
            val sessionStartTime = System.currentTimeMillis()
            var speechStartTime = if (isManual) sessionStartTime else 0L

            try {
                while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readCount = record.read(shortBuf, 0, CHUNK_SIZE)
                    if (readCount <= 0) continue

                    // 轉為 16-bit PCM little-endian 位元組
                    for (i in 0 until readCount) {
                        val s = shortBuf[i].toInt()
                        byteBuf[i * 2] = (s and 0xFF).toByte()
                        byteBuf[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }

                    val vadRes = vad.processChunk(shortBuf, readCount, chunkDurationMs = (readCount * 1000L / SAMPLE_RATE))
                    _audioEnergy.value = (vadRes.rms / 2500f).coerceIn(0f, 1f)

                    val now = System.currentTimeMillis()

                    if (_uiState.value == AssistantUiState.LISTENING_WAKE) {
                        if (vadRes.isSpeechStarted) {
                            // 3.5秒視窗內偵測到說話起點！
                            Log.d(TAG, "Speech detected during wake window! Starting aura & recording...")
                            _uiState.value = AssistantUiState.RECORDING_SPEECH
                            _isAuraVisible.value = true
                            vibrate(40, 220)
                            speechStartTime = now
                            pcmStream.write(byteBuf, 0, readCount * 2)
                        } else if (now - sessionStartTime >= WAKE_LISTEN_TIMEOUT_MS) {
                            // 3.5秒逾時未說話 -> 自動釋放麥克風
                            Log.d(TAG, "No speech in 3.5s wake window, releasing mic.")
                            break
                        }
                    } else if (_uiState.value == AssistantUiState.RECORDING_SPEECH) {
                        pcmStream.write(byteBuf, 0, readCount * 2)

                        if (vadRes.isSpeechEnded || (now - speechStartTime >= MAX_SPEECH_DURATION_MS)) {
                            Log.d(TAG, "VAD speech ended! Captured ${pcmStream.size()} bytes.")
                            vibrate(30, 180)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording loop error: ${e.message}", e)
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {}
                try {
                    record.release()
                } catch (_: Exception) {}
            }

            if (_uiState.value == AssistantUiState.RECORDING_SPEECH && pcmStream.size() >= SAMPLE_RATE) {
                handleCapturedSpeech(pcmStream.toByteArray())
            } else {
                _isAuraVisible.value = false
                _uiState.value = AssistantUiState.IDLE
            }
        }
    }

    private fun handleCapturedSpeech(pcmData: ByteArray) {
        val ctx = appContext ?: return
        _uiState.value = AssistantUiState.PROCESSING

        // 停止光環並等待 0.5 秒光環淡出消失 (依據使用者規範)
        scope.launch {
            delay(500)
            _isAuraVisible.value = false
        }

        val wavFile = File(ctx.cacheDir, "gemini_prompt_${System.currentTimeMillis()}.wav")
        WavUtils.pcmToWavFile(pcmData, wavFile, sampleRate = SAMPLE_RATE)

        AiSyncManager.uploadAudio(
            context = ctx,
            audioFile = wavFile,
            onSuccess = { conv ->
                _currentTranscript.value = conv.userText
                _currentReply.value = conv.aiReply
                _currentAction.value = conv.action
                _uiState.value = AssistantUiState.REPLY_SHOWING

                // 揚聲器 TTS 朗讀
                WatchTtsManager.getInstance(ctx).speak(conv.aiReply)

                scheduleAutoDismiss()
            },
            onError = { err ->
                _currentTranscript.value = "語音辨識"
                _currentReply.value = err
                _currentAction.value = null
                _uiState.value = AssistantUiState.REPLY_SHOWING

                scheduleAutoDismiss()
            }
        )
    }

    private fun scheduleAutoDismiss() {
        autoDismissJob?.cancel()
        autoDismissJob = scope.launch {
            // 保留 6 秒顯示時間供使用者閱讀，隨後自動收回
            delay(6000)
            if (_uiState.value == AssistantUiState.REPLY_SHOWING) {
                _uiState.value = AssistantUiState.IDLE
            }
        }
    }

    fun dismissReply() {
        autoDismissJob?.cancel()
        _uiState.value = AssistantUiState.IDLE
    }
}
