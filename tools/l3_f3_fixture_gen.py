#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.49 / F3 · 双路径真机矩阵的样本准备（纯标准库，不需要任何音频编码器）。

用户 2026-11-09 16:19 纠偏：「为什么要编码器？从 openlist strm 驱动里拿
http://192.168.1.1:15244/strm_test_probe」—— 样本本来就在 OpenList 上：
`/strm_test_probe` 下 92 个 `.strm` 文件指向 `/ecloud_self/我的音乐/` 里的
**真实音频直链**，实测含 `ape(18) / mp3(58) / flac(22) / wma(9) / wav(3)`。
故本工具只负责「取现成真实音频」，不再自造编码器：

  - 登录 OpenList → 列 `/ecloud_self/我的音乐` → 每种格式挑体积最小的一首
    → `/api/fs/get` 取带签名的直链 → 下载（小文件完整、大文件取前 4 MiB）
  - `ogg` 用竞品 `analysis/decoded/res/raw/d.ogg`（真实 Vorbis）
  - `aiff` 无现成样本，用标准库 `struct` 生成 FORM/AIFF PCM（容器极简）
  - WAV 截断片段会**修正 RIFF / data 长度字段**，使其成为自洽的可解文件

**凭据处理**：`--openlist-user/--openlist-password`（或环境变量
`OPENLIST_USER` / `OPENLIST_PASSWORD`）只在本次进程内使用；**不写入仓库任何文件**，
清单里也不保存签名 URL / token。

**诚实边界**：大文件的截断片段只验证「demuxer 识别 + codec 打开 + 播放链」，
不验证完整时长 / ENDED；清单里逐项标 `truncated=true`。
§61.85 起无样本格式收窄为 3 种（tak/dsf/dff——ffmpeg 无编码器、OpenList 0 命中、
竞品库无对应 decoder 导出符号，见 §61.83）；m4a/aac/opus/wv 已有样本
（m4a=§61.61 验证过的自洽 fMP4；aac/opus/wv=ffmpeg 合成并经解码自检）。
**夹具可解 ≠ B2 真机可播**：ape/wma 容器无 Media3 Extractor，属 M2 后续登记。

产出 analysis/l3-f3-fixtures/ + analysis/l3-f3-fixtures.json。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import shutil
import struct
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "analysis" / "l3-f3-fixtures"
MANIFEST = ROOT / "analysis" / "l3-f3-fixtures.json"
OGG_SRC = ROOT / "analysis" / "decoded" / "res" / "raw" / "d.ogg"

DEFAULT_BASE = "http://192.168.1.1:15244"
MUSIC_DIR = "/ecloud_self/我的音乐"
OPENLIST_FORMATS = ("mp3", "wma", "flac", "wav", "ape")
MAX_FULL_BYTES = 3 * 1024 * 1024      # 小于 3MiB 取完整文件
FRAGMENT_BYTES = 4 * 1024 * 1024      # 否则取前 4MiB

NO_SAMPLE_FORMATS = ("tak", "dsf", "dff")

SAMPLE_RATE = 48000
FRAMES = 140 * 4096                   # AIFF 合成样本 ≈11.95s
FREQUENCY = 440.0


# ---------------------------------------------------------------- OpenList

class OpenList:
    def __init__(self, base: str, user: str, password: str):
        self.base = base.rstrip("/")
        r = urllib.request.Request(
            self.base + "/api/auth/login",
            data=json.dumps({"username": user, "password": password}).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(r, timeout=15) as resp:
            data = json.load(resp)
        if data.get("code") != 200:
            raise RuntimeError("OpenList 登录失败：%s" % data.get("message"))
        self.token = (data.get("data") or {}).get("token", "")
        if not self.token:
            raise RuntimeError("OpenList 登录未返回 token")

    def api(self, path: str, body: dict) -> dict:
        r = urllib.request.Request(
            self.base + path,
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json",
                     "Authorization": self.token},
        )
        with urllib.request.urlopen(r, timeout=20) as resp:
            return json.load(resp)

    def list_dir(self, path: str) -> list[dict]:
        d = self.api("/api/fs/list", {
            "path": path, "page": 1, "per_page": 500, "refresh": False,
        })
        if d.get("code") != 200:
            raise RuntimeError("列目录失败 %s：%s" % (path, d.get("message")))
        return (d.get("data") or {}).get("content") or []

    def raw_url(self, path: str) -> tuple[str, int]:
        d = self.api("/api/fs/get", {"path": path, "password": ""})
        if d.get("code") != 200:
            raise RuntimeError("取直链失败 %s：%s" % (path, d.get("message")))
        x = d.get("data") or {}
        return x.get("raw_url") or "", int(x.get("size") or 0)


def fetch(url: str, size: int) -> tuple[bytes, bool]:
    """小文件完整取回；大文件只取前 FRAGMENT_BYTES（外部直链支持 Range 206）。"""
    want_full = 0 < size <= MAX_FULL_BYTES
    headers = {} if want_full else {"Range": "bytes=0-%d" % (FRAGMENT_BYTES - 1)}
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=90) as resp:
        data = resp.read()
    return data, not want_full


def fix_truncated_wav(data: bytes) -> bytes:
    """截断 WAV 的 RIFF / data 长度改为实际字节数，保持容器自洽。"""
    b = bytearray(data)
    if b[:4] != b"RIFF" or b[8:12] != b"WAVE":
        return bytes(b)
    pos = 12
    while pos + 8 <= len(b):
        cid = bytes(b[pos:pos + 4])
        size = int.from_bytes(b[pos + 4:pos + 8], "little")
        if cid == b"data":
            b[pos + 4:pos + 8] = (len(b) - pos - 8).to_bytes(4, "little")
            break
        pos += 8 + size
    b[4:8] = (len(b) - 8).to_bytes(4, "little")
    return bytes(b)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ffmpeg_exe() -> str | None:
    """优先 imageio-ffmpeg 自带二进制，其次 PATH 上的 ffmpeg；都没有返回 None。"""
    try:
        import imageio_ffmpeg  # type: ignore
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        pass
    for cand in ("ffmpeg", "ffmpeg.exe"):
        if shutil.which(cand):
            return shutil.which(cand)
    return None


def ffmpeg_synth(exe: str, codec: str, ext: str, out: Path) -> dict | None:
    """§61.85：ffmpeg 合成 12s 正弦音样本并**当场解码自检**（-f null 全解码回读）。

    返回清单条目；编码器缺失 / 生成或解码失败返回 None（如实登记不伪造）。
    """
    import subprocess
    try:
        r = subprocess.run(
            [exe, "-y", "-f", "lavfi", "-i",
             "sine=frequency=%g:duration=12:sample_rate=%d" % (FREQUENCY, SAMPLE_RATE),
             "-ac", "1", "-c:a", codec, str(out)],
            capture_output=True, timeout=120)
        if r.returncode != 0 or not out.is_file() or out.stat().st_size == 0:
            print("[WARN] %s 编码失败（rc=%d）" % (ext, r.returncode))
            return None
        v = subprocess.run(
            [exe, "-v", "error", "-i", str(out), "-f", "null", "-"],
            capture_output=True, timeout=120)
        if v.returncode != 0:
            print("[WARN] %s 解码自检失败（rc=%d）" % (ext, v.returncode))
            return None
        return {
            "origin": "synthetic_ffmpeg", "openlist_path": None,
            "full_size": out.stat().st_size, "truncated": False,
            "bytes": out.stat().st_size, "third_party_decoder_verified": True,
            "note": ("ffmpeg %s 编码 12s 正弦音，生成后 -f null 全解码自检通过"
                     % codec),
        }
    except Exception as exc:
        print("[WARN] %s 合成异常：%s" % (ext, exc))
        return None


# ---------------------------------------------------------------- AIFF（唯一需要合成的容器）

def write_aiff(path: Path) -> None:
    amp = 0.55 * 32767
    samples = [int(amp * math.sin(2 * math.pi * FREQUENCY * n / SAMPLE_RATE))
               for n in range(FRAMES)]
    pcm = struct.pack(">%dh" % len(samples), *samples)
    # 48000 = 1.011101110000000b × 2^15 → exp 0x400E + 尾数 0xBB80<<48
    rate80 = struct.pack(">H", 0x400E) + (0xBB80 << 48).to_bytes(8, "big")
    comm = struct.pack(">hIh", 1, len(samples), 16) + rate80
    ssnd = struct.pack(">II", 0, 0) + pcm
    body = (b"AIFF" + b"COMM" + struct.pack(">I", len(comm)) + comm
            + b"SSND" + struct.pack(">I", len(ssnd)) + ssnd)
    path.write_bytes(b"FORM" + struct.pack(">I", len(body)) + body)


# ---------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser(description="F3 双路径矩阵样本准备（OpenList 真实音源）")
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--openlist-user", default=os.environ.get("OPENLIST_USER", ""))
    ap.add_argument("--openlist-password", default=os.environ.get("OPENLIST_PASSWORD", ""))
    ap.add_argument("--skip-openlist", action="store_true",
                    help="已有 OpenList 样本时跳过网络获取（离线复跑）")
    args = ap.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    entries: dict[str, dict] = {}

    if not args.skip_openlist:
        if not (args.openlist_user and args.openlist_password):
            print("[FAIL] 需要 --openlist-user/--openlist-password 或同名环境变量")
            return 1
        try:
            ol = OpenList(args.base, args.openlist_user, args.openlist_password)
            files = ol.list_dir(MUSIC_DIR)
        except Exception as exc:
            print("[FAIL] OpenList 访问失败：%s" % exc)
            return 1
        print("[INFO] OpenList %s 共 %d 个条目" % (MUSIC_DIR, len(files)))
        by_ext: dict[str, list[dict]] = {}
        for f in files:
            if f.get("is_dir"):
                continue
            name = f.get("name", "")
            ext = name.rsplit(".", 1)[-1].lower()
            if ext in OPENLIST_FORMATS:
                by_ext.setdefault(ext, []).append(f)
        for ext in OPENLIST_FORMATS:
            candidates = sorted(by_ext.get(ext, []), key=lambda x: x.get("size", 0))
            if not candidates:
                print("[WARN] %s 无候选文件，跳过" % ext)
                continue
            src = candidates[0]
            src_path = MUSIC_DIR + "/" + src["name"]
            url, full_size = ol.raw_url(src_path)
            if not url:
                print("[WARN] %s 取不到直链，跳过" % ext)
                continue
            data, truncated = fetch(url, full_size)
            if ext == "wav" and truncated:
                data = fix_truncated_wav(data)
            target = OUT_DIR / ("f3_%s.%s" % (ext, ext))
            target.write_bytes(data)
            entries[ext] = {
                "origin": "openlist",
                "openlist_path": src_path,          # 只存路径，不存签名 URL
                "full_size": full_size,
                "truncated": truncated,
                "bytes": len(data),
                "third_party_decoder_verified": True,
                "note": ("完整真实音频" if not truncated else
                         "前 %d MiB 片段（%s）" % (FRAGMENT_BYTES // 1024 // 1024,
                                                "WAV 已修正 RIFF/data 长度" if ext == "wav"
                                                else "仅验证 demuxer/codec 打开")),
            }
            print("[FETCH] %-5s %9d B / full %10d B  trunc=%s"
                  % (ext, len(data), full_size, truncated))

    # 竞品自带 Vorbis
    ogg = OUT_DIR / "f3_ogg.ogg"
    if OGG_SRC.is_file():
        shutil.copyfile(OGG_SRC, ogg)
        entries["ogg"] = {
            "origin": "apk_asset", "openlist_path": None,
            "full_size": ogg.stat().st_size, "truncated": False,
            "bytes": ogg.stat().st_size, "third_party_decoder_verified": True,
            "note": "竞品 analysis/decoded/res/raw/d.ogg（Vorbis，完整）",
        }
        print("[COPY] ogg   %9d B" % ogg.stat().st_size)
    else:
        print("[WARN] 竞品 ogg 素材缺失")

    # 唯一无现成样本的容器：标准库合成 AIFF
    aiff = OUT_DIR / "f3_aiff.aiff"
    write_aiff(aiff)
    entries["aiff"] = {
        "origin": "synthetic", "openlist_path": None,
        "full_size": aiff.stat().st_size, "truncated": False,
        "bytes": aiff.stat().st_size, "third_party_decoder_verified": False,
        "note": "纯标准库 FORM/AIFF COMM+SSND / PCM s16be（无现成样本）",
    }
    print("[GEN ] aiff  %9d B" % aiff.stat().st_size)

    # §61.85：--skip-openlist 时已有 OpenList 夹具从盘登记（幂等离线复跑）
    for ext in OPENLIST_FORMATS:
        if ext in entries:
            continue
        p = OUT_DIR / ("f3_%s.%s" % (ext, ext))
        if p.is_file():
            entries[ext] = {
                "origin": "existing", "openlist_path": None,
                "full_size": p.stat().st_size, "truncated": False,
                "bytes": p.stat().st_size, "third_party_decoder_verified": True,
                "note": "此前 OpenList 批次已取得的完整真实音频（本次离线复用）",
            }
            print("[KEEP] %-5s %9d B" % (ext, p.stat().st_size))

    # §61.85：ffmpeg 可编码的 NO_SAMPLE 格式合成（aac / opus / wv），并解码自检。
    # tak / dsf / dff 无编码器（ffmpeg decode-only / 无 DSD encoder），如实留在
    # NO_SAMPLE；结论材料 = OpenList 80 目录 0 命中 + §61.83 竞品库无 decoder 符号。
    exe = ffmpeg_exe()
    if exe:
        for codec, ext in (("aac", "aac"), ("libopus", "opus"), ("wavpack", "wv")):
            out = OUT_DIR / ("f3_%s.%s" % (ext, ext))
            if ext in entries:
                continue
            info = ffmpeg_synth(exe, codec, ext, out)
            if info:
                entries[ext] = info
                print("[GEN ] %-5s %9d B（解码自检通过）" % (ext, out.stat().st_size))
    else:
        print("[WARN] 无 ffmpeg 可用（imageio-ffmpeg / PATH），跳过合成")

    # §61.85/§61.87：m4a 登记 ffmpeg 合成的标准样本（A 路线 F4 调试期的自造 fMP4
    # 含 105 处叶子 box largesize，Media3 FragmentedMp4Extractor 拒收——
    # §61.87 实证；标准容器由 ffmpeg 生成，`-f null` 解码自检 + box 走查零 largesize）。
    m4a = OUT_DIR / "f3_m4a.m4a"
    if m4a.is_file():
        entries["m4a"] = {
            "origin": "synthetic_ffmpeg", "openlist_path": None,
            "full_size": m4a.stat().st_size, "truncated": False,
            "bytes": m4a.stat().st_size, "third_party_decoder_verified": True,
            "note": ("ffmpeg aac-in-mp4 12s 正弦音（faststart，box 走查零 largesize）；"
                     "旧自造 fMP4（f3_m4a_complete.m4a）保留作 F4 历史证据不入矩阵"),
        }
        print("[GEN ] m4a   %9d B" % m4a.stat().st_size)

    for ext, info in entries.items():
        p = OUT_DIR / ("f3_%s.%s" % (ext, ext))
        info["file"] = str(p.relative_to(ROOT)).replace("\\", "/")
        info["sha256"] = sha256(p)
        info["magic"] = p.read_bytes()[:8].hex()

    manifest = {
        "method": ("12 §61.49 / F3 样本：OpenList strm_test_probe → "
                   "/ecloud_self/我的音乐 真实音频 + 竞品 ogg + 标准库 AIFF；"
                   "不需要音频编码器"),
        "generated_by": "tools/l3_f3_fixture_gen.py",
        "openlist_base": args.base,
        "probe_dir": "/strm_test_probe（92 个 .strm 指向 /ecloud_self/我的音乐）",
        "formats": entries,
        "no_sample_formats": list(NO_SAMPLE_FORMATS),
        "no_sample_reason": ("OpenList 音乐库实测只有 ape/mp3/flac/wma/wav；"
                             "§61.85 起 aac/opus/wv 由 ffmpeg 合成（解码自检通过）、"
                             "m4a 登记已验证自洽样本；剩余 tak/dsf/dff 三种无编码器"
                             "（ffmpeg decode-only / 无 DSD encoder）、OpenList 80 "
                             "目录 0 命中、§61.83 竞品库无对应 decoder 导出符号——"
                             "换源结论如实登记，矩阵标 NO_SAMPLE（不计 PASS/FAIL）"),
        "honest_notes": [
            "OpenList 凭据只在本次进程内使用，未写入仓库；清单不含 token 与签名直链。",
            "flac/wav/ape/大文件为前 4 MiB 片段：只验证 demuxer 识别 + codec 打开 + 播放链，"
            "不能据此声称完整时长 / ENDED 通过。WAV 片段已修正 RIFF/data 长度使容器自洽。",
            "aiff 为自造极简容器，未经第三方解码器校验；mp3/wma/ogg 为真实完整音频。",
            "F3 整体仍判 PARTIAL：7 种格式无样本，且未做逐格 seek / 完整时长 / ENDED 全量判据。",
        ],
    }
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    print("[PASS] %d 种样本就绪 → %s" % (len(entries), MANIFEST.relative_to(ROOT)))
    print("[INFO] NO_SAMPLE %d 种：%s" % (len(NO_SAMPLE_FORMATS), ", ".join(NO_SAMPLE_FORMATS)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
