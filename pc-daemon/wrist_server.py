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

user32 = ctypes.windll.user32

def send_key(vk_code):
    user32.keybd_event(vk_code, 0, 0, 0)
    user32.keybd_event(vk_code, 0, 2, 0)

def show_desktop():
    user32.keybd_event(VK_LWIN, 0, 0, 0)
    user32.keybd_event(VK_D, 0, 0, 0)
    user32.keybd_event(VK_D, 0, 2, 0)
    user32.keybd_event(VK_LWIN, 0, 2, 0)

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
