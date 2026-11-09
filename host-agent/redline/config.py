"""Persistent agent configuration (redline_config.json next to the agent)."""

from __future__ import annotations

import json
import os
import secrets
import socket
import threading
from pathlib import Path
from typing import Any

DEFAULTS: dict[str, Any] = {
    "name": socket.gethostname(),
    "http_port": 48100,
    "stream_port": 48101,
    "discovery_port": 48102,
    # MAC used for Wake-on-LAN; auto-detected when empty.
    "mac": "",
    # ffmpeg binary (put ffmpeg.exe next to the agent or on PATH).
    "ffmpeg": "ffmpeg",
    # auto | h264_nvenc | hevc_nvenc | h264_amf | hevc_amf | h264_qsv | hevc_qsv | libx264
    "encoder": "auto",
    # ddagrab (fast, GPU, Windows 8+) | gdigrab (compat)
    "capture": "ddagrab",
    "monitor_index": 0,
    # Scaling to the resolution the handheld asks for. "auto": with ddagrab + NVENC/AMF, stream the native
    # resolution and let the handheld's hardware decoder scale it (scaling on the PC means copying every frame
    # off the GPU, which cost ~40% of the frame rate under game load). "always": scale on the PC anyway
    # (less bandwidth headroom needed at the same bitrate on a 4K desktop).
    "host_scaling": "auto",
    # Lossless Scaling integration (frame generation)
    "lossless_scaling_path": "",
    "lossless_scaling_hotkey": "ctrl+alt+s",
    # Extra non-Steam games: [{"id": "my-game", "name": "...", "exe": "C:/...", "args": [], "image": "https://..."}]
    "custom_games": [],
    # Steam silent installs restart Steam to pick up the queued download.
    "steam_silent_install": True,
    # token -> device name
    "paired_devices": {},
}


class Config:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._lock = threading.Lock()
        self.data: dict[str, Any] = dict(DEFAULTS)
        if path.exists():
            try:
                self.data.update(json.loads(path.read_text("utf-8")))
            except (OSError, ValueError) as exc:
                print(f"[config] could not read {path}: {exc}; using defaults")
        self.save()

    def __getitem__(self, key: str) -> Any:
        return self.data.get(key, DEFAULTS.get(key))

    def __setitem__(self, key: str, value: Any) -> None:
        with self._lock:
            self.data[key] = value
        self.save()

    def save(self) -> None:
        with self._lock:
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.data, indent=2), "utf-8")
            os.replace(tmp, self.path)

    # ---- pairing -------------------------------------------------------
    def is_authorized(self, token: str | None) -> bool:
        if not token:
            return False
        return any(secrets.compare_digest(token, t) for t in self["paired_devices"])

    def add_device(self, device_name: str) -> str:
        token = secrets.token_urlsafe(32)
        devices = dict(self["paired_devices"])
        devices[token] = device_name[:64] or "device"
        self["paired_devices"] = devices
        return token

    def remove_device(self, token: str) -> None:
        devices = dict(self["paired_devices"])
        devices.pop(token, None)
        self["paired_devices"] = devices
