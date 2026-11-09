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
# The real python.exe, skipping the Microsoft Store stub in WindowsApps (it only prints "Python was not found")
function Find-Python {
    foreach ($c in @(Get-Command python -All -ErrorAction SilentlyContinue)) {
        if ($c.Source -notmatch "\\WindowsApps\\") { return $c.Source }
    }
    if (Have "py") {
        $exe = (& py -3 -c "import sys; print(sys.executable)" 2>$null | Select-Object -First 1)
        if ($exe -and (Test-Path $exe)) { return $exe }
    }
    $local = Get-ChildItem "$env:LOCALAPPDATA\Programs\Python\Python3*\python.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($local) { return $local.FullName }
    return $null
}
# winget's Google.PlatformTools manifest sometimes points at a zip Google has removed (404): use the stable link
function Install-Adb {
    Install-Pkg "Google.PlatformTools"
    if (Have "adb") { return }
    Note "winget couldn't install platform-tools: downloading it from Google instead"
    $dest = Join-Path $env:LOCALAPPDATA "Android"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    $zip = Join-Path $env:TEMP "platform-tools-latest.zip"
    Invoke-WebRequest -UseBasicParsing "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
    Expand-Archive $zip $dest -Force
    Remove-Item $zip
    $pt = Join-Path $dest "platform-tools"
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if (($userPath -split ";") -notcontains $pt) {
        [Environment]::SetEnvironmentVariable("Path", (($userPath.TrimEnd(";"), $pt) -join ";"), "User")
    }
    Refresh-Path
}

# ---- 1. tools ------------------------------------------------------------------------------
Step "Checking tools"
Refresh-Path   # pick up anything installed since this window opened
if (-not (Have "winget")) { throw "winget is missing: install 'App Installer' from the Microsoft Store, then run this again." }
if (-not (Have "git")) { Install-Pkg "Git.Git" }
if (-not (Have "gh")) { Install-Pkg "GitHub.cli" }
if (-not (Have "adb")) { Install-Adb }
if (-not (Find-Python)) { Install-Pkg "Python.Python.3.12" }
if (-not (Find-Python)) { throw "Python 3 was installed but can't be found yet: open a new window and run the script again." }
foreach ($cmd in "git", "gh", "adb") {
    if (-not (Have $cmd)) { throw "$cmd was installed but isn't on PATH yet: close this window, open a new one and run the script again." }
}
Note "git, gh, adb and python are ready"

# ---- 2. code -------------------------------------------------------------------------------
Step "Getting the code into $Dir"
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$code = Join-Path $Dir "Prot-Virto"
if (Test-Path (Join-Path $code ".git")) {
    # git prints progress on stderr, which Windows PowerShell shows as red errors: print it as plain text
    git -C $code fetch origin $Branch 2>&1 | ForEach-Object { Note "$_" }
    git -C $code checkout $Branch 2>&1 | ForEach-Object { Note "$_" }
    git -C $code pull --ff-only origin $Branch 2>&1 | ForEach-Object { Note "$_" }
} else {
    git clone -b $Branch "https://github.com/$Repo" $code 2>&1 | ForEach-Object { Note "$_" }
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
# adb often lists the same Thor twice (its IP:port and its mDNS name), and then every plain `adb shell` fails
# with "more than one device". Pin the first one for the rest of this script (and thor-check.ps1).
function Connected {
    $serial = @((adb devices) -match "`tdevice$" | ForEach-Object { ($_ -split "`t")[0] }) | Select-Object -First 1
    if ($serial) { $env:ANDROID_SERIAL = $serial }
    [bool]$serial
}
# The Thor advertises Wireless debugging over mDNS: "pairing" while the pairing-code popup is open, "connect" while
# Wireless debugging is on. Both ports change every time it's toggled, so look them up instead of asking.
function Find-Adb($kind) {
    $m = (adb mdns services) | Select-String "_adb-tls-$kind\._tcp\s+(\S+)" | Select-Object -First 1
    if ($m) { $m.Matches[0].Groups[1].Value } else { $null }
}
if (-not (Connected)) {
    adb start-server | Out-Null
    Write-Host "   On the Thor: Settings > About > tap 'Build number' 7 times (Developer options)."
    Write-Host "   Then: Settings > System > Developer options > Wireless debugging > ON > 'Pair device with pairing code'."
    Write-Host "   (The Thor and this PC must be on the same Wi-Fi.)"
    if (-not $PairCode) { $PairCode = Read-Host "   The 6-digit Wi-Fi pairing code (keep the popup open)" }
    if (-not $PairAddress) {
        for ($i = 0; $i -lt 10 -and -not $PairAddress; $i++) { $PairAddress = Find-Adb "pairing"; if (-not $PairAddress) { Start-Sleep 2 } }
        if (-not $PairAddress) { $PairAddress = Read-Host "   IP address & port shown under the pairing code (e.g. 192.168.1.42:37123)" }
    }
    Note "pairing with $PairAddress"
    adb pair $PairAddress $PairCode
    Start-Sleep 3   # adb usually connects by itself over mDNS right after pairing
    if (-not (Connected)) {
        if (-not $Connect) { $Connect = Find-Adb "connect" }
        if (-not $Connect) { $Connect = Read-Host "   Now the 'IP address & port' at the top of the Wireless debugging screen" }
        adb connect $Connect
        Start-Sleep 2
    }
    if (-not (Connected)) { throw "The Thor didn't connect. Check Wireless debugging is still on and try again." }
}
Note "using $env:ANDROID_SERIAL"
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
Note "overlay, usage access, all files, notifications, no battery limits: granted"
# CapriService isn't exported, so adb can't start it directly: ask her app to wake her
adb shell am start -n "$p/.MainActivity" --ez wake true | Out-Null
Start-Sleep 3
if ((adb shell dumpsys activity services $p) -match "CapriService") { Note "Capri is awake (bottom screen)" }
else { Write-Host "   Capri didn't start by herself: open the Capri app on the Thor and tap WAKE HER UP." -ForegroundColor Yellow }

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
