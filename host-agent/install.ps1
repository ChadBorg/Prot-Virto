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
# The real python.exe. Skip the Microsoft Store stub in WindowsApps: it's often first on PATH and only prints
# "Python was not found", which made `python -m venv` fail here.
function Find-Python {
    foreach ($c in @(Get-Command python -All -ErrorAction SilentlyContinue)) {
        if ($c.Source -notmatch "\\WindowsApps\\") { return $c.Source }
    }
    if (Get-Command py -ErrorAction SilentlyContinue) {
        $exe = (& py -3 -c "import sys; print(sys.executable)" 2>$null | Select-Object -First 1)
        if ($exe -and (Test-Path $exe)) { return $exe }
    }
    $local = Get-ChildItem "$env:LOCALAPPDATA\Programs\Python\Python3*\python.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($local) { return $local.FullName }
    return $null
}
$python = Find-Python
if (-not $python) {
    Write-Host "Installing Python 3.12 with winget..."
    winget install -e --id Python.Python.3.12 --accept-source-agreements --accept-package-agreements
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
    $python = Find-Python
    if (-not $python) { throw "Python 3 isn't installed (or can't be found). Install it from python.org and run this again." }
}
& $python --version

Step "Creating virtual environment + installing packages"
if (-not (Test-Path ".venv")) { & $python -m venv .venv }
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
# Windows often marks a wired home network "Public", and Private/Domain-only rules then silently block the Thor.
# Allow every profile, but only from the local subnet and Tailscale's address range (100.64.0.0/10).
$fw = @{ Direction = "Inbound"; Action = "Allow"; Profile = "Any"; RemoteAddress = @("LocalSubnet", "100.64.0.0/10") }
New-NetFirewallRule -DisplayName "REDLINE TCP" -Protocol TCP -LocalPort 48100,48101 @fw | Out-Null
New-NetFirewallRule -DisplayName "REDLINE UDP" -Protocol UDP -LocalPort 48102 @fw | Out-Null

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
