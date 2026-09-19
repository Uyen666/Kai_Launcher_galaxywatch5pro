package com.wristhub.launcher.audio

import kotlin.math.sqrt

/**
 * 輕量級即時 VAD (Voice Activity Detection) 靜音偵測器
 * 依據 16-bit PCM 音訊能量 (RMS) 動態判定語音起點與終點 (800ms 靜音截斷)
 */
class VoiceActivityDetector(
    private val speechStartThreshold: Float = 1400f,
    private val speechEndThreshold: Float = 900f,
    private val silenceDurationMs: Long = 800L
) {
    private var isSpeechActive = false
    private var silenceAccumulatedMs = 0L

    fun reset() {
        isSpeechActive = false
        silenceAccumulatedMs = 0L
    }

    /**
     * 處理音訊片段，返回當前狀態：
     * @param buffer 16-bit PCM short buffer
     * @param readCount 實際讀取的 short 數量
     * @param chunkDurationMs 該 buffer 代表的毫秒數 (例如 16kHz 下 640 shorts = 40ms)
     * @return VadResult (包含即時 RMS、是否正在說話、是否剛觸發結束)
     */
    fun processChunk(buffer: ShortArray, readCount: Int, chunkDurationMs: Long): VadResult {
        if (readCount <= 0) {
            return VadResult(rms = 0f, isSpeech = isSpeechActive, isSpeechEnded = false, isSpeechStarted = false)
        }

        var sumSquare = 0.0
        for (i in 0 until readCount) {
            val sample = buffer[i].toDouble()
            sumSquare += sample * sample
        }
        val rms = sqrt(sumSquare / readCount).toFloat()

        var isSpeechStarted = false
        var isSpeechEnded = false

        if (!isSpeechActive) {
            if (rms >= speechStartThreshold) {
                isSpeechActive = true
                silenceAccumulatedMs = 0L
                isSpeechStarted = true
            }
        } else {
            if (rms < speechEndThreshold) {
                silenceAccumulatedMs += chunkDurationMs
                if (silenceAccumulatedMs >= silenceDurationMs) {
                    isSpeechActive = false
                    isSpeechEnded = true
                    silenceAccumulatedMs = 0L
                }
            } else {
                silenceAccumulatedMs = 0L
            }
        }

        return VadResult(
            rms = rms,
            isSpeech = isSpeechActive,
            isSpeechStarted = isSpeechStarted,
            isSpeechEnded = isSpeechEnded
        )
    }

    data class VadResult(
        val rms: Float,
        val isSpeech: Boolean,
        val isSpeechStarted: Boolean,
        val isSpeechEnded: Boolean
    )
}
