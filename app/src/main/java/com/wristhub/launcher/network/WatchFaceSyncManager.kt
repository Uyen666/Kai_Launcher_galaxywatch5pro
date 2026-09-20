package com.wristhub.launcher.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.wristhub.launcher.data.WatchFaceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object WatchFaceSyncManager {
    private const val TAG = "WatchFaceSync"
    private const val CONFIG_FILE_NAME = "watchface_config.json"
    private const val BG_FILE_NAME = "custom_bg.webp"

    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _config = MutableStateFlow(WatchFaceConfig())
    val config: StateFlow<WatchFaceConfig> = _config.asStateFlow()

    private val _cachedBgBitmap = MutableStateFlow<Bitmap?>(null)
    val cachedBgBitmap: StateFlow<Bitmap?> = _cachedBgBitmap.asStateFlow()

    private val _bgVersion = MutableStateFlow(System.currentTimeMillis())
    val bgVersion: StateFlow<Long> = _bgVersion.asStateFlow()

    fun init(context: Context) {
        scope.launch {
            loadLocalConfig(context)
            loadLocalBitmap(context)
        }
    }

    private fun loadLocalConfig(context: Context) {
        val file = File(context.filesDir, CONFIG_FILE_NAME)
        if (file.exists()) {
            try {
                val jsonStr = file.readText()
                val json = JSONObject(jsonStr)
                _config.value = WatchFaceConfig.fromJson(json)
                Log.d(TAG, "Loaded local watch face config: ${_config.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load local config: ${e.message}")
            }
        }
    }

    private fun loadLocalBitmap(context: Context) {
        val file = File(context.filesDir, BG_FILE_NAME)
        if (file.exists()) {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                _cachedBgBitmap.value = bitmap
                Log.d(TAG, "Preloaded cached background bitmap: ${bitmap?.width}x${bitmap?.height}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode cached bitmap: ${e.message}")
            }
        }
    }

    fun saveConfig(context: Context, newConfig: WatchFaceConfig) {
        _config.value = newConfig
        scope.launch {
            try {
                val file = File(context.filesDir, CONFIG_FILE_NAME)
                file.writeText(newConfig.toJson().toString(2))
                Log.d(TAG, "Saved watch face config locally")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save config: ${e.message}")
            }
        }
    }

    fun downloadBackground(context: Context, url: String) {
        scope.launch {
            Log.d(TAG, "Starting background download from: $url")
            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val outFile = File(context.filesDir, BG_FILE_NAME)
                            FileOutputStream(outFile).use { fos ->
                                fos.write(bytes)
                            }
                            // Decode immediately into in-memory bitmap for 0ms transitions
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            _cachedBgBitmap.value = bitmap
                            _bgVersion.value = System.currentTimeMillis()
                            Log.d(TAG, "Successfully saved and cached custom background (${bytes.size} bytes)")
                            
                            // Update config to hasCustomBg = true
                            val updated = _config.value.copy(hasCustomBg = true)
                            saveConfig(context, updated)
                            
                            // Report back to PC
                            PcWebSocketManager.sendCommand("BG_DOWNLOAD_SUCCESS")
                        }
                    } else {
                        Log.w(TAG, "Download failed with code: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading background: ${e.message}")
            }
        }
    }
}
