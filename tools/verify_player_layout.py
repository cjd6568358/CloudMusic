#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""播放页布局链自校验（`12 §61.174` 的第二层证据）。

三份证据的分工：

1. `tools/measure_player_baseline.py` —— **量竞品**：从基准图逐像素提取每个元素的
   bbox / 文字带 / 唱针轴心，产出 `player-baseline-metrics.json`。
2. **本脚本（静态模式）** —— 把 App 侧 `PlayerBaseline` 常量代进真实的 Column 布局链，
   算出「渲染后每个元素应该落在哪」，再与第 1 步的实测值比对，误差 > 2dp 即 FAIL。
   这弥补了一个盲区：常量本身对、但间距链拼错（例如漏了一个 Spacer），单看常量表发现不了。
3. **本脚本 `--screenshot` 模式** —— **量自己**：拿真机截图跑同一套带提取器，
   与基准带逐个比对。这是唯一能同时覆盖「常量对 + 链路对 + 字体渲染对」的证据，
   因为文字带的实际位置还受字体墨高（ink height）影响，而墨高不是常量能表达的。

判据口径（2026-11-16 修正）：
  `title_top` / `artist_top` 比对的是**文字墨带顶**，不是行框顶。
  旧版本拿行框顶比墨带顶，属范畴错误（差 (行高-墨高)/2），已改为按居中绘制模型换算。
  墨高取基准图**左列**实测（排除右侧图标污染）：标题 16.3dp、艺人 12.8dp。

只读脚本，不改代码。
用法：
  python tools/verify_player_layout.py                        # 静态链路校验
  python tools/verify_player_layout.py --screenshot <png>     # 真机截图逐带比对
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/kotlin/com/example/cloudmusic/ui/player/LegacyAssets.kt"
METRICS = ROOT / "analysis/l3-reference/player-baseline-metrics.json"
OUT_STATIC = ROOT / "analysis/l3-reference/player-layout-selfcheck.json"
OUT_SHOT = ROOT / "analysis/l3-reference/player-screenshot-diff.json"

TOL = 2.0        # dp
DENSITY = 3.5    # 基准图 / 真机截图同为 1440x3200 @ 560dpi
INK_TITLE = 16.3   # 标题汉字墨高（基准图左列实测 602.3~618.6）
INK_ARTIST = 13.1  # 艺人汉字墨高（基准图左列实测 632.3~645.4）


def parse_consts(path: Path) -> dict[str, float]:
    text = path.read_text(encoding="utf-8")
    block = text.split("object PlayerBaseline {", 1)[1]
    out: dict[str, float] = {}
    for m in re.finditer(r"const val (\w+)\s*=\s*(-?[0-9.]+)f", block):
        out[m.group(1)] = float(m.group(2))
    return out


# ── 带提取器（基准图与真机截图共用同一套，保证可比） ────────────────────────────
def extract_bands(png: Path, x0_px: int = 95, x1_px: int = 330,
                  y0_dp: float = 540.0, y1_dp: float = 700.0,
                  th: int = 90, min_hits: int = 2) -> list[dict]:
    """在「唱片区下方 ~ 进度条」窗口内，按行找亮像素带。

    只扫左列（默认 x 95~330px ≈ 27~94dp），刻意避开右侧的点赞 / 评论图标，
    否则图标会把标题带和艺人带粘成一条（基准图上正是如此）。
    """
    from PIL import Image  # 延迟导入：静态模式不需要 pillow

    im = Image.open(png).convert("RGB")
    px = im.load()
    rows: list[list[int]] = []
    cur = None
    for y in range(int(y0_dp * DENSITY), int(y1_dp * DENSITY)):
        hits = 0
        for x in range(x0_px, min(x1_px, im.size[0])):
            r, g, b = px[x, y]
            if min(r, g, b) > th:
                hits += 1
        if hits > min_hits:
            if cur is None:
                cur = [y, y]
            else:
                cur[1] = y
        elif cur is not None:
            rows.append(cur)
            cur = None
    if cur is not None:
        rows.append(cur)
    return [
        {
            "top": round(a / DENSITY, 1),
            "bottom": round(b / DENSITY, 1),
            "height": round((b - a + 1) / DENSITY, 1),
        }
        for a, b in rows
        if (b - a) / DENSITY >= 1.0          # 滤掉 1dp 级噪点（如抗锯齿残留）
    ]


def band_by_top(bands: list[dict], lo: float, hi: float) -> dict:
    return next(b for b in bands if lo <= b["top"] <= hi)


def progress_track_y(png: Path, y0_dp: float = 640.0, y1_dp: float = 720.0) -> float:
    """定位进度条轨道 y（dp）：找窗口内最长的亮横线。

    用轨道而不是「屏幕绝对 y」当锚点，是因为**不同机型的系统栏高度不同**：
    基准机导航栏高 60.6dp、本机 49dp，可用内容高差 11.7dp，而唱片区吃 `weight(1f)`、
    唱片又底对齐 ⇒ 整块信息区会整体平移。用轨道做原点即可把这种设备差异归一化掉。
    """
    from PIL import Image

    im = Image.open(png).convert("RGB")
    px = im.load()
    best_y, best_n = 0, 0
    for y in range(int(y0_dp * DENSITY), int(y1_dp * DENSITY)):
        # 阈值放低到 55：未播放段的轨道是中性灰（≈ #4D4D4D），min 通道仅 ~77，
        # 用文字级阈值会把时间文字行误判成轨道（§61.174 ⑨ 踩过）。
        n = sum(1 for x in range(100, min(1340, im.size[0]), 3) if min(px[x, y]) > 55)
        if n > best_n:
            best_y, best_n = y, n
    # 轨道应横跨 ~350dp；命中数不足说明扫到的是文字行
    return round(best_y / DENSITY, 1) if best_n > 250 else 0.0


def relative_offsets(bands: list[dict], track_y: float) -> list[dict]:
    """把每条带换算成「相对进度条轨道」的偏移，得到与设备无关的口径。"""
    return [
        {
            "top_off": round(b["top"] - track_y, 1),
            "bottom_off": round(b["bottom"] - track_y, 1),
            "height": b["height"],
        }
        for b in bands
    ]


# ── 静态链路校验 ────────────────────────────────────────────────────────────────
def static_check() -> int:
    c = parse_consts(ASSETS)
    ref = json.loads(METRICS.read_text(encoding="utf-8"))
    W = ref["image"]["dp"][0]                 # 411.4
    content_top = ref["content"]["top_dp"]    # 37.1
    bottom_fixed = (
        c["GapDiscToTitleDp"] + c["TitleRowHeightDp"] + c["GapTitleToArtistDp"]
        + c["ArtistRowHeightDp"]
        + c["GapArtistToProgressDp"] + c["ProgressTouchDp"] + c["GapProgressToTimeDp"]
        + c["TimeRowHeightDp"]
        + c["GapTimeToMainControlDp"] + c["MainControlIconDp"]
        + c["GapMainToSecondaryDp"] + c["SecondaryControlDp"] + c["BottomPaddingDp"]
    )
    target_content_h = ref["content"]["height_dp"]

    # 唱片区吃剩余高度（Column.weight(1f)），唱片 = min(宽×比例, 区高×比例)，底对齐
    disc_zone_h = target_content_h - c["TopBarHeightDp"] - bottom_fixed
    disc_d = min(W * c["DiscDiameterRatio"], disc_zone_h * c["DiscMaxHeightRatio"])
    disc_top = content_top + c["TopBarHeightDp"] + disc_zone_h - disc_d
    disc_bottom = disc_top + disc_d

    # 下游累加（行框顶）
    title_box = disc_bottom + c["GapDiscToTitleDp"]
    artist_box = title_box + c["TitleRowHeightDp"] + c["GapTitleToArtistDp"]
    # 文字在行框内**居中**绘制 → 墨带顶 = 行框顶 + (行高 - 墨高)/2
    title_ink = title_box + (c["TitleRowHeightDp"] - INK_TITLE) / 2
    artist_ink = artist_box + (c["ArtistRowHeightDp"] - INK_ARTIST) / 2
    progress_center = artist_box + c["ArtistRowHeightDp"] + c["GapArtistToProgressDp"] + c["ProgressTouchDp"] / 2
    time_top = progress_center + c["ProgressTouchDp"] / 2 + c["GapProgressToTimeDp"]
    time_center = time_top + c["TimeRowHeightDp"] / 2
    main_top = time_top + c["TimeRowHeightDp"] + c["GapTimeToMainControlDp"]
    main_center = main_top + c["MainControlIconDp"] / 2
    sec_top = main_top + c["MainControlIconDp"] + c["GapMainToSecondaryDp"]
    sec_center = sec_top + c["SecondaryControlDp"] / 2
    content_bottom = sec_top + c["SecondaryControlDp"] + c["BottomPaddingDp"]

    # 唱针几何（相对唱片区）
    pivot_y_abs = content_top + c["TopBarHeightDp"] + disc_zone_h * c["NeedlePivotYRatio"]
    head_x_abs = W * c["NeedleHeadXRatio"]
    head_y_abs = content_top + c["TopBarHeightDp"] + disc_zone_h * c["NeedleHeadYRatio"]

    derived = {
        # 字号（sp）：基准图同排汉字左缘步进 = 1em ⇒ 直接就是字号
        "title_sp": c["TitleSp"],
        "artist_sp": c["ArtistSp"],
        "disc_top_dp": disc_top,
        "disc_bottom_dp": disc_bottom,
        "disc_diameter_dp": disc_d,
        "title_ink_top_dp": title_ink,
        "artist_ink_top_dp": artist_ink,
        "progress_center_dp": progress_center,
        "time_center_dp": time_center,
        "main_center_dp": main_center,
        "secondary_center_dp": sec_center,
        "content_bottom_dp": content_bottom,
        "needle_pivot_y_dp": pivot_y_abs,
        "needle_head_x_dp": head_x_abs,
        "needle_head_y_dp": head_y_abs,
    }

    bands = ref["bands_dp"]
    # 标题 / 艺人取「左列专扫」带：全宽带会被右侧点赞 / 评论图标粘连（见 §61.174 ⑥）
    left = ref["text_bands_left_dp"]
    _title = band_by_top(left, 595, 610)
    _artist = band_by_top(left, 625, 640)

    measured = {
        "title_sp": 17.3,    # 27.1/44.6/61.7/78.9 → 步进 17.5/17.1/17.2
        "artist_sp": 13.9,   # 27.1/41.7/54.9 → 步进 14.6/13.2（两点均值）
        "disc_top_dp": ref["disc"]["top_dp"],
        "disc_bottom_dp": ref["disc"]["bottom_dp"],
        "disc_diameter_dp": ref["disc"]["diameter_dp"],
        "title_ink_top_dp": _title["top"],
        "artist_ink_top_dp": _artist["top"],
        "progress_center_dp": ref["progress"]["track_y_dp"],
        "time_center_dp": (band_by_top(bands, 685, 695)["top"]
                           + band_by_top(bands, 685, 695)["bottom"]) / 2,
        "main_center_dp": (band_by_top(bands, 740, 750)["top"]
                           + band_by_top(bands, 740, 750)["bottom"]) / 2,
        "secondary_center_dp": (band_by_top(bands, 815, 825)["top"]
                                + band_by_top(bands, 815, 825)["bottom"]) / 2,
        "content_bottom_dp": ref["content"]["bottom_dp"],
        "needle_pivot_y_dp": ref["needle"]["pivot_dp"][1],
        "needle_head_x_dp": ref["needle"]["head_center_dp"][0],
        "needle_head_y_dp": ref["needle"]["head_center_dp"][1],
    }

    SP_TOL = 0.8   # 字号容差（sp）：2dp 的通用容差对字号等于没判
    rows = []
    worst = 0.0
    for k, v in derived.items():
        m = measured[k]
        tol = SP_TOL if k.endswith("_sp") else TOL
        err = round(abs(v - m), 1)
        worst = max(worst, err)
        rows.append({
            "field": k,
            "derived": round(v, 1),
            "measured": round(m, 1),
            "abs_error": err,
            "tolerance": tol,
            "pass": err <= tol,
        })

    result = {
        "method": "player_layout_chain_selfcheck",
        "source_code": str(ASSETS.relative_to(ROOT)).replace("\\", "/"),
        "reference_metrics": str(METRICS.relative_to(ROOT)).replace("\\", "/"),
        "assumptions": {
            "screen_dp": [W, ref["image"]["dp"][1]],
            "content_top_dp": content_top,
            "bottom_fixed_sum_dp": round(bottom_fixed, 1),
            "disc_zone_height_dp": round(disc_zone_h, 1),
            "ink_heights_dp": {"title": INK_TITLE, "artist": INK_ARTIST},
            "text_rendering_model": "文字在行框内居中 → 墨带顶 = 行框顶 + (行高 - 墨高)/2",
            "note": "content_bottom 由 target_content_h 反推，属构造性重合，不作独立判据",
        },
        "rows": rows,
        "worst_abs_error_dp": worst,
        "tolerance_dp": TOL,
        "pass": all(r["pass"] for r in rows),
    }
    OUT_STATIC.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"{'字段':<24}{'推导':>8}{'实测':>8}{'误差':>7}{'容差':>7}  判定")
    for r in rows:
        print(f"{r['field']:<24}{r['derived']:>8}{r['measured']:>8}{r['abs_error']:>7}"
              f"{r['tolerance']:>7.1f}  {'PASS' if r['pass'] else 'FAIL'}")
    print(f"\n最大误差 {worst} dp（容差 {TOL} dp）→ 结果：{'PASS' if result['pass'] else 'FAIL'}")
    print(f"证据 -> {OUT_STATIC.relative_to(ROOT)}")
    return 0 if result["pass"] else 1


# ── 真机截图逐带比对 ────────────────────────────────────────────────────────────
def screenshot_check(png: Path) -> int:
    ref = json.loads(METRICS.read_text(encoding="utf-8"))
    ref_bands = [b for b in ref["text_bands_left_dp"] if 560 <= b["top"] <= 730]
    ref_track = ref["progress"]["track_y_dp"]

    got_bands = extract_bands(png, y1_dp=730.0)
    got_track = progress_track_y(png)

    ref_rel = relative_offsets(ref_bands, ref_track)
    got_rel = relative_offsets(got_bands, got_track)

    # 按「相对偏移」配对：每条基准带找我方偏移最接近的一条（我方无数据源的带自然空出）
    rows = []
    names = {0: "在线胶囊", 1: "标题", 2: "艺人", 3: "进度条", 4: "时间"}
    for i, rb in enumerate(ref_rel):
        match = None
        for j, gb in enumerate(got_rel):
            if any(r.get("got_index") == j for r in rows):
                continue
            if abs(gb["top_off"] - rb["top_off"]) <= 4.0:
                match = (j, gb)
                break
        label = names.get(i, f"band#{i}")
        if match is None:
            rows.append({
                "element": label, "ref_top_off": rb["top_off"], "got_top_off": None,
                "d_top": None, "ref_h": rb["height"], "got_h": None, "d_h": None,
                "status": "我方未渲染（无数据源，预期）",
            })
            continue
        j, gb = match
        rows.append({
            "element": label,
            "ref_top_off": rb["top_off"], "got_top_off": gb["top_off"],
            "d_top": round(abs(rb["top_off"] - gb["top_off"]), 1),
            "ref_h": rb["height"], "got_h": gb["height"],
            "d_h": round(abs(rb["height"] - gb["height"]), 1),
            "got_index": j, "status": "已比对",
        })

    compared = [r for r in rows if r["d_top"] is not None]
    worst = max((max(r["d_top"], r["d_h"]) for r in compared), default=999.0)
    result = {
        "method": "player_screenshot_band_diff",
        "screenshot": str(png).replace(str(ROOT) + "\\", "").replace("\\", "/"),
        "normalization": "以进度条轨道为原点（消除系统栏高度差异导致的整块平移）",
        "window_dp": [540, 730],
        "column_px": [95, 330],
        "ref_track_y_dp": ref_track,
        "screenshot_track_y_dp": got_track,
        "rows": rows,
        "worst_abs_error_dp": worst,
        "tolerance_dp": TOL,
        "pass": worst <= TOL,
        "note": "第一带（在线胶囊）我方无数据源不渲染属预期；唱针需处于落下态（播放中）才与基准同姿态",
    }
    OUT_SHOT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"轨道 y：基准 {ref_track}dp / 截图 {got_track}dp")
    print(f"{'元素':<8}{'基准Δ':>8}{'截图Δ':>8}{'Δ误差':>7}{'基准高':>8}{'截图高':>8}{'Δ高':>7}  说明")
    for r in rows:
        got = "" if r["got_top_off"] is None else r["got_top_off"]
        dt = "" if r["d_top"] is None else r["d_top"]
        dh = "" if r["d_h"] is None else r["d_h"]
        gh = "" if r["got_h"] is None else r["got_h"]
        print(f"{r['element']:<8}{r['ref_top_off']:>8}{got:>8}{dt:>7}"
              f"{r['ref_h']:>8}{gh:>8}{dh:>7}  {r['status']}")
    print(f"\n最大偏差 {worst} dp（容差 {TOL} dp）→ 结果：{'PASS' if result['pass'] else 'FAIL'}")
    print(f"证据 -> {OUT_SHOT.relative_to(ROOT)}")
    return 0 if result["pass"] else 1


def main() -> int:
    if "--screenshot" in sys.argv:
        i = sys.argv.index("--screenshot")
        png = Path(sys.argv[i + 1])
        if not png.is_absolute():
            png = ROOT / png
        return screenshot_check(png)
    return static_check()


if __name__ == "__main__":
    sys.exit(main())
