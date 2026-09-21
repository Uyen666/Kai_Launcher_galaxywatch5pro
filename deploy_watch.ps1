[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$Release,
    [switch]$Logcat
)

$ErrorActionPreference = "Stop"

# 1. Locate ADB
$adb = "C:\Users\rock9\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) {
        $adb = $cmd.Source
    }
}

if (-not $adb -or -not (Test-Path $adb)) {
    Write-Host "Error: Cannot find adb.exe. Please ensure Android SDK platform-tools is installed." -ForegroundColor Red
    exit 1
}

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "   WristHub Deploy Tool (Galaxy Watch 5 Pro) " -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan

# 2. Check Watch Connection
Write-Host "[1/4] Checking watch connection..." -ForegroundColor Yellow
$deviceConfigFile = Join-Path $PSScriptRoot ".last_watch_ip"
if (Test-Path $deviceConfigFile) {
    $lastDevice = (Get-Content $deviceConfigFile -Raw).Trim()
    if ($lastDevice) {
        Write-Host "Trying auto-connect to last known watch ($lastDevice)..." -ForegroundColor Gray
        & $adb connect $lastDevice | Out-Null
    }
}

$deviceList = & $adb devices
$connected = $false
foreach ($line in ($deviceList -split "`r?`n")) {
    if ($line -match "^\s*([^\s]+)\s+device$") {
        $activeDevice = $matches[1]
        Write-Host "Found device: $activeDevice" -ForegroundColor Green
        $connected = $true
        $activeDevice | Set-Content $deviceConfigFile
        break
    }
}

if (-not $connected) {
    Write-Host "[!] No watch detected via ADB." -ForegroundColor Yellow
    Write-Host "    On Galaxy Watch: Settings -> Developer options -> Wireless debugging" -ForegroundColor Gray
    $ans = Read-Host "Connect to watch now via IP:port? (y/N)"
    if ($ans -eq "y" -or $ans -eq "Y") {
        $ip = Read-Host "Enter watch IP and Port (e.g. 192.168.0.103:41117)"
        if ($ip) {
            & $adb connect $ip
            $deviceList = & $adb devices
            foreach ($line in ($deviceList -split "`r?`n")) {
                if ($line -match "^\s*([^\s]+)\s+device$") {
                    $connected = $true
                    $matches[1] | Set-Content $deviceConfigFile
                }
            }
        }
    }
    if (-not $connected) {
        Write-Host "No device connected. Exiting deployment." -ForegroundColor Yellow
        exit 0
    }
}

# 3. Build APK
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$flavor = if ($Release) { "release" } else { "debug" }
$task = if ($Release) { "assembleRelease" } else { "assembleDebug" }
$apkName = if ($Release) { "app-release.apk" } else { "app-debug.apk" }

if (-not $SkipBuild) {
    Write-Host "[2/4] Building Wear OS APK ($task)..." -ForegroundColor Yellow
    $gradlew = Join-Path $projectRoot "gradlew.bat"
    & $gradlew $task
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Build failed! Please check Gradle errors above." -ForegroundColor Red
        exit 1
    }
    Write-Host "[OK] Build succeeded!" -ForegroundColor Green
} else {
    Write-Host "[2/4] Skipping build, using existing $flavor APK ($apkName)." -ForegroundColor Gray
}

$apkPath = Join-Path $projectRoot "app\build\outputs\apk\$flavor\$apkName"
if (-not (Test-Path $apkPath)) {
    Write-Host "Error: APK not found at $apkPath" -ForegroundColor Red
    exit 1
}

# 4. Install APK
Write-Host "[3/4] Installing APK to watch..." -ForegroundColor Yellow
& $adb -s $activeDevice install -r $apkPath
if ($LASTEXITCODE -ne 0) {
    Write-Host "Install failed. Make sure watch is paired and authorized." -ForegroundColor Red
    exit 1
}
Write-Host "[OK] Installation successful!" -ForegroundColor Green

# 5. Launch Launcher
Write-Host "[4/4] Launching WristHub Launcher..." -ForegroundColor Yellow
& $adb -s $activeDevice shell am start -n com.wristhub.launcher/.presentation.MainActivity
try {
    & $adb -s $activeDevice shell cmd package set-home-activity com.wristhub.launcher/.presentation.MainActivity | Out-Null
} catch {}
Write-Host "[Done] WristHub Launcher started on watch!" -ForegroundColor Green

# 6. Automatically sync PC IP to Watch (Dynamically detect active IP, works across Dorm, Home, Hotspot)
$pcIp = $null
try {
    $watchHost = ($activeDevice -split ":")[0]
    $route = Get-NetRoute -DestinationPrefix "$watchHost/32" -ErrorAction SilentlyContinue
    if (-not $route) {
        $route = Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue | Sort-Object RouteMetric | Select-Object -First 1
    }
    if ($route) {
        $pcIp = (Get-NetIPAddress -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object -First 1).IPAddress
    }
} catch {}

if (-not $pcIp) {
    $pcIp = (Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias "Wi-Fi*" -ErrorAction SilentlyContinue | Select-Object -First 1).IPAddress
}

if ($pcIp) {
    Start-Sleep -Milliseconds 800
    Write-Host "Syncing dynamic PC IP ($pcIp) to watch..." -ForegroundColor Cyan
    & $adb -s $activeDevice shell am broadcast -a com.wristhub.SET_PC_IP --es ip $pcIp
}

if ($Logcat) {
    Write-Host "Streaming watch logs (Press Ctrl+C to stop)..." -ForegroundColor Cyan
    & $adb -s $activeDevice logcat -s MainActivity WristHub VoiceDetector WakeAssistant WatchHardware
}
