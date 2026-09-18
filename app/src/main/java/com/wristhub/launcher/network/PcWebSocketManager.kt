package com.wristhub.launcher.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val client = OkHttpClient.Builder()
        .readTimeout(3, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _lastMessage = MutableStateFlow("")
    val lastMessage: StateFlow<String> = _lastMessage.asStateFlow()

    // Default 6 buttons if offline
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
                // Ask for latest config
                sendCommand("GET_CONFIG")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Received from PC: $text")
                _lastMessage.value = text
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "CONFIG") {
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
                Log.w(TAG, "WebSocket failure: ${t.message}")
                _isConnected.value = false
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
}
