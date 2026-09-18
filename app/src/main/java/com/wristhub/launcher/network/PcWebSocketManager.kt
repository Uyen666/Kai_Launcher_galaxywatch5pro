package com.wristhub.launcher.network

import android.content.Context
import android.util.Log
import com.wristhub.launcher.data.WatchFaceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RemoteButtonConfig(
    val id: String,
    val label: String,
    val icon: String,
    val colorHex: String
)

object PcWebSocketManager {
    private const val TAG = "PcWebSocket"
    private const val DEFAULT_PORT = 8765

    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(3, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var appContext: Context? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _lastMessage = MutableStateFlow("")
    val lastMessage: StateFlow<String> = _lastMessage.asStateFlow()

    private val defaultButtons = listOf(
        RemoteButtonConfig("btn_1", "音量-", "🔉", "#222933"),
        RemoteButtonConfig("btn_2", "靜音", "🔇", "#FF9100"),
        RemoteButtonConfig("btn_3", "音量+", "🔊", "#222933"),
        RemoteButtonConfig("btn_4", "上一首", "◀", "#14181D"),
        RemoteButtonConfig("btn_5", "播放", "⏯", "#00E5FF"),
        RemoteButtonConfig("btn_6", "下一首", "▶", "#14181D")
    )

    private val _buttonList = MutableStateFlow<List<RemoteButtonConfig>>(defaultButtons)
    val buttonList: StateFlow<List<RemoteButtonConfig>> = _buttonList.asStateFlow()

    var currentPcIp: String = "192.168.0.109"

    fun init(context: Context) {
        appContext = context.applicationContext
        WatchFaceSyncManager.init(context.applicationContext)
    }

    fun connect(ip: String = currentPcIp) {
        currentPcIp = ip
        if (_isConnected.value) return

        val request = Request.Builder()
            .url("ws://$ip:$DEFAULT_PORT")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected to PC: $ip")
                _isConnected.value = true
                sendCommand("GET_CONFIG")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Received from PC: $text")
                _lastMessage.value = text
                try {
                    val json = JSONObject(text)
                    val msgType = json.optString("type")
                    
                    if (msgType == "CONFIG") {
                        val array = json.optJSONArray("buttons") ?: JSONArray()
                        val list = mutableListOf<RemoteButtonConfig>()
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            list.add(
                                RemoteButtonConfig(
                                    id = obj.optString("id", "btn_$i"),
                                    label = obj.optString("label", ""),
                                    icon = obj.optString("icon", "🔘"),
                                    colorHex = obj.optString("color", "#222933")
                                )
                            )
                        }
                        if (list.isNotEmpty()) {
                            _buttonList.value = list
                        }
                    } else if (msgType == "WATCHFACE_UPDATE") {
                        val cfgObj = json.optJSONObject("config")
                        if (cfgObj != null && appContext != null) {
                            val wfConfig = WatchFaceConfig.fromJson(cfgObj)
                            WatchFaceSyncManager.saveConfig(appContext!!, wfConfig)
                        }
                        val bgUrl = json.optString("bg_url", "")
                        if (bgUrl.isNotEmpty() && appContext != null) {
                            WatchFaceSyncManager.downloadBackground(appContext!!, bgUrl)
                        }
                    } else if (msgType == "SYNC_AI_CONFIG") {
                        val key = json.optString("api_key", "").trim()
                        val model = json.optString("model", "gemini-3.5-flash-lite").trim()
                        if (appContext != null && key.isNotEmpty()) {
                            val prefs = appContext!!.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                            prefs.edit()
                                .putString("gemini_api_key", key)
                                .putString("gemini_model", model)
                                .apply()
                            Log.d(TAG, "Synced Gemini AI config to local preferences (model: $model)")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message: ${e.message}")
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                _isConnected.value = false
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}. Retrying in 3s...")
                _isConnected.value = false
                scope.launch {
                    delay(3000L)
                    connect(currentPcIp)
                }
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _isConnected.value = false
    }

    fun sendCommand(action: String, extra: Map<String, Any>? = null) {
        val payload = JSONObject().apply {
            put("action", action)
            extra?.forEach { (k, v) -> put(k, v) }
            put("timestamp", System.currentTimeMillis())
        }
        val sent = webSocket?.send(payload.toString()) ?: false
        if (!sent) {
            Log.w(TAG, "Command failed to send, reconnecting...")
            _isConnected.value = false
            connect(currentPcIp)
        }
    }

    fun getStoredApiKey(context: Context): String {
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        return prefs.getString("gemini_api_key", "") ?: ""
    }

    fun getStoredModel(context: Context): String {
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        return prefs.getString("gemini_model", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"
    }
}
