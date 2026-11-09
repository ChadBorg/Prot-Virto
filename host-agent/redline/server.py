"""REDLINE agent HTTP API, LAN discovery and process entry point."""

from __future__ import annotations

import argparse
import asyncio
import json
import secrets
import sys
from pathlib import Path

from aiohttp import web

from . import __version__
from .config import Config
from .library import Library, steam_store_search
from .stream import StreamServer
from .system import (POWER_ACTIONS, FrameGen, Input, mac_address, power, primary_ip,
                     send_magic_packet, system_stats)

DISCOVER_MAGIC = b"REDLINE_DISCOVER"


class Agent:
    def __init__(self, config: Config, relay_only: bool = False) -> None:
        self.config = config
        self.relay_only = relay_only
        self.pin = ""
        self.new_pin()
        self.state: dict = {"streaming": False}
        self.library = Library(config)
        self.input = None if relay_only else Input()
        self.framegen = FrameGen(config, self.library.steam.root)
        self.stream = None if relay_only else StreamServer(config, self.input, self.state)

    def new_pin(self) -> None:
        """Rotate the pairing PIN and drop it in PAIRING-PIN.txt (handy when running hidden at logon)."""
        self.pin = f"{secrets.randbelow(10**6):06d}"
        try:
            (self.config.path.parent / "PAIRING-PIN.txt").write_text(
                f"REDLINE pairing PIN: {self.pin}\n(changes after each successful pairing)\n", "utf-8")
        except OSError:
            pass

    # ------------------------------------------------------------------
    def info(self) -> dict:
        return {
            "agent": "redline",
            "version": __version__,
            "name": self.config["name"],
            "role": "relay" if self.relay_only else "host",
            "mac": self.config["mac"] or mac_address(),
            "ip": primary_ip(),
            "os": sys.platform,
            "http_port": self.config["http_port"],
            "stream_port": self.config["stream_port"],
        }

    # ------------------------------------------------------------------
    @web.middleware
    async def auth(self, request: web.Request, handler):
        open_paths = {"/api/info", "/api/pair", "/api/wake"}
        if request.path in open_paths or request.method == "OPTIONS":
            return await handler(request)
        if not self.config.is_authorized(request.headers.get("X-Redline-Token")):
            return web.json_response({"error": "not paired"}, status=401)
        return await handler(request)

    @web.middleware
    async def errors(self, request: web.Request, handler):
        try:
            return await handler(request)
        except web.HTTPException:
            raise
        except (ValueError, KeyError, OSError) as exc:
            return web.json_response({"error": str(exc)}, status=400)

    def app(self) -> web.Application:
        app = web.Application(middlewares=[self.errors, self.auth])
        r = app.router
        r.add_get("/api/info", self.h_info)
        r.add_post("/api/pair", self.h_pair)
        r.add_post("/api/wake", self.h_wake)
        if not self.relay_only:
            r.add_post("/api/unpair", self.h_unpair)
            r.add_get("/api/status", self.h_status)
            r.add_get("/api/games", self.h_games)
            r.add_post("/api/games/launch", self.h_launch)
            r.add_post("/api/games/close", self.h_close)
            r.add_post("/api/games/install", self.h_install)
            r.add_post("/api/games/uninstall", self.h_uninstall)
            r.add_get("/api/store/search", self.h_search)
            r.add_post("/api/power/{action}", self.h_power)
            r.add_get("/api/framegen", self.h_framegen_get)
            r.add_post("/api/framegen", self.h_framegen_set)
            r.add_get("/api/speedtest", self.h_speedtest)
        return app

    # ------------------------------------------------------------------
    async def h_info(self, _r):
        return web.json_response(self.info())

    async def h_pair(self, request: web.Request):
        body = await request.json()
        if not secrets.compare_digest(str(body.get("pin", "")), self.pin):
            await asyncio.sleep(1)  # slow down brute force
            return web.json_response({"error": "wrong PIN"}, status=403)
        token = self.config.add_device(str(body.get("device", "handheld")))
        self.new_pin()  # one PIN, one device
        print(f"[pair] paired '{body.get('device')}'. New PIN for the next device: {self.pin}")
        return web.json_response({"token": token, **self.info()})

    async def h_unpair(self, request: web.Request):
        self.config.remove_device(request.headers.get("X-Redline-Token", ""))
        return web.json_response({"ok": True})

    async def h_wake(self, request: web.Request):
        body = await request.json()
        send_magic_packet(str(body["mac"]), str(body.get("broadcast", "255.255.255.255")))
        return web.json_response({"ok": True})

    async def h_status(self, _r):
        loop = asyncio.get_running_loop()
        stats = await loop.run_in_executor(None, system_stats)
        running = await loop.run_in_executor(None, self.library.running)
        name = ""
        if running:
            g = await loop.run_in_executor(None, self.library.find, running)
            name = g.name if g else running
        return web.json_response({
            **self.info(), **stats,
            "running_game": running, "running_game_name": name,
            "stream": self.state,
            "framegen": await loop.run_in_executor(None, self.framegen.state),
        })

    async def h_games(self, _r):
        games = await asyncio.get_running_loop().run_in_executor(None, self.library.all)
        return web.json_response({"games": [g.to_json() for g in games]})

    async def h_launch(self, request: web.Request):
        body = await request.json()
        msg = await asyncio.get_running_loop().run_in_executor(None, self.library.launch, body["id"])
        return web.json_response({"ok": True, "message": msg})

    async def h_close(self, _r):
        msg = await asyncio.get_running_loop().run_in_executor(None, self.library.close_running)
        return web.json_response({"ok": True, "message": msg})

    async def h_install(self, request: web.Request):
        body = await request.json()
        msg = await asyncio.get_running_loop().run_in_executor(
            None, self.library.install, int(body["appid"]), str(body.get("name", "")))
        return web.json_response({"ok": True, "message": msg})

    async def h_uninstall(self, request: web.Request):
        body = await request.json()
        msg = await asyncio.get_running_loop().run_in_executor(None, self.library.uninstall, body["id"])
        return web.json_response({"ok": True, "message": msg})

    async def h_search(self, request: web.Request):
        q = request.query.get("q", "").strip()
        if not q:
            return web.json_response({"results": []})
        results = await asyncio.get_running_loop().run_in_executor(None, steam_store_search, q)
        owned = {g.id for g in self.library.steam.games()}
        for r in results:
            r["installed"] = f"steam:{r['appid']}" in owned
        return web.json_response({"results": results})

    async def h_power(self, request: web.Request):
        action = request.match_info["action"]
        if action not in POWER_ACTIONS:
            return web.json_response({"error": "unknown action"}, status=404)
        power(action)
        return web.json_response({"ok": True, "action": action})

    async def h_framegen_get(self, _r):
        return web.json_response(await asyncio.get_running_loop().run_in_executor(None, self.framegen.state))

    async def h_framegen_set(self, request: web.Request):
        body = await request.json()
        st = await asyncio.get_running_loop().run_in_executor(
            None, self.framegen.set_enabled, bool(body.get("enabled")))
        return web.json_response(st)

    async def h_speedtest(self, request: web.Request):
        """Streams N bytes so the app can measure throughput and pick a bitrate."""
        size = min(int(request.query.get("bytes", 4_000_000)), 32_000_000)
        resp = web.StreamResponse(headers={"Content-Type": "application/octet-stream",
                                           "Content-Length": str(size), "Cache-Control": "no-store"})
        await resp.prepare(request)
        chunk = secrets.token_bytes(64 * 1024)
        sent = 0
        while sent < size:
            part = chunk[: min(len(chunk), size - sent)]
            await resp.write(part)
            sent += len(part)
        await resp.write_eof()
        return resp


class DiscoveryProtocol(asyncio.DatagramProtocol):
    def __init__(self, agent: Agent) -> None:
        self.agent = agent
        self.transport = None

    def connection_made(self, transport) -> None:
        self.transport = transport

    def datagram_received(self, data: bytes, addr) -> None:
        if data.strip() == DISCOVER_MAGIC and self.transport:
            self.transport.sendto(json.dumps(self.agent.info()).encode(), addr)


async def run(agent: Agent) -> None:
    cfg = agent.config
    runner = web.AppRunner(agent.app())
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", cfg["http_port"]).start()
    loop = asyncio.get_running_loop()
    try:
        await loop.create_datagram_endpoint(lambda: DiscoveryProtocol(agent),
                                            local_addr=("0.0.0.0", cfg["discovery_port"]),
                                            allow_broadcast=True)
    except OSError as exc:
        print(f"[discovery] disabled: {exc}")

    info = agent.info()
    bar = "=" * 58
    print(bar)
    print(f"  REDLINE agent v{__version__}  ({info['role']})  —  {info['name']}")
    print(f"  address : {info['ip']}:{cfg['http_port']}   MAC: {info['mac']}")
    print(f"  PAIR PIN: {agent.pin}")
    print(bar)
    if agent.stream:
        await agent.stream.start()
    else:
        await asyncio.Event().wait()


def main() -> None:
    ap = argparse.ArgumentParser(prog="redline-agent", description="REDLINE PC host agent")
    ap.add_argument("--config", default=str(Path(__file__).resolve().parent.parent / "redline_config.json"))
    ap.add_argument("--relay", action="store_true",
                    help="run as an always-on Wake-on-LAN relay (e.g. on a Raspberry Pi)")
    args = ap.parse_args()
    if sys.stdout is None:  # started with pythonw (autostart): log to a file instead
        log = open(Path(args.config).with_name("redline_agent.log"), "a", buffering=1, encoding="utf-8")
        sys.stdout = sys.stderr = log
    agent = Agent(Config(Path(args.config)), relay_only=args.relay)
    try:
        asyncio.run(run(agent))
    except KeyboardInterrupt:
        pass
