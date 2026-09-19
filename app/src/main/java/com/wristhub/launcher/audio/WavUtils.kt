package com.wristhub.launcher.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtils {
    /**
     * 將記憶體中的原始 PCM 位元組包裝成標準 44-byte WAV 檔
     */
    fun pcmToWavFile(
        pcmData: ByteArray,
        outputFile: File,
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ) {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF Header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen)
        buffer.put("WAVE".toByteArray())

        // fmt Sub-chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size = 16 for PCM
        buffer.putShort(1.toShort()) // 1 = PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * bitsPerSample / 8).toShort())
        buffer.putShort(bitsPerSample.toShort())

        // data Sub-chunk
        buffer.put("data".toByteArray())
        buffer.putInt(totalAudioLen)

        FileOutputStream(outputFile).use { fos ->
            fos.write(header)
            fos.write(pcmData)
        }
    }
}
