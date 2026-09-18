package com.wristhub.launcher.data

import org.json.JSONObject

data class WatchFaceConfig(
    val clockStyle: String = "DIGITAL", // DIGITAL, ANALOG
    val clockColorHex: String = "#00E5FF",
    val dimPercent: Int = 25,
    
    // Analog Hands & 3D Layered Shadows
    val hourHandColorHex: String = "#FFFFFF",
    val minuteHandColorHex: String = "#00E5FF",
    val secondHandColorHex: String = "#00E676",
    val enableHandShadows: Boolean = true,
    val shadowDepthLevel: Int = 3, // 1 to 5

    // Battery Arc Ring
    val showBattery: Boolean = true,
    val batteryColorMode: String = "DYNAMIC", // DYNAMIC, CUSTOM
    val batteryCustomColorHex: String = "#00E676",
    val batteryStrokeWidth: Int = 6, // dp
    val batteryInset: Int = 4, // dp
    
    // Battery Number Text (⚡ XX%)
    val showBatteryText: Boolean = false,
    val batteryTextColorHex: String = "#94A3B8",
    val batteryTextOffsetX: Int = 0,
    val batteryTextOffsetY: Int = 56,

    // Date & Day (2D X/Y)
    val showDate: Boolean = true,
    val dateColorHex: String = "#94A3B8",
    val dateFontSize: Int = 11, // sp
    val dateOffsetX: Int = 0, // dp from center
    val dateOffsetY: Int = -52, // dp from center

    // PC Status Indicator (2D X/Y)
    val showPcStatus: Boolean = true,
    val pcStatusColorHex: String = "#00E676",
    val pcStatusSize: Int = 7, // dp
    val pcStatusOffsetX: Int = 0, // dp from center
    val pcStatusOffsetY: Int = -74, // dp from center

    // Complications: Steps Counter (2D X/Y)
    val showSteps: Boolean = false,
    val stepsColorHex: String = "#E2E8F0",
    val stepsFontSize: Int = 11, // sp
    val stepsOffsetX: Int = -46, // dp from center
    val stepsOffsetY: Int = 40, // dp from center

    // Complications: Heart Rate Monitor (2D X/Y)
    val showHeartRate: Boolean = false,
    val heartRateColorHex: String = "#FF5252",
    val heartRateFontSize: Int = 11, // sp
    val heartRateOffsetX: Int = 46, // dp from center
    val heartRateOffsetY: Int = 40, // dp from center

    // Dial Ticks & Markers (NONE, BARS, DOTS, NUMBERS)
    val ticksStyle: String = "BARS",
    val ticksColorHex: String = "#CCCCCC",

    val hasCustomBg: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("clock_style", clockStyle)
            put("clock_color", clockColorHex)
            put("dim_percent", dimPercent)
            put("hour_hand_color", hourHandColorHex)
            put("minute_hand_color", minuteHandColorHex)
            put("second_hand_color", secondHandColorHex)
            put("enable_hand_shadows", enableHandShadows)
            put("shadow_depth_level", shadowDepthLevel)
            put("show_battery", showBattery)
            put("battery_color_mode", batteryColorMode)
            put("battery_custom_color", batteryCustomColorHex)
            put("battery_stroke_width", batteryStrokeWidth)
            put("battery_inset", batteryInset)
            put("show_battery_text", showBatteryText)
            put("battery_text_color", batteryTextColorHex)
            put("battery_text_offset_x", batteryTextOffsetX)
            put("battery_text_offset_y", batteryTextOffsetY)
            put("show_date", showDate)
            put("date_color", dateColorHex)
            put("date_font_size", dateFontSize)
            put("date_offset_x", dateOffsetX)
            put("date_offset_y", dateOffsetY)
            put("show_pc_status", showPcStatus)
            put("pc_status_color", pcStatusColorHex)
            put("pc_status_size", pcStatusSize)
            put("pc_status_offset_x", pcStatusOffsetX)
            put("pc_status_offset_y", pcStatusOffsetY)
            put("show_steps", showSteps)
            put("steps_color", stepsColorHex)
            put("steps_font_size", stepsFontSize)
            put("steps_offset_x", stepsOffsetX)
            put("steps_offset_y", stepsOffsetY)
            put("show_heart_rate", showHeartRate)
            put("heart_rate_color", heartRateColorHex)
            put("heart_rate_font_size", heartRateFontSize)
            put("heart_rate_offset_x", heartRateOffsetX)
            put("heart_rate_offset_y", heartRateOffsetY)
            put("ticks_style", ticksStyle)
            put("ticks_color", ticksColorHex)
            put("has_custom_bg", hasCustomBg)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): WatchFaceConfig {
            return WatchFaceConfig(
                clockStyle = json.optString("clock_style", "DIGITAL"),
                clockColorHex = json.optString("clock_color", "#00E5FF"),
                dimPercent = json.optInt("dim_percent", 25),
                hourHandColorHex = json.optString("hour_hand_color", "#FFFFFF"),
                minuteHandColorHex = json.optString("minute_hand_color", "#00E5FF"),
                secondHandColorHex = json.optString("second_hand_color", "#00E676"),
                enableHandShadows = json.optBoolean("enable_hand_shadows", true),
                shadowDepthLevel = json.optInt("shadow_depth_level", 3),
                showBattery = json.optBoolean("show_battery", true),
                batteryColorMode = json.optString("battery_color_mode", "DYNAMIC"),
                batteryCustomColorHex = json.optString("battery_custom_color", "#00E676"),
                batteryStrokeWidth = json.optInt("battery_stroke_width", 6),
                batteryInset = json.optInt("battery_inset", 4),
                showBatteryText = json.optBoolean("show_battery_text", false),
                batteryTextColorHex = json.optString("battery_text_color", "#94A3B8"),
                batteryTextOffsetX = json.optInt("battery_text_offset_x", 0),
                batteryTextOffsetY = json.optInt("battery_text_offset_y", 56),
                showDate = json.optBoolean("show_date", true),
                dateColorHex = json.optString("date_color", "#94A3B8"),
                dateFontSize = json.optInt("date_font_size", 11),
                dateOffsetX = json.optInt("date_offset_x", 0),
                dateOffsetY = json.optInt("date_offset_y", -52),
                showPcStatus = json.optBoolean("show_pc_status", true),
                pcStatusColorHex = json.optString("pc_status_color", "#00E676"),
                pcStatusSize = json.optInt("pc_status_size", 7),
                pcStatusOffsetX = json.optInt("pc_status_offset_x", 0),
                pcStatusOffsetY = json.optInt("pc_status_offset_y", -74),
                showSteps = json.optBoolean("show_steps", false),
                stepsColorHex = json.optString("steps_color", "#E2E8F0"),
                stepsFontSize = json.optInt("steps_font_size", 11),
                stepsOffsetX = json.optInt("steps_offset_x", -46),
                stepsOffsetY = json.optInt("steps_offset_y", 40),
                showHeartRate = json.optBoolean("show_heart_rate", false),
                heartRateColorHex = json.optString("heart_rate_color", "#FF5252"),
                heartRateFontSize = json.optInt("heart_rate_font_size", 11),
                heartRateOffsetX = json.optInt("heart_rate_offset_x", 46),
                heartRateOffsetY = json.optInt("heart_rate_offset_y", 40),
                ticksStyle = json.optString("ticks_style", "BARS"),
                ticksColorHex = json.optString("ticks_color", "#CCCCCC"),
                hasCustomBg = json.optBoolean("has_custom_bg", false)
            )
        }
    }
}
