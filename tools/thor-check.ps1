# What's working on the Thor: screenshots of both screens, Capri's and REDLINE's logs, ES-DE's log
# lines Capri reads, and the PC agent. Run any time (the Thor connected over ADB):
#   .\thor-check.ps1            -> writes everything to .\report and prints a summary
param([string]$Out = (Join-Path (Get-Location) "report"))
$ErrorActionPreference = "Continue"
New-Item -ItemType Directory -Force -Path $Out | Out-Null

function Line($label, $value, $ok) {
    $mark = if ($ok) { "[ OK ]" } else { "[ !! ]" }
    $color = if ($ok) { "Green" } else { "Yellow" }
    Write-Host ("{0} {1,-28} {2}" -f $mark, $label, $value) -ForegroundColor $color
}

Write-Host "`n>> Thor report" -ForegroundColor Red
$serials = @((adb devices) -match "`tdevice$" | ForEach-Object { ($_ -split "`t")[0] })
if (-not $serials) { Write-Host "   The Thor isn't connected (adb devices)." -ForegroundColor Yellow; return }
# the same Thor is often listed twice (IP:port and mDNS name): pin one so `adb shell` doesn't fail
if (-not $env:ANDROID_SERIAL -or $serials -notcontains $env:ANDROID_SERIAL) { $env:ANDROID_SERIAL = $serials[0] }

# ---- apps ------------------------------------------------------------------------------------
$pkgs = adb shell pm list packages
foreach ($p in "com.protvirto.redline", "com.protvirto.capri", "org.es_de.frontend") {
    Line $p ($(if ($pkgs -match [regex]::Escape($p)) { "installed" } else { "missing" })) ($pkgs -match [regex]::Escape($p))
}

# ---- Capri -----------------------------------------------------------------------------------
$svc = adb shell dumpsys activity services com.protvirto.capri
Line "Capri service" ($(if ($svc -match "CapriService") { "running" } else { "not running" })) ($svc -match "CapriService")
foreach ($op in "SYSTEM_ALERT_WINDOW", "GET_USAGE_STATS", "MANAGE_EXTERNAL_STORAGE") {
    $v = (adb shell appops get com.protvirto.capri $op) -join " "
    Line "  $op" $v.Trim() ($v -match "allow")
}
$windows = adb shell dumpsys window windows
$overlays = ($windows | Select-String "Window\{[^}]*Capri" | Measure-Object).Count
Line "Capri overlay windows" $overlays ($overlays -gt 0)

# ---- displays (the Thor's two screens) --------------------------------------------------------
$ids = @()
foreach ($l in (adb shell dumpsys SurfaceFlinger --display-id)) {
    if ($l -match "^Display (\d+)") { $ids += $Matches[1] }
}
Line "Screens" ($ids.Count) ($ids.Count -ge 1)
$i = 0
foreach ($id in $ids) {
    $remote = "/sdcard/redline-check-$i.png"
    adb shell screencap -p -d $id $remote | Out-Null
    adb pull $remote (Join-Path $Out "screen-$i.png") 2>$null | Out-Null
    adb shell rm $remote | Out-Null
    $i++
}
Write-Host "   screenshots: $Out\screen-*.png" -ForegroundColor DarkGray

# ---- ES-DE: what Capri reads -------------------------------------------------------------------
# ES-DE's folder is often on the SD card (/storage/XXXX-XXXX/ES-DE), not internal storage
$esde = "/sdcard/ES-DE"
$found = @(adb shell "ls -d /sdcard/ES-DE/logs/es_log.txt /storage/*/ES-DE/logs/es_log.txt 2>/dev/null") |
    Where-Object { $_ -match "/ES-DE/logs/es_log\.txt$" } | Select-Object -First 1
if ($found) { $esde = $found.Trim() -replace "/logs/es_log\.txt$", "" }
$hasLog = [bool]$found
Line "ES-DE log" ($(if ($hasLog) { "$esde/logs/es_log.txt" } else { "not found (set the ES-DE folder in the Capri app)" })) $hasLog
if ($hasLog) {
    $launch = adb shell "grep -i -n 'launch' $esde/logs/es_log.txt | tail -n 12"
    $launch | Set-Content (Join-Path $Out "esde-launch-lines.txt")
    $matched = ($launch | Select-String 'Launching game "').Count
    Line "  'Launching game' lines" $matched ($matched -gt 0)
    Write-Host "   (launch lines saved: esde-launch-lines.txt - Capri's game detection depends on them)" -ForegroundColor DarkGray
}
$lists = (adb shell "ls $esde/gamelists 2>/dev/null") | Where-Object { $_.Trim() }
Line "ES-DE gamelists" ("{0} systems" -f $lists.Count) ($lists.Count -gt 0)

# ---- logs ---------------------------------------------------------------------------------------
adb logcat -d -v time -s CapriWeb CapriOverlay CapriBridge CapriWatch CapriDecoder RedlineDecoder AndroidRuntime > (Join-Path $Out "logcat.txt")
$crashes = (Select-String -Path (Join-Path $Out "logcat.txt") -Pattern "FATAL EXCEPTION" -ErrorAction SilentlyContinue | Measure-Object).Count
Line "Crashes in the log" $crashes ($crashes -eq 0)

# ---- the PC agent ---------------------------------------------------------------------------------
try {
    $info = Invoke-RestMethod -Uri "http://127.0.0.1:48100/api/info" -TimeoutSec 3
    Line "REDLINE agent" ("v{0} on {1} ({2})" -f $info.version, $info.name, $info.ip) $true
    $thorIp = ((adb shell "ip -4 addr show wlan0") -join " ") -replace '.*inet (\d+\.\d+\.\d+\.\d+).*', '$1'
    Line "Thor Wi-Fi address" $thorIp ($thorIp -match "^\d+\.")
} catch {
    Line "REDLINE agent" "not answering on :48100 (run host-agent\start-agent.bat)" $false
}
Write-Host "`n   Full report: $Out" -ForegroundColor DarkGray
