import asyncio
import json
import ctypes
import datetime
import websockets

# Windows Virtual Key Codes
VK_VOLUME_MUTE = 0xAD
VK_VOLUME_DOWN = 0xAE
VK_VOLUME_UP = 0xAF
VK_MEDIA_NEXT_TRACK = 0xB0
VK_MEDIA_PREV_TRACK = 0xB1
VK_MEDIA_PLAY_PAUSE = 0xB3
VK_PRIOR = 0x21  # Page Up
VK_NEXT = 0x22   # Page Down

user32 = ctypes.windll.user32

def send_key(vk_code):
    user32.keybd_event(vk_code, 0, 0, 0)
    user32.keybd_event(vk_code, 0, 2, 0)

def handle_action(action: str) -> str:
    now_str = datetime.datetime.now().strftime("%H:%M:%S")
    print(f"[{now_str}] ⌚ 收到手錶指令: {action}", flush=True)
    
    if action == "VOLUME_UP":
        send_key(VK_VOLUME_UP)
        return "音量增加"
    elif action == "VOLUME_DOWN":
        send_key(VK_VOLUME_DOWN)
        return "音量減少"
    elif action == "MUTE_TOGGLE":
        send_key(VK_VOLUME_MUTE)
        return "切換靜音"
    elif action == "PLAY_PAUSE":
        send_key(VK_MEDIA_PLAY_PAUSE)
        return "播放/暫停"
    elif action == "NEXT_TRACK":
        send_key(VK_MEDIA_NEXT_TRACK)
        return "下一首"
    elif action == "PREV_TRACK":
        send_key(VK_MEDIA_PREV_TRACK)
        return "上一首"
    elif action == "PPT_NEXT":
        send_key(VK_NEXT)
        return "簡報下一頁"
    elif action == "PPT_PREV":
        send_key(VK_PRIOR)
        return "簡報上一頁"
    elif action == "LOCK_PC":
        user32.LockWorkStation()
        return "已鎖定電腦"
    elif action == "AI_VOICE_START":
        return "正在聆聽..."
    elif action == "AI_VOICE_STOP":
        return "AI 分析完成：已收到指令！"
    else:
        return f"未知指令: {action}"

async def handler(websocket):
    client_ip = websocket.remote_address[0]
    print(f"\n[+] ⌚ Galaxy Watch 已連線！來源 IP: {client_ip}", flush=True)
    await websocket.send(json.dumps({"status": "CONNECTED", "msg": "Wrist Hub Server Ready"}))
    
    try:
        async for message in websocket:
            try:
                data = json.loads(message)
                action = data.get("action", "")
                result = handle_action(action)
                await websocket.send(json.dumps({"status": "OK", "result": result}))
            except json.JSONDecodeError:
                result = handle_action(message)
                await websocket.send(result)
    except websockets.exceptions.ConnectionClosed:
        print(f"[-] ⌚ Galaxy Watch 已中斷連線 ({client_ip})", flush=True)

async def main():
    port = 8765
    print("=" * 55, flush=True)
    print(f"🚀 Wrist Hub PC 遙控背景伺服器已啟動！", flush=True)
    print(f"📡 監聽通訊埠: {port}", flush=True)
    print(f"💻 本機 IP: 192.168.0.109", flush=True)
    print("=" * 55, flush=True)
    
    async with websockets.serve(handler, "0.0.0.0", port):
        await asyncio.Future()  # run forever

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n伺服器已停止。")
