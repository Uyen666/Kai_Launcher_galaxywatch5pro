import os
import io
import json
import ctypes
import datetime
import subprocess
import socket
import asyncio
from typing import List, Optional
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Request, UploadFile, File
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from PIL import Image, ImageSequence
import tempfile
import imageio
import uvicorn
import base64
import urllib.request
import urllib.error

# Windows Virtual Key Codes
VK_VOLUME_MUTE = 0xAD
VK_VOLUME_DOWN = 0xAE
VK_VOLUME_UP = 0xAF
VK_MEDIA_NEXT_TRACK = 0xB0
VK_MEDIA_PREV_TRACK = 0xB1
VK_MEDIA_PLAY_PAUSE = 0xB3
VK_PRIOR = 0x21  # Page Up
VK_NEXT = 0x22   # Page Down
VK_LWIN = 0x5B
VK_D = 0x44
VK_RETURN = 0x0D

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32
from ctypes import wintypes
import time

kernel32.GlobalAlloc.restype = ctypes.c_void_p
kernel32.GlobalLock.restype = ctypes.c_void_p
kernel32.GlobalLock.argtypes = [ctypes.c_void_p]
kernel32.GlobalUnlock.argtypes = [ctypes.c_void_p]
user32.SetClipboardData.restype = ctypes.c_void_p
user32.SetClipboardData.argtypes = [wintypes.UINT, ctypes.c_void_p]

CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002
VK_CONTROL = 0x11
VK_V = 0x56

def send_key(vk_code):
    user32.keybd_event(vk_code, 0, 0, 0)
    user32.keybd_event(vk_code, 0, 2, 0)

def show_desktop():
    user32.keybd_event(VK_LWIN, 0, 0, 0)
    user32.keybd_event(VK_D, 0, 0, 0)
    user32.keybd_event(VK_D, 0, 2, 0)
    user32.keybd_event(VK_LWIN, 0, 2, 0)

def type_text_to_pc(text: str):
    """
    透過 Windows 剪貼簿與鍵盤貼上事件 (Ctrl+V)，直接將文字貼入焦點視窗游標處。
    100% 相容 64 位元 Windows、繁體中文、英數字、標點符號與 Emoji，且不受 UIPI 權限阻擋。
    """
    if not text:
        return
    try:
        # 重試開啟並清空剪貼簿
        for _ in range(5):
            if user32.OpenClipboard(None):
                break
            time.sleep(0.02)
        else:
            return

        user32.EmptyClipboard()
        encoded = text.encode('utf-16le') + b'\x00\x00'
        h_mem = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(encoded))
        if h_mem:
            p_mem = kernel32.GlobalLock(h_mem)
            if p_mem:
                ctypes.memmove(p_mem, encoded, len(encoded))
                kernel32.GlobalUnlock(h_mem)
                user32.SetClipboardData(CF_UNICODETEXT, h_mem)
        user32.CloseClipboard()

        # 等待剪貼簿就緒後模擬按下 Ctrl+V
        time.sleep(0.03)
        user32.keybd_event(VK_CONTROL, 0, 0, 0)
        user32.keybd_event(VK_V, 0, 0, 0)
        time.sleep(0.02)
        user32.keybd_event(VK_V, 0, 2, 0)
        user32.keybd_event(VK_CONTROL, 0, 2, 0)
    except Exception as e:
        print(f"type_text_to_pc error: {e}", flush=True)

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(CURRENT_DIR, "static")
CONFIG_FILE = os.path.join(CURRENT_DIR, "config.json")
WF_CONFIG_FILE = os.path.join(CURRENT_DIR, "watchface_config.json")
INDEX_FILE = os.path.join(CURRENT_DIR, "index.html")

os.makedirs(STATIC_DIR, exist_ok=True)

app = FastAPI(title="WristHub Companion Server")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

dashboard_websockets: List[WebSocket] = []
watch_websocket: Optional[WebSocket] = None

watch_state = {
    "connected": False,
    "ip": None,
    "battery": None
}

def get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "192.168.0.109"

def load_config():
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, "r", encoding="utf-8-sig") as f:
            return json.load(f)
    return {"buttons": []}

def save_config(data):
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

def load_wf_config():
    if os.path.exists(WF_CONFIG_FILE):
        with open(WF_CONFIG_FILE, "r", encoding="utf-8-sig") as f:
            return json.load(f)
    return {
        "clock_style": "DIGITAL",
        "clock_color": "#00E5FF",
        "dim_percent": 25,
        "hour_hand_color": "#FFFFFF",
        "minute_hand_color": "#00E5FF",
        "second_hand_color": "#00E676",
        "enable_hand_shadows": True,
        "shadow_depth_level": 3,
        "show_battery": True,
        "battery_color_mode": "DYNAMIC",
        "battery_custom_color": "#00E676",
        "battery_stroke_width": 6,
        "battery_inset": 4,
        "show_battery_text": False,
        "battery_text_color": "#94A3B8",
        "battery_text_offset_x": 0,
        "battery_text_offset_y": 56,
        "show_date": True,
        "date_color": "#94A3B8",
        "date_font_size": 11,
        "date_offset_x": 0,
        "date_offset_y": -52,
        "show_pc_status": True,
        "pc_status_color": "#00E676",
        "pc_status_size": 7,
        "pc_status_offset_x": 0,
        "pc_status_offset_y": -74,
        "show_steps": False,
        "steps_color": "#E2E8F0",
        "steps_font_size": 11,
        "steps_offset_x": -46,
        "steps_offset_y": 40,
        "show_heart_rate": False,
        "heart_rate_color": "#FF5252",
        "heart_rate_font_size": 11,
        "heart_rate_offset_x": 46,
        "heart_rate_offset_y": 40,
        "ticks_style": "BARS",
        "ticks_color": "#CCCCCC",
        "has_custom_bg": False
    }

def save_wf_config(data):
    with open(WF_CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

async def broadcast_log(category: str, message: str):
    now = datetime.datetime.now().strftime("%H:%M:%S")
    payload = json.dumps({
        "type": "LOG",
        "category": category,
        "message": message,
        "time": now
    })
    print(f"[{now}] [{category}] {message}", flush=True)
    dead_ws = []
    for ws in dashboard_websockets:
        try:
            await ws.send_text(payload)
        except Exception:
            dead_ws.append(ws)
    for ws in dead_ws:
        if ws in dashboard_websockets:
            dashboard_websockets.remove(ws)

async def broadcast_status():
    payload = json.dumps({
        "type": "STATUS",
        "watch_connected": watch_state["connected"],
        "watch_ip": watch_state["ip"],
        "watch_battery": watch_state["battery"]
    })
    dead_ws = []
    for ws in dashboard_websockets:
        try:
            await ws.send_text(payload)
        except Exception:
            dead_ws.append(ws)
    for ws in dead_ws:
        if ws in dashboard_websockets:
            dashboard_websockets.remove(ws)

async def broadcast_watchface_update(wf_config, bg_url=None):
    if watch_websocket is not None:
        payload = {
            "type": "WATCHFACE_UPDATE",
            "config": wf_config
        }
        if bg_url:
            payload["bg_url"] = bg_url
        try:
            await watch_websocket.send_text(json.dumps(payload))
            await broadcast_log("WATCHFACE", "已將最新錶盤配置推播至手錶")
        except Exception as e:
            await broadcast_log("ERROR", f"推播錶盤給手錶失敗: {e}")

def execute_action(action_type: str, action_val: str) -> str:
    if action_type == "SYSTEM_VOLUME":
        if action_val == "VOLUME_UP":
            send_key(VK_VOLUME_UP)
            return "系統音量 +"
        elif action_val == "VOLUME_DOWN":
            send_key(VK_VOLUME_DOWN)
            return "系統音量 -"
        elif action_val == "MUTE_TOGGLE":
            send_key(VK_VOLUME_MUTE)
            return "切換靜音"
    elif action_type == "MEDIA":
        if action_val == "PLAY_PAUSE":
            send_key(VK_MEDIA_PLAY_PAUSE)
            return "播放/暫停"
        elif action_val == "PPT_NEXT":
            send_key(VK_NEXT)
            return "簡報下一頁"
        elif action_val == "PPT_PREV":
            send_key(VK_PRIOR)
            return "簡報上一頁"
        elif action_val == "NEXT_TRACK":
            send_key(VK_MEDIA_NEXT_TRACK)
            return "下一首歌曲"
        elif action_val == "PREV_TRACK":
            send_key(VK_MEDIA_PREV_TRACK)
            return "上一首歌曲"
    elif action_type == "SYSTEM_ACTION":
        if action_val == "LOCK_PC":
            user32.LockWorkStation()
            return "鎖定電腦"
        elif action_val == "SHOW_DESKTOP":
            show_desktop()
            return "顯示桌面"
    elif action_type == "CMD":
        try:
            subprocess.Popen(action_val, shell=True)
            return f"執行 CMD: {action_val}"
        except Exception as e:
            return f"CMD 執行失敗: {e}"
            
    # Direct action compatibility
    if action_val == "VOLUME_UP":
        send_key(VK_VOLUME_UP)
        return "音量增加"
    elif action_val == "VOLUME_DOWN":
        send_key(VK_VOLUME_DOWN)
        return "音量減少"
    elif action_val == "MUTE_TOGGLE":
        send_key(VK_VOLUME_MUTE)
        return "切換靜音"
    elif action_val == "LOCK_PC":
        user32.LockWorkStation()
        return "鎖定電腦"
        
    return f"執行: {action_val}"

@app.get("/")
async def get_index():
    return FileResponse(INDEX_FILE)

@app.get("/api/config")
async def api_get_config():
    return load_config()

@app.post("/api/config")
async def api_save_config(request: Request):
    global watch_websocket
    data = await request.json()
    save_config(data)
    await broadcast_log("CONFIG", "按鍵映射已更新並儲存至 config.json")
    
    if watch_websocket is not None:
        try:
            config_payload = json.dumps({"type": "CONFIG", "buttons": data.get("buttons", [])})
            await watch_websocket.send_text(config_payload)
            await broadcast_log("CONFIG", "已即時推送最新按鍵配置給 Galaxy Watch！")
        except Exception as e:
            await broadcast_log("ERROR", f"推送給手錶失敗: {e}")
            
    return {"status": "OK", "msg": "Config saved and synced"}

@app.get("/api/watchface-config")
async def api_get_wf_config():
    return load_wf_config()

@app.post("/api/watchface-config")
async def api_save_wf_config(request: Request):
    data = await request.json()
    save_wf_config(data)
    await broadcast_log("WATCHFACE", "錶盤自訂設定已儲存！")
    bg_url = None
    if data.get("has_custom_bg"):
        bg_url = f"http://{get_local_ip()}:8765/static/custom_bg.webp?t={int(datetime.datetime.now().timestamp())}"
    await broadcast_watchface_update(data, bg_url)
    return {"status": "OK", "msg": "Watchface config updated"}

@app.post("/api/upload-bg")
async def api_upload_bg(file: UploadFile = File(...)):
    contents = await file.read()
    filename = file.filename or "upload.png"
    lower_name = filename.lower()
    is_video = lower_name.endswith((".mp4", ".webm", ".mov", ".avi", ".mkv")) or (file.content_type and "video" in file.content_type)
    is_gif = lower_name.endswith(".gif") or (file.content_type and "gif" in file.content_type)
    out_path = os.path.join(STATIC_DIR, "custom_bg.webp")

    try:
        if is_video:
            await broadcast_log("WATCHFACE", f"偵測到影片檔案 ({filename})，開始抽幀裁切並轉碼為動態 WebP...")
            with tempfile.NamedTemporaryFile(delete=False, suffix=os.path.splitext(filename)[1]) as tmp:
                tmp.write(contents)
                tmp_path = tmp.name
            try:
                reader = imageio.get_reader(tmp_path, 'ffmpeg')
                meta = reader.get_meta_data()
                fps = meta.get('fps', 20) or 20
                # Target 15 fps, max 45 frames (3 seconds loop)
                step = max(1, int(round(fps / 15)))
                frames = []
                for idx, frame in enumerate(reader):
                    if idx % step != 0:
                        continue
                    img = Image.fromarray(frame)
                    if img.mode not in ("RGB", "RGBA"):
                        img = img.convert("RGB")
                    w, h = img.size
                    min_dim = min(w, h)
                    left = (w - min_dim) // 2
                    top = (h - min_dim) // 2
                    cropped = img.crop((left, top, left + min_dim, top + min_dim))
                    resized = cropped.resize((450, 450), Image.Resampling.LANCZOS)
                    frames.append(resized)
                    if len(frames) >= 45:
                        break
                reader.close()
                if frames:
                    frame_duration = int(1000 / 15)
                    frames[0].save(
                        out_path,
                        "WEBP",
                        save_all=True,
                        append_images=frames[1:],
                        duration=frame_duration,
                        loop=0,
                        quality=75,
                        method=6
                    )
            finally:
                if os.path.exists(tmp_path):
                    try:
                        os.remove(tmp_path)
                    except Exception:
                        pass
        elif is_gif:
            await broadcast_log("WATCHFACE", f"偵測到 GIF 動畫 ({filename})，開始處理動態幀...")
            gif = Image.open(io.BytesIO(contents))
            frames = []
            durations = []
            for idx, frame in enumerate(ImageSequence.Iterator(gif)):
                if getattr(gif, "n_frames", 1) > 60 and idx % 2 != 0:
                    continue
                frame_img = frame.convert("RGBA")
                w, h = frame_img.size
                min_dim = min(w, h)
                left = (w - min_dim) // 2
                top = (h - min_dim) // 2
                cropped = frame_img.crop((left, top, left + min_dim, top + min_dim))
                resized = cropped.resize((450, 450), Image.Resampling.LANCZOS)
                frames.append(resized)
                dur = frame.info.get("duration", 100) or 100
                durations.append(dur)
                if len(frames) >= 50:
                    break
            if frames:
                avg_duration = sum(durations) // len(durations) if durations else 100
                frames[0].save(
                    out_path,
                    "WEBP",
                    save_all=True,
                    append_images=frames[1:],
                    duration=avg_duration,
                    loop=0,
                    quality=75,
                    method=6
                )
        else:
            image = Image.open(io.BytesIO(contents))
            if image.mode not in ("RGB", "RGBA"):
                image = image.convert("RGBA")
            w, h = image.size
            min_dim = min(w, h)
            left = (w - min_dim) // 2
            top = (h - min_dim) // 2
            cropped = image.crop((left, top, left + min_dim, top + min_dim))
            resized = cropped.resize((450, 450), Image.Resampling.LANCZOS)
            resized.save(out_path, "WEBP", quality=85)

        wf_cfg = load_wf_config()
        wf_cfg["has_custom_bg"] = True
        save_wf_config(wf_cfg)

        bg_url = f"http://{get_local_ip()}:8765/static/custom_bg.webp?t={int(datetime.datetime.now().timestamp())}"
        await broadcast_watchface_update(wf_cfg, bg_url)
        file_type_str = "動態影音" if (is_video or is_gif) else "靜態圖片"
        await broadcast_log("WATCHFACE", f"成功轉碼為 450x450 {file_type_str} WebP！已通知手錶下載：{bg_url}")
        return {"status": "OK", "bg_url": bg_url, "is_animated": (is_video or is_gif)}
    except Exception as e:
        await broadcast_log("ERROR", f"背景轉碼失敗: {e}")
        return JSONResponse(status_code=500, content={"status": "ERROR", "msg": str(e)})

# ============================================================
# Gemini AI Assistant Endpoints
# ============================================================

AI_SYSTEM_INSTRUCTION = """
你是一個專為 Samsung Galaxy Watch 5 Pro 設計的手腕 Siri / Intelligence 語音助理 (WristHub Assistant)。
使用者對手錶說了一段話，請完成以下任務：
1. 完整精確辨識使用者說的話 (transcript)。
2. 判斷使用者的意圖與對應操作代碼 (action) 及參數 (action_params)。
   支援的 action 代碼包含：
   【手錶本機硬體控制】
   - FLASHLIGHT_ON (打開手電筒 / 開啟照明)
   - FLASHLIGHT_OFF (關閉手電筒)
   - WATCH_VOLUME_UP (手錶音量調大)
   - WATCH_VOLUME_DOWN (手錶音量調小)
   - WATCH_MUTE (手錶靜音)
   - WATCH_VIBRATE (切換手錶為震動模式)
   - SET_TIMER (倒數計時，需附帶 action_params: {"minutes": 整數, "seconds": 整數})
   - CANCEL_TIMER (取消倒數計時)
   - SET_ALARM (設定手錶鬧鐘，需附帶 action_params: {"hour": 整數, "minute": 整數, "title": "名稱"})
   - GET_BATTERY_STATUS (查詢手錶電量或續航)
   - GET_HEART_RATE (查詢目前心率或心跳)
   - GET_STEP_COUNT (查詢今日步數或運動進度)
   - OPEN_APP (開啟/打開手錶內已安裝的應用程式，例如「打開 Spotify」、「開啟設定」、「打開三星健康」、「打開地圖」等，需附帶 action_params: {"app_name": "App名稱"})
   - INTRODUCE_CAPABILITIES (當詢問你能做什麼/有什麼功能時，請熱情精簡地介紹手電筒、心跳/步數/電量、計時器、鬧鐘、開啟應用與電腦遙控)
   
   【Windows 電腦遠端遙控】
   - TYPE_TEXT (在電腦當前游標處打字/輸入文字。當使用者要求「打字」、「輸入」、「在電腦打...」或要求文字鍵入時使用，需附帶 action_params: {"text": "要打在電腦上的純文字內容"})
   - MUTE_TOGGLE (電腦靜音 / 取消靜音)
   - VOLUME_UP (電腦音量加大)
   - VOLUME_DOWN (電腦音量降低)
   - PLAY_PAUSE (電腦播放 / 暫停音樂或影片)
   - NEXT_TRACK (下一首 / 簡報下一頁)
   - PREV_TRACK (上一首 / 簡報上一頁)
   - LOCK_PC (鎖定電腦)
   - SHOW_DESKTOP (顯示電腦桌面)
   - OPEN_NOTEPAD (打開記事本)
   - OPEN_CALC (打開計算機)
   
   【雜音與非語音過濾守則 (極重要)】
   - 若音訊僅為環境噪音、衣服摩擦、抓頭髮聲、麥克風刮擦、碰撞聲、咳嗽、呼吸聲或無清晰語音指令：
     * 切勿猜測或強行腦補指令（嚴禁將摩擦雜音誤判為開手電筒、開應用程式或打字）！
     * transcript 請固定填寫 ""（空字串）
     * action 請固定填寫 "NONE"
     * action_params 請固定填寫 {}
     * reply 請固定填寫 ""（空字串）

3. 給予繁體中文回答 (reply)。
   - 語氣自然、親切、口語化，適合在智慧手錶小螢幕閱讀與手錶揚聲器語音朗讀（繁體中文，約 20~45 個字，重點清晰）。
   - 如果是打字指令 (TYPE_TEXT)，回答例如：「已為您輸入文字！」
   - 如果是開啟 App (OPEN_APP)，回答例如：「正在為您開啟「App名稱」...」。
   - 如果是電腦指令，回答例如：「已為您靜音電腦」、「已加大音量」。
   - 如果是手錶指令，回答例如：「已為您打開手電筒」、「已開始倒數計時」。
   - 如果是資料查詢，直接回答精確重點。

請務必嚴格輸出符合以下結構的 JSON：
{
  "transcript": "使用者說的原始文字",
  "action": "ACTION_CODE",
  "action_params": {},
  "reply": "繁體中文回覆"
}
"""

def execute_ai_action(action_code: str, action_params: dict = None, transcript: str = ""):
    if not action_code or action_code == "NONE":
        return None
    code = action_code.strip().upper()
    if code == "TYPE_TEXT":
        text_to_type = ""
        if action_params and isinstance(action_params, dict):
            text_to_type = str(action_params.get("text", "")).strip()
        if not text_to_type and transcript:
            text_to_type = transcript.strip()
            for prefix in ["打字：", "打字:", "打字 ", "輸入：", "輸入:", "輸入 ", "打出：", "打出:"]:
                if text_to_type.startswith(prefix):
                    text_to_type = text_to_type[len(prefix):].strip()
                    break
        type_text_to_pc(text_to_type)
        return f"打字輸入: {text_to_type[:30]}"
    elif code in ["MUTE_TOGGLE", "MUTE"]:
        return execute_action("SYSTEM_VOLUME", "MUTE_TOGGLE")
    elif code in ["VOLUME_UP", "VOL_UP"]:
        return execute_action("SYSTEM_VOLUME", "VOLUME_UP")
    elif code in ["VOLUME_DOWN", "VOL_DOWN"]:
        return execute_action("SYSTEM_VOLUME", "VOLUME_DOWN")
    elif code in ["PLAY_PAUSE", "PLAY", "PAUSE"]:
        return execute_action("MEDIA", "PLAY_PAUSE")
    elif code in ["NEXT_TRACK", "PPT_NEXT"]:
        return execute_action("MEDIA", "PPT_NEXT")
    elif code in ["PREV_TRACK", "PPT_PREV"]:
        return execute_action("MEDIA", "PPT_PREV")
    elif code in ["LOCK_PC", "LOCK"]:
        return execute_action("SYSTEM_ACTION", "LOCK_PC")
    elif code in ["SHOW_DESKTOP", "DESKTOP"]:
        return execute_action("SYSTEM_ACTION", "SHOW_DESKTOP")
    elif code in ["OPEN_NOTEPAD", "NOTEPAD"]:
        return execute_action("CMD", "notepad.exe")
    elif code in ["OPEN_CALC", "CALC", "CALCULATOR"]:
        return execute_action("CMD", "calc.exe")
    elif code in ["FLASHLIGHT_ON", "FLASHLIGHT_OFF", "WATCH_VOLUME_UP", "WATCH_VOLUME_DOWN", "WATCH_MUTE", "WATCH_VIBRATE", "SET_TIMER", "CANCEL_TIMER", "SET_ALARM", "GET_BATTERY_STATUS", "GET_HEART_RATE", "GET_STEP_COUNT", "OPEN_APP", "INTRODUCE_CAPABILITIES"]:
        return f"手錶指令: {code}"
    return None

def call_gemini_api(parts: list, api_key: str, model: str = "gemini-3.5-flash-lite") -> dict:
    if not api_key:
        return {
            "error": True,
            "transcript": "",
            "action": "NONE",
            "reply": "尚未設定 Gemini API 金鑰，請先在控制台輸入 Key。"
        }
        
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    payload = {
        "contents": [
            {
                "parts": parts
            }
        ],
        "generationConfig": {
            "temperature": 0.2,
            "response_mime_type": "application/json"
        }
    }
    
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            resp_bytes = resp.read()
            res_data = json.loads(resp_bytes.decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code in (404, 503) and model != "gemini-3.5-flash-lite":
            # Auto-fallback to active gemini-3.5-flash-lite if selected model is retired or overloaded
            return call_gemini_api(parts, api_key, model="gemini-3.5-flash-lite")
            
        err_msg = ""
        try:
            err_body = e.read().decode("utf-8", errors="ignore")
            err_json = json.loads(err_body)
            err_msg = err_json.get("error", {}).get("message", err_body)
        except Exception:
            err_msg = str(e)
            
        if "API_KEY_INVALID" in err_msg or "API key not valid" in err_msg:
            friendly = "Gemini API 金鑰無效，請至控制台「🤖 Gemini AI 設定」輸入正確的金鑰。"
        elif "Resource has been exhausted" in err_msg or "Quota exceeded" in err_msg:
            friendly = "Gemini API 請求配額已耗盡，請稍後再試或更換金鑰。"
        elif "models/" in err_msg and "not found" in err_msg:
            friendly = f"模型 {model} 不可用或已退役，請切換其他模型 (例如 gemini-3.5-flash-lite)。"
        elif e.code == 503:
            friendly = f"Google 伺服器忙碌中 (503)，請稍後再試。"
        else:
            friendly = f"Google Gemini 錯誤 ({e.code}): {err_msg[:120]}"
            
        return {
            "error": True,
            "transcript": "API 請求失敗",
            "action": "NONE",
            "reply": friendly
        }
    except urllib.error.URLError as e:
        return {
            "error": True,
            "transcript": "網路連線失敗",
            "action": "NONE",
            "reply": f"無法連線至 Google 伺服器，請檢查網路: {e.reason}"
        }
    except Exception as e:
        return {
            "error": True,
            "transcript": "呼叫出錯",
            "action": "NONE",
            "reply": f"Gemini 呼叫異常: {str(e)}"
        }
        
    candidates = res_data.get("candidates", [])
    if candidates:
        content = candidates[0].get("content", {})
        parts_list = content.get("parts", [])
        if parts_list:
            raw_text = parts_list[0].get("text", "")
            try:
                clean_text = raw_text.strip()
                if clean_text.startswith("```json"):
                    clean_text = clean_text[7:]
                elif clean_text.startswith("```"):
                    clean_text = clean_text[3:]
                if clean_text.endswith("```"):
                    clean_text = clean_text[:-3]
                clean_text = clean_text.strip()
                
                parsed = json.loads(clean_text)
                if isinstance(parsed, dict):
                    return {
                        "transcript": str(parsed.get("transcript", "")),
                        "action": str(parsed.get("action", "NONE")),
                        "action_params": parsed.get("action_params", {}),
                        "reply": str(parsed.get("reply", ""))
                    }
            except Exception:
                return {
                    "transcript": "提問解析",
                    "action": "NONE",
                    "reply": raw_text.strip() or "處理完成，但未收到具體內容。"
                }
                
    prompt_feedback = res_data.get("promptFeedback", {})
    block_reason = prompt_feedback.get("blockReason")
    if block_reason:
        return {
            "error": True,
            "transcript": "內容遭攔截",
            "action": "NONE",
            "reply": f"回答內容被安全機制攔截: {block_reason}"
        }

    return {"transcript": "語音解析失敗", "action": "NONE", "reply": "抱歉，目前無法理解這段語音。"}

@app.get("/api/ai/config")
async def get_ai_config():
    cfg = load_config()
    key = (cfg.get("gemini_api_key") or os.environ.get("GEMINI_API_KEY", "")).strip()
    masked_key = (key[:6] + "..." + key[-4:]) if len(key) > 10 else ("已設定" if key else "")
    return {
        "has_key": bool(key),
        "masked_key": masked_key,
        "model": cfg.get("gemini_model", "gemini-3.5-flash-lite")
    }

@app.post("/api/ai/config")
async def save_ai_config(request: Request):
    data = await request.json()
    cfg = load_config()
    if "gemini_api_key" in data:
        cfg["gemini_api_key"] = data["gemini_api_key"].strip()
    if "gemini_model" in data and data["gemini_model"].strip():
        cfg["gemini_model"] = data["gemini_model"].strip()
    save_config(cfg)
    await broadcast_log("AI", f"已更新 Gemini 設定: 模型 {cfg.get('gemini_model')}")
    
    if watch_websocket is not None:
        try:
            await watch_websocket.send_text(json.dumps({
                "type": "SYNC_AI_CONFIG",
                "api_key": cfg.get("gemini_api_key", ""),
                "model": cfg.get("gemini_model", "gemini-3.5-flash-lite")
            }))
            await broadcast_log("AI", "已自動將最新 Gemini 金鑰同步至手錶！")
        except Exception as e:
            await broadcast_log("ERROR", f"同步 AI 設定給手錶失敗: {e}")
            
    return {"status": "OK"}

DICTATION_SYSTEM_INSTRUCTION = """
你是一個專為智慧手錶設計的極速語音輸入聽寫引擎 (WristHub Voice Dictation)。
使用者說了一段要直接打入電腦游標處的文字。
請完成以下任務：
1. 完整精確辨識使用者說的話 (transcript)，並加上適當的標點符號。
2. 固定將 action 設為 "TYPE_TEXT"，並將 action_params 設為 {"text": transcript}。
3. reply 固定回答 "已在電腦輸入文字！"。

請務必嚴格輸出符合以下結構的 JSON：
{
  "transcript": "辨識後的文字",
  "action": "TYPE_TEXT",
  "action_params": {
    "text": "辨識後的文字"
  },
  "reply": "已在電腦輸入文字！"
}
"""

@app.post("/api/ai/voice")
async def process_ai_voice(file: UploadFile = File(...), dictation: bool = False):
    cfg = load_config()
    api_key = (cfg.get("gemini_api_key") or os.environ.get("GEMINI_API_KEY", "")).strip()
    model = cfg.get("gemini_model", "gemini-3.5-flash-lite")
    
    if not api_key:
        msg = "請至電腦 Web 控制台 (http://localhost:8765) 的「🤖 Gemini AI 設定」輸入您的 API 金鑰！"
        await broadcast_log("AI", "收到語音但未設定 Gemini API Key")
        return JSONResponse({
            "status": "NO_KEY",
            "transcript": "(尚未設定金鑰)",
            "reply": msg,
            "action": "NONE",
            "action_params": {},
            "action_result": None
        })
        
    try:
        audio_bytes = await file.read()
        mime_type = file.content_type or "audio/mp4"
        if not mime_type or mime_type == "application/octet-stream":
            mime_type = "audio/mp4"
            
        mode_label = "【聽寫模式】" if dictation else ""
        await broadcast_log("AI", f"接收到手錶語音音訊 ({len(audio_bytes)} bytes) {mode_label}，正在請求 Gemini ({model}) 解析...")
        
        b64_audio = base64.b64encode(audio_bytes).decode("utf-8")
        system_prompt = DICTATION_SYSTEM_INSTRUCTION if dictation else AI_SYSTEM_INSTRUCTION
        parts = [
            {
                "inline_data": {
                    "mime_type": mime_type,
                    "data": b64_audio
                }
            },
            {
                "text": system_prompt
            }
        ]
        
        t0 = datetime.datetime.now()
        ai_res = call_gemini_api(parts, api_key, model)
        elapsed_ms = (datetime.datetime.now() - t0).total_seconds() * 1000
        
        transcript = (ai_res.get("transcript") or "").strip()
        action = (ai_res.get("action") or "NONE").strip()
        action_params = ai_res.get("action_params") or {}
        reply = (ai_res.get("reply") or "").strip()

        # 雜音防禦：若識別結果為空字串或雜音且動作為 NONE，靜默結束，不發送卡片亦不報錯
        if not transcript or transcript == "(雜音)":
            if action == "NONE":
                await broadcast_log("AI", f"🤫 偵測到環境摩擦雜音/非語音，已安靜過濾 (耗時 {elapsed_ms:.0f}ms)")
                return {
                    "status": "SUCCESS",
                    "transcript": "",
                    "reply": "",
                    "action": "NONE",
                    "action_params": {},
                    "action_result": None
                }
        
        if ai_res.get("error"):
            await broadcast_log("AI", f"❌ 手錶語音 Gemini 失敗: {reply}")
            return JSONResponse({
                "status": "ERROR",
                "transcript": transcript or "語音解析失敗",
                "reply": reply,
                "action": "NONE",
                "action_params": {},
                "action_result": None
            })
            
        action_result = execute_ai_action(action, action_params, transcript)
        
        await broadcast_log("AI", f"🗣️ [{transcript}] -> 🤖 {reply} (動作: {action_result or action}, 耗時 {elapsed_ms:.0f}ms)")
        
        # Broadcast conversation card to web dashboard
        card_payload = json.dumps({
            "type": "AI_CARD",
            "transcript": transcript,
            "reply": reply,
            "action": action,
            "action_params": action_params,
            "action_result": action_result,
            "time": datetime.datetime.now().strftime("%H:%M:%S")
        })
        for ws in dashboard_websockets:
            try:
                await ws.send_text(card_payload)
            except Exception:
                pass
                
        return {
            "status": "SUCCESS",
            "transcript": transcript,
            "reply": reply,
            "action": action,
            "action_params": action_params,
            "action_result": action_result
        }
    except Exception as e:
        err_msg = f"Gemini 處理失敗: {str(e)}"
        await broadcast_log("AI", f"❌ {err_msg}")
        return JSONResponse({
            "status": "ERROR",
            "transcript": "處理出錯",
            "reply": f"連線異常: {str(e)[:60]}",
            "action": "NONE",
            "action_result": None
        })

@app.post("/api/ai/text")
async def process_ai_text(request: Request):
    try:
        cfg = load_config()
        api_key = (cfg.get("gemini_api_key") or os.environ.get("GEMINI_API_KEY", "")).strip()
        model = cfg.get("gemini_model", "gemini-3.5-flash-lite")
        data = await request.json()
        prompt = data.get("prompt", "").strip()
        
        if not prompt:
            return JSONResponse({
                "status": "ERROR",
                "transcript": "",
                "reply": "提問內容不能為空！",
                "action": "NONE",
                "action_result": None
            })
            
        if not api_key:
            return JSONResponse({
                "status": "NO_KEY",
                "transcript": prompt,
                "reply": "尚未設定 Gemini API Key，請先在上方輸入金鑰並點擊儲存！",
                "action": "NONE",
                "action_result": None
            })
            
        parts = [
            {"text": f"使用者指令/提問：{prompt}"},
            {"text": AI_SYSTEM_INSTRUCTION}
        ]
        ai_res = call_gemini_api(parts, api_key, model)
        
        action = ai_res.get("action", "NONE")
        action_params = ai_res.get("action_params", {})
        transcript = ai_res.get("transcript") or prompt
        action_result = execute_ai_action(action, action_params, transcript)
        reply = ai_res.get("reply", "")
        
        if ai_res.get("error"):
            await broadcast_log("AI", f"❌ Gemini 錯誤: {reply}")
            return JSONResponse({
                "status": "ERROR",
                "transcript": transcript,
                "reply": reply,
                "action": "NONE",
                "action_result": None
            })
            
        await broadcast_log("AI", f"Web 測試 🗣️ [{transcript}] -> 🤖 {reply} (動作: {action_result or action})")
        return {
            "status": "SUCCESS",
            "transcript": transcript,
            "reply": reply,
            "action": action,
            "action_result": action_result
        }
    except Exception as e:
        await broadcast_log("AI", f"❌ process_ai_text 異常: {e}")
        return JSONResponse({
            "status": "ERROR",
            "transcript": prompt if 'prompt' in locals() else "",
            "reply": f"系統錯誤: {str(e)}",
            "action": "NONE",
            "action_result": None
        })

@app.websocket("/ws/dashboard")
async def dashboard_endpoint(websocket: WebSocket):
    await websocket.accept()
    dashboard_websockets.append(websocket)
    await websocket.send_text(json.dumps({
        "type": "STATUS",
        "watch_connected": watch_state["connected"],
        "watch_ip": watch_state["ip"],
        "watch_battery": watch_state["battery"]
    }))
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        if websocket in dashboard_websockets:
            dashboard_websockets.remove(websocket)

@app.websocket("/")
async def watch_endpoint(websocket: WebSocket):
    global watch_websocket
    await websocket.accept()
    watch_websocket = websocket
    
    client_ip = websocket.client.host if websocket.client else "Unknown"
    watch_state["connected"] = True
    watch_state["ip"] = client_ip
    
    await broadcast_log("CONNECT", f"Galaxy Watch 已連線！來源 IP: {client_ip}")
    await broadcast_status()
    
    # Send both button config and watchface config right on connect
    cfg = load_config()
    await websocket.send_text(json.dumps({"type": "CONFIG", "buttons": cfg.get("buttons", [])}))
    
    # Send Gemini AI config for standalone direct mode
    ai_key = (cfg.get("gemini_api_key") or "").strip()
    ai_model = cfg.get("gemini_model", "gemini-3.5-flash-lite")
    if ai_key:
        await websocket.send_text(json.dumps({
            "type": "SYNC_AI_CONFIG",
            "api_key": ai_key,
            "model": ai_model
        }))
    
    wf_cfg = load_wf_config()
    bg_url = f"http://{get_local_ip()}:8765/static/custom_bg.webp" if wf_cfg.get("has_custom_bg") else None
    await websocket.send_text(json.dumps({
        "type": "WATCHFACE_UPDATE",
        "config": wf_cfg,
        "bg_url": bg_url
    }))
    
    try:
        while True:
            msg_text = await websocket.receive_text()
            t_start = datetime.datetime.now()
            try:
                data = json.loads(msg_text)
                action = data.get("action", "")
                
                if action == "GET_CONFIG":
                    current_cfg = load_config()
                    await websocket.send_text(json.dumps({"type": "CONFIG", "buttons": current_cfg.get("buttons", [])}))
                    curr_ai_key = (current_cfg.get("gemini_api_key") or "").strip()
                    curr_ai_model = current_cfg.get("gemini_model", "gemini-3.5-flash-lite")
                    if curr_ai_key:
                        await websocket.send_text(json.dumps({
                            "type": "SYNC_AI_CONFIG",
                            "api_key": curr_ai_key,
                            "model": curr_ai_model
                        }))
                    current_wf = load_wf_config()
                    b_url = f"http://{get_local_ip()}:8765/static/custom_bg.webp" if current_wf.get("has_custom_bg") else None
                    await websocket.send_text(json.dumps({"type": "WATCHFACE_UPDATE", "config": current_wf, "bg_url": b_url}))
                elif action == "BUTTON_CLICK":
                    btn_id = data.get("id", "")
                    action_id = data.get("actionId", "")
                    current_cfg = load_config()
                    btn = next((b for b in current_cfg.get("buttons", []) if b["id"] == btn_id), None)
                    
                    if btn:
                        res = execute_action(btn.get("action_type", ""), btn.get("action_val", ""))
                        latency = (datetime.datetime.now() - t_start).total_seconds() * 1000
                        await broadcast_log("CLICK", f"按下 [{btn.get('icon')} {btn.get('label')}] -> {res} (耗時 {latency:.1f}ms)")
                    else:
                        res = execute_action("MEDIA", action_id or btn_id)
                        await broadcast_log("CLICK", f"按下未知按鍵 {btn_id} -> {res}")
                elif action == "ROTARY_SCROLL":
                    delta = data.get("delta", 0)
                    if delta > 0:
                        send_key(VK_VOLUME_UP)
                        await broadcast_log("ROTARY", f"旋轉錶圈順時針 (Delta: +{delta}) -> 音量 +2%")
                    else:
                        send_key(VK_VOLUME_DOWN)
                        await broadcast_log("ROTARY", f"旋轉錶圈逆時針 (Delta: {delta}) -> 音量 -2%")
                elif action == "BATTERY_UPDATE":
                    level = data.get("level", 100)
                    watch_state["battery"] = level
                    await broadcast_status()
                    await broadcast_log("BATTERY", f"手錶電量上報: {level}% ⚡")
                elif action == "BG_DOWNLOAD_SUCCESS":
                    await broadcast_log("WATCHFACE", "手錶已成功透過協程完成自訂背景下載並存入本機儲存空間！")
                elif action == "TYPE_TEXT":
                    text_to_type = data.get("text", "")
                    type_text_to_pc(text_to_type)
                    await broadcast_log("TYPE", f"⌨️ 手錶無線打字: {text_to_type[:40]}")
                else:
                    res = execute_action("SYSTEM_VOLUME", action)
                    await broadcast_log("CLICK", f"手錶傳統指令: {action} -> {res}")
                    
                await websocket.send_text(json.dumps({"status": "OK"}))
            except json.JSONDecodeError:
                res = execute_action("SYSTEM_VOLUME", msg_text)
                await broadcast_log("CLICK", f"手錶純文字指令: {msg_text} -> {res}")
                await websocket.send_text(json.dumps({"status": "OK"}))
    except WebSocketDisconnect:
        watch_state["connected"] = False
        watch_websocket = None
        await broadcast_log("CONNECT", f"Galaxy Watch 已斷開連線 ({client_ip})")
        await broadcast_status()

if __name__ == "__main__":
    local_ip = get_local_ip()
    print("=" * 60, flush=True)
    print("🚀 WristHub FastAPI 伺服器啟動中...", flush=True)
    print(f"💻 本機控制台網址: http://localhost:8765", flush=True)
    print(f"📡 區域網路 IP: {local_ip}:8765", flush=True)
    print(f"⌚ 手錶通訊端點: ws://0.0.0.0:8765", flush=True)
    print("=" * 60, flush=True)
    uvicorn.run(app, host="0.0.0.0", port=8765, log_level="warning")
