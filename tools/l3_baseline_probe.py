#!/usr/bin/env python3
"""T-L L3 / 12 §61.43：竞品播放页真机基准图的纯标准库像素实证。

零依赖（不用 Pillow）：解 PNG chunk → zlib IDAT → 反 filter 得 RGBA，
产出 analysis/l3-baseline-probe.json 与局部放大 / 封面裁剪证据图。

取证对象：analysis/l3-reference/competitor-player-baseline.png
（2026-11-09 用户手动把竞品切到 com.netease.cloudmusic/.activity.PlayerActivity
后由 adb screencap 取得的一手基准，1440x3200 设备截图）。

用途：为「背景随封面变化」的自研实现提供精确色值 / 几何规格，
以及为 1:1 逐像素对齐提供可复跑的度量基线。**不修改任何 APK / 基线文件**。
"""
from __future__ import annotations

import json
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "analysis" / "l3-reference" / "competitor-player-baseline.png"
OUT_JSON = ROOT / "analysis" / "l3-baseline-probe.json"
REF = ROOT / "analysis" / "l3-reference"

# 采样点：(名称, 归一化 x, 归一化 y, 说明)。用归一化坐标以兼容不同截图缩放。
SAMPLES = [
    ("bg_top_left",     0.02, 0.17, "顶栏下方左侧背景"),
    ("bg_top_right",    0.98, 0.17, "顶栏下方右侧背景"),
    ("bg_mid_left",     0.02, 0.66, "唱片下方左侧背景"),
    ("bg_mid_right",    0.98, 0.66, "唱片下方右侧背景"),
    ("bg_below_disc",   0.50, 0.65, "唱片正下方背景"),
    ("bg_info",         0.50, 0.73, "歌曲信息区背景"),
    ("bg_control",      0.50, 0.85, "主控制行背景"),
    ("bg_bottom",       0.50, 0.97, "底部功能行背景"),
    ("bg_topbar",       0.50, 0.11, "顶栏中央背景"),
    ("bg_above_disc",   0.50, 0.22, "唱片上方背景"),
    ("disc_vinyl",      0.13, 0.43, "黑胶盘外圈"),
    ("disc_vinyl2",     0.87, 0.43, "黑胶盘外圈右侧"),
    ("cover_center",    0.50, 0.43, "中心专辑封面"),
    ("needle",          0.52, 0.17, "唱臂 pivot 附近"),
    ("text_title",      0.10, 0.70, "歌曲标题文字"),
    ("progress_active", 0.20, 0.774, "进度条已播部分"),
]

# 局部放大区域：(名称, 归一化 top, bottom, 说明)
CROPS = [
    ("01-topbar",   0.00, 0.16, "顶栏：收起箭头 / 私人漫游·默认模式 / 刷新"),
    ("02-disc",     0.15, 0.63, "唱片：黑胶 + 中心封面 + 白色弯曲唱臂"),
    ("03-info",     0.61, 0.79, "422人 / 标题 / 艺人关注 / 收藏评论 / 进度条 / 极高音质"),
    ("04-controls", 0.79, 0.97, "主控制行 5 图标 + 底部功能行 5 图标"),
]

# 中心封面裁剪（归一化），用于「封面 → 背景」机制的视觉证据。
COVER_BOX = (0.24, 0.31, 0.76, 0.59)


def read_png(path: Path) -> tuple[int, int, bytearray, int]:
    """返回 (width, height, RGBA bytes, channels)。仅支持 8bit RGB/RGBA 非隔行。"""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos = 8
    idat = bytearray()
    w = h = bit = ctype = interlace = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        ctype_b = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype_b == b"IHDR":
            w, h, bit, ctype, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
        elif ctype_b == b"IDAT":
            idat.extend(chunk)
        elif ctype_b == b"IEND":
            break
    if w is None or bit != 8 or interlace != 0 or ctype not in (2, 6):
        raise ValueError(f"unsupported PNG: bit={bit} ctype={ctype} interlace={interlace}")
    channels = 4 if ctype == 6 else 3
    raw = zlib.decompress(bytes(idat))
    stride = w * channels
    out = bytearray(w * h * 4)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        ft = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        for x in range(stride):
            a = line[x - channels] if x >= channels else 0
            b = prev[x]
            c = prev[x - channels] if x >= channels else 0
            if ft == 1:
                line[x] = (line[x] + a) & 0xFF
            elif ft == 2:
                line[x] = (line[x] + b) & 0xFF
            elif ft == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 0xFF
            elif ft == 4:
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 0xFF
        prev = line
        if channels == 4:
            out[y * w * 4:(y + 1) * w * 4] = line
        else:
            for x in range(w):
                o = (y * w + x) * 4
                s = x * 3
                out[o:o + 3] = line[s:s + 3]
                out[o + 3] = 255
    return w, h, out, channels


def pixel(rgba: bytearray, w: int, x: int, y: int) -> tuple[int, int, int, int]:
    o = (y * w + x) * 4
    return rgba[o], rgba[o + 1], rgba[o + 2], rgba[o + 3]


def write_png(path: Path, w: int, h: int, rgba: bytearray) -> None:
    """把 RGBA 写成 filter=0 的 PNG（纯标准库）。"""
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw.extend(rgba[y * w * 4:(y + 1) * w * 4])

    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + tag + payload
                + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    path.write_bytes(png)


def scale(rgba: bytearray, w: int, h: int, factor: int) -> tuple[int, int, bytearray]:
    """最近邻放大（够用于视觉核对，且零依赖）。"""
    nw, nh = w * factor, h * factor
    out = bytearray(nw * nh * 4)
    for y in range(nh):
        sy = y // factor
        for x in range(nw):
            sx = x // factor
            out[(y * nw + x) * 4:(y * nw + x) * 4 + 4] = rgba[(sy * w + sx) * 4:(sy * w + sx) * 4 + 4]
    return nw, nh, out


def crop(rgba: bytearray, w: int, h: int, box: tuple[int, int, int, int]) -> tuple[int, int, bytearray]:
    x0, y0, x1, y1 = box
    cw, ch = x1 - x0, y1 - y0
    out = bytearray(cw * ch * 4)
    for y in range(ch):
        out[y * cw * 4:(y + 1) * cw * 4] = rgba[((y0 + y) * w + x0) * 4:((y0 + y) * w + x1) * 4]
    return cw, ch, out


def main() -> int:
    if not SRC.is_file():
        print(f"[FAIL] 基准图缺失：{SRC.relative_to(ROOT)}")
        return 1
    w, h, rgba, ch = read_png(SRC)
    print(f"[INFO] 基准图 {w}x{h} channels={ch}")

    samples = []
    for name, nx, ny, note in SAMPLES:
        x = min(w - 1, int(w * nx))
        y = min(h - 1, int(h * ny))
        r, g, b, a = pixel(rgba, w, x, y)
        hexv = f"#{r:02X}{g:02X}{b:02X}"
        samples.append({"name": name, "norm": [nx, ny], "px": [x, y],
                        "rgb": [r, g, b], "hex": hexv, "note": note})
        print(f"[SAMPLE] {name:<20} ({nx:.2f},{ny:.2f}) -> {hexv}  {note}")

    REF.mkdir(parents=True, exist_ok=True)
    crop_files = []
    for name, nt, nb, note in CROPS:
        y0, y1 = int(h * nt), int(h * nb)
        cw, chh, cdata = crop(rgba, w, h, (0, y0, w, y1))
        nw, nh, big = scale(cdata, cw, chh, 2)
        p = REF / f"cp-crop-{name}.png"
        write_png(p, nw, nh, big)
        crop_files.append(str(p.relative_to(ROOT)))
        print(f"[CROP] {name} y={y0}..{y1} -> {nw}x{nh} {note}")

    x0, y0, x1, y1 = (int(w * COVER_BOX[0]), int(h * COVER_BOX[1]),
                      int(w * COVER_BOX[2]), int(h * COVER_BOX[3]))
    cw, chh, cdata = crop(rgba, w, h, (x0, y0, x1, y1))
    cover_path = REF / "competitor-cover-extract.png"
    write_png(cover_path, cw, chh, cdata)
    print(f"[COVER] {cw}x{chh} -> {cover_path.relative_to(ROOT)}")

    # 背景竖向渐变：取左侧无控件列 x=2% 一列，多点采样看色阶。
    gradient = []
    for fy in (0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 0.80, 0.90, 0.96):
        r, g, b, _ = pixel(rgba, w, max(1, int(w * 0.02)), min(h - 1, int(h * fy)))
        gradient.append({"y": round(fy, 2), "hex": f"#{r:02X}{g:02X}{b:02X}", "rgb": [r, g, b]})

    spec = {
        "method": "12 §61.43：竞品 PlayerActivity 真机基准图的纯标准库 PNG 像素实证",
        "generated_by": "tools/l3_baseline_probe.py",
        "source": str(SRC.relative_to(ROOT)),
        "size": [w, h],
        "channels": ch,
        "competitor_activity": "com.netease.cloudmusic/.activity.PlayerActivity",
        "captured_by": "adb screencap（用户手动切到竞品播放页后取图，2026-11-09）",
        "reverse_engineered_background_chain": [
            "layout/c5y.xml: FrameLayout #ff262626 兜底 + ImageSwitcher @id/playerBackgroundImage",
            "PlayerCoverCacheHelper.a(MusicInfo, Function1, Function0)：切歌时异步加载封面 + 取色并缓存",
            "PlayerCoverCacheHelper.t(J)/y2(J) -> ExtractColorResult（getMainColor / getDominantColorHSL）",
            "androidx.palette.graphics.Palette.getDominantColor(int)（we/b.smali 实证）",
            "PlayerActivity.Ph(ExtractColorResult) -> nf(Drawable, ExtractColorResult, int, float[])",
            "nf: PlayerCoverCacheHelper.n(ViewGroup, ExtractColorResult) + "
            "r8.j:PlayerBackgroundImage.setImageDrawable(Drawable)",
            "PlayerBackgroundImage.BLUR_RADIUS / setBlurCover(String,String[,int]) / "
            "OnDrawableChangedListener（classes15.strings.txt 实证）",
            "qh.p(DraweeView, String url, int blurRadius)：带模糊半径加载封面",
            "supportGaussianBlur / updateCurrentBgAlphaAndBlur / CustomBgBlurMaterialHelper / "
            "getCacheBgBlurDrawable",
        ],
        "conclusion": "竞品播放页背景 = 当前曲目封面经 Palette 取主色 + 高斯模糊 + 暗化/主色叠加后，"
                      "由 ImageSwitcher 带动画切换的模糊封面；#ff262626 仅是无封面/加载中的兜底底色。"
                      "背景随封面变化，不是固定深色（印证用户观察）。",
        "samples": samples,
        "left_edge_vertical_gradient": gradient,
        "crop_zoom_files": crop_files,
        "cover_extract": str(cover_path.relative_to(ROOT)),
        "pixel_level_1to1": {
            "baseline_available": True,
            "self_ui_aligned": False,
            "note": "基准已取得；自研页按本报告色值 / 几何规格在 12 §61.43 对齐，"
                    "对齐后仍需真机截图逐像素复核，不预先宣称达成。",
        },
    }
    OUT_JSON.write_text(json.dumps(spec, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"[INFO] 证据 -> {OUT_JSON.relative_to(ROOT)}")
    print("结果：PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
