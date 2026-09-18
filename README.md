# Kai Launcher for Galaxy Watch 5 Pro (WristHub) ⌚⚡

[![Platform](https://img.shields.io/badge/Platform-Wear%20OS%204%2B%20%2F%20Android%2014%2B-blue.svg)](https://developer.android.com/wear)
[![Device](https://img.shields.io/badge/Device-Samsung%20Galaxy%20Watch%205%20Pro-orange.svg)](https://www.samsung.com)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20for%20Wear%20OS-brightgreen.svg)](https://developer.android.com/training/wearables/compose)
[![Backend](https://img.shields.io/badge/PC%20Server-Python%20WebSocket-yellow.svg)](https://python.org)

**WristHub** 是一款專為 **Samsung Galaxy Watch 5 Pro (SM-R925F)** 量身打造的「手腕全能指揮中心／自訂啟動器（Custom Launcher）」。

透過遵循 Android Wear OS 原生架構規範，**完全不需要 Root、刷機或破壞三星 Knox 原廠保固**，即可賦予手錶極具科幻感的 HUD 儀表錶盤、毫秒級延遲的電腦遙控台（支援手錶旋轉外圈邊框調音量），以及手腕 AI 語音助理介面。

---

## 📸 實機畫面（Galaxy Watch 5 Pro 實拍截圖）

| 🎨 自訂背景 + 數位 HUD | 🕰️ 經典指針模式 (每秒跳動) | 💻 PC 遙控台 (熱映射) |
| :---: | :---: | :---: |
| ![自訂數位錶盤](docs/images/watch_digital_bg.png) | ![經典指針錶盤](docs/images/watch_analog.png) | ![PC 遙控台](docs/images/watch_dynamic_updated.png) |

---

## 🌟 核心特色功能

### 1. 🎨 高度可自訂義錶面（鎖定畫面級體驗 + 100% 離線優先）
* **大螢幕 Web 視覺化工坊（Watch Face Studio）：**
  * 打開 `http://localhost:8765` 切換至「🎨 錶面自訂工坊」，具備 450x450 圓形實時 WYSIWYG 畫布預覽。
* **個人照片 / 動圖導入（完全無線化）：**
  * 支援上傳任意 JPG、PNG、WebP 或 GIF，電腦後端（Pillow）自動置中裁切、縮放至 450x450，並轉為超省電高壓縮 WebP。
  * 手錶端透過背景協程（Coroutine）將檔案永久下載至 `context.filesDir/custom_bg.webp`。
* **出門 100% 離線可用（Offline-First）：**
  * 斷開電腦或出門在外時，手錶開機瞬間自本地快取載入圖片與配置，基本時鐘、電量弧環、日期功能完整保留，不依賴電腦連線。
* **雙時鐘模式：**
  * **數位 HUD 模式**：霓虹色調大字體數位時間、實時秒數。
  * **經典指針模式**：高精度幾何 Canvas 繪製 12 小時刻度、時針、分針與每秒跳動一次的秒針（Tick-Tock）。
* **防眩光暗角濾鏡 (Dim Ratio)**：0%~70% 可調黑底半透明遮罩，確保背景再花俏也能清晰閱讀時間。
* **AOD 微光常亮模式相容：** 手放下時背景自動轉為純黑 OLED 關閉像素省電防烙印，抬腕喚醒瞬間（0ms）無縫恢復個人自訂背景！

### 2. 💻 PC 遙控控制台（左滑分頁 & 電腦端 Web 控制台）
* **本機 Web 控制台（Local Web Dashboard）：**
  * 瀏覽器打開 `http://localhost:8765` 即可進入視覺化管理後台。
  * **動態按鍵映射（Dynamic Mapping）**：在網頁上隨意變更按鈕圖示、名稱、顏色與動作，點擊「儲存並同步」，手錶畫面**秒速熱更新**，完全無需重新編譯 APK！
  * **支援自訂指令（CMD Execution）**：可在電腦控制台上綁定任意指令（如 `notepad.exe`、`calc.exe`、`code .` 或 Python 自動化腳本）。
  * **即時除錯與封包監視器（Live Debugger）**：即時串流顯示手錶點擊延遲（如 `耗時 7.0ms`）、旋轉錶圈 Delta 數值、手錶電池回報與連線狀態。
* **手錶端互動：**
  * 🔇 **一鍵切換靜音**
  * 🔊 **音量增加** / 🔉 **音量減少**
  * ⏯️ **媒體播放 / 暫停**
  * ◀ **簡報上一頁** / ▶ **簡報下一頁**（PPT 翻頁器）
  * 🔒 **一鍵鎖定 Windows 電腦**（`Win + L`）
  * **旋轉外圈（Rotary Bezel）支援：** 手指在 Watch 5 Pro 螢幕外圈滑動觸碰邊框，可平滑調節電腦音量！
  * **線性馬達觸覺震動（Haptic Feedback）：** 每次按鍵均提供清脆的觸覺反饋。

### 3. 🤖 Gemini AI 隨身助理（右滑分頁）
* 手腕隨身錄音按鈕與對話卡片，支援語音下達指令與接收文字/語音摘要。

---

## 🔒 安全性與隱私聲明 (Security & Privacy)

本專案遵循資安與隱私保護原則：
1. **純區域網路通訊（Local Only）：** 手錶與電腦之間的 WebSocket 預設僅在家庭/工作區網（如 `192.168.0.x`）直連傳輸，不經過任何第三方雲端伺服器，避免指令被竊聽。
2. **無敏感個人資料（No Secrets Committed）：** `.gitignore` 已嚴密過濾本機 SDK 路徑（`local.properties`）、金鑰證書（`*.jks`, `*.keystore`）與未來之 API 金鑰設定檔。
3. **系統無損保證（Non-invasive Architecture）：** 程式為標準 Wear OS App，不侵入 bootloader，隨時可安全解除安裝，不影響 Samsung Pay、Samsung Health 等 Knox 安全組件。

---

## 🛠️ 專案架構

```text
wrist-hub/
├── app/                            # Wear OS 手錶端 Android 專案
│   ├── src/main/
│   │   ├── java/com/wristhub/launcher/
│   │   │   ├── presentation/
│   │   │   │   ├── MainActivity.kt        # 主入口、Ambient 微光常亮監聽
│   │   │   │   ├── WristHubApp.kt         # HorizontalPager 多頁容器
│   │   │   │   ├── screens/               # HUD 錶盤、PC 遙控、AI 介面
│   │   │   │   └── theme/                 # 科幻 HUD 霓虹色系配色
│   │   │   └── network/
│   │   │       └── PcWebSocketManager.kt  # OkHttp WebSocket 客戶端
│   │   ├── res/                           # 圖示、字串與資源
│   │   └── AndroidManifest.xml            # 權限、HOME 啟動器宣告
│   └── build.gradle.kts
├── pc-daemon/                      # 電腦端 Python 守護程式
│   └── wrist_server.py             # 監聽 WebSocket 並模擬鍵鼠 (Win32 API)
├── docs/images/                    # 手錶實拍截圖
├── .gitignore                      # 嚴格過濾構建與隱私檔案
└── settings.gradle.kts
```

---

## 🚀 快速開始指南

### 1. 前置需求
* **電腦端：** Windows 10/11，已安裝 Python 3.10+、Android Studio 與 Android SDK Platform-Tools（adb）。
* **手錶端：** Samsung Galaxy Watch 4 / 5 / 5 Pro / 6 / 7 等運行 Wear OS 3.0+ 之裝置。

### 2. 手錶 Wi-Fi ADB 配對與連線
1. 手錶開啟 **「設定」➔「關於手錶」➔「軟體資訊」➔ 連續點擊「軟體版本」數次**，開啟開發人員選項。
2. 進入 **「設定」➔「開發人員選項」**，開啟 **「ADB 偵錯」** 與 **「無線偵錯」**。
3. 點選「使用配對碼配對新裝置」，在電腦終端機輸入：
   ```powershell
   adb pair <手錶IP>:<配對連接埠> <6位配對碼>
   ```
4. 配對完成後，連線至主連接埠：
   ```powershell
   adb connect <手錶IP>:<主要連接埠>
   ```

### 3. 啟動電腦端 Python 守護程式
安裝 WebSocket 依賴：
```powershell
pip install websockets
```
啟動伺服器：
```powershell
python pc-daemon/wrist_server.py
```
*(伺服器將在 `0.0.0.0:8765` 監聽來自手錶的指令)*

### 4. 編譯並安裝手錶 App
在專案根目錄執行 Gradle 編譯並推送至手錶：
```powershell
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
啟動手錶 App：
```powershell
adb shell am start -n com.wristhub.launcher/.presentation.MainActivity
```

---

## 💡 推薦設定：設定手錶實體按鈕秒開 WristHub

1. 手錶開啟 **「設定」➔「進階功能」➔「自訂按鍵」**。
2. 找到 **「按兩次（首頁鍵）」**。
3. 在清單中勾選 **`Wrist Hub`**。
4. 設定完成後，在任何畫面**連續按兩次右上角實體鍵**即可瞬間喚醒進入指揮中心！

---

## 📄 License
MIT License
