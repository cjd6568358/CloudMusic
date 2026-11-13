#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""12 §61.107 / ReplayGain 真机验证用的带标签 MP3 夹具（纯离线生成）。

`f3_mp3.mp3` 本身已有 ID3v2.3 tag（magic `49443303...`）。本脚本**保留原有
frames**并追加 4 个 TXXX（ID3v2.3 TXXX = encoding(0x00) + description + NUL +
value），供 Media3 `Id3Decoder` → `TextInformationFrame` →
`Media3EngineAdapter.metadataLines` → `ReplayGainController` 链路消费。

标签值：TRACK_GAIN=-6.54 dB / ALBUM_GAIN=-3.20 dB /
TRACK_PEAK=0.98765 / ALBUM_PEAK=0.91234
→ `ReplayGain.targetVolume(TRACK)` ≈ 0.471（10^(-6.54/20)）。

产物：analysis/l3-f3-fixtures/f3_mp3_rg.mp3
      analysis/b2-replaygain-fixture-2026-11-13.json
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "analysis" / "l3-f3-fixtures" / "f3_mp3.mp3"
DST = ROOT / "analysis" / "l3-f3-fixtures" / "f3_mp3_rg.mp3"
OUT = ROOT / "analysis" / "b2-replaygain-fixture-2026-11-13.json"

TAGS = [
    ("REPLAYGAIN_TRACK_GAIN", "-6.54 dB"),
    ("REPLAYGAIN_ALBUM_GAIN", "-3.20 dB"),
    ("REPLAYGAIN_TRACK_PEAK", "0.98765"),
    ("REPLAYGAIN_ALBUM_PEAK", "0.91234"),
]


def syncsafe(n: int) -> bytes:
    return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])


def unsyncsafe(b: bytes) -> int:
    return (b[0] << 21) | (b[1] << 14) | (b[2] << 7) | b[3]


def txxx(description: str, value: str) -> bytes:
    content = b"\x00" + description.encode("latin-1") + b"\x00" + value.encode("latin-1")
    return b"TXXX" + len(content).to_bytes(4, "big") + b"\x00\x00" + content


def main() -> int:
    data = SRC.read_bytes()
    if data[:3] != b"ID3":
        print("[FAIL] 源 MP3 无 ID3v2 tag，需先改脚本支持裸 MP3")
        return 1
    version = (data[3], data[4])
    old_size = unsyncsafe(data[6:10])
    old_frames = data[10:10 + old_size]
    # §61.111 真机一手根因：原标签尾部是全零 padding（实测 77059~78138 共 1089B），
    # ID3 解析器顺序读帧遇到零填充即判帧区结束 → 追加在其后的 TXXX 帧永不被读
    # （首轮探针 diag==... 空 key 7 行、REPLAYGAIN_* 0 命中）。追加前剥离 padding，
    # size 字段由下方 syncsafe(len(old_frames)+len(added)) 同步更新。
    old_frames = old_frames.rstrip(b"\x00")
    audio = data[10 + old_size:]
    added = b"".join(txxx(k, v) for k, v in TAGS)
    new_tag = b"ID3" + bytes(version) + data[5:6] + syncsafe(len(old_frames) + len(added))
    out = new_tag + old_frames + added + audio
    DST.write_bytes(out)
    meta = {
        "method": "12 §61.107 / ReplayGain 带标签 MP3 夹具",
        "generated_by": "tools/b2_replaygain_fixture.py",
        "source": str(SRC.relative_to(ROOT)),
        "output": str(DST.relative_to(ROOT)),
        "source_bytes": len(data),
        "output_bytes": len(out),
        "id3_version": "2.%d.%d" % version,
        "original_tag_size": old_size,
        "appended_txxx": [{"description": k, "value": v} for k, v in TAGS],
        "expected_track_volume": round(10 ** (-6.54 / 20), 4),
        "sha256": hashlib.sha256(out).hexdigest(),
        "boundary": ("夹具只用于真机验证 ReplayGain 标签链路；不改写 manifest / "
                     "历史矩阵，不宣称 ReplayGain 全格式生效。"),
    }
    OUT.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[PASS] %s（%d → %d B，追加 4 个 TXXX）→ %s" % (
        DST.name, len(data), len(out), OUT.name))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
