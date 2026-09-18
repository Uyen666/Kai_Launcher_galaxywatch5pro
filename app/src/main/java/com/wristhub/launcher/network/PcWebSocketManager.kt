package com.wristhub.launcher.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Received from PC: $text")
                _lastMessage.value = text
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

    fun sendCommand(action: String, extra: String? = null) {
        val payload = JSONObject().apply {
            put("action", action)
            if (extra != null) {
                put("extra", extra)
            }
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
