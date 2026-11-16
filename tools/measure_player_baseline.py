#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""播放页基准图逐像素量化证据（`12 §61.174` 的可复跑来源）。

真相源：`analysis/l3-reference/competitor-player-baseline.png`（1440x3200，density 3.5
→ 411.43 x 914.29 dp）。本脚本把该图里每个元素的 bbox 中心与跨度量出来，除以 density
得到 dp 值，输出到 `analysis/l3-reference/player-baseline-metrics.json`。

App 侧 `PlayerBaseline`（`app/.../ui/player/LegacyAssets.kt`）的每个常量都必须能在这里
找到对应测量值；改了常量却不改本证据（或反之）即视为证据漂移。

测量方法：
- 文字 / 图标：逐行统计「亮像素」（sum(rgb) > 阈值）得到文字带；
  再在同一带内逐列聚类得到每个图标的水平中心。
- 唱片：逐行统计「暗中性像素」（max(rgb) < 80 且 max-min < 15）得到黑胶环跨度。
- 唱针：追踪白色像素（min(rgb) > 225）轨迹，跳过轴心圆后取最宽块中心为唱头。

只读脚本，不改写基准图。用法：python tools/measure_player_baseline.py [--json]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    print("需要 Pillow：python -m pip install pillow")
    sys.exit(2)

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "analysis/l3-reference/competitor-player-baseline.png"
OUT = ROOT / "analysis/l3-reference/player-baseline-metrics.json"

DENSITY = 3.5
STATUS_BAR_PX = 130      # Android 13 状态栏高（本基准机 37.1dp）
NAV_BAR_TOP_PX = 2988    # 首个纯黑像素（= 系统导航栏上缘，853.7dp）


def bright_bands(px, W, y0, y1, thr=340, min_h=6, step=1):
    """逐行统计亮像素，返回连续文字带 [(top, bottom)]。"""
    out = []
    in_band = False
    start = 0
    for y in range(y0, y1, step):
        n = sum(1 for x in range(0, W, 4) if sum(px[x, y]) > thr)
        if n > 2 and not in_band:
            in_band, start = True, y
        elif n <= 2 and in_band:
            in_band = False
            if y - start >= min_h:
                out.append((start, y))
    if in_band:
        out.append((start, y1))
    return out


def bright_bands_win(px, x0, x1, y0, y1, thr=270, min_hits=2, min_h=4):
    """限定列窗口的逐行亮带。

    全宽扫描时右侧的点赞 / 评论图标会把「标题带」和「艺人带」粘成一条，
    因此标题 / 艺人这类左侧文字必须限定 x 窗口单独扫（`12 §61.174` 修正）。
    """
    out = []
    in_band = False
    start = 0
    for y in range(y0, y1):
        n = sum(1 for x in range(x0, x1) if sum(px[x, y]) > thr)
        if n > min_hits and not in_band:
            in_band, start = True, y
        elif n <= min_hits and in_band:
            in_band = False
            if y - start >= min_h:
                out.append((start, y))
    if in_band:
        out.append((start, y1))
    return out


def col_clusters(px, W, y0, y1, thr=260, min_w=10):
    """在文字带内逐列统计亮像素，返回每个元素的 (left, right, center, width)。"""
    col = [0] * W
    for y in range(y0, y1, 2):
        for x in range(W):
            if sum(px[x, y]) > thr:
                col[x] += 1
    out = []
    in_c = False
    start = 0
    for x in range(W):
        if col[x] > 0 and not in_c:
            in_c, start = True, x
        elif col[x] == 0 and in_c:
            in_c = False
            if x - start >= min_w:
                out.append((start, x, (start + x) // 2, x - start))
    return out


def dp(px: float) -> float:
    return round(px / DENSITY, 1)


def main() -> int:
    im = Image.open(BASELINE).convert("RGB")
    W, H = im.size
    px = im.load()

    # ── 唱片（暗中性像素的黑胶环） ─────────────────────────────────────────
    def dark_neutral(p):
        return max(p) < 80 and (max(p) - min(p)) < 15

    row_y = 1375  # 圆心行（由纵向扫描确定，见 docstring）
    xs = [x for x in range(50, W - 50) if dark_neutral(px[x, row_y])]
    disc_l, disc_r = min(xs), max(xs)
    ys = [y for y in range(300, 2400) if dark_neutral(px[720, y])]
    disc_t, disc_b = min(ys), max(ys)

    # ── 唱针轴心（唱片上方纯白圆） ─────────────────────────────────────────
    # 轴心是**实心圆**（直径 78px），杆宽仅 22px —— 逐行统计白像素数 > 60 才判为圆，
    # 否则会把弧杆起点误算进 bbox（旧版误差 40dp 的原因）。
    circle_rows = []
    for y in range(400, 800):
        n = sum(1 for x in range(600, 900) if min(px[x, y]) > 210)
        if n > 40:
            circle_rows.append(y)
    wxs = []
    for y in (min(circle_rows), max(circle_rows)):
        for x in range(600, 900):
            if min(px[x, y]) > 210:
                wxs.append(x)
    pivot_cy = (min(circle_rows) + max(circle_rows)) / 2
    pivot_cx = (min(wxs) + max(wxs)) / 2
    pivot_r = (max(circle_rows) - min(circle_rows)) / 2

    # ── 唱针轨迹 → 唱头（跳过轴心，取最宽白块） ────────────────────────────
    track = []
    for y in range(640, 1200, 4):
        row = [x for x in range(500, 1100) if min(px[x, y]) > 225]
        if row:
            track.append((y, min(row), max(row), sum(row) // len(row)))
    # 唱头是斜置圆角块：从「首次达到最大宽度」起的所有行都属唱头，取其中点。
    arm_w = sorted(t[2] - t[1] for t in track[:20])[len(track[:20]) // 2] if track else 0
    widest = max((t[2] - t[1] for t in track), default=0)
    head_rows = [t for t in track if t[2] - t[1] >= widest - 8]
    if head_rows:
        head = (
            (head_rows[0][0] + head_rows[-1][0]) // 2,
            min(t[1] for t in head_rows),
            max(t[2] for t in head_rows),
            sum(t[3] for t in head_rows) // len(head_rows),
        )
    else:
        head = (0, 0, 0, 0)

    # ── 各文字 / 图标带 ────────────────────────────────────────────────────
    bands = bright_bands(px, W, STATUS_BAR_PX, NAV_BAR_TOP_PX)
    # band 会被抗锯齿切断（如 50.9~53.7 + 54.0~69.7 是同一行文字），相邻间隔 < 0.5dp 即合并（标题与艺人之间隔 0.9dp，必须保持分开）。
    merged = []
    for b in bands:
        if merged and dp(b[0] - merged[-1][1]) <= 0.5:
            merged[-1] = (merged[-1][0], b[1])
        else:
            merged.append(list(b))
    top_bar = merged[0] if merged else [178, 244]

    # 进度条轨道（最亮的一行，跨整宽）
    track_row, track_l, track_r = 0, 0, 0
    for y in range(2300, 2420):
        row = [x for x in range(W) if sum(px[x, y]) > 200]
        if len(row) > 800:
            track_row, track_l, track_r = y, min(row), max(row)
            break

    main_row = col_clusters(px, W, 2600, 2720)
    sec_row = col_clusters(px, W, 2860, 2935)
    tbar_row = col_clusters(px, W, 160, 260)

    text_left = bright_bands_win(px, 95, 330, 1890, 2450)

    result = {
        "source": str(BASELINE.relative_to(ROOT)).replace("\\", "/"),
        "image": {"w": W, "h": H, "density": DENSITY,
                  "dp": [dp(W), dp(H)],
                  "status_bar_px": STATUS_BAR_PX, "nav_bar_top_px": NAV_BAR_TOP_PX},
        "content": {
            "top_dp": dp(STATUS_BAR_PX),
            "bottom_dp": dp(NAV_BAR_TOP_PX),
            "height_dp": dp(NAV_BAR_TOP_PX - STATUS_BAR_PX),
        },
        "disc": {
            "left_px": disc_l, "right_px": disc_r, "top_px": disc_t, "bottom_px": disc_b,
            "cx_px": (disc_l + disc_r) // 2, "cy_px": (disc_t + disc_b) // 2,
            "diameter_px": disc_r - disc_l,
            "diameter_dp": dp(disc_r - disc_l),
            "diameter_ratio_of_width": round((disc_r - disc_l) / W, 4),
            "top_dp": dp(disc_t), "bottom_dp": dp(disc_b), "center_dp": dp((disc_t + disc_b) / 2),
        },
        "needle": {
            "pivot_px": [round(pivot_cx, 1), round(pivot_cy, 1)],
            "pivot_dp": [dp(pivot_cx), dp(pivot_cy)],
            "pivot_x_ratio": round(pivot_cx / W, 4),
            "pivot_radius_px": round(pivot_r, 1),
            "pivot_radius_dp": dp(pivot_r),
            "arm_width_px": arm_w,
            "arm_width_dp": dp(arm_w),
            "head_center_px": [head[3], head[0]],
            "head_center_dp": [dp(head[3]), dp(head[0])],
            "head_x_ratio": round(head[3] / W, 4),
            "head_width_px": head[2] - head[1],
            "head_width_dp": dp(head[2] - head[1]),
        },
        "progress": {
            "track_y_px": track_row, "track_y_dp": dp(track_row),
            "left_px": track_l, "right_px": track_r,
            "left_dp": dp(track_l), "right_dp": dp(track_r),
            "width_dp": dp(track_r - track_l),
        },
        "bands_dp": [{"top": dp(a), "bottom": dp(b), "height": dp(b - a)} for a, b in merged],
        # 左列专扫（x 95~330px ≈ 27~94dp，避开右侧图标）：在线胶囊 / 标题 / 艺人 / 轨道
        "text_bands_left_dp": [
            {"top": dp(a), "bottom": dp(b), "height": dp(b - a)} for a, b in text_left
        ],
        "top_bar": [{"center_dp": dp(c[2]), "width_dp": dp(c[3])} for c in tbar_row],
        "main_control": [{"center_dp": dp(c[2]), "width_dp": dp(c[3])} for c in main_row],
        "secondary_control": [{"center_dp": dp(c[2]), "width_dp": dp(c[3])} for c in sec_row],
        "derived": {
            "top_bar_height_dp": dp(top_bar[1] - top_bar[0] + 26),
            "pause_bar_width_dp": dp(main_row[2][3]) if len(main_row) > 3 else None,
            "pause_bar_gap_dp": dp(main_row[3][2] - main_row[2][2] - main_row[2][3])
            if len(main_row) > 3 else None,
        },
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(f"\n已写入 {OUT.relative_to(ROOT)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
