package com.wristhub.launcher.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.wristhub.launcher.audio.AudioRecorderManager
import com.wristhub.launcher.audio.WatchTtsManager
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
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.wristhub.launcher.hardware.WatchHardwareManager
import com.wristhub.launcher.hardware.OfflineIntentMatcher
import java.util.concurrent.TimeUnit

object AiSyncManager {
    private const val TAG = "AiSync"

    fun getSystemInstruction(): String {
        val sensorContext = WatchHardwareManager.getSensorContextPrompt()
        return """
你是一個專為 Samsung Galaxy Watch 5 Pro 設計的手腕 Siri / Intelligence 語音助理 (WristHub Assistant)。
使用者對手錶說了一段話，請依據其語音內容與以下手錶當前實時狀態快照完成任務：

$sensorContext

請完成以下任務：
1. 完整精確辨識使用者說的話 (transcript)。
2. 判斷使用者的意圖與對應操作代碼 (action) 及參數 (action_params)。
   支援的 action 代碼包含：
   【手錶本機硬體控制】
   - FLASHLIGHT_ON (打開手電筒 / 開啟照明)
   - FLASHLIGHT_OFF (關閉手電筒)
   - WATCH_VOLUME_UP (手錶音量調大)
   - WATCH_VOLUME_DOWN (手錶音量調小)
   - WATCH_MUTE (手錶靜音)
   - WATCH_VIBRATE (切換手錶為震動模式)
   - SET_TIMER (倒數計時，需附帶 action_params: {"minutes": 整數, "seconds": 整數})
   - CANCEL_TIMER (取消倒數計時)
   - SET_ALARM (設定手錶鬧鐘，需附帶 action_params: {"hour": 整數, "minute": 整數, "title": "名稱"})
   - GET_BATTERY_STATUS (查詢手錶電量或續航，請直接參考上方狀態快照給予精確回覆)
   - GET_HEART_RATE (查詢目前心率或心跳，請直接參考上方狀態快照的心率數值回覆)
   - GET_STEP_COUNT (查詢今日步數或運動進度，請直接參考上方狀態快照的步數回覆)
   - INTRODUCE_CAPABILITIES (當詢問你能做什麼/有什麼功能時，請熱情精簡地介紹手電筒、心跳/步數/電量、計時器、鬧鐘與電腦遙控)
   
   【Windows 電腦遠端遙控 (若連線中)】
   - MUTE_TOGGLE (電腦靜音 / 取消靜音)
   - VOLUME_UP (電腦音量加大)
   - VOLUME_DOWN (電腦音量降低)
   - PLAY_PAUSE (電腦播放 / 暫停音樂或影片)
   - NEXT_TRACK (下一首 / 簡報下一頁)
   - PREV_TRACK (上一首 / 簡報上一頁)
   - LOCK_PC (鎖定電腦)
   - SHOW_DESKTOP (顯示電腦桌面)
   - OPEN_NOTEPAD (打開記事本)
   - OPEN_CALC (打開計算機)
   
   - NONE (一般常識問答、天氣、算術、閒聊，不需要硬體操作)

3. 給予繁體中文回覆 (reply)。
   - 語氣自然、親切、口語化，適合手錶小螢幕閱讀與手錶揚聲器朗讀（繁體中文，約 20~45 個字，重點清晰）。
   - 若為查詢心率、步數、電量，請直接引用上方實時狀態快照中的最新數值回答。
   - 若為電腦指令且手錶未連線電腦，回覆如：「已收到指令，但目前未連線電腦喔」。

請務必嚴格輸出符合以下結構的 JSON：
{
  "transcript": "使用者說的原始文字",
  "action": "ACTION_CODE",
  "action_params": {},
  "reply": "繁體中文回覆"
}
""".trimIndent()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
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

    fun isAiTaskActive(): Boolean {
        return AudioRecorderManager.isAnyRecording ||
               com.wristhub.launcher.audio.WakeAssistantManager.isRecordingOrProcessing() ||
               _isProcessing.value ||
               WatchTtsManager.isCurrentlySpeaking
    }

    fun uploadAudio(
        context: Context,
        audioFile: File,
        onSuccess: (AiConversation) -> Unit,
        onError: (String) -> Unit
    ) {
        if (_isProcessing.value) return
        _isProcessing.value = true

        scope.launch {
            val isPcOnline = PcWebSocketManager.isConnected.value

            if (isPcOnline) {
                Log.d(TAG, "PC 在線，優先走電腦端處理...")
                val pcSuccess = uploadAudioToPc(audioFile, onSuccess)
                if (!pcSuccess) {
                    Log.w(TAG, "電腦端處理失敗，自動無縫降級至 HTTPS 直連 Gemini...")
                    callDirectGeminiApi(context, audioFile, onSuccess, onError)
                }
            } else {
                Log.d(TAG, "電腦離線（隨身/外出模式），直接走 HTTPS 連線 Google Gemini...")
                callDirectGeminiApi(context, audioFile, onSuccess, onError)
            }
        }
    }

    private suspend fun uploadAudioToPc(
        audioFile: File,
        onSuccess: (AiConversation) -> Unit
    ): Boolean {
        return try {
            val ip = PcWebSocketManager.currentPcIp
            val url = "http://$ip:8765/api/ai/voice"
            Log.d(TAG, "Uploading audio to PC: $url (${audioFile.length()} bytes)")

            val mime = if (audioFile.name.endsWith(".wav", ignoreCase = true)) "audio/wav" else "audio/mp4"
            val fileBody = audioFile.asRequestBody(mime.toMediaType())
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
                if (json.optString("status") == "ERROR") {
                    return false
                }
                val transcript = json.optString("transcript", "語音指令")
                val reply = json.optString("reply", "處理完成")
                val action = json.optString("action", "NONE")
                val actionResult = if (json.has("action_result") && !json.isNull("action_result")) json.getString("action_result") else null

                val actionParams = mutableMapOf<String, Any>()
                val paramsObj = json.optJSONObject("action_params")
                if (paramsObj != null) {
                    val it = paramsObj.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        actionParams[k] = paramsObj.get(k)
                    }
                }

                // 立即調用手錶本機硬體與系統控制器
                withContext(Dispatchers.Main) {
                    WatchHardwareManager.executeAction(action, actionParams)
                }

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
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "PC voice upload failed: ${e.message}")
            false
        }
    }

    private suspend fun callDirectGeminiApi(
        context: Context,
        audioFile: File,
        onSuccess: (AiConversation) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val apiKey = PcWebSocketManager.getStoredApiKey(context).trim()
            val model = PcWebSocketManager.getStoredModel(context).trim().ifEmpty { "gemini-3.5-flash-lite" }

            if (apiKey.isEmpty()) {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                    onError("尚未同步金鑰！請先連線電腦一次以獲取金鑰")
                }
                return
            }

            if (!audioFile.exists() || audioFile.length() == 0L) {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                    onError("錄音檔為空，請再試一次")
                }
                return
            }

            val audioBytes = audioFile.readBytes()
            val b64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            val mimeType = if (audioFile.name.endsWith(".wav", ignoreCase = true)) "audio/wav" else "audio/mp4"

            Log.d(TAG, "Direct Gemini: sending ${audioBytes.size} bytes (mime: $mimeType, b64: ${b64Audio.length}) to $model...")

            val payloadJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", mimeType)
                                    put("data", b64Audio)
                                })
                            })
                            put(JSONObject().apply {
                                put("text", getSystemInstruction())
                            })
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.2)
                    put("response_mime_type", "application/json")
                })
            }

            var activeModel = model
            var url = "https://generativelanguage.googleapis.com/v1beta/models/$activeModel:generateContent?key=$apiKey"

            var request = Request.Builder()
                .url(url)
                .post(payloadJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            var response = httpClient.newCall(request).execute()

            // Auto-fallback if model is overloaded (503) or retired (404)
            if ((response.code == 404 || response.code == 503) && activeModel != "gemini-3.5-flash-lite") {
                Log.w(TAG, "Model $activeModel returned ${response.code}, falling back to gemini-3.5-flash-lite...")
                activeModel = "gemini-3.5-flash-lite"
                url = "https://generativelanguage.googleapis.com/v1beta/models/$activeModel:generateContent?key=$apiKey"
                request = Request.Builder()
                    .url(url)
                    .post(payloadJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                response = httpClient.newCall(request).execute()
            }

            val resBody = response.body?.string() ?: ""

            if (response.isSuccessful && resBody.isNotBlank()) {
                val resJson = JSONObject(resBody)
                val candidates = resJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val rawText = parts.getJSONObject(0).optString("text", "{}")
                        var cleanText = rawText.trim()
                        if (cleanText.startsWith("```json")) cleanText = cleanText.removePrefix("```json")
                        if (cleanText.startsWith("```")) cleanText = cleanText.removePrefix("```")
                        if (cleanText.endsWith("```")) cleanText = cleanText.removeSuffix("```")
                        cleanText = cleanText.trim()

                        var transcript = "語音提問"
                        var reply = "抱歉，目前無法理解這段內容。"
                        var action = "NONE"
                        val actionParams = mutableMapOf<String, Any>()

                        try {
                            val parsed = JSONObject(cleanText)
                            transcript = parsed.optString("transcript", "語音提問")
                            reply = parsed.optString("reply", "處理完成")
                            action = parsed.optString("action", "NONE")

                            val paramsObj = parsed.optJSONObject("action_params")
                            if (paramsObj != null) {
                                val it = paramsObj.keys()
                                while (it.hasNext()) {
                                    val k = it.next()
                                    actionParams[k] = paramsObj.get(k)
                                }
                            }
                        } catch (_: Exception) {
                            reply = cleanText.ifEmpty { reply }
                        }

                        // 立即調用手錶本機硬體與系統控制器
                        withContext(Dispatchers.Main) {
                            WatchHardwareManager.executeAction(action, actionParams)
                        }

                        val actionResult = if (action != "NONE") "（本機執行完成）" else null

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
                        return
                    }
                }
            }

            withContext(Dispatchers.Main) {
                _isProcessing.value = false
                val errMsg = when (response.code) {
                    400 -> "API 金鑰格式無效，請至控制台重新確認"
                    403 -> "請求受限或配額已滿"
                    503 -> "Google 伺服器忙碌中，請稍後再試"
                    else -> "連線異常 (${response.code})"
                }
                onError(errMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct Gemini error: ${e.message}", e)
            withContext(Dispatchers.Main) {
                _isProcessing.value = false
                onError("連線失敗: 請確認手機藍牙網路或手錶 Wi-Fi 連線")
            }
        } finally {
            try {
                audioFile.delete()
            } catch (_: Exception) {}
        }
    }

    fun processOfflineText(
        text: String,
        onSuccess: (AiConversation) -> Unit
    ): Boolean {
        val matched = OfflineIntentMatcher.match(text) ?: return false
        WatchHardwareManager.executeAction(matched.action)
        _conversations.value = listOf(matched) + _conversations.value
        _latestReply.value = matched
        _isProcessing.value = false
        onSuccess(matched)
        return true
    }

    fun clearHistory() {
        _conversations.value = emptyList()
        _latestReply.value = null
    }
}
