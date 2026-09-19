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
                val icon = try {
                    resolveInfo.loadIcon(pm)
                } catch (_: Exception) {
                    null
                }
                val iconBitmap = try {
                    icon?.let { d ->
                        if (d is android.graphics.drawable.BitmapDrawable && d.bitmap != null) {
                            d.bitmap
                        } else {
                            val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 64
                            val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 64
                            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bmp)
                            d.setBounds(0, 0, w, h)
                            d.draw(canvas)
                            bmp
                        }
                    }
                } catch (_: Exception) {
                    null
                }
                val isSystem = (resolveInfo.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                items.add(
                    AppItem(
                        packageName = pkgName,
                        activityName = resolveInfo.activityInfo.name,
                        label = label,
                        icon = icon,
                        iconBitmap = iconBitmap,
                        isSystemApp = isSystem
                    )
                )
            }

            // 依 A~Z 字母/中文拼音排序
            val sorted = items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            _installedApps.value = sorted

            // 載入最近常用推薦 App (Top 3)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedRecentList = prefs.getString(KEY_RECENT_PACKAGES, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

            val recentMapped = savedRecentList.mapNotNull { pkg ->
                sorted.find { it.packageName == pkg }
            }.take(3)

            // 若常用不足 3 個，以系統前置 App 或默認常用遞補展示
            _recentApps.value = if (recentMapped.isNotEmpty()) recentMapped else sorted.take(3)
            _isLoading.value = false
            Log.d(TAG, "Refreshed installed apps: ${sorted.size} apps found (self excluded).")
        }
    }

    fun launchApp(context: Context, appItem: AppItem) {
        try {
            // 1. 觸發微震動回饋
            WatchHardwareManager.vibratePattern(longArrayOf(0, 30))

            // 2. 更新最近常用持久化記錄
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentRecents = prefs.getString(KEY_RECENT_PACKAGES, "")?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
            currentRecents.remove(appItem.packageName)
            currentRecents.add(0, appItem.packageName)
            val topSaved = currentRecents.take(5)
            prefs.edit().putString(KEY_RECENT_PACKAGES, topSaved.joinToString(",")).apply()

            // 立即更新 UI 常用推薦
            _recentApps.value = topSaved.mapNotNull { pkg ->
                _installedApps.value.find { it.packageName == pkg }
            }.take(3)

            // 4. 以獨立任務棧（NEW_TASK）啟動第三方 App
            val launchIntent = context.packageManager.getLaunchIntentForPackage(appItem.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(launchIntent)
                Log.d(TAG, "Launched app: ${appItem.label} (${appItem.packageName})")
            } else {
                Log.w(TAG, "No launch intent found for ${appItem.packageName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ${appItem.label}: ${e.message}", e)
        }
    }
}
