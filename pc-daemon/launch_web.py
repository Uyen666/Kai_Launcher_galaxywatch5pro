import os
import sys
import time
import socket
import webbrowser
import subprocess

# 當透過 pythonw.exe 執行時，防止 sys.stdout 為 None 拋出例外
if sys.stdout is None:
    sys.stdout = open(os.devnull, "w", encoding="utf-8")
if sys.stderr is None:
    sys.stderr = open(os.devnull, "w", encoding="utf-8")

def is_port_in_use(port: int = 8765) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.5)
        return s.connect_ex(('127.0.0.1', port)) == 0

def main():
    current_dir = os.path.dirname(os.path.abspath(__file__))
    server_script = os.path.join(current_dir, "wrist_server.py")
    
    # 確保 server 使用 python.exe 以便在獨立終端機視窗中顯示日誌
    python_exe = sys.executable
    if python_exe.lower().endswith("pythonw.exe"):
        py_normal = os.path.join(os.path.dirname(python_exe), "python.exe")
        if os.path.exists(py_normal):
            python_exe = py_normal
    
    if not is_port_in_use(8765):
        print("=" * 60)
        print("  正在啟動 WristHub PC 伺服器...")
        print("=" * 60)
        
        # 建立獨立的 Console 視窗執行伺服器 (並強制 UTF-8 編碼)
        env = os.environ.copy()
        env["PYTHONUTF8"] = "1"
        env["PYTHONIOENCODING"] = "utf-8"
        subprocess.Popen(
            [python_exe, server_script],
            cwd=current_dir,
            env=env,
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
