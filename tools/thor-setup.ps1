# REDLINE + Capri: one-shot setup on your Windows PC, with the AYN Thor over Wi-Fi ADB.
#
# Run in a normal PowerShell window (it asks for Administrator once, for the agent's firewall rules):
#   Set-ExecutionPolicy -Scope Process Bypass
#   irm https://raw.githubusercontent.com/ChadBorg/Prot-Virto/claude/awesome-planck-4vam9a/tools/thor-setup.ps1 -OutFile thor-setup.ps1
#   .\thor-setup.ps1
#
# What it does:
#   1. installs Git, GitHub CLI, Android platform-tools (adb) and Python with winget (only what's missing)
#   2. clones / updates this repo into $Dir
#   3. downloads the latest built APKs from GitHub Actions (asks you to log in to GitHub once)
#   4. pairs with the Thor (Wireless debugging), installs REDLINE + Capri, grants Capri's permissions
#   5. installs and starts the REDLINE agent on this PC and shows its pairing PIN
#   6. runs thor-check.ps1: a report of what's working
param(
    [string]$Dir = "$env:USERPROFILE\Thor",
    [string]$Branch = "claude/awesome-planck-4vam9a",
    [string]$PairAddress = "",   # the Thor's "Pair device with pairing code" IP:port
    [string]$PairCode = "",      # the 6-digit code shown with it
    [string]$Connect = "",       # the IP:port at the top of the Wireless debugging screen
    [switch]$SkipAgent,
    [switch]$SkipApks
)
# Continue, not Stop: Windows PowerShell 5.1 turns any stderr from adb/gh/git into a fatal error
$ErrorActionPreference = "Continue"
$Repo = "ChadBorg/Prot-Virto"

function Step($msg) { Write-Host "`n>> $msg" -ForegroundColor Red }
function Note($msg) { Write-Host "   $msg" -ForegroundColor DarkGray }
function Have($cmd) { [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }
function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
}
function Install-Pkg($id) {
    winget install -e --id $id --accept-source-agreements --accept-package-agreements --silent
    Refresh-Path
}

# ---- 1. tools ------------------------------------------------------------------------------
Step "Checking tools"
if (-not (Have "winget")) { throw "winget is missing: install 'App Installer' from the Microsoft Store, then run this again." }
if (-not (Have "git")) { Install-Pkg "Git.Git" }
if (-not (Have "gh")) { Install-Pkg "GitHub.cli" }
if (-not (Have "adb")) { Install-Pkg "Google.PlatformTools" }
$py = $null
try { $py = (& python --version 2>&1) -join " " } catch { $py = "" }
if ($py -notmatch "Python 3") { Install-Pkg "Python.Python.3.12" }
foreach ($cmd in "git", "gh", "adb") {
    if (-not (Have $cmd)) { throw "$cmd was installed but isn't on PATH yet: close this window, open a new one and run the script again." }
}
Note "git, gh, adb and python are ready"

# ---- 2. code -------------------------------------------------------------------------------
Step "Getting the code into $Dir"
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$code = Join-Path $Dir "Prot-Virto"
if (Test-Path (Join-Path $code ".git")) {
    git -C $code fetch origin $Branch
    git -C $code checkout $Branch
    git -C $code pull --ff-only origin $Branch
} else {
    git clone -b $Branch "https://github.com/$Repo" $code
}
if (-not (Test-Path (Join-Path $code "tools\thor-check.ps1"))) { throw "Getting the code failed (see above)." }

# ---- 3. APKs -------------------------------------------------------------------------------
$apkDir = Join-Path $Dir "apk"
if (-not $SkipApks) {
    Step "Downloading the latest APKs from GitHub Actions"
    gh auth status *> $null
    if ($LASTEXITCODE -ne 0) {
        Note "GitHub wants you logged in to download build artifacts (a browser window opens)."
        gh auth login --web -h github.com -p https
    }
    $run = gh run list -R $Repo -b $Branch -s success -L 1 --json databaseId -q ".[0].databaseId"
    if (-not $run) { throw "No successful build found on $Branch." }
    if (Test-Path $apkDir) { Remove-Item $apkDir -Recurse -Force }
    gh run download $run -R $Repo -n "REDLINE-apk" -D (Join-Path $apkDir "redline")
    gh run download $run -R $Repo -n "CapriThor-apk" -D (Join-Path $apkDir "capri")
    if (-not (Get-ChildItem $apkDir -Recurse -Filter *.apk)) { throw "The APK download failed (see above)." }
    Note "build $run downloaded"
}

# ---- 4. the Thor ---------------------------------------------------------------------------
Step "Connecting to the Thor"
function Connected { (adb devices) -match "`tdevice$" }
if (-not (Connected)) {
    if (-not $PairAddress) {
        Write-Host "   On the Thor: Settings > About > tap 'Build number' 7 times (Developer options)."
        Write-Host "   Then: Settings > System > Developer options > Wireless debugging > ON > 'Pair device with pairing code'."
        Write-Host "   (The Thor and this PC must be on the same Wi-Fi.)"
        $PairAddress = Read-Host "   IP address & port shown under the pairing code (e.g. 192.168.1.42:37123)"
        $PairCode = Read-Host "   Wi-Fi pairing code"
    }
    adb pair $PairAddress $PairCode
    if (-not $Connect) {
        $Connect = Read-Host "   Now the 'IP address & port' at the top of the Wireless debugging screen"
    }
    adb connect $Connect
    Start-Sleep 2
    if (-not (Connected)) { throw "The Thor didn't connect. Check Wireless debugging is still on and try again." }
}
$model = (adb shell getprop ro.product.model).Trim()
$android = (adb shell getprop ro.build.version.release).Trim()
Note "connected: $model (Android $android)"

if (-not $SkipApks) {
    Step "Installing REDLINE and Capri on the Thor"
    $red = Get-ChildItem (Join-Path $apkDir "redline") -Filter *.apk | Select-Object -First 1
    $cap = Get-ChildItem (Join-Path $apkDir "capri") -Filter *.apk | Select-Object -First 1
    adb install -r $red.FullName
    adb install -r $cap.FullName
}

Step "Giving Capri her permissions"
$p = "com.protvirto.capri"
adb shell appops set $p SYSTEM_ALERT_WINDOW allow
adb shell appops set $p GET_USAGE_STATS allow
adb shell appops set $p MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant $p android.permission.POST_NOTIFICATIONS *> $null
adb shell dumpsys deviceidle whitelist +$p | Out-Null
adb shell am start-foreground-service -n "$p/.CapriService" | Out-Null
Note "overlay, usage access, all files, notifications, no battery limits: granted; Capri started"

# ---- 5. the PC agent -----------------------------------------------------------------------
$agent = Join-Path $code "host-agent"
if (-not $SkipAgent) {
    Step "Installing the REDLINE agent on this PC (Windows asks for Administrator)"
    Start-Process powershell -Verb RunAs -Wait -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$agent\install.ps1`""
    Step "Starting the agent"
    Start-Process -FilePath (Join-Path $agent "start-agent.bat") -WorkingDirectory $agent
    Start-Sleep 8
    $pinFile = Join-Path $agent "PAIRING-PIN.txt"
    if (Test-Path $pinFile) { Get-Content $pinFile | ForEach-Object { Write-Host "   $_" -ForegroundColor Yellow } }
    adb shell am start -n "com.protvirto.redline/.MainActivity" | Out-Null
    Note "REDLINE is open on the Thor: tap SCAN, pick this PC and enter the PIN above."
}

# ---- 6. report -----------------------------------------------------------------------------
& (Join-Path $code "tools\thor-check.ps1") -Out (Join-Path $Dir "report")
