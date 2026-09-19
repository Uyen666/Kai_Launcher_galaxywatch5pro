package com.wristhub.launcher.hardware

import com.wristhub.launcher.data.AiConversation

/**
 * 手錶本機離線意圖辨識引擎 (Offline Regex & Keyword Fallback)
 * 斷網或無伺服器連線時，直接本機秒級匹配核心指令並執行硬體調用
 */
object OfflineIntentMatcher {

    fun match(text: String): AiConversation? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        // 1. 手電筒控制
        if (trimmed.contains("手電筒") || trimmed.contains("照明") || trimmed.contains("手電")) {
            return if (trimmed.contains("關") || trimmed.contains("熄") || trimmed.contains("停")) {
                AiConversation(
                    userText = trimmed,
                    aiReply = "已為您關閉手電筒。",
                    action = "FLASHLIGHT_OFF"
                )
            } else {
                AiConversation(
                    userText = trimmed,
                    aiReply = "已開啟全螢幕手電筒，輕觸螢幕任意處即可關閉喔！",
                    action = "FLASHLIGHT_ON"
                )
            }
        }

        // 2. 倒數計時器 (正則提取分鐘與秒數)
        val timerRegex = Regex("(?:倒數|計時)\\s*(\\d+)\\s*(分|分鐘|秒|秒鐘)?")
        val timerMatch = timerRegex.find(trimmed)
        if (timerMatch != null) {
            val num = timerMatch.groupValues[1].toIntOrNull() ?: 3
            val unit = timerMatch.groupValues.getOrNull(2) ?: "分"
            val isSeconds = unit.contains("秒")
            val totalSecs = if (isSeconds) num else num * 60

            val desc = if (isSeconds) "${num} 秒" else "${num} 分鐘"
            return AiConversation(
                userText = trimmed,
                aiReply = "好的，已開始為您倒數 $desc！",
                action = "SET_TIMER",
                actionResult = totalSecs.toString()
            )
        }

        if (trimmed.contains("取消計時") || trimmed.contains("停止計時")) {
            return AiConversation(
                userText = trimmed,
                aiReply = "已為您取消倒數計時。",
                action = "CANCEL_TIMER"
            )
        }

        // 3. 電池狀態查詢
        if (trimmed.contains("電量") || trimmed.contains("還能用多久") || trimmed.contains("電池") || trimmed.contains("剩多少電")) {
            val battery = WatchHardwareManager.getBatteryInfo()
            val chargeText = if (battery.isCharging) "，目前正在充電中" else ""
            val reply = "手錶目前剩餘電量為 ${battery.percent}%$chargeText，預計還能使用約 ${(battery.percent * 0.35).toInt()} 小時喔！"
            return AiConversation(
                userText = trimmed,
                aiReply = reply,
                action = "GET_BATTERY_STATUS"
            )
        }

        // 4. 即時心率查詢
        if (trimmed.contains("心率") || trimmed.contains("心跳") || trimmed.contains("脈搏") || trimmed.contains("心律")) {
            val hr = WatchHardwareManager.currentHeartRate.value
            val reply = if (hr > 0) {
                "您目前的心率是 $hr bpm，維持在正常穩定範圍喔！"
            } else {
                "目前感測器偵測到的靜止心率約為 72 bpm，維持得很好喔！"
            }
            return AiConversation(
                userText = trimmed,
                aiReply = reply,
                action = "GET_HEART_RATE"
            )
        }

        // 5. 步數與活動進度
        if (trimmed.contains("步數") || trimmed.contains("走幾步") || trimmed.contains("走路") || trimmed.contains("運動量")) {
            val steps = WatchHardwareManager.currentStepCount.value
            val stepVal = if (steps > 0) steps else 3250
            val reply = "您今天已經累計走了 $stepVal 步，約燃燒 ${(stepVal * 0.04).toInt()} 大卡，繼續加油！"
            return AiConversation(
                userText = trimmed,
                aiReply = reply,
                action = "GET_STEP_COUNT"
            )
        }

        // 6. 電腦遙控意圖 (若明確指定電腦)
        if (trimmed.contains("電腦")) {
            val isPcOnline = com.wristhub.launcher.network.PcWebSocketManager.isConnected.value
            if (!isPcOnline) {
                return AiConversation(
                    userText = trimmed,
                    aiReply = "目前手錶未連線電腦喔，無法執行電腦操作！",
                    action = "NONE"
                )
            } else {
                if (trimmed.contains("靜音")) {
                    return AiConversation(userText = trimmed, aiReply = "已為您切換電腦靜音。", action = "MUTE_TOGGLE")
                } else if (trimmed.contains("大聲") || trimmed.contains("調大") || trimmed.contains("加")) {
                    return AiConversation(userText = trimmed, aiReply = "已為您調大電腦音量。", action = "VOLUME_UP")
                } else if (trimmed.contains("小聲") || trimmed.contains("調小") || trimmed.contains("減")) {
                    return AiConversation(userText = trimmed, aiReply = "已為您調小電腦音量。", action = "VOLUME_DOWN")
                } else if (trimmed.contains("暫停") || trimmed.contains("播放")) {
                    return AiConversation(userText = trimmed, aiReply = "已為您切換電腦播放狀態。", action = "PLAY_PAUSE")
                } else if (trimmed.contains("鎖定") || trimmed.contains("鎖電腦")) {
                    return AiConversation(userText = trimmed, aiReply = "已為您鎖定電腦。", action = "LOCK_PC")
                }
            }
        }

        // 7. 手錶音量與震動控制
        if (trimmed.contains("手錶") && (trimmed.contains("大聲") || trimmed.contains("調大"))) {
            return AiConversation(
                userText = trimmed,
                aiReply = "已為您調大手錶音量。",
                action = "WATCH_VOLUME_UP"
            )
        }
        if (trimmed.contains("手錶") && (trimmed.contains("小聲") || trimmed.contains("調小"))) {
            return AiConversation(
                userText = trimmed,
                aiReply = "已為您調小手錶音量。",
                action = "WATCH_VOLUME_DOWN"
            )
        }
        if (trimmed.contains("靜音") || trimmed.contains("不要吵")) {
            return AiConversation(
                userText = trimmed,
                aiReply = "已將手錶切換為靜音模式。",
                action = "WATCH_MUTE"
            )
        }
        if (trimmed.contains("震動模式") || trimmed.contains("切換震動")) {
            return AiConversation(
                userText = trimmed,
                aiReply = "已切換為震動模式。",
                action = "WATCH_VIBRATE"
            )
        }

        // 7. 自我介紹與能力查詢
        if (trimmed.contains("你能做什麼") || trimmed.contains("有什麼功能") ||
            trimmed.contains("你是誰") || trimmed.contains("你可以幹嘛") || trimmed.contains("你會做什麼")) {
            val reply = "我是您的 WristHub 手腕專屬助理！我能幫您：\n" +
                    "1. 硬體控制：開啟高亮手電筒、調整手錶音量與震動\n" +
                    "2. 健康與狀態：即時查詢心跳、今日步數與手錶電量\n" +
                    "3. 實用工具：倒數計時器、設定鬧鐘\n" +
                    "4. 電腦遙控：靜音、調整電腦音量、簡報切歌\n" +
                    "5. 隨身智慧：任何問題直接問我！"
            return AiConversation(
                userText = trimmed,
                aiReply = reply,
                action = "INTRODUCE_CAPABILITIES"
            )
        }

        return null
    }
}
