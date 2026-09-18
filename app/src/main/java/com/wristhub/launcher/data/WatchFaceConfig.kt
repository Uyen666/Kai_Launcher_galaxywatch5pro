package com.wristhub.launcher.data

import org.json.JSONObject

data class WatchFaceConfig(
    val clockStyle: String = "DIGITAL", // DIGITAL, ANALOG
    val clockColorHex: String = "#00E5FF",
    val dimPercent: Int = 25,
    val showBattery: Boolean = true,
    val batteryColorMode: String = "DYNAMIC", // DYNAMIC, CUSTOM
    val batteryCustomColorHex: String = "#00E676",
    val batteryStrokeWidth: Int = 6, // dp
    val batteryInset: Int = 4, // dp
    val showDate: Boolean = true,
    val dateColorHex: String = "#94A3B8",
    val dateFontSize: Int = 11, // sp
    val dateOffsetY: Int = -52, // dp from center
    val showPcStatus: Boolean = true,
    val pcStatusColorHex: String = "#00E676",
    val pcStatusSize: Int = 7, // dp
    val pcStatusOffsetY: Int = -74, // dp from center
    val hasCustomBg: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("clock_style", clockStyle)
            put("clock_color", clockColorHex)
            put("dim_percent", dimPercent)
            put("show_battery", showBattery)
            put("battery_color_mode", batteryColorMode)
            put("battery_custom_color", batteryCustomColorHex)
            put("battery_stroke_width", batteryStrokeWidth)
            put("battery_inset", batteryInset)
            put("show_date", showDate)
            put("date_color", dateColorHex)
            put("date_font_size", dateFontSize)
            put("date_offset_y", dateOffsetY)
            put("show_pc_status", showPcStatus)
            put("pc_status_color", pcStatusColorHex)
            put("pc_status_size", pcStatusSize)
            put("pc_status_offset_y", pcStatusOffsetY)
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
                batteryColorMode = json.optString("battery_color_mode", "DYNAMIC"),
                batteryCustomColorHex = json.optString("battery_custom_color", "#00E676"),
                batteryStrokeWidth = json.optInt("battery_stroke_width", 6),
                batteryInset = json.optInt("battery_inset", 4),
                showDate = json.optBoolean("show_date", true),
                dateColorHex = json.optString("date_color", "#94A3B8"),
                dateFontSize = json.optInt("date_font_size", 11),
                dateOffsetY = json.optInt("date_offset_y", -52),
                showPcStatus = json.optBoolean("show_pc_status", true),
                pcStatusColorHex = json.optString("pc_status_color", "#00E676"),
                pcStatusSize = json.optInt("pc_status_size", 7),
                pcStatusOffsetY = json.optInt("pc_status_offset_y", -74),
                hasCustomBg = json.optBoolean("has_custom_bg", false)
            )
        }
    }
}
