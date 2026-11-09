"""Low-latency desktop streaming: ffmpeg capture/encode -> FLV demux -> REDLINE protocol."""

from __future__ import annotations

import asyncio
import json
import shutil
import struct
import subprocess
import sys
import threading
from pathlib import Path

from . import protocol as P
from .system import Input

IS_WINDOWS = sys.platform == "win32"
NO_WINDOW = 0x08000000 if IS_WINDOWS else 0

ENCODER_PREFERENCE = {
    "h264": ["h264_nvenc", "h264_amf", "h264_qsv", "libx264"],
    "hevc": ["hevc_nvenc", "hevc_amf", "hevc_qsv", "libx265"],
}


def ffmpeg_binary(configured: str) -> str:
    local = Path(__file__).resolve().parent.parent / ("ffmpeg.exe" if IS_WINDOWS else "ffmpeg")
    if local.exists():
        return str(local)
    return shutil.which(configured) or configured


_encoder_cache: dict[str, bool] = {}


def encoder_works(ffmpeg: str, encoder: str) -> bool:
    """Try a tiny encode; hardware encoders are listed even when no GPU supports them."""
    if encoder in _encoder_cache:
        return _encoder_cache[encoder]
    try:
        r = subprocess.run(
            [ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
             "color=black:s=256x256:r=30", "-frames:v", "2", "-c:v", encoder, "-f", "null", "-"],
            capture_output=True, timeout=15, creationflags=NO_WINDOW,
        )
        ok = r.returncode == 0
    except (OSError, subprocess.SubprocessError):
        ok = False
    _encoder_cache[encoder] = ok
    return ok


def pick_encoder(ffmpeg: str, codec: str, configured: str) -> str:
    if configured and configured != "auto":
        return configured
    for enc in ENCODER_PREFERENCE.get(codec, ENCODER_PREFERENCE["h264"]):
        if encoder_works(ffmpeg, enc):
            return enc
    return "libx264"


def build_ffmpeg_cmd(ffmpeg: str, encoder: str, capture: str, monitor: int, width: int,
                     height: int, fps: int, bitrate_kbps: int, source_override: list[str] | None = None) -> list[str]:
    hw_capture = capture == "ddagrab"
    hw_encoder = any(encoder.endswith(s) for s in ("_nvenc", "_amf"))

    if source_override:
        src = source_override
        hw_capture = False
    elif hw_capture:
        src = ["-f", "lavfi", "-i", f"ddagrab=output_idx={monitor}:framerate={fps}:draw_mouse=1"]
    elif IS_WINDOWS:
        src = ["-f", "gdigrab", "-framerate", str(fps), "-draw_mouse", "1", "-i", "desktop"]
    else:
        src = ["-f", "x11grab", "-framerate", str(fps), "-i", ":0.0"]

    scale = f"scale={width}:{height}:flags=fast_bilinear" if width and height else ""
    filters: list[str] = []
    if hw_capture and hw_encoder and not scale:
        pass  # zero-copy: D3D11 frames go straight into NVENC/AMF
    else:
        if hw_capture:
            filters += ["hwdownload", "format=bgra"]
        if scale:
            filters.append(scale)
        filters.append("format=nv12" if hw_encoder or encoder.endswith("_qsv") else "format=yuv420p")

    gop = max(fps, 30)  # keyframe each second: fast recovery if the client skips ahead
    vbv = max(bitrate_kbps // max(fps, 1) * 2, 200)  # ~2 frames of buffer = low latency
    rate = ["-b:v", f"{bitrate_kbps}k", "-maxrate", f"{bitrate_kbps}k", "-bufsize", f"{vbv}k"]

    if encoder.endswith("_nvenc"):
        enc = ["-c:v", encoder, "-preset", "p1", "-tune", "ull", "-zerolatency", "1",
               "-rc", "cbr", "-delay", "0", "-forced-idr", "1"]
    elif encoder.endswith("_amf"):
        enc = ["-c:v", encoder, "-usage", "ultralowlatency", "-quality", "speed", "-rc", "cbr"]
    elif encoder.endswith("_qsv"):
        enc = ["-c:v", encoder, "-preset", "veryfast", "-low_power", "1", "-async_depth", "1"]
    elif encoder == "libx265":
        enc = ["-c:v", encoder, "-preset", "ultrafast", "-tune", "zerolatency",
               "-x265-params", "log-level=error"]
    else:
        enc = ["-c:v", encoder, "-preset", "ultrafast", "-tune", "zerolatency"]

    cmd = [ffmpeg, "-hide_banner", "-loglevel", "error", "-fflags", "nobuffer", *src]
    if filters:
        cmd += ["-vf", ",".join(filters)]
    cmd += [*enc, *rate, "-g", str(gop), "-bf", "0", "-an",
            "-flush_packets", "1", "-f", "flv", "-"]
    return cmd


class AudioCapture:
    """WASAPI loopback (what you hear) via the optional `soundcard` package."""

    BLOCK = 480  # 10 ms @ 48 kHz

    def __init__(self, on_pcm) -> None:
        self.on_pcm = on_pcm
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.error = ""

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _run(self) -> None:
        try:
            import numpy as np
            import soundcard as sc

            speaker = sc.default_speaker()
            mic = sc.get_microphone(id=str(speaker.name), include_loopback=True)
            with mic.recorder(samplerate=48000, channels=2, blocksize=self.BLOCK) as rec:
                while not self._stop.is_set():
                    data = rec.record(numframes=self.BLOCK)
                    pcm = (np.clip(data, -1.0, 1.0) * 32767).astype("<i2").tobytes()
                    self.on_pcm(pcm)
        except Exception as exc:  # noqa: BLE001
            self.error = f"audio unavailable: {exc}"
            print(f"[stream] {self.error}")


class StreamServer:
    """Accepts one stream client at a time on the stream port."""

    # If more than this many bytes sit unsent in the socket buffer, we're
    # congested: drop frames until the next keyframe instead of letting
    # latency balloon.
    CONGESTION_BYTES = 1_500_000

    def __init__(self, config, input_: Input, state: dict) -> None:
        self.config = config
        self.input = input_
        self.state = state  # shared, shown on /api/status
        self._active: asyncio.Task | None = None
        self._active_writer: asyncio.StreamWriter | None = None
        self.source_override: list[str] | None = None  # tests only (e.g. lavfi testsrc)

    async def start(self) -> None:
        server = await asyncio.start_server(self._handle, "0.0.0.0", self.config["stream_port"])
        print(f"[stream] listening on :{self.config['stream_port']}")
        async with server:
            await server.serve_forever()

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peer = writer.get_extra_info("peername")
        try:
            line = await asyncio.wait_for(reader.readline(), 10)
            hello = json.loads(line.decode("utf-8"))
        except (asyncio.TimeoutError, ValueError, UnicodeDecodeError):
            writer.close()
            return
        if not self.config.is_authorized(hello.get("token")):
            writer.write(P.pack(P.ERROR, b"not paired"))
            await writer.drain()
            writer.close()
            return

        # Kick any previous session: one stream at a time.
        if self._active_writer is not None:
            self._active_writer.close()
        self._active_writer = writer
        sock = writer.get_extra_info("socket")
        if sock is not None:
            import socket as _s

            sock.setsockopt(_s.IPPROTO_TCP, _s.TCP_NODELAY, 1)
        print(f"[stream] session from {peer}: {hello}")
        try:
            await self._session(reader, writer, hello)
        finally:
            if self._active_writer is writer:
                self._active_writer = None
            self.state["streaming"] = False
            self.input.release_all()
            writer.close()
            print(f"[stream] session from {peer} ended")

    async def _session(self, reader, writer, hello: dict) -> None:
        loop = asyncio.get_running_loop()
        fps = int(hello.get("fps", 60))
        width = int(hello.get("width", 0))
        height = int(hello.get("height", 0))
        bitrate = int(hello.get("bitrate_kbps", 20000))
        codec = "hevc" if hello.get("codec") == "hevc" else "h264"
        ffmpeg = ffmpeg_binary(self.config["ffmpeg"])
        encoder = await loop.run_in_executor(None, pick_encoder, ffmpeg, codec, self.config["encoder"])
        cmd = build_ffmpeg_cmd(ffmpeg, encoder, self.config["capture"], int(self.config["monitor_index"]),
                               width, height, fps, bitrate, self.source_override)
        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
                creationflags=NO_WINDOW)
        except OSError as exc:
            writer.write(P.pack(P.ERROR, f"could not start ffmpeg: {exc}".encode()))
            await writer.drain()
            return

        self.state.update(streaming=True, encoder=encoder, fps=fps, bitrate_kbps=bitrate,
                          resolution=f"{width}x{height}" if width else "native")
        info = {"encoder": encoder, "codec": codec, "fps": fps, "bitrate_kbps": bitrate,
                "width": width, "height": height, "gamepad": self.input.pad is not None,
                "gamepad_error": self.input.pad_error}
        writer.write(P.pack(P.INFO, json.dumps(info).encode()))

        audio = None
        if hello.get("audio", True):
            def on_pcm(pcm: bytes) -> None:
                loop.call_soon_threadsafe(self._send_audio, writer, pcm)
            audio = AudioCapture(on_pcm)
            audio.start()

        video = asyncio.create_task(self._pump_video(proc, writer))
        inputs = asyncio.create_task(self._pump_input(reader, writer))
        done, pending = await asyncio.wait({video, inputs}, return_when=asyncio.FIRST_COMPLETED)
        for t in pending:
            t.cancel()
        for t in done:
            if not t.cancelled():
                t.exception()  # client hang-ups are expected; just mark as retrieved
        if audio:
            audio.stop()
        if proc.returncode is None:
            proc.kill()
        try:
            err = (await asyncio.wait_for(proc.stderr.read(), 2)).decode(errors="replace").strip()
        except (asyncio.TimeoutError, OSError):
            err = ""
        if err:
            print(f"[stream] ffmpeg: {err[-800:]}")
            if video in done and not writer.is_closing():
                writer.write(P.pack(P.ERROR, f"encoder stopped: {err[-300:]}".encode()))
                try:
                    await writer.drain()
                except ConnectionError:
                    pass

    def _send_audio(self, writer: asyncio.StreamWriter, pcm: bytes) -> None:
        if writer.is_closing():
            return
        if writer.transport.get_write_buffer_size() > self.CONGESTION_BYTES:
            return  # audio is cheap to drop
        writer.write(P.pack(P.AUDIO_PCM, pcm))

    async def _pump_video(self, proc, writer: asyncio.StreamWriter) -> None:
        demux = P.FlvDemuxer()
        waiting_for_key = False
        dropped = 0
        config_sent = False
        while True:
            chunk = await proc.stdout.read(256 * 1024)
            if not chunk:
                return
            for ev in demux.feed(chunk):
                if isinstance(ev, P.VideoConfig):
                    writer.write(P.pack_config(ev.codec, ev.annexb))
                    config_sent = True
                    continue
                if not config_sent or not ev.annexb:
                    continue
                congested = writer.transport.get_write_buffer_size() > self.CONGESTION_BYTES
                if congested and not ev.keyframe:
                    waiting_for_key = True
                if waiting_for_key:
                    if ev.keyframe and not congested:
                        waiting_for_key = False
                        if dropped:
                            writer.write(P.pack(P.CONGESTION, struct.pack(">I", dropped)))
                            dropped = 0
                    else:
                        dropped += 1
                        continue
                writer.write(P.pack_frame(ev.keyframe, ev.pts_ms, ev.annexb))
            # Yield to the event loop; never block on drain (we drop instead).
            if writer.is_closing():
                return
            await asyncio.sleep(0)

    async def _pump_input(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        inp = self.input
        while True:
            header = await reader.readexactly(5)
            mtype, length = struct.unpack(">BI", header)
            if length > 65536:
                return
            payload = await reader.readexactly(length) if length else b""
            try:
                if mtype == P.GAMEPAD and length >= 12:
                    inp.gamepad(*struct.unpack(">HBBhhhh", payload[:12]))
                elif mtype == P.MOUSE_MOVE:
                    inp.mouse_move(*struct.unpack(">hh", payload[:4]))
                elif mtype == P.MOUSE_ABS:
                    inp.mouse_abs(*struct.unpack(">HH", payload[:4]))
                elif mtype == P.MOUSE_BUTTON:
                    inp.mouse_button(payload[0], bool(payload[1]))
                elif mtype == P.MOUSE_WHEEL:
                    inp.mouse_wheel(struct.unpack(">h", payload[:2])[0])
                elif mtype == P.TEXT:
                    inp.text(payload.decode("utf-8", errors="ignore"))
                elif mtype == P.KEY:
                    vk, down = struct.unpack(">HB", payload[:3])
                    inp.key(vk, bool(down))
                elif mtype == P.PING:
                    writer.write(P.pack(P.PONG, payload))
            except (struct.error, IndexError):
                continue

