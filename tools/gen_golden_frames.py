#!/usr/bin/env python3
"""§61.322 / §61.347 · 黄金样本生成器：按参考实现产出容器帧表 TSV。

用途：把我们手写的 extractor（Kotlin）与「按参考源码转录的独立实现」（本脚本）
逐帧/逐块比对。脚本不依赖 ffmpeg 二进制，只依赖 `engine-opensource/src/main/jni/ffmpeg`
里的 C 源码或项目实测钉死的布局契约——每个字段的取值位置都在注释里标了出处。

用法：
    python tools/gen_golden_frames.py <container> <src> <dst>

已实现容器：
    ape      —— 转录 libavformat/ape.c（ape_read_header + ape_read_packet）
    wavpack  —— 转录 libavformat/wv.c:ff_wv_parse_header + wvdec.c（逐块）
    aiff     —— 头字段转录 libavformat/aiffdec.c:aiff_read_header；数据分段按项目
                AiffExtractor.SAMPLE_CHUNK_BYTES 契约（§61.102，非 ffmpeg 分块策略）
    asf      —— 转录 asfdec_f.c 的 Header Object/FileProps 字段 + 项目实测钉死的
                包布局契约（§61.94/§61.107：27B 头 + block_align 载荷 + padding）

输出 TSV（`#` 开头为元数据，空行与注释在单测里跳过；刻意不用 JSON——
JVM 单测里没有可靠的 JSON 依赖，手写解析器是新的 bug 来源）。
`avail` / `body_size` / `length` = 在（可能被截断的）文件里实际可读字节数。
"""
import struct
import sys
from pathlib import Path


def le16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def le32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def be16(b, o):
    return struct.unpack_from(">H", b, o)[0]


def be32(b, o):
    return struct.unpack_from(">I", b, o)[0]


def be64(b, o):
    return struct.unpack_from(">Q", b, o)[0]


def le64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


# ---------------------------------------------------------------- §61.322 APE ----
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


def ape_meta(src, data, header):
    # 逐字节保持 §61.322 既有输出（顺序/字段名不变）
    return [
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


# ------------------------------------------------------------ §61.347 WavPack ----
def parse_wavpack(data: bytes):
    """逐块扫描 wvpk：字段位置转录自 wv.c:ff_wv_parse_header（行 30-52）。

    块布局（32B 头 + body）：
      ckSize@4（= body + 24，ffmpeg 先读后减 24 → wc->header.blocksize）
      version@8 / total_samples@12 / block_index@16 / samples@20 /
      flags@24 / crc@28 —— 与 wv.c AV_RL32(data+N) 逐字段对应。
    每块总长 = ckSize + 8（ffmpeg wv_read_packet: blocksize + WV_HEADER_SIZE）。
    """
    if data[:4] != b"wvpk":
        raise SystemExit("不是 WavPack：首块缺 wvpk magic")
    blocks = []
    pos = 0
    while pos + 32 <= len(data) and data[pos:pos + 4] == b"wvpk":
        ck = le32(data, pos + 4)
        if ck < 24 or ck > (1 << 20):
            raise SystemExit("非法 ckSize=%d @%d" % (ck, pos))
        total = ck + 8
        body = ck - 24
        blocks.append([
            pos, ck, le16(data, pos + 8), le32(data, pos + 12),
            le32(data, pos + 16), le32(data, pos + 20),
            le32(data, pos + 24), le32(data, pos + 28), body,
        ])
        pos += total
    if not blocks:
        raise SystemExit("零个 wvpk 块")
    header = {
        "block_count": len(blocks),
        "data_end": pos,
        "tail": len(data) - pos,
    }
    return header, blocks


def wavpack_meta(src, data, header):
    return [
        "# container=wavpack",
        "# source=ffmpeg/libavformat/wv.c:ff_wv_parse_header + wvdec.c:wv_read_packet (per-block)",
        f"# file={src.name}",
        f"# file_size={len(data)}",
        f"# block_count={header['block_count']}",
        f"# data_end={header['data_end']}",
        f"# tail_bytes={header['tail']}",
        "pos\tcksize\tversion\ttotal_samples\tblock_index\tsamples\tflags\tcrc\tbody_size",
    ]


# ---------------------------------------------------------------- §61.347 AIFF ----
def aiff_rate80(b, o):
    """80-bit IEEE extended（AIFF COMM 采样率）→ Int；与 aiffdec.c:ff_aiff_get_sample_rate 同语义。"""
    exp = ((b[o] & 0x7F) << 8) | b[o + 1]
    mant = int.from_bytes(b[o + 2:o + 10], "big")
    if exp == 0 or exp == 0x7FFF or mant == 0:
        return 0
    val = mant / (2 ** 63) * (2 ** (exp - 16383))
    return int(val) if 1.0 <= val <= 1_000_000 else 0


def parse_aiff(data: bytes, chunk_bytes: int = 8192):
    """头字段转录自 aiffdec.c:aiff_read_header；数据分段按项目 SAMPLE_CHUNK_BYTES 契约。

    诚实边界：分块大小 8192 是本项目 AiffExtractor 的实现常量（§61.102），不是 ffmpeg
    aiff_read_packet 的 (MAX_SIZE / block_align) * block_align 策略。黄金表因此校验的是
    「数据区字节流 + 我方分段边界」——独立转录的规则，能抓 Kotlin 侧差一错误。
    """
    if data[:4] != b"FORM" or data[8:12] not in (b"AIFF", b"AIFC"):
        raise SystemExit("不是 AIFF/AIFC：FORM/type magic 不符")
    form_size = be32(data, 4)
    pos = 12
    ch = nf = bits = rate = None
    data_start = data_bytes = None
    while pos + 8 <= form_size + 8:
        cid = data[pos:pos + 4]
        size = be32(data, pos + 4)
        body = pos + 8
        if cid == b"COMM" and size >= 18:
            ch = be16(data, body)
            nf = be32(data, body + 2)
            bits = be16(data, body + 6)
            rate = aiff_rate80(data, body + 8)
        elif cid == b"SSND" and size >= 8:
            offset = be32(data, body)
            data_start = body + 8 + offset
            data_bytes = size - 8 - offset
        pos = body + size + (size & 1)
        if cid == b"SSND":
            break
    if None in (ch, nf, bits, rate, data_start, data_bytes):
        raise SystemExit("AIFF 头解析不全（COMM/SSND 缺失）")
    segs = []
    off = 0
    while off < data_bytes:
        length = min(chunk_bytes, data_bytes - off)
        # extractor 的帧对齐：frame = ch * bits/8；不足整帧的尾部不投递
        frame = max(1, ch * (bits // 8))
        length = (length // frame) * frame
        if length <= 0:
            break
        segs.append([data_start + off, length])
        off += length
    header = {
        "channels": ch, "num_frames": nf, "bits": bits, "rate": rate,
        "data_start": data_start, "data_bytes": data_bytes, "chunk_bytes": chunk_bytes,
    }
    return header, segs


def aiff_meta(src, data, header):
    return [
        "# container=aiff",
        "# source=ffmpeg/libavformat/aiffdec.c:aiff_read_header (header) + project AiffExtractor.SAMPLE_CHUNK_BYTES (segments, §61.102)",
        f"# file={src.name}",
        f"# file_size={len(data)}",
        f"# channels={header['channels']}",
        f"# num_frames={header['num_frames']}",
        f"# bits_per_sample={header['bits']}",
        f"# sample_rate={header['rate']}",
        f"# data_start={header['data_start']}",
        f"# data_bytes={header['data_bytes']}",
        f"# chunk_bytes={header['chunk_bytes']}",
        "pos\tlength",
    ]


# ------------------------------------------------------------------ §61.347 ASF ----
ASF_HEADER_GUID = bytes.fromhex("3026b2758e66cf11a6d900aa0062ce6c")
ASF_FILEPROPS_GUID = bytes.fromhex("a1dcab8c47a9cf118ee400c00c205365")
ASF_STREAM_GUID = bytes.fromhex("9107dcb7b7a9cf118ee600c00c205365")
ASF_DATA_GUID = bytes.fromhex("3626b2758e66cf11a6d900aa0062ce6c")


def parse_asf(data: bytes):
    """Header Object 字段转录自 asfdec_f.c:asf_read_file_properties；包布局按项目实测契约。

    包布局（§61.94/§61.107）：27B 包头 + block_align 载荷 + padding(= max_ps - 27 - align)。
    sendMs@包头+6、deltaMs@包头+23——项目实测：呈现时间取 sendMs（§61.112），deltaMs
    是载荷递增字段，仅作诊断，不作时间戳。
    """
    if data[:16] != ASF_HEADER_GUID:
        raise SystemExit("不是 ASF：Header Object GUID 不符")
    hdr_size = le64(data, 16)
    pos = 30
    packet_count = play_dur = max_ps = rate = ch = align = None
    while pos + 24 <= hdr_size:
        guid = data[pos:pos + 16]
        size = le64(data, pos + 16)
        if size < 24 or pos + size > len(data):
            raise SystemExit("非法 child size=%d @%d" % (size, pos))
        body = data[pos + 24:pos + size]
        if guid == ASF_FILEPROPS_GUID and len(body) >= 76:
            # asfdec_f.c:274 —— packets@+32, play_duration(100ns)@+40, max_ps@+72
            packet_count = le64(body, 32)
            play_dur = le64(body, 40)
            max_ps = le32(body, 72)
        elif guid == ASF_STREAM_GUID:
            for i in range(0, len(body) - 14):
                tag = le16(body, i)
                c = le16(body, i + 2)
                r = le32(body, i + 4)
                al = le16(body, i + 12)
                if tag in (0x160, 0x161, 0x162) and 1 <= c <= 8 and 8000 <= r <= 96000 and al > 0:
                    rate, ch, align = r, c, al
                    break
        pos += size
    if None in (packet_count, play_dur, max_ps, rate, ch, align):
        raise SystemExit("ASF Header 解析不全（FileProps/StreamProps）")
    if data[hdr_size:hdr_size + 16] != ASF_DATA_GUID:
        raise SystemExit("Data Object GUID 不符")
    data_start = hdr_size + 50
    padding = max_ps - 27 - align
    if padding < 0:
        raise SystemExit("非法包布局：max_ps < 27 + align")
    pkts = []
    for i in range(packet_count):
        p = data_start + i * max_ps
        if p + 27 > len(data):
            break
        send = le32(data, p + 6)
        delta = le32(data, p + 23)
        avail = max(0, min(align, len(data) - p - 27))
        pkts.append([p, send, delta, avail, padding])
    header = {
        "packet_count": packet_count, "play_duration_100ns": play_dur,
        "max_packet_size": max_ps, "block_align": align,
        "rate": rate, "channels": ch, "data_start": data_start, "padding": padding,
    }
    return header, pkts


def asf_meta(src, data, header):
    return [
        "# container=asf",
        "# source=ffmpeg/libavformat/asfdec_f.c:asf_read_file_properties (FileProps) + project §61.94/§61.107 packet layout",
        f"# file={src.name}",
        f"# file_size={len(data)}",
        f"# packet_count={header['packet_count']}",
        f"# play_duration_100ns={header['play_duration_100ns']}",
        f"# max_packet_size={header['max_packet_size']}",
        f"# block_align={header['block_align']}",
        f"# sample_rate={header['rate']}",
        f"# channels={header['channels']}",
        f"# data_start={header['data_start']}",
        f"# padding={header['padding']}",
        "pos\tsend_ms\tdelta_ms\tpayload_avail\tpadding",
    ]


PARSERS = {
    "ape": (parse_ape, ape_meta, lambda d: parse_ape(d)),
    "wavpack": (parse_wavpack, wavpack_meta, lambda d: parse_wavpack(d)),
    "aiff": (parse_aiff, aiff_meta, lambda d: parse_aiff(d)),
    "asf": (parse_asf, asf_meta, lambda d: parse_asf(d)),
}


def main(argv):
    if len(argv) != 4:
        raise SystemExit(__doc__)
    container, src, dst = argv[1], Path(argv[2]), Path(argv[3])
    if container not in PARSERS:
        raise SystemExit("未知容器 %s（可用：%s）" % (container, ", ".join(PARSERS)))
    data = src.read_bytes()
    parse_fn, meta_fn, _ = PARSERS[container]
    header, rows = parse_fn(data)
    lines = meta_fn(src, data, header)
    lines += ["\t".join(str(v) for v in r) for r in rows]
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("[OK] %s %s: rows=%d -> %s (%d bytes)" %
          (container, src.name, len(rows), dst, dst.stat().st_size))


if __name__ == "__main__":
    main(sys.argv)
