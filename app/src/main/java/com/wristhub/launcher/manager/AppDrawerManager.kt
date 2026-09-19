package com.wristhub.launcher.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import com.wristhub.launcher.data.AppItem
import com.wristhub.launcher.hardware.WatchHardwareManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 手錶 App Drawer 應用程式管理器
 * 自動掃描手錶已安裝的應用程式、過濾排除自身 Launcher、
 * 動態監聽 Google Play 商店安裝與卸載、維護最近常用 App、並以獨立任務棧啟動
 */
object AppDrawerManager {
    private const val TAG = "AppDrawer"
    private const val PREFS_NAME = "wristhub_app_drawer"
    private const val KEY_RECENT_PACKAGES = "recent_packages"

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _installedApps = MutableStateFlow<List<AppItem>>(emptyList())
    val installedApps: StateFlow<List<AppItem>> = _installedApps.asStateFlow()

    private val _recentApps = MutableStateFlow<List<AppItem>>(emptyList())
    val recentApps: StateFlow<List<AppItem>> = _recentApps.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isDrawerOpen = MutableStateFlow(false)
    val isDrawerOpen: StateFlow<Boolean> = _isDrawerOpen.asStateFlow()

    @Volatile
    var isLaunchingApp = false

    fun toggleDrawer() {
        _isDrawerOpen.value = !_isDrawerOpen.value
    }

    fun setDrawerOpen(open: Boolean) {
        _isDrawerOpen.value = open
    }

    private var isReceiverRegistered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Package event received: ${intent?.action} for ${intent?.data}")
            context?.let { refreshApps(it) }
        }
    }

    fun init(context: Context) {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            try {
                context.applicationContext.registerReceiver(packageReceiver, filter)
                isReceiverRegistered = true
                Log.d(TAG, "Package change receiver registered.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register package receiver: ${e.message}")
            }
        }
        refreshApps(context)
    }

    // 圖標動態記憶體快取 (LruCache)，上限為可用 VM Heap 的 1/8（最高 12MB）
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = (maxMemoryKb / 8).coerceIn(2048, 12288)
    private val iconLruCache = object : android.util.LruCache<String, android.graphics.Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, bitmap: android.graphics.Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    // 手錶圓形螢幕最佳渲染圖標尺寸（96x96 px，單張約 36.8KB，百款 App 佔用 < 4MB）
    private const val TARGET_ICON_SIZE_PX = 96

    fun refreshApps(context: Context) {
        scope.launch {
            _isLoading.value = true
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val rawList = pm.queryIntentActivities(launcherIntent, 0)
            val myPackage = context.packageName

            val items = mutableListOf<AppItem>()
            for (resolveInfo in rawList) {
                val pkgName = resolveInfo.activityInfo.packageName
                // 絕對過濾排除自身 Launcher 應用
                if (pkgName == myPackage) continue

                val label = resolveInfo.loadLabel(pm).toString()
                
                // 檢查 LruCache 是否已有縮放快取
                var iconBitmap = iconLruCache.get(pkgName)
                if (iconBitmap == null || iconBitmap.isRecycled) {
                    val rawIcon = try {
                        resolveInfo.loadIcon(pm)
                    } catch (_: Exception) {
                        null
                    }

                    iconBitmap = try {
                        rawIcon?.let { d ->
                            if (d is android.graphics.drawable.BitmapDrawable && d.bitmap != null) {
                                val src = d.bitmap
                                if (src.width == TARGET_ICON_SIZE_PX && src.height == TARGET_ICON_SIZE_PX) {
                                    src
                                } else {
                                    android.graphics.Bitmap.createScaledBitmap(src, TARGET_ICON_SIZE_PX, TARGET_ICON_SIZE_PX, true)
                                }
                            } else {
                                val bmp = android.graphics.Bitmap.createBitmap(
                                    TARGET_ICON_SIZE_PX,
                                    TARGET_ICON_SIZE_PX,
                                    android.graphics.Bitmap.Config.ARGB_8888
                                )
                                val canvas = android.graphics.Canvas(bmp)
                                d.setBounds(0, 0, TARGET_ICON_SIZE_PX, TARGET_ICON_SIZE_PX)
                                d.draw(canvas)
                                bmp
                            }
                        }
                    } catch (_: Exception) {
                        null
                    }

                    if (iconBitmap != null) {
                        iconLruCache.put(pkgName, iconBitmap)
                    }
                }

                val isSystem = (resolveInfo.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                items.add(
                    AppItem(
                        packageName = pkgName,
                        activityName = resolveInfo.activityInfo.name,
                        label = label,
                        iconBitmap = iconBitmap,
                        isSystemApp = isSystem
                    )
                )
            }

            // 依 A~Z 字母/中文拼音排序
            val sorted = items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            _installedApps.value = sorted

            // 載入最近常用推薦 App (Top 3) 並執行髒數據清洗（Orphan Package Pruning）
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedRecentList = prefs.getString(KEY_RECENT_PACKAGES, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            val validInstalledPackages = sorted.map { it.packageName }.toSet()
            val cleanedRecentList = savedRecentList.filter { validInstalledPackages.contains(it) }

            // 若有已卸載的孤立套件殘留，立即持久化清洗
            if (cleanedRecentList.size != savedRecentList.size) {
                prefs.edit().putString(KEY_RECENT_PACKAGES, cleanedRecentList.joinToString(",")).apply()
                Log.d(TAG, "Pruned orphan packages from recent list. Old count: ${savedRecentList.size}, Cleaned count: ${cleanedRecentList.size}")
            }

            val recentMapped = cleanedRecentList.mapNotNull { pkg ->
                sorted.find { it.packageName == pkg }
            }.take(3)

            // 若常用不足 3 個，以系統前置 App 或默認常用遞補展示
            _recentApps.value = if (recentMapped.isNotEmpty()) recentMapped else sorted.take(3)
            _isLoading.value = false
            Log.d(TAG, "Refreshed installed apps: ${sorted.size} apps found (self excluded). LruCache entries: ${iconLruCache.size()}")
        }
    }

    fun launchApp(context: Context, appItem: AppItem) {
        try {
            // 1. 觸發清脆微震動回饋
            WatchHardwareManager.vibratePattern(longArrayOf(0, 25))

            // 2. 獲取啟動 Intent
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(appItem.packageName)
                ?: throw android.content.ActivityNotFoundException("No launch intent found for ${appItem.packageName}")

            // 3. 以獨立任務棧（NEW_TASK）啟動第三方 App
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            isLaunchingApp = true
            context.startActivity(launchIntent)
            Log.d(TAG, "Launched app successfully: ${appItem.label} (${appItem.packageName})")

            // 4. 啟動成功後持久化寫入常用紀錄
            updateRecentPackage(context, appItem.packageName)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "Target app missing or uninstalled: ${appItem.packageName}", e)
            // 錯誤震動警報
            WatchHardwareManager.vibratePattern(longArrayOf(0, 40, 40, 40))
            // 即時除名清洗髒數據
            pruneOrphanPackage(context, appItem.packageName)
            refreshApps(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ${appItem.label}: ${e.message}", e)
        }
    }

    private fun updateRecentPackage(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentRecents = prefs.getString(KEY_RECENT_PACKAGES, "")?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        currentRecents.remove(packageName)
        currentRecents.add(0, packageName)
        val topSaved = currentRecents.take(5)
        prefs.edit().putString(KEY_RECENT_PACKAGES, topSaved.joinToString(",")).apply()

        _recentApps.value = topSaved.mapNotNull { pkg ->
            _installedApps.value.find { it.packageName == pkg }
        }.take(3)
    }

    private fun pruneOrphanPackage(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentRecents = prefs.getString(KEY_RECENT_PACKAGES, "")?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        if (currentRecents.remove(packageName)) {
            prefs.edit().putString(KEY_RECENT_PACKAGES, currentRecents.joinToString(",")).apply()
        }
        _recentApps.value = _recentApps.value.filter { it.packageName != packageName }
    }

    /**
     * 依名稱或關鍵字模糊匹配並啟動手錶 App (供 Gemini 助理調用)
     * 支援完整名稱、包含搜尋與中英常見別名 (如 Spotify、健康、設定、地圖等)
     */
    fun launchAppByName(context: Context, query: String): String? {
        val clean = query.trim().lowercase()
        if (clean.isBlank()) return null

        val apps = if (_installedApps.value.isNotEmpty()) {
            _installedApps.value
        } else {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            pm.queryIntentActivities(intent, 0).map {
                AppItem(
                    packageName = it.activityInfo.packageName,
                    activityName = it.activityInfo.name,
                    label = it.loadLabel(pm).toString()
                )
            }.filter { it.packageName != context.packageName }
        }

        // 1. 完全一致匹配
        var target = apps.find { it.label.trim().lowercase() == clean }

        // 2. 包含匹配 (例如「健康」匹配「Samsung Health」或「三星健康」)
        if (target == null) {
            target = apps.find { 
                val labelLower = it.label.lowercase()
                labelLower.contains(clean) || clean.contains(labelLower) 
            }
        }

        // 3. 常用別名與包名對照
        if (target == null) {
            val aliasMap = mapOf(
                "設定" to listOf("settings", "設定"),
                "音樂" to listOf("spotify", "music", "yt music", "youtube music"),
                "spotify" to listOf("spotify"),
                "健康" to listOf("health", "samsung health", "運動", "健身"),
                "地圖" to listOf("maps", "google 地圖", "導航"),
                "天氣" to listOf("weather", "天氣"),
                "時鐘" to listOf("clock", "alarm", "時鐘", "鬧鐘"),
                "鬧鐘" to listOf("clock", "alarm", "時鐘", "鬧鐘"),
                "計算機" to listOf("calculator", "計算機", "計算"),
                "錄音機" to listOf("voice recorder", "錄音", "錄音機"),
                "play 商店" to listOf("vending", "play", "商店", "google play"),
                "商店" to listOf("vending", "play", "商店", "google play"),
                "訊息" to listOf("messaging", "message", "簡訊", "訊息"),
                "電話" to listOf("dialer", "phone", "電話", "通話"),
                "聯絡人" to listOf("contacts", "電話簿", "聯絡人"),
                "指南針" to listOf("compass", "指南針", "羅盤"),
                "相簿" to listOf("gallery", "相簿", "照片")
            )

            for ((aliasKey, aliases) in aliasMap) {
                if (clean.contains(aliasKey) || aliases.any { clean.contains(it) }) {
                    target = apps.find { app ->
                        val appLower = app.label.lowercase()
                        val pkgLower = app.packageName.lowercase()
                        aliases.any { appLower.contains(it) || pkgLower.contains(it) }
                    }
                    if (target != null) break
                }
            }
        }

        if (target != null) {
            launchApp(context, target)
            return target.label
        }
        return null
    }
}
