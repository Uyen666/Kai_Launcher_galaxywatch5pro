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
        val isPcOnline = PcWebSocketManager.isConnected.value
        val pcStatusText = if (isPcOnline) "已連線 (可正常遠端遙控電腦)" else "未連線 / 離線 (手錶未連接電腦，無法執行電腦指令)"

        return """
你是一個專為 Samsung Galaxy Watch 5 Pro 設計的手腕 Siri / Intelligence 語音助理 (WristHub Assistant)。
使用者對手錶說了一段話，請依據其語音內容與以下手錶當前實時狀態快照完成任務：

$sensorContext
[當前系統連線狀態]
- 電腦連線狀態: $pcStatusText

請完成以下任務：
1. 完整精確辨識使用者說的話 (transcript)。
2. 判斷使用者的意圖與對應操作代碼 (action) 及參數 (action_params)。
   支援的 action 代碼包含：
   【手錶本機硬體控制】
   - FLASHLIGHT_ON (打開手電筒 / 開啟照明)
   - FLASHLIGHT_OFF (關閉手電筒)
   - WATCH_VOLUME_UP (手錶音量調大)
   - WATCH_VOLUME_DOWN (手錶音量調小)
   - WATCH_VOLUME_MAX (手錶音量開到最大 / 一鍵拉滿)
   - WATCH_VOLUME_SET (精確設定手錶音量百分比，需附帶 action_params: {"percent": 0~100 的整數})
   - WATCH_MUTE (手錶靜音)
   - WATCH_VIBRATE (切換手錶為震動模式)
   - SET_TIMER (倒數計時，需附帶 action_params: {"minutes": 整數, "seconds": 整數})
   - CANCEL_TIMER (取消倒數計時)
   - SET_ALARM (設定手錶鬧鐘，需附帶 action_params: {"hour": 整數, "minute": 整數, "title": "名稱"})
   - GET_BATTERY_STATUS (查詢手錶電量或續航，請直接參考上方狀態快照給予精確回覆)
   - GET_HEART_RATE (查詢目前心率或心跳，請直接參考上方狀態快照的心率數值回覆)
   - GET_STEP_COUNT (查詢今日步數或運動進度，請直接參考上方狀態快照的步數回覆)
   - OPEN_APP (開啟/打開手錶內已安裝的應用程式，例如「打開 Spotify」、「開啟設定」、「打開三星健康」、「打開地圖」等，需附帶 action_params: {"app_name": "App名稱"})
   - INTRODUCE_CAPABILITIES (當詢問你能做什麼/有什麼功能時，請熱情精簡地介紹手電筒、心跳/步數/電量、計時器、鬧鐘、開啟手錶App與電腦打字遙控)
   
   【Windows 電腦遠端遙控 (僅在電腦連線狀態為「已連線」時可用)】
   - TYPE_TEXT (在電腦當前游標處打字/輸入文字。當使用者要求「打字」、「輸入」、「在電腦打...」或要求文字鍵入時使用，需附帶 action_params: {"text": "要打在電腦上的純文字內容"})
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
   
   - NONE (一般常識問答、天氣、算術、閒聊，或因電腦離線而無法執行的電腦指令)

3. 回覆規則與約束：
   【音量精確控制規範】
   - 若使用者要求「開到最大」、「音量拉滿」、「最大聲」，請回傳 WATCH_VOLUME_MAX（或 WATCH_VOLUME_SET 附帶 action_params: {"percent": 100}），並給予如「已將手錶音量開到最大！」之親切回覆。
   - 若使用者指定特定音量百分比（如「音量調到 80%」、「聲音設為 50%」），請回傳 WATCH_VOLUME_SET 並附帶 action_params: {"percent": 數值}。
   - 若使用者僅說「靜音」或「調大音量」而未特別指明電腦，則一律判定為「手錶本機」控制（WATCH_MUTE 或 WATCH_VOLUME_UP）。

   【應用程式開啟規範】
   - 若為開啟手錶 App (OPEN_APP)，reply 請回答例如：「正在為您開啟「App名稱」...」。

   【電腦打字輸入規範】
   - 若為在電腦打字 (TYPE_TEXT)，reply 請回答例如：「已為您在電腦輸入「打字內容」！」。

   【電腦離線守則 (極重要)】
   - 若「電腦連線狀態」為「未連線 / 離線」，且使用者要求操作電腦（如「電腦打字」、「電腦靜音」、「電腦大聲點」、「電腦暫停」、「鎖定電腦」等）：
     * action 必須設為 "NONE"（絕不能回傳電腦操作代碼）
     * reply 必須清楚告知手錶未連線電腦，例如：「目前手錶未連線到電腦喔，無法執行電腦操作！」（嚴禁回答已調整完成）
   
   【雜音與非語音過濾守則 (極重要)】
   - 若音訊僅為環境噪音、衣服摩擦、抓頭髮聲、麥克風刮擦、碰撞聲、咳嗽、呼吸聲或無清晰語音指令：
     * 切勿猜測或強行腦補指令（嚴禁將摩擦雜音誤判為開手電筒、開應用程式或打字）！
     * transcript 請固定填寫 ""（空字串）
     * action 請固定填寫 "NONE"
     * action_params 請固定填寫 {}
     * reply 請固定填寫 ""（空字串）
    
   【回覆語氣】
   - 給予繁體中文回覆 (reply)。
   - 語氣自然、親切、口語化，適合手錶小螢幕閱讀與手錶揚聲器朗讀（繁體中文，約 20~45 個字，重點清晰）。
   - 若為查詢心率、步數、電量，請直接引用上方實時狀態快照中的最新數值回答。

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
        isDictation: Boolean = false,
        onSuccess: (AiConversation) -> Unit,
        onError: (String) -> Unit
    ) {
        if (_isProcessing.value) return
        _isProcessing.value = true

        scope.launch {
            val isPcOnline = PcWebSocketManager.isConnected.value

            if (isPcOnline) {
                Log.d(TAG, "PC 在線，優先走電腦端處理 (isDictation=$isDictation)...")
                val pcSuccess = uploadAudioToPc(audioFile, isDictation, onSuccess)
                if (!pcSuccess) {
                    Log.w(TAG, "電腦端處理失敗，自動無縫降級至 HTTPS 直連 Gemini...")
                    callDirectGeminiApi(context, audioFile, isDictation, onSuccess, onError)
                }
            } else {
                Log.d(TAG, "電腦離線（隨身/外出模式），直接走 HTTPS 連線 Google Gemini...")
                callDirectGeminiApi(context, audioFile, isDictation, onSuccess, onError)
            }
        }
    }

    private suspend fun uploadAudioToPc(
        audioFile: File,
        isDictation: Boolean,
        onSuccess: (AiConversation) -> Unit
    ): Boolean {
        return try {
            val ip = PcWebSocketManager.currentPcIp
            val url = "http://$ip:8765/api/ai/voice" + if (isDictation) "?dictation=true" else ""
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
                val transcript = json.optString("transcript", "").trim()
                val reply = json.optString("reply", "").trim()
                val action = json.optString("action", "NONE").trim()
                val actionResult = if (json.has("action_result") && !json.isNull("action_result")) json.getString("action_result") else null

                // 雜音過濾防禦：若為空字串或雜音且動作為 NONE，靜默結束
                if (transcript.isEmpty() || transcript == "(雜音)") {
                    if (action == "NONE") {
                        withContext(Dispatchers.Main) {
                            _isProcessing.value = false
                            onSuccess(AiConversation(userText = "", aiReply = "", action = "NONE"))
                        }
                        return true
                    }
                }

                val actionParams = mutableMapOf<String, Any>()
                val paramsObj = json.optJSONObject("action_params")
                if (paramsObj != null) {
                    val it = paramsObj.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        actionParams[k] = paramsObj.get(k)
                    }
                }

                // 僅在有具體操作時調用硬體控制器
                if (action != "NONE") {
                    withContext(Dispatchers.Main) {
                        WatchHardwareManager.executeAction(action, actionParams)
                    }
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

    private val DICTATION_SYSTEM_INSTRUCTION = """
你是一個專為智慧手錶設計的極速語音輸入聽寫引擎 (WristHub Voice Dictation)。
使用者說了一段要直接打入電腦游標處的文字。
請完成以下任務：
1. 完整精確辨識使用者說的話 (transcript)，並加上適當的標點符號。
2. 固定將 action 設為 "TYPE_TEXT"，並將 action_params 設為 {"text": transcript}。
3. reply 固定回答 "已在電腦輸入文字！"。

請務必嚴格輸出符合以下結構的 JSON：
{
  "transcript": "辨識後的文字",
  "action": "TYPE_TEXT",
  "action_params": {
    "text": "辨識後的文字"
  },
  "reply": "已在電腦輸入文字！"
}
""".trimIndent()

    private suspend fun callDirectGeminiApi(
        context: Context,
        audioFile: File,
        isDictation: Boolean = false,
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

            Log.d(TAG, "Direct Gemini: sending ${audioBytes.size} bytes (mime: $mimeType, b64: ${b64Audio.length}, isDictation=$isDictation) to $model...")

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
                                put("text", if (isDictation) DICTATION_SYSTEM_INSTRUCTION else getSystemInstruction())
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

                        var transcript = ""
                        var reply = ""
                        var action = "NONE"
                        val actionParams = mutableMapOf<String, Any>()

                        try {
                            val parsed = JSONObject(cleanText)
                            transcript = parsed.optString("transcript", "").trim()
                            reply = parsed.optString("reply", "").trim()
                            action = parsed.optString("action", "NONE").trim()

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

                        // 雜音過濾防禦：若識別文字為空或標示為雜音，且無具體操作，則安靜結束
                        if (transcript.isEmpty() || transcript == "(雜音)") {
                            if (action == "NONE") {
                                Log.d(TAG, "Direct Gemini: audio identified as noise/empty, quietly finishing.")
                                withContext(Dispatchers.Main) {
                                    _isProcessing.value = false
                                    onSuccess(AiConversation(userText = "", aiReply = "", action = "NONE"))
                                }
                                return
                            }
                        }

                        // 雙重防禦：若為電腦指令但手錶處於離線狀態，攔截錯誤宣告
                        val isPcAction = action in listOf(
                            "MUTE_TOGGLE", "VOLUME_UP", "VOLUME_DOWN", "PLAY_PAUSE",
                            "NEXT_TRACK", "PREV_TRACK", "LOCK_PC", "SHOW_DESKTOP",
                            "OPEN_NOTEPAD", "OPEN_CALC", "TYPE_TEXT"
                        )
                        if (isPcAction && !PcWebSocketManager.isConnected.value) {
                            Log.w(TAG, "Gemini returned PC action $action while PC is offline! Overriding...")
                            action = "NONE"
                            if (!reply.contains("未連線") && !reply.contains("離線")) {
                                reply = "目前手錶尚未連線到電腦喔，無法執行打字或電腦操作！"
                            }
                        } else if (action == "TYPE_TEXT" && PcWebSocketManager.isConnected.value) {
                            val textToType = (actionParams["text"] as? String)?.trim() ?: transcript
                            PcWebSocketManager.sendCommand("TYPE_TEXT", mapOf("text" to textToType))
                        }

                        // 立即調用手錶本機硬體與系統控制器（僅在有具體操作時執行）
                        if (action != "NONE") {
                            try {
                                withContext(Dispatchers.Main) {
                                    WatchHardwareManager.executeAction(action, actionParams)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Action execution error: ${e.message}", e)
                            }
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
        val params: Map<String, Any>? = when (matched.action) {
            "WATCH_VOLUME_SET" -> {
                val p = matched.actionResult?.toIntOrNull() ?: 100
                mapOf("percent" to p)
            }
            "OPEN_APP" -> {
                val app = matched.actionResult ?: ""
                mapOf("app_name" to app)
            }
            "TYPE_TEXT" -> {
                val t = matched.actionResult ?: ""
                if (PcWebSocketManager.isConnected.value) {
                    PcWebSocketManager.sendCommand("TYPE_TEXT", mapOf("text" to t))
                }
                mapOf("text" to t)
            }
            else -> null
        }
        WatchHardwareManager.executeAction(matched.action, params)
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
