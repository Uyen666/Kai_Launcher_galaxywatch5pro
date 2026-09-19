package com.wristhub.launcher.data

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

/**
 * 手錶應用程式資料模型
 */
data class AppItem(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable? = null,
    val iconBitmap: Bitmap? = null,
    val isSystemApp: Boolean = false,
    val lastUsedTime: Long = 0L
)
