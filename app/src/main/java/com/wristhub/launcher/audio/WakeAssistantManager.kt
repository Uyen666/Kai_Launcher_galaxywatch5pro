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
        speechStartThreshold = 1800f,
        speechEndThreshold = 950f,
        silenceDurationMs = 850L,
        minVoicedFramesForStart = 3,
        maxZcrThreshold = 0.40f
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var recordingJob: Job? = null
    private var autoDismissJob: Job? = null
    private var isDictationSession = false

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

        isDictationSession = false
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
        isDictationSession = false
        startListeningSession(isManual = true)
    }

    /**
     * 觸發電腦語音打字聽寫模式：
     * 所講內容直接轉為純文字並輸入至電腦游標處
     */
    fun startDictationListening() {
        val ctx = appContext ?: return
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        isDictationSession = true
        vibrate(durationMs = 30, amplitude = 200)
        startListeningSession(isManual = true)
    }

    /**
     * 當螢幕熄滅或進入微光模式時：立即釋放麥克風，零後台耗電
     */
    fun onScreenSleep() {
        if (_uiState.value == AssistantUiState.LISTENING_WAKE || _uiState.value == AssistantUiState.RECORDING_SPEECH) {
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
                // 抬腕亮螢幕：進入 3.5s 靜態人聲偵測視窗（無光圈、無震動，安靜日常看錶）
                _uiState.value = AssistantUiState.LISTENING_WAKE
                _isAuraVisible.value = false
                _audioEnergy.value = 0f
            }

            val pcmStream = ByteArrayOutputStream()
            val shortBuf = ShortArray(CHUNK_SIZE)
            val byteBuf = ByteArray(CHUNK_SIZE * 2)
            val sessionStartTime = System.currentTimeMillis()
            var speechStartTime = if (isManual) sessionStartTime else 0L

            // 預錄音環形緩衝區 (保留最近 160ms 音訊，避免開頭單字音被截斷)
            val preRollBuffer = ArrayDeque<ByteArray>()
            val maxPreRollChunks = 4

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
                    if (_isAuraVisible.value) {
                        _audioEnergy.value = (vadRes.rms / 2500f).coerceIn(0f, 1f)
                    } else {
                        _audioEnergy.value = 0f
                    }

                    val now = System.currentTimeMillis()

                    if (_uiState.value == AssistantUiState.LISTENING_WAKE) {
                        // 保留最近 160ms 音訊訊框
                        val chunkCopy = byteBuf.copyOf(readCount * 2)
                        if (preRollBuffer.size >= maxPreRollChunks) {
                            preRollBuffer.removeFirst()
                        }
                        preRollBuffer.addLast(chunkCopy)

                        if (vadRes.isSpeechStarted) {
                            // 3.5 秒靜態視窗內偵測到清晰人聲！此時才點亮彩色光圈並震動提示
                            Log.d(TAG, "Speech detected during wake window! Starting aura & vibration...")
                            _uiState.value = AssistantUiState.RECORDING_SPEECH
                            _isAuraVisible.value = true
                            vibrate(40, 220)
                            speechStartTime = now

                            // 將開口前 160ms 音訊全數灌入音訊流，保證開頭第一個字完整
                            while (preRollBuffer.isNotEmpty()) {
                                val pre = preRollBuffer.removeFirst()
                                pcmStream.write(pre, 0, pre.size)
                            }
                            pcmStream.write(byteBuf, 0, readCount * 2)
                        } else if (now - sessionStartTime >= WAKE_LISTEN_TIMEOUT_MS) {
                            // 3.5秒逾時未說話 -> 安靜釋放麥克風，不亮光圈、不震動
                            Log.d(TAG, "No speech in 3.5s wake window, releasing mic silently.")
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

            val recordedDurationMs = if (speechStartTime > 0L) (System.currentTimeMillis() - speechStartTime) else 0L
            // 最短有效語音保護：若有效說話長度小於 600ms 或音訊小於 19200 bytes，視為摩擦或短暫雜音，安靜捨棄
            if (_uiState.value == AssistantUiState.RECORDING_SPEECH && pcmStream.size() >= 19200 && recordedDurationMs >= 600L) {
                handleCapturedSpeech(pcmStream.toByteArray())
            } else {
                Log.d(TAG, "Audio discarded: size=${pcmStream.size()} bytes, duration=${recordedDurationMs}ms (below threshold).")
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

        val dictationModeActive = isDictationSession
        isDictationSession = false

        AiSyncManager.uploadAudio(
            context = ctx,
            audioFile = wavFile,
            isDictation = dictationModeActive,
            onSuccess = { conv ->
                val transcript = conv.userText.trim()
                if (transcript.isEmpty() || transcript == "(雜音)" || (conv.action == "NONE" && conv.aiReply.isBlank())) {
                    Log.d(TAG, "Ignored noise/empty transcript, resetting to IDLE.")
                    _uiState.value = AssistantUiState.IDLE
                    return@uploadAudio
                }

                _currentTranscript.value = conv.userText
                _currentReply.value = conv.aiReply
                _currentAction.value = conv.action
                _uiState.value = AssistantUiState.REPLY_SHOWING

                // 揚聲器 TTS 朗讀與震動回饋
                if (conv.action == "TYPE_TEXT") {
                    vibrate(durationMs = 45, amplitude = 220)
                    WatchTtsManager.getInstance(ctx).speak("已輸入電腦")
                } else {
                    WatchTtsManager.getInstance(ctx).speak(conv.aiReply)
                }

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
