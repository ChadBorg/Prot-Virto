# REDLINE agent installer for Windows 10/11.
# Run from this folder in an *Administrator* PowerShell:
#   Set-ExecutionPolicy -Scope Process Bypass; .\install.ps1
param(
    [switch]$NoAutostart,
    [switch]$NoFfmpeg
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

function Step($msg) { Write-Host "`n>> $msg" -ForegroundColor Red }

# 1. Python ------------------------------------------------------------------
Step "Checking Python"
$py = Get-Command python -ErrorAction SilentlyContinue
if (-not $py) {
    Write-Host "Installing Python 3.12 with winget..."
    winget install -e --id Python.Python.3.12 --accept-source-agreements --accept-package-agreements
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
}
python --version

Step "Creating virtual environment + installing packages"
if (-not (Test-Path ".venv")) { python -m venv .venv }
& .\.venv\Scripts\python.exe -m pip install --upgrade pip
& .\.venv\Scripts\python.exe -m pip install -r requirements.txt
Write-Host "If a ViGEmBus driver installer popped up, accept it - it provides the virtual Xbox controller."

# 2. ffmpeg -----------------------------------------------------------------------
if (-not $NoFfmpeg -and -not (Test-Path "ffmpeg.exe")) {
    Step "Downloading ffmpeg (gyan.dev essentials build)"
    $zip = Join-Path $env:TEMP "ffmpeg-redline.zip"
    Invoke-WebRequest "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip" -OutFile $zip
    $dest = Join-Path $env:TEMP "ffmpeg-redline"
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    Expand-Archive $zip $dest
    Copy-Item (Get-ChildItem $dest -Recurse -Filter ffmpeg.exe | Select-Object -First 1).FullName "$here\ffmpeg.exe"
    Remove-Item $zip, $dest -Recurse -Force
}

# 3. Firewall -----------------------------------------------------------------------
Step "Opening firewall ports 48100-48101 (TCP) and 48102 (UDP discovery)"
Get-NetFirewallRule -DisplayName "REDLINE*" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName "REDLINE TCP" -Direction Inbound -Protocol TCP -LocalPort 48100,48101 -Action Allow -Profile Private,Domain | Out-Null
New-NetFirewallRule -DisplayName "REDLINE UDP" -Direction Inbound -Protocol UDP -LocalPort 48102 -Action Allow -Profile Private,Domain | Out-Null

# 4. Autostart at logon --------------------------------------------------------------
if (-not $NoAutostart) {
    Step "Starting REDLINE automatically when you log in"
    $action = New-ScheduledTaskAction -Execute "$here\.venv\Scripts\pythonw.exe" -Argument "`"$here\redline_agent.py`"" -WorkingDirectory $here
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit 0
    Register-ScheduledTask -TaskName "REDLINE Agent" -Action $action -Trigger $trigger -Settings $settings -RunLevel Highest -Force | Out-Null
}

Step "Done. Run start-agent.bat now to see the pairing PIN."
Write-Host "Tip: enable Wake-on-LAN in your BIOS and in Device Manager > network adapter > Power Management."
