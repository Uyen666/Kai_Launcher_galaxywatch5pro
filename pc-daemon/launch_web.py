import os
import sys
import time
import socket
import webbrowser
import subprocess

def is_port_in_use(port: int = 8765) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.5)
        return s.connect_ex(('127.0.0.1', port)) == 0

def main():
    current_dir = os.path.dirname(os.path.abspath(__file__))
    server_script = os.path.join(current_dir, "wrist_server.py")
    
    if not is_port_in_use(8765):
        print("=" * 60)
        print("  正在啟動 WristHub PC 伺服器...")
        print("=" * 60)
        
        # 建立獨立的 Console 視窗執行伺服器
        subprocess.Popen(
            [sys.executable, server_script],
            cwd=current_dir,
            creationflags=subprocess.CREATE_NEW_CONSOLE
        )
        
        # 等待伺服器 Port 8765 真正就緒 (最多等 6 秒)
        for _ in range(20):
            time.sleep(0.3)
            if is_port_in_use(8765):
                break
    else:
        print("[*] WristHub 伺服器已在運行中。")
    
    # 伺服器就緒後開啟瀏覽器
    print("[*] 正在開啟瀏覽器: http://localhost:8765")
    webbrowser.open("http://localhost:8765")

if __name__ == "__main__":
    main()
