#!/usr/bin/env python3
"""§61.322 · 黄金样本生成器：按 ffmpeg 参考实现产出容器帧表 JSON。

用途：把我们手写的 extractor（Kotlin）与「按 ffmpeg 源码转录的独立实现」（本脚本）
逐帧比对。脚本不依赖 ffmpeg 二进制，只依赖 `engine-opensource/src/main/jni/ffmpeg`
里的 C 源码——每个字段的取值位置都在下面的注释里标了出处，便于复核。

用法：
    python tools/gen_golden_frames.py ape analysis/l3-f3-fixtures/f3_ape.ape \
        analysis/l3-f3-fixtures/golden/ape_f3_ape.frames.tsv

输出 TSV（`#` 开头为元数据，空行与注释在单测里跳过；刻意不用 JSON——
JVM 单测里没有可靠的 JSON 依赖，手写解析器是新的 bug 来源）：
    # container=ape
    # file=...
    # file_size=...
    # header.version=...
    pos	nblocks	skip	size	avail

`avail` = 该帧在（可能被截断的）文件里实际可读字节数 = min(size, file_size - pos)。
"""
import json
import struct
import sys
from pathlib import Path


def le16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def le32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def parse_ape(data: bytes):
    """转录自 libavformat/ape.c:ape_read_header + ape_read_packet（版本 ≥ 3980 分支）。"""
    if data[:4] != b"MAC ":
        raise SystemExit("不是 APE：缺 MAC magic")
    version = le16(data, 4)
    if version < 3980:
        raise SystemExit("只支持 fileversion>=3980（老版本走 bittable 分支，未转录）")

    # ape.c:950-975 —— 描述符（descriptor）字段位置
    descriptor_len = le32(data, 8)
    header_len = le32(data, 12)
    seektable_len = le32(data, 16)
    wav_header_len = le32(data, 20)
    wav_tail_len = le32(data, 32)

    # ape.c:978-990 —— 描述符之后接 header 数据
    h = 52 if descriptor_len <= 52 else descriptor_len
    blocks_per_frame = le32(data, h + 4)
    final_frame_blocks = le32(data, h + 8)
    total_frames = le32(data, h + 12)
    if total_frames == 0:
        raise SystemExit("totalframes=0")

    junk_len = 0  # 夹具无前置 id3v2
    first_frame = junk_len + descriptor_len + header_len + seektable_len + wav_header_len
    if version < 3810:
        first_frame += total_frames

    seek_off = junk_len + descriptor_len + header_len
    frames = []
    frames.append([first_frame, blocks_per_frame, 0, 0])  # pos,nblocks,skip,size(待填)
    for i in range(1, total_frames):
        entry = le32(data, seek_off + i * 4)
        pos = entry + junk_len
        frames[i - 1][3] = pos - frames[i - 1][0]
        skip = (pos - first_frame) & 3
        frames.append([pos, blocks_per_frame, skip, 0])

    # 末帧：nblocks=finalframeblocks，size=(file_size - lastpos - wavtail) & ~3
    last = frames[-1]
    last[1] = final_frame_blocks
    final_size = len(data) - last[0] - wav_tail_len
    final_size -= final_size & 3
    if final_size <= 0:
        final_size = final_frame_blocks * 8
    last[3] = final_size

    # ape.c:1042-1052 —— skip 回退 + size 补齐，再做 align4
    for f in frames:
        if f[2]:
            f[0] -= f[2]
            f[3] += f[2]
        f[3] = (f[3] + 3) & ~3

    # ape_read_packet：一次 avio_read 读 size 字节；截断样本读不满
    out = []
    for pos, nblocks, skip, size in frames:
        avail = max(0, min(size, len(data) - pos))
        out.append([pos, nblocks, skip, size, avail])

    header = {
        "version": version,
        "blocks_per_frame": blocks_per_frame,
        "final_frame_blocks": final_frame_blocks,
        "total_frames": total_frames,
        "first_frame": first_frame,
        "wav_tail": wav_tail_len,
        "audio_data_len": le32(data, 24),
    }
    return header, out


def main(argv):
    if len(argv) != 4:
        raise SystemExit(__doc__)
    container, src, dst = argv[1], Path(argv[2]), Path(argv[3])
    if container != "ape":
        raise SystemExit("本版只实现了 ape；wavpack / asf / aiff 待补（见 pending-register D-1）")
    data = src.read_bytes()
    header, frames = parse_ape(data)
    lines = [
        "# container=ape",
        "# source=ffmpeg/libavformat/ape.c (ape_read_header + ape_read_packet)",
        f"# file={src.name}",
        f"# file_size={len(data)}",
        f"# version={header['version']}",
        f"# blocks_per_frame={header['blocks_per_frame']}",
        f"# final_frame_blocks={header['final_frame_blocks']}",
        f"# total_frames={header['total_frames']}",
        f"# first_frame={header['first_frame']}",
        f"# wav_tail={header['wav_tail']}",
        "pos\tnblocks\tskip\tsize\tavail",
    ]
    lines += ["\t".join(str(v) for v in f) for f in frames]
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text("\n".join(lines) + "\n", encoding="utf-8")
    # 自检：不变量（对齐 / 单调 / 末帧收口）
    bad = [i for i, f in enumerate(frames) if f[0] % 4 and f[2] == 0]
    mono = all(frames[i][0] >= frames[i - 1][0] for i in range(1, len(frames)))
    print(f"[OK] {src.name}: frames={len(frames)} first_frame={header['first_frame']} "
          f"blocks/frame={header['blocks_per_frame']} 非对齐帧={len(bad)} 单调={mono}")
    print(f"[OK] 写出 {dst} ({dst.stat().st_size} bytes)")


if __name__ == "__main__":
    main(sys.argv)
