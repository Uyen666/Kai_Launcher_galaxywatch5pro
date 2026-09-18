@echo off
chcp 65001 >nul
title WristHub Dashboard Launcher

cd /d "%~dp0"

:: 檢查 8765 埠是否已被監聽
netstat -ano | findstr /R /C:":8765.*LISTENING" >nul
if %errorlevel% neq 0 (
    echo ============================================================
    echo  🚀 正在啟動 WristHub PC 伺服器...
    echo ============================================================
    start "WristHub Server" cmd /k "python wrist_server.py"
    timeout /t 2 /nobreak >nul
) else (
    echo [OK] WristHub 伺服器已在運行中。
)

:: 打開預設瀏覽器進入 Web 後台
echo 正在開啟瀏覽器: http://localhost:8765 ...
start "" "http://localhost:8765"

timeout /t 1 >nul
exit
