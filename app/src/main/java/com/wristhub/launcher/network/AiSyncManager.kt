package com.wristhub.launcher.network

import android.util.Log
import com.wristhub.launcher.data.AiConversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object AiSyncManager {
    private const val TAG = "AiSync"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _conversations = MutableStateFlow<List<AiConversation>>(emptyList())
    val conversations: StateFlow<List<AiConversation>> = _conversations.asStateFlow()

    private val _latestReply = MutableStateFlow<AiConversation?>(null)
    val latestReply: StateFlow<AiConversation?> = _latestReply.asStateFlow()

    fun uploadAudio(
        audioFile: File,
        onSuccess: (AiConversation) -> Unit,
        onError: (String) -> Unit
    ) {
        if (_isProcessing.value) return
        _isProcessing.value = true

        scope.launch {
            try {
                val ip = PcWebSocketManager.currentPcIp
                val url = "http://$ip:8765/api/ai/voice"
                Log.d(TAG, "Uploading audio to $url (${audioFile.length()} bytes)")

                val fileBody = audioFile.asRequestBody("audio/mp4".toMediaType())
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.name, fileBody)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val resBody = response.body?.string() ?: ""

                if (response.isSuccessful && resBody.isNotBlank()) {
                    val json = JSONObject(resBody)
                    val transcript = json.optString("transcript", "語音指令")
                    val reply = json.optString("reply", "處理完成")
                    val action = json.optString("action", "NONE")
                    val actionResult = if (json.has("action_result") && !json.isNull("action_result")) json.getString("action_result") else null

                    val conversation = AiConversation(
                        userText = transcript,
                        aiReply = reply,
                        action = action,
                        actionResult = actionResult
                    )

                    withContext(Dispatchers.Main) {
                        _conversations.value = listOf(conversation) + _conversations.value
                        _latestReply.value = conversation
                        _isProcessing.value = false
                        onSuccess(conversation)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _isProcessing.value = false
                        onError("伺服器回應錯誤 (${response.code})")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI voice upload failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                    onError("連線失敗: 請確認電腦端伺服器已啟動")
                }
            } finally {
                audioFile.delete()
            }
        }
    }

    fun clearHistory() {
        _conversations.value = emptyList()
        _latestReply.value = null
    }
}
