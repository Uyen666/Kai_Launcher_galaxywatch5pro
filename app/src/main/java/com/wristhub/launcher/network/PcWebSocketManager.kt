package com.wristhub.launcher.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.wristhub.launcher.data.WatchFaceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
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
        .pingInterval(10, TimeUnit.SECONDS)
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

    private const val PREFS_NAME = "wristhub_net_prefs"
    private const val KEY_PC_IP = "pc_ip"
    private const val DISCOVERY_PORT = 8766
    const val DEFAULT_PC_IP = "192.168.0.102"

    var currentPcIp: String = DEFAULT_PC_IP

    private var udpDiscoveryJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentPcIp = prefs.getString(KEY_PC_IP, DEFAULT_PC_IP) ?: DEFAULT_PC_IP
        WatchFaceSyncManager.init(context.applicationContext)
        startUdpDiscovery()
    }

    fun startUdpDiscovery() {
        if (_isConnected.value || udpDiscoveryJob?.isActive == true) return
        udpDiscoveryJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                val wifiManager = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock("wristhub_udp")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }

                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1500
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }

                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                val probeData = "{\"cmd\":\"DISCOVER_WRISTHUB\"}".toByteArray()
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val probePacket = DatagramPacket(probeData, probeData.size, broadcastAddr, DISCOVERY_PORT)

                var attempts = 0
                while (!_isConnected.value && attempts < 60) {
                    attempts++
                    try {
                        socket.send(probePacket)
                    } catch (_: Exception) {}

                    try {
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        val json = JSONObject(msg)
                        if (json.optString("service") == "wristhub") {
                            val discoveredIp = json.optString("ip").trim()
                            if (discoveredIp.isNotEmpty() && discoveredIp != "127.0.0.1") {
                                Log.i(TAG, "🔍 UDP 自動發現 WristHub 電腦 IP: $discoveredIp")
                                appContext?.let { ctx ->
                                    updatePcIp(ctx, discoveredIp)
                                }
                                break
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // 正常逾時繼續等待
                    }
                    delay(1000)
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP Discovery error: ${e.message}")
            } finally {
                socket?.close()
                try {
                    if (multicastLock?.isHeld == true) multicastLock?.release()
                } catch (_: Exception) {}
            }
        }
    }

    fun stopUdpDiscovery() {
        udpDiscoveryJob?.cancel()
        udpDiscoveryJob = null
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
    }

    fun updatePcIp(context: Context, newIp: String) {
        val trimmed = newIp.trim()
        if (trimmed.isNotEmpty()) {
            currentPcIp = trimmed
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PC_IP, trimmed)
                .apply()
            Log.d(TAG, "PC IP updated to: $trimmed, reconnecting...")
            disconnect()
            connect(trimmed, forceImmediate = true)
        }
    }

    private const val INITIAL_RETRY_DELAY_MS = 3000L
    private const val MAX_RETRY_DELAY_MS = 60000L
    private var currentRetryDelayMs = INITIAL_RETRY_DELAY_MS
    private var reconnectJob: Job? = null

    fun connect(ip: String = currentPcIp, forceImmediate: Boolean = false) {
        currentPcIp = ip
        if (forceImmediate) {
            currentRetryDelayMs = INITIAL_RETRY_DELAY_MS
            reconnectJob?.cancel()
        }
        if (_isConnected.value) return

        val request = Request.Builder()
            .url("ws://$ip:$DEFAULT_PORT")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected to PC: $ip")
                _isConnected.value = true
                currentRetryDelayMs = INITIAL_RETRY_DELAY_MS
                stopUdpDiscovery()
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
                val delayMs = currentRetryDelayMs
                currentRetryDelayMs = (currentRetryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                Log.w(TAG, "WebSocket failure: ${t.message}. Retrying in ${delayMs / 1000}s (exponential backoff)...")
                _isConnected.value = false
                startUdpDiscovery()
                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    delay(delayMs)
                    connect(currentPcIp)
                }
            }
        })
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
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
