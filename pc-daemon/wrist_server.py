import os
import json
import ctypes
import datetime
import subprocess
import asyncio
from typing import List, Optional
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
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
CONFIG_FILE = os.path.join(CURRENT_DIR, "config.json")
INDEX_FILE = os.path.join(CURRENT_DIR, "index.html")

app = FastAPI(title="WristHub Companion Server")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

dashboard_websockets: List[WebSocket] = []
watch_websocket: Optional[WebSocket] = None

watch_state = {
    "connected": False,
    "ip": None,
    "battery": None
}

def load_config():
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, "r", encoding="utf-8-sig") as f:
            return json.load(f)
    return {"buttons": []}

def save_config(data):
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
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
    
    # Notify watch to reload dynamic buttons immediately
    if watch_websocket is not None:
        try:
            config_payload = json.dumps({"type": "CONFIG", "buttons": data.get("buttons", [])})
            await watch_websocket.send_text(config_payload)
            await broadcast_log("CONFIG", "已即時推送最新按鍵配置給 Galaxy Watch！")
        except Exception as e:
            await broadcast_log("ERROR", f"推送給手錶失敗: {e}")
            
    return {"status": "OK", "msg": "Config saved and synced"}

@app.websocket("/ws/dashboard")
async def dashboard_endpoint(websocket: WebSocket):
    await websocket.accept()
    dashboard_websockets.append(websocket)
    # Send current status immediately
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
    
    # Send config to watch right on connect
    cfg = load_config()
    await websocket.send_text(json.dumps({"type": "CONFIG", "buttons": cfg.get("buttons", [])}))
    
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
                    await broadcast_log("CONFIG", "手錶請求按鍵配置，已下發最新配置")
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
                else:
                    # Legacy fallback
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
    print("=" * 60, flush=True)
    print("🚀 WristHub FastAPI 伺服器啟動中...", flush=True)
    print("💻 控制台網址: http://localhost:8765", flush=True)
    print("📡 手錶通訊埠: ws://0.0.0.0:8765", flush=True)
    print("=" * 60, flush=True)
    uvicorn.run(app, host="0.0.0.0", port=8765, log_level="warning")
