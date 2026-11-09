"""REDLINE stream wire protocol + an incremental FLV demuxer.

Transport: one TCP connection per stream session.

Client -> host
    1. A single UTF-8 JSON line (the "hello"):
       {"token": "...", "width": 1920, "height": 1080, "fps": 60,
        "bitrate_kbps": 20000, "codec": "h264", "audio": true}
    2. Then framed binary messages.

Every binary message (both directions) is:
    [type: u8][length: u32 big-endian][payload: length bytes]

Host -> client message types
    VIDEO_CONFIG  0x01  [codec u8 (0=h264, 1=hevc)][Annex-B parameter sets]
    VIDEO_FRAME   0x02  [flags u8 (bit0=keyframe)][pts_ms u32][Annex-B access unit]
    AUDIO_PCM     0x03  [interleaved s16le stereo @ 48 kHz]
    PONG          0x04  [echo of the ping payload]
    INFO          0x05  [UTF-8 JSON: encoder, resolution, etc.]
    CONGESTION    0x06  [dropped_frames u32] host had to drop frames
    ERROR         0x07  [UTF-8 text]

Client -> host message types
    GAMEPAD       0x10  [buttons u16][lt u8][rt u8][lx i16][ly i16][rx i16][ry i16]  (XInput layout)
    MOUSE_MOVE    0x11  [dx i16][dy i16]
    MOUSE_BUTTON  0x12  [button u8 (1=L,2=R,3=M)][down u8]
    MOUSE_ABS     0x13  [x u16][y u16]   (0..65535 normalised across the primary display)
    MOUSE_WHEEL   0x14  [delta i16]
    TEXT          0x15  [UTF-8 text to type]
    KEY           0x16  [windows virtual-key u16][down u8]
    PING          0x17  [opaque, echoed back as PONG]
"""

from __future__ import annotations

import struct
from dataclasses import dataclass

# host -> client
VIDEO_CONFIG = 0x01
VIDEO_FRAME = 0x02
AUDIO_PCM = 0x03
PONG = 0x04
INFO = 0x05
CONGESTION = 0x06
ERROR = 0x07

# client -> host
GAMEPAD = 0x10
MOUSE_MOVE = 0x11
MOUSE_BUTTON = 0x12
MOUSE_ABS = 0x13
MOUSE_WHEEL = 0x14
TEXT = 0x15
KEY = 0x16
PING = 0x17

CODEC_H264 = 0
CODEC_HEVC = 1

START_CODE = b"\x00\x00\x00\x01"
MAX_MESSAGE = 8 * 1024 * 1024


def pack(msg_type: int, payload: bytes) -> bytes:
    return struct.pack(">BI", msg_type, len(payload)) + payload


def pack_frame(keyframe: bool, pts_ms: int, data: bytes) -> bytes:
    return pack(VIDEO_FRAME, struct.pack(">BI", 1 if keyframe else 0, pts_ms & 0xFFFFFFFF) + data)


def pack_config(codec: int, data: bytes) -> bytes:
    return pack(VIDEO_CONFIG, bytes([codec]) + data)


@dataclass
class VideoConfig:
    codec: int
    annexb: bytes


@dataclass
class VideoFrame:
    codec: int
    keyframe: bool
    pts_ms: int
    annexb: bytes


def _length_prefixed_to_annexb(data: bytes, nal_len_size: int) -> bytes:
    out = bytearray()
    i = 0
    n = len(data)
    while i + nal_len_size <= n:
        size = int.from_bytes(data[i : i + nal_len_size], "big")
        i += nal_len_size
        if size == 0 or i + size > n:
            break
        out += START_CODE
        out += data[i : i + size]
        i += size
    return bytes(out)


def parse_avcc(record: bytes) -> tuple[bytes, int]:
    """AVCDecoderConfigurationRecord -> (Annex-B SPS+PPS, NAL length size)."""
    if len(record) < 7:
        return b"", 4
    nal_len_size = (record[4] & 0x03) + 1
    out = bytearray()
    i = 5
    num_sps = record[i] & 0x1F
    i += 1
    for _ in range(num_sps):
        ln = int.from_bytes(record[i : i + 2], "big")
        i += 2
        out += START_CODE + record[i : i + ln]
        i += ln
    if i < len(record):
        num_pps = record[i]
        i += 1
        for _ in range(num_pps):
            ln = int.from_bytes(record[i : i + 2], "big")
            i += 2
            out += START_CODE + record[i : i + ln]
            i += ln
    return bytes(out), nal_len_size


def parse_hvcc(record: bytes) -> tuple[bytes, int]:
    """HEVCDecoderConfigurationRecord -> (Annex-B VPS+SPS+PPS(+SEI), NAL length size)."""
    if len(record) < 23:
        return b"", 4
    nal_len_size = (record[21] & 0x03) + 1
    num_arrays = record[22]
    i = 23
    out = bytearray()
    for _ in range(num_arrays):
        if i + 3 > len(record):
            break
        i += 1  # array_completeness / reserved / NAL_unit_type
        num_nalus = int.from_bytes(record[i : i + 2], "big")
        i += 2
        for _ in range(num_nalus):
            ln = int.from_bytes(record[i : i + 2], "big")
            i += 2
            out += START_CODE + record[i : i + ln]
            i += ln
    return bytes(out), nal_len_size


class FlvDemuxer:
    """Incremental FLV demuxer for the video track (legacy AVC + Enhanced-FLV HEVC).

    ffmpeg writes FLV with an explicit size per tag, which lets us hand the
    client one complete access unit per message instead of guessing frame
    boundaries from start codes (which would add a frame of latency).
    """

    def __init__(self) -> None:
        self._buf = bytearray()
        self._header_done = False
        self._nal_len_size = 4
        self.codec = CODEC_H264

    def feed(self, data: bytes) -> list[VideoConfig | VideoFrame]:
        self._buf += data
        events: list[VideoConfig | VideoFrame] = []
        buf = self._buf
        if not self._header_done:
            if len(buf) < 9:
                return events
            if bytes(buf[:3]) != b"FLV":
                raise ValueError("not an FLV stream")
            header_size = int.from_bytes(buf[5:9], "big")
            if len(buf) < header_size + 4:
                return events
            del buf[: header_size + 4]  # header + PreviousTagSize0
            self._header_done = True

        pos = 0
        n = len(buf)
        while n - pos >= 11:
            tag_type = buf[pos] & 0x1F
            size = int.from_bytes(buf[pos + 1 : pos + 4], "big")
            ts = int.from_bytes(buf[pos + 4 : pos + 7], "big") | (buf[pos + 7] << 24)
            total = 11 + size + 4
            if n - pos < total:
                break
            if tag_type == 9 and size > 0:
                ev = self._video_tag(bytes(buf[pos + 11 : pos + 11 + size]), ts)
                if ev is not None:
                    events.append(ev)
            pos += total
        if pos:
            del buf[:pos]
        return events

    def _video_tag(self, data: bytes, ts: int) -> VideoConfig | VideoFrame | None:
        b0 = data[0]
        if b0 & 0x80:  # Enhanced RTMP / FLV (HEVC, AV1, ...)
            frame_type = (b0 >> 4) & 0x07
            packet_type = b0 & 0x0F
            fourcc = data[1:5]
            if fourcc != b"hvc1":
                return None
            self.codec = CODEC_HEVC
            body = data[5:]
            if packet_type == 0:  # SequenceStart
                annexb, self._nal_len_size = parse_hvcc(body)
                return VideoConfig(CODEC_HEVC, annexb)
            if packet_type == 1:  # CodedFrames (with composition time)
                body = body[3:]
            elif packet_type != 3:  # 3 = CodedFramesX (no composition time)
                return None
            return VideoFrame(CODEC_HEVC, frame_type == 1, ts,
                              _length_prefixed_to_annexb(body, self._nal_len_size))

        frame_type = b0 >> 4
        codec_id = b0 & 0x0F
        if codec_id not in (7, 12):  # 7 = AVC, 12 = legacy HEVC extension
            return None
        codec = CODEC_H264 if codec_id == 7 else CODEC_HEVC
        self.codec = codec
        packet_type = data[1]
        body = data[5:]
        if packet_type == 0:
            if codec == CODEC_H264:
                annexb, self._nal_len_size = parse_avcc(body)
            else:
                annexb, self._nal_len_size = parse_hvcc(body)
            return VideoConfig(codec, annexb)
        if packet_type == 1:
            return VideoFrame(codec, frame_type == 1, ts,
                              _length_prefixed_to_annexb(body, self._nal_len_size))
        return None
