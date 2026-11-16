#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""播放页封面驱动背景取色算法的可复核标定证据。

对照真相源：
- analysis/l3-reference/competitor-cover-extract.png  —— 竞品基准播放页所用歌曲的封面
- analysis/l3-reference/competitor-player-baseline.png —— 同一首歌的竞品播放页真机截图

验证 2026-11-16 严格像素级标定结论：
  封面主色 (16x16 降采样 → 饱和度×频次加权) → HSV 变换 (S×0.8, V×1.3)
  → 背景顶端，应与竞品背景顶端实测误差 ≤2；底端 = 顶端 ×0.515，误差 ≤2。

§61.174 修正（2026-11-16）：旧版把底端系数写成 0.56、采样点取 `y≈81.25%`。
逐像素复测表明竞品背景是**贯穿整页的线性渐变**：y=0 → (40,79,103)，
y=2900px（导航栏顶）→ (20,40,52)，比值 0.515；而 y=2600px（81.25%）恰好是
(22,44,57) = 0.56 —— 0.56 是「渐变中段」而非「渐变终点」。Compose 的
`Brush.verticalGradient(listOf(top, bottom))` 终点落在页面底部，故系数必须是 0.515。

算法与 LegacyPlayerPage.kt 的 dominantCoverColor / shadeBottom 逐行对应。
本脚本只读，不改写任何证据文件。
"""
from __future__ import annotations

import colorsys
import json
import sys
from collections import Counter
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    print("需要 Pillow：C:/Python313/python.exe -m pip install pillow")
    sys.exit(2)

ROOT = Path(__file__).resolve().parent.parent
COVER = ROOT / "analysis/l3-reference/competitor-cover-extract.png"
BASELINE = ROOT / "analysis/l3-reference/competitor-player-baseline.png"


def cover_dominant(img: Image.Image) -> tuple[int, int, int]:
    """对应 dominantCoverColor 的采样+加权部分（HSV 变换前）。"""
    small = img.convert("RGB").resize((16, 16), Image.BILINEAR)
    counts: Counter[tuple[int, int, int]] = Counter(small.getdata())
    best, best_score = (0, 0, 0), -1.0
    for (r, g, b), n in counts.items():
        mx, mn = max(r, g, b), min(r, g, b)
        sat = 0.0 if mx == 0 else (mx - mn) / mx
        score = sat * n + n * 0.05
        if score > best_score:
            best_score, best = score, (r, g, b)
    return best


def to_top(dominant: tuple[int, int, int]) -> tuple[int, int, int]:
    r, g, b = dominant
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    s = min(0.65, max(0.35, s * 0.8))
    v = min(0.45, max(0.32, v * 1.3))
    rr, gg, bb = colorsys.hsv_to_rgb(h, s, v)
    return int(rr * 255), int(gg * 255), int(bb * 255)


def to_bottom(top: tuple[int, int, int]) -> tuple[int, int, int]:
    return tuple(int(c * 0.515) for c in top)  # type: ignore[return-value]


def baseline_bg(img: Image.Image) -> tuple[tuple[int, int, int], tuple[int, int, int]]:
    """竞品基准背景实测：顶端取**内容顶**（状态栏下缘 y≈4.06%），底端取**导航栏顶**（y≈90.6%）。

    顶端必须与 Kotlin 输出的 bgTop 对应——bgTop 是渐变的最亮端，故取最顶端采样；
    底端必须与 Compose 渐变的终点对应——`Brush.verticalGradient` 覆盖到 Column 底部
    （= 导航栏上缘 2900/3200 = 90.6%），y>90.6% 是系统导航栏纯黑，不能采。
    """
    rgb = img.convert("RGB")
    w, h = rgb.size
    top = rgb.getpixel((int(w * 0.014), int(h * 0.0406)))
    bottom = rgb.getpixel((int(w * 0.014), int(h * 0.906)))
    return top, bottom  # type: ignore[return-value]


def close(a: tuple[int, int, int], b: tuple[int, int, int], tol: int = 2) -> bool:
    return all(abs(x - y) <= tol for x, y in zip(a, b))


def main() -> int:
    cover = Image.open(COVER)
    base = Image.open(BASELINE)

    dominant = cover_dominant(cover)
    top = to_top(dominant)
    bottom = to_bottom(top)
    exp_top, exp_bottom = baseline_bg(base)

    result = {
        "method": "cover_driven_background_calibration",
        "sources": {
            "cover": str(COVER.relative_to(ROOT)),
            "baseline": str(BASELINE.relative_to(ROOT)),
        },
        "algorithm": {
            "sample": "16x16 bilinear downscale",
            "dominant": "argmax(saturation*count + count*0.05)",
            "top": "HSV(S*=0.8 clamp[0.35,0.65], V*=1.3 clamp[0.32,0.45])",
            "bottom": "top * 0.515 per channel",
        },
        "derived": {"dominant": dominant, "top": top, "bottom": bottom},
        "baseline_measured": {"top": exp_top, "bottom": exp_bottom},
        "abs_error": {
            "top": [abs(a - b) for a, b in zip(top, exp_top)],
            "bottom": [abs(a - b) for a, b in zip(bottom, exp_bottom)],
        },
        "pass": close(top, exp_top) and close(bottom, exp_bottom),
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
