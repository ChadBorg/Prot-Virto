"""Power control, telemetry, Wake-on-LAN, frame generation and input injection."""

from __future__ import annotations

import ctypes
import os
import re
import socket
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

IS_WINDOWS = sys.platform == "win32"
STARTED = time.time()

# --------------------------------------------------------------------------
# Power
# --------------------------------------------------------------------------
POWER_ACTIONS = ("shutdown", "restart", "sleep", "hibernate", "lock")


def power(action: str) -> None:
    if action not in POWER_ACTIONS:
        raise ValueError(f"unknown power action {action}")

    def run() -> None:
        time.sleep(1.5)  # let the HTTP response reach the phone first
        if IS_WINDOWS:
            cmd = {
                "shutdown": ["shutdown", "/s", "/t", "0"],
                "restart": ["shutdown", "/r", "/t", "0"],
                "hibernate": ["shutdown", "/h"],
                "sleep": ["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"],
                "lock": ["rundll32.exe", "user32.dll,LockWorkStation"],
            }[action]
        else:
            cmd = {
                "shutdown": ["systemctl", "poweroff"],
                "restart": ["systemctl", "reboot"],
                "hibernate": ["systemctl", "hibernate"],
                "sleep": ["systemctl", "suspend"],
                "lock": ["loginctl", "lock-session"],
            }[action]
        subprocess.Popen(cmd)

    threading.Thread(target=run, daemon=True).start()


# --------------------------------------------------------------------------
# Telemetry
# --------------------------------------------------------------------------
def gpu_stats() -> dict:
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-gpu=name,utilization.gpu,temperature.gpu,memory.used,memory.total",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=3,
            creationflags=0x08000000 if IS_WINDOWS else 0,
        ).stdout.strip().splitlines()
        if out:
            name, util, temp, used, total = [x.strip() for x in out[0].split(",")]
            return {"name": name, "util": float(util), "temp": float(temp),
                    "mem_used_mb": float(used), "mem_total_mb": float(total), "vendor": "nvidia"}
    except (OSError, ValueError, subprocess.SubprocessError):
        pass
    return {}


def system_stats() -> dict:
    stats: dict = {"uptime_s": int(time.time() - STARTED)}
    try:
        import psutil

        stats["cpu"] = psutil.cpu_percent(interval=None)
        vm = psutil.virtual_memory()
        stats["ram"] = vm.percent
        stats["uptime_s"] = int(time.time() - psutil.boot_time())
    except ImportError:
        pass
    gpu = gpu_stats()
    if gpu:
        stats["gpu"] = gpu
    return stats


def primary_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


_MAC_RE = re.compile(r"([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")
_WIRELESS = re.compile(r"wi-?fi|wlan|wireless", re.I)
_WIRED = re.compile(r"^(ethernet|eth\d|en[op]\w*|lan)", re.I)
_VIRTUAL = re.compile(r"vethernet|virtual|vmware|virtualbox|hyper-v|tailscale|zerotier|bluetooth|loopback|wsl", re.I)


def _nic_mac(addrs) -> str | None:
    import psutil

    for a in addrs:
        if a.family == psutil.AF_LINK and _MAC_RE.fullmatch(a.address):
            return a.address.replace("-", ":").upper()
    return None


def pick_wol_mac(nics: dict, primary: str) -> str | None:
    """The MAC to wake: the primary NIC's, unless that's Wi-Fi and a wired NIC exists.

    Wake-on-LAN practically only works over wired Ethernet (here the Wi-Fi adapter isn't even wake-armed), so a PC
    that is on Wi-Fi *now* but has a cable port should be woken through the cable port.
    """
    primary_name = next((n for n, addrs in nics.items() if any(a.address == primary for a in addrs)), None)
    if primary_name and not _WIRELESS.search(primary_name):
        return _nic_mac(nics[primary_name])
    for name, addrs in nics.items():
        if _WIRED.match(name) and not _VIRTUAL.search(name):
            mac = _nic_mac(addrs)
            if mac:
                return mac
    return _nic_mac(nics[primary_name]) if primary_name else None


def mac_address() -> str:
    """MAC used for Wake-on-LAN (see pick_wol_mac). Set "mac" in redline_config.json to override."""
    try:
        import psutil

        mac = pick_wol_mac(psutil.net_if_addrs(), primary_ip())
        if mac:
            return mac
    except ImportError:
        pass
    node = uuid.getnode()
    return ":".join(f"{(node >> s) & 0xFF:02X}" for s in range(40, -1, -8))


def send_magic_packet(mac: str, broadcast: str = "255.255.255.255", port: int = 9) -> None:
    raw = bytes.fromhex(re.sub(r"[^0-9A-Fa-f]", "", mac))
    if len(raw) != 6:
        raise ValueError("invalid MAC address")
    packet = b"\xff" * 6 + raw * 16
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for p in {port, 7, 9}:
            s.sendto(packet, (broadcast, p))


# --------------------------------------------------------------------------
# Windows input helpers (SendInput)
# --------------------------------------------------------------------------
if IS_WINDOWS:
    from ctypes import wintypes

    ULONG_PTR = ctypes.c_size_t

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                    ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]

    class _INPUTUNION(ctypes.Union):
        _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("u", _INPUTUNION)]

    _SendInput = ctypes.windll.user32.SendInput

    def _send(*inputs: "INPUT") -> None:
        arr = (INPUT * len(inputs))(*inputs)
        _SendInput(len(inputs), arr, ctypes.sizeof(INPUT))

    def _mouse(flags: int, dx: int = 0, dy: int = 0, data: int = 0) -> "INPUT":
        i = INPUT(type=0)
        i.u.mi = MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
        return i

    def _key(vk: int, scan: int, flags: int) -> "INPUT":
        i = INPUT(type=1)
        i.u.ki = KEYBDINPUT(vk, scan, flags, 0, 0)
        return i


class Input:
    """Virtual Xbox 360 pad (ViGEmBus via vgamepad) + mouse/keyboard via SendInput."""

    MOUSE_FLAGS = {1: (0x0002, 0x0004), 2: (0x0008, 0x0010), 3: (0x0020, 0x0040)}

    def __init__(self) -> None:
        self.pad = None
        self.pad_error = ""
        try:
            import vgamepad

            self.pad = vgamepad.VX360Gamepad()
        except Exception as exc:  # noqa: BLE001 - driver missing, import errors...
            self.pad_error = f"virtual gamepad unavailable: {exc}"

    def gamepad(self, buttons: int, lt: int, rt: int, lx: int, ly: int, rx: int, ry: int) -> None:
        if not self.pad:
            return
        r = self.pad.report
        r.wButtons = buttons
        r.bLeftTrigger, r.bRightTrigger = lt, rt
        r.sThumbLX, r.sThumbLY, r.sThumbRX, r.sThumbRY = lx, ly, rx, ry
        self.pad.update()

    def mouse_move(self, dx: int, dy: int) -> None:
        if IS_WINDOWS:
            _send(_mouse(0x0001, dx, dy))

    def mouse_abs(self, x: int, y: int) -> None:
        if IS_WINDOWS:
            _send(_mouse(0x0001 | 0x8000, x, y))

    def mouse_button(self, button: int, down: bool) -> None:
        if IS_WINDOWS and button in self.MOUSE_FLAGS:
            _send(_mouse(self.MOUSE_FLAGS[button][0 if down else 1]))

    def mouse_wheel(self, delta: int) -> None:
        if IS_WINDOWS:
            _send(_mouse(0x0800, data=delta))

    def key(self, vk: int, down: bool) -> None:
        if IS_WINDOWS:
            _send(_key(vk, 0, 0 if down else 0x0002))

    def text(self, text: str) -> None:
        if not IS_WINDOWS:
            return
        for ch in text:
            if ch == "\n":
                self.key(0x0D, True)
                self.key(0x0D, False)
                continue
            data = ch.encode("utf-16-le")
            for k in range(0, len(data), 2):
                code = int.from_bytes(data[k:k + 2], "little")
                _send(_key(0, code, 0x0004), _key(0, code, 0x0004 | 0x0002))

    def release_all(self) -> None:
        if self.pad:
            self.pad.reset()
            self.pad.update()


# --------------------------------------------------------------------------
# Frame generation (Lossless Scaling)
# --------------------------------------------------------------------------
VK = {"ctrl": 0x11, "alt": 0x12, "shift": 0x10, "win": 0x5B}


def _hotkey_codes(spec: str) -> list[int]:
    codes = []
    for part in spec.lower().split("+"):
        part = part.strip()
        if part in VK:
            codes.append(VK[part])
        elif len(part) == 1:
            codes.append(ord(part.upper()))
        elif re.fullmatch(r"f\d{1,2}", part):
            codes.append(0x70 + int(part[1:]) - 1)
        else:
            raise ValueError(f"unsupported hotkey part '{part}'")
    return codes


class FrameGen:
    """Drives Lossless Scaling (LSFG) on the host.

    Lossless Scaling applies frame generation to the *foreground* window when
    its scale hotkey is pressed. Because the stream encodes whatever is on
    screen, the generated frames are streamed to the handheld too, so the
    Thor receives e.g. 120 fps from a game that renders at 60.
    """

    def __init__(self, config, steam_root: Path | None) -> None:
        self.config = config
        self.steam_root = steam_root
        self.enabled = False

    def exe_path(self) -> Path | None:
        configured = self.config["lossless_scaling_path"]
        if configured and Path(configured).exists():
            return Path(configured)
        if self.steam_root:
            from .library import Steam

            for lib in Steam().libraries():
                p = lib / "common" / "Lossless Scaling" / "LosslessScaling.exe"
                if p.exists():
                    return p
        return None

    @staticmethod
    def _running() -> bool:
        if not IS_WINDOWS:
            return False
        out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq LosslessScaling.exe"],
                             capture_output=True, text=True,
                             creationflags=0x08000000).stdout
        return "losslessscaling.exe" in out.lower()

    def state(self) -> dict:
        exe = self.exe_path()
        return {
            "available": exe is not None,
            "running": self._running(),
            "enabled": self.enabled,
            "hotkey": self.config["lossless_scaling_hotkey"],
            "provider": "Lossless Scaling (LSFG)",
            "note": "" if exe else "Install Lossless Scaling from Steam to enable host frame generation.",
        }

    def set_enabled(self, enabled: bool) -> dict:
        if enabled == self.enabled:
            return self.state()
        exe = self.exe_path()
        if not exe or not IS_WINDOWS:
            raise ValueError("Lossless Scaling is not installed on this PC")
        if not self._running():
            subprocess.Popen([str(exe)], cwd=str(exe.parent))
            time.sleep(4)
        codes = _hotkey_codes(self.config["lossless_scaling_hotkey"])
        downs = [_key(c, 0, 0) for c in codes]
        ups = [_key(c, 0, 0x0002) for c in reversed(codes)]
        _send(*downs)
        time.sleep(0.05)
        _send(*ups)
        self.enabled = enabled
        return self.state()
