"""Measure a live REDLINE stream from a PC: FPS, bitrate, ping RTT, frame-arrival jitter, host drops.

    python tests/stream_probe.py --host 127.0.0.1 --token <paired token> --seconds 20 --fps 60

It speaks the same protocol as the Thor app (redline/protocol.py) but doesn't decode video, so the numbers are
what the agent delivers: capture + encode + network. The Thor adds its decode time on top (shown on its HUD).
"""

from __future__ import annotations

import argparse
import json
import socket
import statistics
import struct
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from redline import protocol as P  # noqa: E402


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("stream closed by the agent")
        buf += chunk
    return bytes(buf)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=48101)
    ap.add_argument("--token", required=True)
    ap.add_argument("--seconds", type=float, default=20)
    ap.add_argument("--width", type=int, default=1920)
    ap.add_argument("--height", type=int, default=1080)
    ap.add_argument("--fps", type=int, default=60)
    ap.add_argument("--bitrate", type=int, default=30000, help="kbps")
    ap.add_argument("--codec", default="hevc")
    a = ap.parse_args()

    sock = socket.create_connection((a.host, a.port), timeout=10)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    hello = {"token": a.token, "width": a.width, "height": a.height, "fps": a.fps,
             "bitrate_kbps": a.bitrate, "codec": a.codec, "audio": False}
    sock.sendall((json.dumps(hello) + "\n").encode())

    rtts: list[float] = []
    sent: dict[int, float] = {}
    stop = threading.Event()
    lock = threading.Lock()

    def pinger() -> None:
        seq = 0
        while not stop.wait(0.25):
            seq += 1
            with lock:
                sent[seq] = time.perf_counter()
            try:
                sock.sendall(P.pack(P.PING, struct.pack(">I", seq)))
            except OSError:
                return

    threading.Thread(target=pinger, daemon=True).start()

    start = time.perf_counter()
    first_frame = None
    frames = keyframes = nbytes = 0
    arrivals: list[float] = []
    host_dropped = 0
    info = None
    errors: list[str] = []
    per_second: list[int] = []
    sec_mark, sec_frames = start, 0
    try:
        while time.perf_counter() - start < a.seconds:
            t, ln = struct.unpack(">BI", recv_exact(sock, 5))
            payload = recv_exact(sock, ln)
            now = time.perf_counter()
            if t == P.VIDEO_FRAME:
                if first_frame is None:
                    first_frame = now
                frames += 1
                sec_frames += 1
                keyframes += payload[0] & 1
                nbytes += ln
                arrivals.append(now)
            elif t == P.PONG and len(payload) >= 4:
                seq = struct.unpack(">I", payload[:4])[0]
                with lock:
                    t0 = sent.pop(seq, None)
                if t0 is not None:
                    rtts.append((now - t0) * 1000)
            elif t == P.CONGESTION:
                host_dropped = struct.unpack(">I", payload[:4])[0]
            elif t == P.INFO:
                info = json.loads(payload.decode("utf-8", "replace"))
            elif t == P.ERROR:
                errors.append(payload.decode("utf-8", "replace"))
            if now - sec_mark >= 1:
                per_second.append(sec_frames)
                sec_mark, sec_frames = now, 0
    except (ConnectionError, OSError) as e:
        errors.append(str(e))
    finally:
        stop.set()
        sock.close()

    span = (arrivals[-1] - arrivals[0]) if len(arrivals) > 1 else 0
    gaps = [(b - a_) * 1000 for a_, b in zip(arrivals, arrivals[1:])]
    report = {
        "requested": f"{a.width}x{a.height}@{a.fps} {a.codec} {a.bitrate} kbps",
        "agent_info": info,
        "time_to_first_frame_ms": round((first_frame - start) * 1000) if first_frame else None,
        "frames": frames,
        "keyframes": keyframes,
        "avg_fps": round((frames - 1) / span, 1) if span else 0,
        "fps_per_second": per_second,
        "mbps": round(nbytes * 8 / span / 1e6, 1) if span else 0,
        "frame_gap_ms": {
            "median": round(statistics.median(gaps), 1) if gaps else None,
            "p95": round(sorted(gaps)[int(len(gaps) * 0.95)], 1) if gaps else None,
            "max": round(max(gaps), 1) if gaps else None,
            "over_2x_target": sum(g > 2000 / a.fps for g in gaps),
        },
        "rtt_ms": {
            "median": round(statistics.median(rtts), 1) if rtts else None,
            "p95": round(sorted(rtts)[int(len(rtts) * 0.95)], 1) if rtts else None,
            "samples": len(rtts),
        },
        "host_dropped_frames": host_dropped,
        "errors": errors,
    }
    print(json.dumps(report, indent=2))
    return 0 if frames and not errors else 1


if __name__ == "__main__":
    sys.exit(main())
