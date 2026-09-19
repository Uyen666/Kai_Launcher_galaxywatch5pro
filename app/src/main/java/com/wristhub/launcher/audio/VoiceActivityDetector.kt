package com.wristhub.launcher.audio

import kotlin.math.sqrt

/**
 * 輕量級即時 VAD (Voice Activity Detection) 靜音與防刮擦雜音偵測器
 * 結合 RMS 能量、連續訊框確認機制與過零率 (ZCR) 排除抓頭髮、衣物摩擦等高頻非語音雜音
 */
class VoiceActivityDetector(
    private val speechStartThreshold: Float = 1800f,
    private val speechEndThreshold: Float = 950f,
    private val silenceDurationMs: Long = 850L,
    private val minVoicedFramesForStart: Int = 3,
    private val maxZcrThreshold: Float = 0.40f
) {
    private var isSpeechActive = false
    private var silenceAccumulatedMs = 0L
    private var consecutiveVoicedFrames = 0

    fun reset() {
        isSpeechActive = false
        silenceAccumulatedMs = 0L
        consecutiveVoicedFrames = 0
    }

    /**
     * 處理音訊片段，返回當前狀態：
     * @param buffer 16-bit PCM short buffer
     * @param readCount 實際讀取的 short 數量
     * @param chunkDurationMs 該 buffer 代表的毫秒數 (例如 16kHz 下 640 shorts = 40ms)
     * @return VadResult (包含即時 RMS、是否正在說話、是否剛觸發起點/結束、以及是否為摩擦雜音)
     */
    fun processChunk(buffer: ShortArray, readCount: Int, chunkDurationMs: Long): VadResult {
        if (readCount <= 0) {
            return VadResult(
                rms = 0f,
                zcr = 0f,
                isSpeech = isSpeechActive,
                isSpeechEnded = false,
                isSpeechStarted = false,
                isFrictionNoise = false
            )
        }

        var sumSquare = 0.0
        var zeroCrossings = 0

        for (i in 0 until readCount) {
            val sample = buffer[i].toDouble()
            sumSquare += sample * sample

            if (i > 0) {
                val prev = buffer[i - 1].toInt()
                val curr = buffer[i].toInt()
                if ((prev >= 0 && curr < 0) || (prev < 0 && curr >= 0)) {
                    zeroCrossings++
                }
            }
        }

        val rms = sqrt(sumSquare / readCount).toFloat()
        val zcr = zeroCrossings.toFloat() / readCount

        // 摩擦雜音判定：抓頭髮、衣物刮擦或拍打麥克風孔常造成極端高頻過零率 (ZCR > 40%)
        val isFrictionNoise = zcr > maxZcrThreshold

        var isSpeechStarted = false
        var isSpeechEnded = false

        if (!isSpeechActive) {
            // 排除單純高頻摩擦雜音，必須是具有母音/語音特徵且能量充足的訊框
            val isValidVoicedFrame = (rms >= speechStartThreshold) && !isFrictionNoise

            if (isValidVoicedFrame) {
                consecutiveVoicedFrames++
                // 需連續維持至少 3 訊框 (~120ms) 才確認為真正的人類開口，避免單點瞬態衝擊
                if (consecutiveVoicedFrames >= minVoicedFramesForStart) {
                    isSpeechActive = true
                    silenceAccumulatedMs = 0L
                    isSpeechStarted = true
                }
            } else {
                if (rms < speechEndThreshold) {
                    consecutiveVoicedFrames = 0
                } else {
                    // 若為高能量但高頻雜音 (摩擦/刮擦)，衰減連續訊框數以防累計觸發
                    consecutiveVoicedFrames = (consecutiveVoicedFrames - 1).coerceAtLeast(0)
                }
            }
        } else {
            // 已進入語音錄製中
            if (rms < speechEndThreshold) {
                silenceAccumulatedMs += chunkDurationMs
                if (silenceAccumulatedMs >= silenceDurationMs) {
                    isSpeechActive = false
                    isSpeechEnded = true
                    silenceAccumulatedMs = 0L
                    consecutiveVoicedFrames = 0
                }
            } else {
                silenceAccumulatedMs = 0L
            }
        }

        return VadResult(
            rms = rms,
            zcr = zcr,
            isSpeech = isSpeechActive,
            isSpeechStarted = isSpeechStarted,
            isSpeechEnded = isSpeechEnded,
            isFrictionNoise = isFrictionNoise
        )
    }

    data class VadResult(
        val rms: Float,
        val zcr: Float,
        val isSpeech: Boolean,
        val isSpeechStarted: Boolean,
        val isSpeechEnded: Boolean,
        val isFrictionNoise: Boolean
    )
}
