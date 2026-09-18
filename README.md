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

### 3. 🤖 Gemini AI 手腕隨身助理（右滑分頁・Siri Intelligence 級體驗）
* **極致手腕錄音動畫（Siri / Apple Intelligence 呼吸光暈）：**
  * 手錶待命時呈現溫潤流轉的彩虹呼吸光暈；點擊錄音時，擴散紅色脈衝動態光環（Pulse Aura）並伴隨線性馬達震動反饋。
  * 透過 [AudioRecorderManager.kt](file:///c:/Users/林尚楷/.gemini/antigravity/scratch/wrist-hub/app/src/main/java/com/wristhub/launcher/audio/AudioRecorderManager.kt) 採樣 16kHz / 32kbps AAC 超輕量音訊（3 秒僅約 15KB），耗電極低且瞬間完成無線上傳。
* **雙向對話卡片（Conversation Cards）：**
  * 專為圓形手錶螢幕打造的流暢捲動對話流：
    * 🗣️ **語音轉譯卡**：精確呈現使用者語音識別出的原始文字與時間戳記。
    * 🤖 **Gemini 智慧回覆卡**：繁體中文重點摘要（25~50 字，完美適配手錶視野）。
    * ⚡ **電腦連動執行徽章**：若指令觸發硬體操作，卡片即時標註 `⚡ 電腦已執行: [動作名稱]`。
* **雙重回報（視覺卡片 + 手錶揚聲器 TTS 朗讀）：**
  * 整合 [WatchTtsManager.kt](file:///c:/Users/林尚楷/.gemini/antigravity/scratch/wrist-hub/app/src/main/java/com/wristhub/launcher/audio/WatchTtsManager.kt)，回答產生時自動調用 Galaxy Watch 5 Pro 內建實體揚聲器進行清晰語音朗讀。
  * 每張卡片皆提供「🔊 重新朗讀 / ⏹ 停止」按鈕。
* **手腕自然語言電腦遙控：**
  * 支援直接用語音下達 Windows 控制指令：
    * 🔇 *「把電腦靜音 / 取消靜音」* ➔ 觸發 `MUTE_TOGGLE`
    * 🔊 *「聲音大一點 / 音量減少」* ➔ 觸發 `VOLUME_UP` / `VOLUME_DOWN`
    * 🔒 *「幫我鎖定電腦」* ➔ 觸發 `LOCK_PC`
    * 📝 *「打開記事本 / 開計算機」* ➔ 呼叫 `notepad.exe` / `calc.exe`
    * ⏯️ *「暫停播放 / 下一首歌 / 簡報下一頁」* ➔ 觸發多媒體與簡報按鍵
    * 🌤️ *「明天台北天氣如何？/ 幫我計算 125 乘 8」* ➔ 智慧百科與資訊查詢
* **最新 2026 世代模型支援與智慧自動備援 (Auto-Fallback)：**
  * 預設採用 **`gemini-3.5-flash-lite`**（1~2 秒極速回覆、穩定不卡頓、低延遲）。
  * 內建 **智慧自動備援**：後端若偵測到 Google 伺服器尖峰忙碌 (503) 或舊模型退役 (404)，自動瞬間切換至最穩定的模型重試，手錶與使用者對話永不中斷！
* **電腦端 Web 控制台整合（「🤖 Gemini AI 設定」分頁）：**
  * 支援視覺化 API Key 輸入、顯示/隱藏與一鍵清空按鈕。
  * 支援模型即時切換（`gemini-3.5-flash-lite`、`gemini-3.6-flash`、`gemini-3.5-flash`、`gemini-3.8-flash`）。
  * 提供電腦端「🧪 模擬提問測試盒」與即時對話串流，方便免戴手錶快速測試。

### 4. ⚡ 圓形螢幕極致切換效能優化
* **消弭切換掉幀與記憶體頻寬瓶頸：**
  * 徹底重構手錶端 `HorizontalPager`，移除每幀強制觸發 GPU 離屏合成的 `saveLayer` 記憶體停頓。
  * 設計獨立圓形黑膠唱片裁切（`CircleShape`）與 3D 景深縮放（`scale = 1f - 0.15f * offset`），左右滑動時呈現優雅深邃的層次感且完全不穿模。
  * 啟用 `beyondViewportPageCount = 1` 預加載相鄰分頁，滑動幀率穩定貼滿 60FPS。
* **Windows 桌面一鍵雙開捷徑：**
  * 具備 `launch_web.py` 與桌面捷徑 `WristHub 控制台.lnk`，雙擊自動在背景喚醒 Python 守護程式並秒開瀏覽器後台。

---

## 🔒 安全性與隱私聲明 (Security & Privacy)

本專案遵循資安與隱私保護原則：
1. **純區域網路通訊（Local Only）：** 手錶與電腦之間的 WebSocket 預設僅在家庭/工作區網直連傳輸，不經過任何第三方雲端伺服器。
2. **無敏感個人資料（No Secrets Committed）：** `.gitignore` 嚴密排除 API 金鑰（`gemini_api_key` 僅保存在本機 `config.json`，絕不上傳公開倉庫）。
3. **系統無損保證（Non-invasive Architecture）：** 程式為標準 Wear OS App，完全不影響 Knox 保固與手錶原廠健康功能。

---

## 🛠️ 專案架構

```text
wrist-hub/
├── app/                            # Wear OS 手錶端 Android 專案
│   ├── src/main/
│   │   ├── java/com/wristhub/launcher/
│   │   │   ├── audio/
│   │   │   │   ├── AudioRecorderManager.kt# AAC 16kHz 麥克風輕量錄音管理
│   │   │   │   └── WatchTtsManager.kt     # 手錶揚聲器 TextToSpeech 朗讀
│   │   │   ├── data/
│   │   │   │   └── AiConversation.kt      # 對話卡片資料模型
│   │   │   ├── presentation/
│   │   │   │   ├── MainActivity.kt        # 主入口、Ambient 微光常亮監聽
│   │   │   │   ├── WristHubApp.kt         # 60FPS 平滑圓形多頁容器
│   │   │   │   ├── screens/               # HUD 錶盤、PC 遙控、AI 助理介面
│   │   │   │   └── theme/                 # 科幻 HUD 霓虹色系配色
│   │   │   └── network/
│   │   │       ├── PcWebSocketManager.kt  # OkHttp WebSocket 客戶端
│   │   │       └── AiSyncManager.kt       # 音訊上傳與 Gemini 狀態分發
│   │   ├── res/                           # 圖示、字串與資源
│   │   └── AndroidManifest.xml            # 錄音、網路、HOME 啟動器宣告
│   └── build.gradle.kts
├── pc-daemon/                      # 電腦端 Python 守護程式
│   ├── wrist_server.py             # FastAPI / WebSocket / Gemini API 伺服器
│   ├── launch_web.py               # 一鍵啟動守護進程與瀏覽器
│   ├── index.html                  # 視覺化 Web 控制台（按鍵/錶盤/AI 管理）
│   ├── config.json                 # 按鍵映射與本機 AI 配置（含 gitignore 保護）
│   └── watchface_config.json       # 錶盤自訂樣式設定
├── docs/images/                    # 手錶實拍截圖
├── .gitignore                      # 嚴格過濾構建與隱私金鑰
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
安裝後端與圖像轉碼相依套件：
```powershell
pip install fastapi uvicorn websockets pillow imageio
```
**啟動方式（二選一）：**
* **方法 A（推薦・一鍵雙開）**：直接雙擊桌面捷徑 **`WristHub 控制台.lnk`**（或在終端機執行 `python pc-daemon/launch_web.py`），將自動常駐伺服器並秒開瀏覽器後台。
* **方法 B（命令列啟動）**：
  ```powershell
  python pc-daemon/wrist_server.py
  ```
*(伺服器將在 `0.0.0.0:8765` 監聽來自手錶的連線與 HTTP 請求)*

### 4. 設定 Gemini AI 手腕助理（可選・推薦）
1. 電腦瀏覽器打開 `http://localhost:8765`。
2. 切換至 **「🤖 Gemini AI 設定」** 頁籤。
3. 貼上您的 [Google AI Studio API Key](https://aistudio.google.com/apikey)（免費），模型推薦選擇 **`Gemini 3.5 Flash Lite (極速響應、最推薦 ⭐)`**，點擊 **「💾 儲存 AI 設定」**。
4. 可在下方測試框輸入「*把電腦靜音*」或「*明天台北天氣如何*」即時驗證。

### 5. 編譯並安裝手錶 App
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
