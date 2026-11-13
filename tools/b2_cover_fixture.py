#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""§61.112 · 带内嵌封面 MP3 夹具（播放页封面驱动背景 / 圆形真实封面真机验证用）。

一手背景（12 §61.111④）：f3 系列样本均无封面 → 播放页永远走 #262626 回退态，
「封面驱动背景」六项代码面在真机上未展示过。本工具从 f3_mp3.mp3 生成
f3_mp3_cover.mp3：追加一个 APIC 帧（512×512 蓝青垂直渐变 PNG，纯 stdlib zlib
编码，不引入第三方依赖、不复用竞品素材——测试夹具不是产品资产）。
真机验证链：playLocal（§61.112 已接 JAudioTagger/CoverWriter）→ coverRef 落盘
→ 播放页圆形真实封面 + 主色渐变背景。

诚实边界：验证只覆盖「MP3 ID3v2.3 APIC + playLocal 注入」通道；扫描管线
（SCAN 扫出同目录封面 / content:// MediaStore 通道）不在此列。
"""
from __future__ import annotations

import json
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "analysis" / "l3-f3-fixtures" / "f3_mp3.mp3"
OUT = ROOT / "analysis" / "l3-f3-fixtures" / "f3_mp3_cover.mp3"
META = ROOT / "analysis" / "b2-cover-fixture-2026-11-14.json"

SIZE = 512


def make_gradient_png(w: int, h: int) -> bytes:
    """垂直渐变 PNG：顶 #1E3A5F（深蓝）→ 底 #4A7BA6（灰蓝），贴近基准图色带。"""

    def row_bytes(y: int) -> bytes:
        t = y / (h - 1)
        r = int(0x1E + (0x4A - 0x1E) * t)
        g = int(0x3A + (0x7B - 0x3A) * t)
        b = int(0x5F + (0xA6 - 0x5F) * t)
        return b"\x00" + bytes([r, g, b]) * w   # filter 0 + RGB 行

    raw = b"".join(row_bytes(y) for y in range(h))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)   # 8bit RGB
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) +
            chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def unsyncsafe(b: bytes) -> int:
    return (b[0] << 21) | (b[1] << 14) | (b[2] << 7) | b[3]


def syncsafe(n: int) -> bytes:
    return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, n & 0x7F, 0]) if False else \
        bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])


def apic(png: bytes, mime: str = "image/png") -> bytes:
    body = (b"\x00"                                   # encoding: latin-1
            + mime.encode("latin-1") + b"\x00"
            + b"\x03"                                 # picture type: front cover
            + b"\x00"                                 # description 空
            + png)
    # ID3v2.3：普通大端 u32 帧长（§61.107 同款教训——v2.3 帧头不是 synchsafe）
    return b"APIC" + len(body).to_bytes(4, "big") + b"\x00\x00" + body


def main() -> int:
    data = SRC.read_bytes()
    if data[:3] != b"ID3":
        print("[FAIL] 源 MP3 无 ID3v2 tag")
        return 1
    old_frames = data[10:10 + unsyncsafe(data[6:10])].rstrip(b"\x00")
    audio = data[10 + unsyncsafe(data[6:10]):]
    png = make_gradient_png(SIZE, SIZE)
    added = apic(png)
    new_tag = (b"ID3" + bytes([data[3], data[4]]) + data[5:6]
               + syncsafe(len(old_frames) + len(added)))
    OUT.write_bytes(new_tag + old_frames + added + audio)
    meta = {
        "method": "12 §61.112 / 带内嵌封面 MP3 夹具（播放页封面驱动验证）",
        "generated_by": "tools/b2_cover_fixture.py",
        "source": str(SRC.relative_to(ROOT)),
        "output": str(OUT.relative_to(ROOT)),
        "png_size": len(png),
        "apic_size": len(added),
        "source_bytes": len(data),
        "output_bytes": OUT.stat().st_size,
        "png_sha256": __import__("hashlib").sha256(png).hexdigest(),
        "boundary": "夹具只用于真机验证封面链路（APIC + playLocal 注入通道）；"
                    "不复用竞品素材、不宣称扫描管线全通道已验证。",
    }
    META.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")
    print("[PASS] %s（+%d B APIC）→ %s" % (OUT.name, len(added), META.name))
    return 0


if __name__ == "__main__":
    sys.exit(main())
