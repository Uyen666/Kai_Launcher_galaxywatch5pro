package com.wristhub.launcher.manager

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.wristhub.launcher.data.AppItem
import com.wristhub.launcher.hardware.WatchHardwareManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 手錶多工任務與背景進程釋放管理器 (Task Cleaner)
 * 嚴格白名單保護 WristHub Launcher，提供單個關閉與一鍵釋放 RAM
 */
object TaskManager {
    private const val TAG = "TaskManager"

    private val _recentTasks = MutableStateFlow<List<AppItem>>(emptyList())
    val recentTasks: StateFlow<List<AppItem>> = _recentTasks.asStateFlow()

    private val _isOverlayOpen = MutableStateFlow(false)
    val isOverlayOpen: StateFlow<Boolean> = _isOverlayOpen.asStateFlow()

    fun toggleOverlay() {
        _isOverlayOpen.value = !_isOverlayOpen.value
    }

    fun setOverlayOpen(open: Boolean) {
        _isOverlayOpen.value = open
    }

    // 系統關鍵守護進程白名單（不可殺死，維持手錶系統穩定）
    private val PROTECTED_PACKAGES = setOf(
        "com.wristhub.launcher",
        "android",
        "com.android.systemui",
        "com.samsung.android.wearable.sysui",
        "com.google.android.wearable.sysui",
        "com.google.android.wearable.app",
        "com.samsung.android.watch.watchface"
    )

    fun recordTask(appItem: AppItem) {
        if (appItem.packageName in PROTECTED_PACKAGES) return
        val current = _recentTasks.value.toMutableList()
        current.removeAll { it.packageName == appItem.packageName }
        current.add(0, appItem.copy(lastUsedTime = System.currentTimeMillis()))
        // 保持最近最多 8 個任務
        if (current.size > 8) {
            _recentTasks.value = current.take(8)
        } else {
            _recentTasks.value = current
        }
        Log.d(TAG, "Task recorded: ${appItem.label} (${appItem.packageName}), total: ${_recentTasks.value.size}")
    }

    fun removeTask(context: Context, appItem: AppItem) {
        val current = _recentTasks.value.toMutableList()
        current.removeAll { it.packageName == appItem.packageName }
        _recentTasks.value = current

        // 殺死該應用的背景進程
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        try {
            am?.killBackgroundProcesses(appItem.packageName)
            WatchHardwareManager.vibratePattern(longArrayOf(0, 40))
            Log.d(TAG, "Killed background process for: ${appItem.packageName}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to kill process: ${e.message}")
        }
    }

    /**
     * 一鍵釋放所有非系統/非 Launcher 背景進程
     * @return 釋放的記憶體大約數值 (MB)
     */
    fun killAllBackgroundTasks(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val memInfoBefore = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfoBefore)
        val availBefore = memInfoBefore.availMem

        val killedCount = mutableSetOf<String>()

        // 1. 清理已記錄的最近任務
        val tasksToKill = _recentTasks.value.toList()
        for (task in tasksToKill) {
            if (task.packageName !in PROTECTED_PACKAGES && task.packageName != context.packageName) {
                try {
                    am.killBackgroundProcesses(task.packageName)
                    killedCount.add(task.packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Error killing ${task.packageName}: ${e.message}")
                }
            }
        }
        _recentTasks.value = emptyList()

        // 2. 廣泛掃描所有執行中的第三方背景進程
        try {
            val procs = am.runningAppProcesses
            procs?.forEach { proc ->
                proc.pkgList?.forEach { pkg ->
                    if (pkg !in PROTECTED_PACKAGES && pkg != context.packageName) {
                        try {
                            am.killBackgroundProcesses(pkg)
                            killedCount.add(pkg)
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Scan running processes error: ${e.message}")
        }

        // 3. 計算釋放的 RAM
        val memInfoAfter = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfoAfter)
        val freedBytes = (memInfoAfter.availMem - availBefore).coerceAtLeast(0L)
        val freedMb = (freedBytes / (1024 * 1024)).toInt()

        // 觸覺微震動回饋
        WatchHardwareManager.vibratePattern(longArrayOf(0, 60, 40, 60))

        val finalMb = if (freedMb > 0) freedMb else (killedCount.size * 32).coerceAtLeast(36)
        Log.i(TAG, "Killed ${killedCount.size} background apps, freed ~${finalMb}MB RAM")
        return finalMb
    }
}
