# REDLINE

**Stream your gaming PC to an AYN Thor (or any Android handheld) from anywhere with Wi-Fi.**
Black-and-red cyberpunk UI, game library management, remote power and frame generation.

```
 ┌────────────── Thor (Android) ──────────────┐            ┌──────────── Gaming PC (Windows) ────────────┐
 │  REDLINE app                               │  LAN or    │  REDLINE agent (Python)                     │
 │   • HOME / LIBRARY / STORE / TUNING / RIGS │  Tailscale │   • HTTP API :48100   games · power · LSFG  │
 │   • HW HEVC/H.264 decode, 120 Hz output    │ ◀────────▶ │   • Stream   :48101   ffmpeg ddagrab → NVENC│
 │   • Thor controls → virtual Xbox pad       │            │   • Discovery UDP :48102                    │
 │   • Bottom screen = HUD + touchpad         │            │   • ViGEm virtual controller, WASAPI audio  │
 └────────────────────────────────────────────┘            └─────────────────────────────────────────────┘
```

## What it does

| Feature | How |
|---|---|
| Stream games | The PC captures the desktop with ffmpeg (`ddagrab`, zero-copy into NVENC/AMF/QSV), sends HEVC or H.264 over TCP, and the Thor decodes it in hardware with Snapdragon low-latency decoder flags. Game audio is streamed too. |
| Play from anywhere | Install [Tailscale](https://tailscale.com) on the PC and the Thor. REDLINE tries your LAN address first and falls back to the Tailscale address, so any Wi-Fi (hotel, café, phone hotspot) works. You don't need to forward any ports. |
| Keep it smooth | Auto bitrate measures the link before each session and lowers bitrate if the network chokes. Latency guard drops stale frames instead of building lag. The host skips frames when the socket backs up. The Thor's panel is locked to the stream rate (up to 120 Hz). |
| Frame generation | The PC runs [Lossless Scaling](https://store.steampowered.com/app/993090/Lossless_Scaling/) frame gen (LSFG). REDLINE toggles it remotely (app, in-stream menu or bottom screen) and the generated frames are streamed. Set stream FPS to 120 to see them. |
| Swap / install / delete games | LIBRARY shows Steam, Epic and custom games with cover art. You can launch, close or delete games (Steam). STORE searches Steam and queues installs of games you own, and download progress shows in the library. |
| Power the PC on/off | Shutdown, restart, sleep and lock go through the agent. Power-on uses Wake-on-LAN, which works directly at home. Away from home it needs a small relay (see below). |
| Thor dual screen | While streaming, the bottom screen shows live stats, quick actions (frame gen, bitrate, keyboard, close game, sleep) and a touchpad. |
| Controls | The Thor's built-in controls become a virtual Xbox 360 pad on the PC. The touch screen works as a touchpad or as direct touch. You can also send keyboard text. **SELECT + START** opens the in-stream menu. In menus: D-pad moves, A selects, B goes back, L1/R1 switch tabs. |

## Setup

### 1. The PC (Windows 10/11)
1. Download `REDLINE-Agent.zip` from the latest **Build REDLINE** run under the repo's *Actions* tab, or copy the `host-agent/` folder.
2. Open PowerShell **as Administrator** in that folder and run:
   ```powershell
   Set-ExecutionPolicy -Scope Process Bypass; .\install.ps1
   ```
   This installs Python packages, the ViGEmBus driver prompt (virtual controller), ffmpeg and firewall rules, and starts the agent at logon.
3. Start it now with `start-agent.bat`. It prints a **6-digit pairing PIN**, which is also saved in `PAIRING-PIN.txt`.
4. For power-on: enable **Wake-on-LAN** in the BIOS, and in *Device Manager → network adapter → Power Management / Advanced*. Wired Ethernet is strongly recommended.
5. Optional: install **Lossless Scaling** from Steam for frame generation. REDLINE presses its scale hotkey (default `Ctrl+Alt+S`; change it in `redline_config.json`).

### 2. The Thor
1. Download `REDLINE-apk` from the same Actions run, unzip it and install `REDLINE-1.0.0-release.apk`. Allow installs from your file manager if Android asks.
2. Open REDLINE, tap **Scan**, pick your PC and enter the PIN.

### 3. Anywhere access
1. Install Tailscale on the PC and the Thor, and sign in with the same account on both.
2. In REDLINE go to **RIGS → Edit** and set **Remote address** to the PC's Tailscale IP (`100.x.y.z`).
3. **Waking the PC while you're away.** A powered-off PC can't hear the internet, so something at home has to send the wake packet. Run the agent in relay mode on an always-on device on your home network (Raspberry Pi, NAS, old laptop) that also has Tailscale:
   ```bash
   pip install aiohttp && python redline_agent.py --relay
   ```
   Then put that device's Tailscale IP in **RIGS → Edit → Wake relay address**.

## Recommended settings

| Where you are | Resolution | FPS | Bitrate | Codec |
|---|---|---|---|---|
| Home Wi-Fi 6, PC on Ethernet | 1080p | 60–120 | 30–50 Mbps | HEVC |
| Hotel / café Wi-Fi | 1080p or 900p | 60 | 10–20 Mbps (auto) | HEVC |
| Phone hotspot | 720p | 60 | 6–10 Mbps (auto) | HEVC |

With frame gen on, set FPS to 120. The game renders at about 60 and Lossless Scaling doubles it.

## Configuration (`host-agent/redline_config.json`)

| Key | Default | Notes |
|---|---|---|
| `encoder` | `auto` | Or force `hevc_nvenc`, `h264_amf`, `hevc_qsv`, `libx264`… |
| `capture` | `ddagrab` | `gdigrab` for very old systems |
| `monitor_index` | `0` | Which display to stream |
| `lossless_scaling_hotkey` | `ctrl+alt+s` | Must match the hotkey in Lossless Scaling |
| `steam_silent_install` | `true` | Restarts Steam to start queued downloads without a click on the PC |
| `custom_games` | `[]` | `[{"id":"emu","name":"RetroArch","exe":"C:/RetroArch/retroarch.exe","image":"https://…"}]` |

## Building yourself

* **APK:** `cd android && ./gradlew assembleRelease`. You need JDK 17 and the Android SDK. Set `REDLINE_KEYSTORE`, `REDLINE_KEYSTORE_PASSWORD`, `REDLINE_KEY_ALIAS` and `REDLINE_KEY_PASSWORD` to sign with your own key; otherwise it uses the debug key. In CI, add the keystore as the base64 secret `REDLINE_KEYSTORE_B64` so every build keeps the same signature and updates install over each other.
* **Agent tests:** `cd host-agent && pip install aiohttp pytest && python -m pytest tests` (needs `ffmpeg` on PATH).

## Security notes
* Pairing needs the PIN shown on the PC. Each device gets its own long random token, and the PIN changes after every pairing.
* The agent speaks plain HTTP/TCP. That's fine on your LAN and inside Tailscale, because Tailscale encrypts everything with WireGuard. **Do not port-forward 48100/48101 to the open internet.**
* Deleting a game only ever removes a folder directly inside `steamapps/common` and asks for confirmation in the app.

## Limitations
* Wake-on-LAN from outside your home needs the relay. Wi-Fi-only PCs often can't be woken at all.
* Silent Steam installs only work for games your Steam account owns. Otherwise Steam's own dialog opens on the PC, and you can click it through the stream.
* Epic games can be launched but not installed or uninstalled remotely.
* The Thor's bottom-screen HUD uses Android's secondary-display API. If your firmware doesn't expose the bottom screen to apps, the HUD stays off and everything is still available from the in-stream menu.
