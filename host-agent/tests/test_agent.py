import asyncio
import json
import shutil
import struct
import subprocess
from pathlib import Path

import pytest
from aiohttp.test_utils import TestClient, TestServer

from redline import protocol as P
from redline.config import Config
from redline.library import Steam, parse_vdf
from redline.server import Agent
from redline.stream import build_ffmpeg_cmd

HAS_FFMPEG = shutil.which("ffmpeg") is not None


def _encode_flv(codec_args: list[str], frames: int = 40) -> bytes:
    return subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
         "testsrc=size=320x240:rate=30", "-frames:v", str(frames), *codec_args,
         "-g", "30", "-bf", "0", "-f", "flv", "-"],
        capture_output=True, check=True).stdout


@pytest.mark.skipif(not HAS_FFMPEG, reason="ffmpeg not installed")
@pytest.mark.parametrize("codec_args,codec,nal_type", [
    (["-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency"], P.CODEC_H264, lambda b: b & 0x1F),
    (["-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=error"], P.CODEC_HEVC,
     lambda b: (b >> 1) & 0x3F),
])
def test_flv_demux_gives_annexb_access_units(codec_args, codec, nal_type):
    data = _encode_flv(codec_args)
    demux = P.FlvDemuxer()
    events = []
    # feed in awkward chunk sizes to exercise the incremental path
    for i in range(0, len(data), 777):
        events += demux.feed(data[i:i + 777])

    configs = [e for e in events if isinstance(e, P.VideoConfig)]
    frames = [e for e in events if isinstance(e, P.VideoFrame)]
    assert len(configs) == 1 and configs[0].codec == codec
    assert configs[0].annexb.startswith(P.START_CODE)
    types = {nal_type(n[0]) for n in configs[0].annexb.split(P.START_CODE) if n}
    assert types >= ({7, 8} if codec == P.CODEC_H264 else {32, 33, 34})
    assert len(frames) == 40
    assert frames[0].keyframe and frames[30].keyframe and not frames[1].keyframe
    assert all(f.annexb.startswith(P.START_CODE) for f in frames)
    assert frames[-1].pts_ms > frames[0].pts_ms


def test_vdf_and_steam_manifests(tmp_path: Path):
    apps = tmp_path / "steamapps"
    (apps / "common" / "Cool Game").mkdir(parents=True)
    (apps / "libraryfolders.vdf").write_text(
        '"libraryfolders"\n{\n\t"0"\n\t{\n\t\t"path"\t\t"%s"\n\t}\n}\n' % str(tmp_path).replace("\\", "\\\\"))
    (apps / "appmanifest_620.acf").write_text(
        '"AppState"\n{\n\t"appid"\t\t"620"\n\t"name"\t\t"Cool Game"\n\t"StateFlags"\t\t"4"\n'
        '\t"installdir"\t\t"Cool Game"\n\t"SizeOnDisk"\t\t"123"\n}\n')
    (apps / "appmanifest_730.acf").write_text(
        '"AppState"\n{\n\t"appid"\t\t"730"\n\t"name"\t\t"Downloading"\n\t"StateFlags"\t\t"1026"\n'
        '\t"BytesToDownload"\t\t"200"\n\t"BytesDownloaded"\t\t"50"\n}\n')
    (apps / "appmanifest_228980.acf").write_text('"AppState"{"appid" "228980" "name" "Redist"}')

    assert parse_vdf('"a" { "b" "c" "d" { "e" "f" } }') == {"a": {"b": "c", "d": {"e": "f"}}}

    steam = Steam.__new__(Steam)
    steam.root = tmp_path
    games = {g.id: g for g in steam.games()}
    assert set(games) == {"steam:620", "steam:730"}
    assert games["steam:620"].installed and games["steam:620"].size_bytes == 123
    assert not games["steam:730"].installed and games["steam:730"].progress == 0.25


def test_ffmpeg_cmd_zero_copy_vs_scaled():
    native = build_ffmpeg_cmd("ffmpeg", "h264_nvenc", "ddagrab", 0, 0, 0, 120, 30000)
    assert "-vf" not in native and "ddagrab=output_idx=0:framerate=120:draw_mouse=1" in native
    assert native[-3:] == ["-f", "flv", "-"] and "-bf" in native
    scaled = build_ffmpeg_cmd("ffmpeg", "libx264", "ddagrab", 1, 1280, 720, 60, 10000)
    vf = scaled[scaled.index("-vf") + 1]
    assert vf.startswith("hwdownload,format=bgra,scale=1280:720") and vf.endswith("yuv420p")


def test_ffmpeg_cmd_auto_keeps_gpu_path_native():
    # ddagrab + NVENC with host_scaling=auto: no hwdownload/scale even when 1080p is asked for
    auto = build_ffmpeg_cmd("ffmpeg", "hevc_nvenc", "ddagrab", 0, 1920, 1080, 60, 30000, host_scaling="auto")
    assert "-vf" not in auto
    forced = build_ffmpeg_cmd("ffmpeg", "hevc_nvenc", "ddagrab", 0, 1920, 1080, 60, 30000, host_scaling="always")
    assert forced[forced.index("-vf") + 1].startswith("hwdownload,format=bgra,scale=1920:1080")
    # software encoders still need the frames on the CPU, so they keep scaling
    sw = build_ffmpeg_cmd("ffmpeg", "libx264", "ddagrab", 0, 1280, 720, 60, 10000, host_scaling="auto")
    assert "scale=1280:720" in sw[sw.index("-vf") + 1]


def test_wol_mac_prefers_wired_nic_when_on_wifi():
    import psutil
    from types import SimpleNamespace as NS

    from redline.system import pick_wol_mac

    def nic(mac, ip):
        return [NS(family=psutil.AF_LINK, address=mac), NS(family=2, address=ip)]

    nics = {"Wi-Fi": nic("C8-5E-A9-53-67-E5", "10.0.0.200"),
            "Ethernet 3": nic("E8-9C-25-6D-B7-AE", "10.0.0.109"),
            "vEthernet (WSL)": nic("00-15-5D-00-00-01", "172.20.0.1")}
    # on Wi-Fi now: wake through the cable port
    assert pick_wol_mac(nics, "10.0.0.200") == "E8:9C:25:6D:B7:AE"
    # on the cable: that one, obviously
    assert pick_wol_mac(nics, "10.0.0.109") == "E8:9C:25:6D:B7:AE"
    # Wi-Fi only machine: Wi-Fi is all there is
    assert pick_wol_mac({"Wi-Fi": nics["Wi-Fi"]}, "10.0.0.200") == "C8:5E:A9:53:67:E5"


def _agent(tmp_path: Path) -> Agent:
    return Agent(Config(tmp_path / "cfg.json"))


def test_pairing_and_auth(tmp_path: Path):
    async def go():
        agent = _agent(tmp_path)
        async with TestClient(TestServer(agent.app())) as c:
            assert (await c.get("/api/info")).status == 200
            assert (await c.get("/api/status")).status == 401
            bad = await c.post("/api/pair", json={"pin": "nope", "device": "thor"})
            assert bad.status == 403
            ok = await c.post("/api/pair", json={"pin": agent.pin, "device": "thor"})
            token = (await ok.json())["token"]
            st = await c.get("/api/status", headers={"X-Redline-Token": token})
            assert st.status == 200 and "stream" in await st.json()
            g = await c.get("/api/games", headers={"X-Redline-Token": token})
            assert g.status == 200
            # PIN rotates after a successful pair
            again = await c.post("/api/pair", json={"pin": "000000" if agent.pin != "000000" else "1",
                                                   "device": "x"})
            assert again.status == 403
    asyncio.run(go())


@pytest.mark.skipif(not HAS_FFMPEG, reason="ffmpeg not installed")
def test_stream_end_to_end(tmp_path: Path):
    async def go():
        agent = _agent(tmp_path)
        token = agent.config.add_device("thor")
        agent.config.data["stream_port"] = 0
        agent.config.data["encoder"] = "libx264"
        agent.stream.source_override = ["-re", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30"]
        server = await asyncio.start_server(agent.stream._handle, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        writer.write(json.dumps({"token": token, "fps": 30, "bitrate_kbps": 2000,
                                 "codec": "h264", "audio": False}).encode() + b"\n")
        writer.write(P.pack(P.PING, b"12345678"))
        await writer.drain()
        seen = {}
        while len(seen.get(P.VIDEO_FRAME, [])) < 10:
            mtype, ln = struct.unpack(">BI", await asyncio.wait_for(reader.readexactly(5), 15))
            seen.setdefault(mtype, []).append(await reader.readexactly(ln))
        writer.close()
        server.close()
        assert json.loads(seen[P.INFO][0])["encoder"] == "libx264"
        assert seen[P.PONG] == [b"12345678"]
        assert seen[P.VIDEO_CONFIG][0][0] == P.CODEC_H264
        first = seen[P.VIDEO_FRAME][0]
        assert first[0] & 1 and first[5:9] == P.START_CODE
    asyncio.run(go())
