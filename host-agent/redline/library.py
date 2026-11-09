"""Game library: Steam (install / uninstall / launch), Epic (launch) and custom games."""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass, field
from pathlib import Path

IS_WINDOWS = sys.platform == "win32"

# Steam "apps" that are not games.
STEAM_IGNORED = {
    228980,   # Steamworks Common Redistributables
    1070560,  # Steam Linux Runtime
    1391110,  # Steam Linux Runtime - Soldier
    1628350,  # Steam Linux Runtime - Sniper
    1493710,  # Proton Experimental
}
STEAM_INSTALLED_FLAG = 4

# --------------------------------------------------------------------------
# VDF (Valve KeyValues text) parser
# --------------------------------------------------------------------------
_TOKEN = re.compile(r'"((?:[^"\\]|\\.)*)"|([{}])')


def parse_vdf(text: str) -> dict:
    root: dict = {}
    stack = [root]
    key: str | None = None
    for m in _TOKEN.finditer(text):
        s, brace = m.group(1), m.group(2)
        if brace == "{":
            new: dict = {}
            stack[-1][key or ""] = new
            stack.append(new)
            key = None
        elif brace == "}":
            if len(stack) > 1:
                stack.pop()
            key = None
        elif key is None:
            key = s.replace("\\\\", "\\")
        else:
            stack[-1][key] = s.replace("\\\\", "\\")
            key = None
    return root


def _ci(d: dict, key: str, default=None):
    """Case-insensitive dict get (Steam is inconsistent about key case)."""
    for k, v in d.items():
        if k.lower() == key.lower():
            return v
    return default


@dataclass
class Game:
    id: str                # "steam:570", "epic:Fortnite", "custom:my-game"
    name: str
    source: str            # steam | epic | custom
    installed: bool
    image: str = ""        # portrait cover art
    hero: str = ""         # wide banner
    size_bytes: int = 0
    progress: float = -1.0  # 0..1 while downloading, -1 otherwise
    can_uninstall: bool = False
    extra: dict = field(default_factory=dict)

    def to_json(self) -> dict:
        d = asdict(self)
        d.pop("extra", None)
        return d


def steam_art(appid: int | str) -> tuple[str, str]:
    base = f"https://cdn.cloudflare.steamstatic.com/steam/apps/{appid}"
    return f"{base}/library_600x900.jpg", f"{base}/header.jpg"


# --------------------------------------------------------------------------
# Steam
# --------------------------------------------------------------------------
class Steam:
    def __init__(self) -> None:
        self.root = self._find_root()

    @staticmethod
    def _find_root() -> Path | None:
        candidates: list[Path] = []
        if IS_WINDOWS:
            try:
                import winreg

                with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as k:
                    candidates.append(Path(winreg.QueryValueEx(k, "SteamPath")[0]))
            except OSError:
                pass
            candidates.append(Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Steam")
        else:
            home = Path.home()
            candidates += [home / ".steam/steam", home / ".local/share/Steam",
                           home / ".var/app/com.valvesoftware.Steam/.local/share/Steam"]
        for c in candidates:
            if (c / "steamapps").is_dir():
                return c
        return None

    @property
    def available(self) -> bool:
        return self.root is not None

    def exe(self) -> str:
        if IS_WINDOWS and self.root:
            return str(self.root / "steam.exe")
        return "steam"

    def libraries(self) -> list[Path]:
        if not self.root:
            return []
        libs = [self.root / "steamapps"]
        vdf = self.root / "steamapps" / "libraryfolders.vdf"
        if vdf.exists():
            data = parse_vdf(vdf.read_text("utf-8", errors="replace"))
            folders = _ci(data, "libraryfolders", {})
            for v in folders.values():
                path = v.get("path") if isinstance(v, dict) else v
                if path:
                    p = Path(path) / "steamapps"
                    if p.is_dir() and p not in libs:
                        libs.append(p)
        return libs

    def manifests(self) -> dict[int, tuple[Path, dict]]:
        out: dict[int, tuple[Path, dict]] = {}
        for lib in self.libraries():
            for acf in lib.glob("appmanifest_*.acf"):
                try:
                    state = _ci(parse_vdf(acf.read_text("utf-8", errors="replace")), "AppState", {})
                    appid = int(_ci(state, "appid", "0"))
                except (OSError, ValueError):
                    continue
                if appid and appid not in STEAM_IGNORED:
                    out[appid] = (acf, state)
        return out

    def games(self) -> list[Game]:
        games = []
        for appid, (acf, st) in self.manifests().items():
            name = _ci(st, "name", f"App {appid}")
            if "redistributable" in name.lower():
                continue
            flags = int(_ci(st, "StateFlags", "0") or 0)
            to_dl = int(_ci(st, "BytesToDownload", "0") or 0)
            done = int(_ci(st, "BytesDownloaded", "0") or 0)
            # 4 = fully installed; 1024/2 bits = update/download in progress
            installed = bool(flags & STEAM_INSTALLED_FLAG) and not (flags & 1024)
            progress = -1.0
            if not installed:
                progress = (done / to_dl) if to_dl else 0.0
            cover, hero = steam_art(appid)
            games.append(Game(
                id=f"steam:{appid}", name=name, source="steam", installed=installed,
                image=cover, hero=hero, size_bytes=int(_ci(st, "SizeOnDisk", "0") or 0),
                progress=progress, can_uninstall=True,
                extra={"acf": str(acf), "installdir": _ci(st, "installdir", "")},
            ))
        return games

    def running_appid(self) -> int:
        if not IS_WINDOWS:
            return 0
        try:
            import winreg

            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as k:
                return int(winreg.QueryValueEx(k, "RunningAppID")[0])
        except OSError:
            return 0

    # -- actions ---------------------------------------------------------
    def _open_url(self, url: str) -> None:
        if IS_WINDOWS:
            os.startfile(url)  # type: ignore[attr-defined]
        else:
            subprocess.Popen(["xdg-open", url])

    def launch(self, appid: int) -> None:
        self._open_url(f"steam://rungameid/{appid}")

    def _restart(self) -> None:
        exe = self.exe()
        subprocess.call([exe, "-shutdown"])
        for _ in range(30):
            time.sleep(1)
            if not self._is_running():
                break
        subprocess.Popen([exe, "-silent"])

    @staticmethod
    def _is_running() -> bool:
        if IS_WINDOWS:
            out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq steam.exe"],
                                 capture_output=True, text=True).stdout
            return "steam.exe" in out.lower()
        return subprocess.run(["pgrep", "-x", "steam"], capture_output=True).returncode == 0

    def install(self, appid: int, name: str, silent: bool) -> str:
        """Queue a download.

        Silent mode drops an appmanifest stub that flags the app as
        "update required" and restarts Steam, which then starts downloading
        on its own (only works for games you own). Otherwise we fire
        steam://install, which pops the install dialog on the PC.
        """
        if silent and self.root:
            lib = self.libraries()[0]
            acf = lib / f"appmanifest_{appid}.acf"
            if not acf.exists():
                installdir = re.sub(r'[<>:"/\\|?*]', "", name).strip() or str(appid)
                acf.write_text(
                    '"AppState"\n{\n'
                    f'\t"appid"\t\t"{appid}"\n\t"Universe"\t\t"1"\n'
                    f'\t"name"\t\t"{name.replace(chr(34), "")}"\n'
                    '\t"StateFlags"\t\t"1026"\n'
                    f'\t"installdir"\t\t"{installdir}"\n'
                    '\t"LastUpdated"\t\t"0"\n\t"UpdateResult"\t\t"0"\n\t"SizeOnDisk"\t\t"0"\n'
                    '\t"buildid"\t\t"0"\n\t"BytesToDownload"\t\t"0"\n\t"BytesDownloaded"\t\t"0"\n'
                    '\t"AutoUpdateBehavior"\t\t"0"\n\t"AllowOtherDownloadsWhileRunning"\t\t"0"\n'
                    '\t"ScheduledAutoUpdate"\t\t"0"\n}\n',
                    "utf-8",
                )
            self._restart()
            return "queued (Steam restarting to start the download)"
        self._open_url(f"steam://install/{appid}")
        return "install dialog opened on the PC"

    def uninstall(self, appid: int) -> str:
        """Silently remove a game: stop Steam, delete files + manifest, restart Steam."""
        entry = self.manifests().get(appid)
        if not entry:
            raise ValueError("game is not installed")
        acf, state = entry
        installdir = _ci(state, "installdir", "")
        common = (acf.parent / "common").resolve()
        target = (common / installdir).resolve() if installdir else None
        # Safety: only ever delete a direct child of steamapps/common.
        if target is None or target.parent != common or target == common:
            self._open_url(f"steam://uninstall/{appid}")
            return "uninstall dialog opened on the PC"
        exe = self.exe()
        subprocess.call([exe, "-shutdown"])
        for _ in range(30):
            time.sleep(1)
            if not self._is_running():
                break
        if target.exists():
            shutil.rmtree(target, ignore_errors=True)
        acf.unlink(missing_ok=True)
        subprocess.Popen([exe, "-silent"])
        return "uninstalled"


def steam_store_search(term: str) -> list[dict]:
    q = urllib.parse.urlencode({"term": term, "l": "english", "cc": "US"})
    req = urllib.request.Request(f"https://store.steampowered.com/api/storesearch/?{q}",
                                 headers={"User-Agent": "REDLINE-agent"})
    with urllib.request.urlopen(req, timeout=10) as r:
        data = json.loads(r.read().decode("utf-8"))
    results = []
    for item in data.get("items", []):
        if item.get("type") != "app":
            continue
        appid = item["id"]
        cover, hero = steam_art(appid)
        price = item.get("price") or {}
        results.append({
            "appid": appid,
            "name": item.get("name", ""),
            "image": cover,
            "hero": hero,
            "price": (price.get("final", 0) / 100.0) if price else 0.0,
            "currency": price.get("currency", ""),
        })
    return results


# --------------------------------------------------------------------------
# Epic Games (read-only: list + launch)
# --------------------------------------------------------------------------
def epic_games() -> list[Game]:
    if not IS_WINDOWS:
        return []
    folder = Path(os.environ.get("ProgramData", r"C:\ProgramData")) / "Epic/EpicGamesLauncher/Data/Manifests"
    games = []
    for item in folder.glob("*.item"):
        try:
            m = json.loads(item.read_text("utf-8"))
        except (OSError, ValueError):
            continue
        if "games" not in [c.lower() for c in m.get("AppCategories", ["games"])]:
            continue
        games.append(Game(
            id=f"epic:{m.get('AppName')}", name=m.get("DisplayName", m.get("AppName", "?")),
            source="epic", installed=True, size_bytes=int(m.get("InstallSize", 0)),
            extra={"launch": "com.epicgames.launcher://apps/"
                   + urllib.parse.quote(f"{m.get('CatalogNamespace')}:{m.get('CatalogItemId')}:{m.get('AppName')}")
                   + "?action=launch&silent=true"},
        ))
    return games


# --------------------------------------------------------------------------
# Facade
# --------------------------------------------------------------------------
class Library:
    def __init__(self, config) -> None:
        self.config = config
        self.steam = Steam()
        self._custom_procs: dict[str, subprocess.Popen] = {}

    def custom_games(self) -> list[Game]:
        out = []
        for g in self.config["custom_games"]:
            gid = str(g.get("id") or g.get("name"))
            out.append(Game(id=f"custom:{gid}", name=g.get("name", gid), source="custom",
                            installed=Path(g.get("exe", "")).exists(), image=g.get("image", ""),
                            hero=g.get("image", ""), extra=g))
        return out

    def all(self) -> list[Game]:
        games = self.steam.games() + epic_games() + self.custom_games()
        games.sort(key=lambda g: (not g.installed, g.name.lower()))
        return games

    def find(self, game_id: str) -> Game | None:
        return next((g for g in self.all() if g.id == game_id), None)

    def running(self) -> str:
        appid = self.steam.running_appid()
        if appid:
            return f"steam:{appid}"
        for gid, p in list(self._custom_procs.items()):
            if p.poll() is None:
                return gid
            del self._custom_procs[gid]
        return ""

    def launch(self, game_id: str) -> str:
        source, _, key = game_id.partition(":")
        if source == "steam":
            self.steam.launch(int(key))
            return "launching"
        game = self.find(game_id)
        if not game:
            raise ValueError("unknown game")
        if source == "epic":
            if IS_WINDOWS:
                os.startfile(game.extra["launch"])  # type: ignore[attr-defined]
            return "launching"
        if source == "custom":
            exe = game.extra["exe"]
            self._custom_procs[game_id] = subprocess.Popen(
                [exe, *game.extra.get("args", [])], cwd=str(Path(exe).parent))
            return "launching"
        raise ValueError("unsupported source")

    def install(self, appid: int, name: str) -> str:
        if not self.steam.available:
            raise ValueError("Steam not found on this PC")
        return self.steam.install(appid, name, bool(self.config["steam_silent_install"]))

    def uninstall(self, game_id: str) -> str:
        source, _, key = game_id.partition(":")
        if source != "steam":
            raise ValueError("only Steam games can be uninstalled remotely")
        return self.steam.uninstall(int(key))

    def close_running(self) -> str:
        """Best effort: kill processes living in the running game's folder."""
        running = self.running()
        if not running:
            return "nothing running"
        try:
            import psutil
        except ImportError:
            return "install psutil to enable closing games"
        roots: list[Path] = []
        game = self.find(running)
        if game and game.source == "steam":
            roots.append((Path(game.extra["acf"]).parent / "common" / game.extra["installdir"]).resolve())
        elif game and game.source == "custom":
            roots.append(Path(game.extra["exe"]).parent.resolve())
        killed = 0
        for p in psutil.process_iter(["exe"]):
            exe = p.info.get("exe")
            if exe and any(str(Path(exe).resolve()).startswith(str(r)) for r in roots):
                try:
                    p.kill()
                    killed += 1
                except psutil.Error:
                    pass
        return f"closed {killed} process(es)"
