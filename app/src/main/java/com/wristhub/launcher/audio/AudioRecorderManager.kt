package com.wristhub.launcher.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorderManager(private val context: Context) {
    private val tag = "AudioRecorder"
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    var isRecording = false
        private set

    fun startRecording(): Boolean {
        if (isRecording) return false
        try {
            val audioFile = File(context.cacheDir, "ai_voice_${System.currentTimeMillis()}.m4a")
            currentFile = audioFile

            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }

            recorder = newRecorder
            isRecording = true
            Log.d(tag, "Recording started -> ${audioFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start recording: ${e.message}", e)
            cleanup()
            return false
        }
    }

    fun stopRecording(): File? {
        if (!isRecording) return null
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            isRecording = false

            val file = currentFile
            if (file != null && file.exists() && file.length() > 0) {
                Log.d(tag, "Recording finished (${file.length()} bytes)")
                file
            } else {
                Log.w(tag, "Recording file is empty or missing")
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "Error stopping recorder: ${e.message}", e)
            cleanup()
            null
        }
    }

    fun cancelRecording() {
        cleanup()
    }

    private fun cleanup() {
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        isRecording = false
        currentFile?.delete()
        currentFile = null
    }
}
