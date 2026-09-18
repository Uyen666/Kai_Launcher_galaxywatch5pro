package com.wristhub.launcher.data

import org.json.JSONObject

data class WatchFaceConfig(
    val clockStyle: String = "DIGITAL", // DIGITAL, ANALOG
    val clockColorHex: String = "#00E5FF",
    val dimPercent: Int = 25,
    val showBattery: Boolean = true,
    val showDate: Boolean = true,
    val showPcStatus: Boolean = true,
    val hasCustomBg: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("clock_style", clockStyle)
            put("clock_color", clockColorHex)
            put("dim_percent", dimPercent)
            put("show_battery", showBattery)
            put("show_date", showDate)
            put("show_pc_status", showPcStatus)
            put("has_custom_bg", hasCustomBg)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): WatchFaceConfig {
            return WatchFaceConfig(
                clockStyle = json.optString("clock_style", "DIGITAL"),
                clockColorHex = json.optString("clock_color", "#00E5FF"),
                dimPercent = json.optInt("dim_percent", 25),
                showBattery = json.optBoolean("show_battery", true),
                showDate = json.optBoolean("show_date", true),
                showPcStatus = json.optBoolean("show_pc_status", true),
                hasCustomBg = json.optBoolean("has_custom_bg", false)
            )
        }
    }
}
