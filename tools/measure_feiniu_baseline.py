"""飞牛设置页 / 搜索页逐像素量化（`12 §61.175`）。

把 `analysis/screenshots/reference-capture/1615{50,34}_feiniu.png`（真机 1440×3200 @3.5
⇒ 411.4×914.3dp）量成 `analysis/l3-reference/feiniu-page-metrics.json`，
作为 `FnBaseline` 常量的唯一来源，`tools/verify_feiniu_pages.py` 负责把它们与
Kotlin 源码里的常量逐项比对。

沿用 `§61.174` 已修正的两条口径：
- 字号一律用**同排汉字相邻左缘步进**（= 1em 宽），不用字带高反推（CJK 墨高仅 ≈0.94em）；
- 左侧文字一律**限定列窗口**扫描，不用全宽（会被右侧图标粘连）。
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REF_DIR = ROOT / "analysis" / "screenshots" / "reference-capture"
SETTINGS = REF_DIR / "161550_feiniu.png"
SEARCH = REF_DIR / "161534_feiniu.png"
OUT = ROOT / "analysis" / "l3-reference" / "feiniu-page-metrics.json"

DENSITY = 3.5
SCREEN_W_DP = 1440 / DENSITY
SCREEN_H_DP = 3200 / DENSITY

BG = (30, 30, 33)
CARD = (43, 43, 46)
DIVIDER = (60, 60, 63)


def dp(px: float) -> float:
    return round(px / DENSITY, 1)


def load(path: Path):
    from PIL import Image

    im = Image.open(path).convert("RGB")
    return im, im.load()


def is_bg(c) -> bool:
    return abs(c[0] - BG[0]) <= 3 and abs(c[1] - BG[1]) <= 3 and abs(c[2] - BG[2]) <= 3


def is_card(c) -> bool:
    return abs(c[0] - CARD[0]) <= 4 and abs(c[1] - CARD[1]) <= 4 and abs(c[2] - CARD[2]) <= 4


def is_divider(c) -> bool:
    return (
        abs(c[0] - DIVIDER[0]) <= 5
        and abs(c[1] - DIVIDER[1]) <= 5
        and abs(c[2] - DIVIDER[2]) <= 5
    )


# ── 通用扫描 ────────────────────────────────────────────────────────────────


def ink_cols(px, x0: int, x1: int, y0: int, y1: int, pred, min_w: int = 2):
    out = []
    cur = None
    for x in range(x0, x1):
        hit = any(pred(px[x, y]) for y in range(y0, y1))
        if hit:
            cur = [x, x] if cur is None else [cur[0], x]
        elif cur is not None:
            out.append(cur)
            cur = None
    if cur is not None:
        out.append(cur)
    return [(a, b) for a, b in out if b - a + 1 >= min_w]


def advance(cols_dp):
    """同排汉字左缘步进 = 1em 宽 ⇒ 字号（sp）。取中位数抗个别字形误差。"""
    if len(cols_dp) < 2:
        return None
    steps = sorted(round(cols_dp[i + 1] - cols_dp[i], 2) for i in range(len(cols_dp) - 1))
    return steps[len(steps) // 2]


def longest_run(flags):
    best = None
    cur = None
    for i, v in enumerate(flags):
        if v:
            cur = [i, i] if cur is None else [cur[0], i]
        elif cur is not None:
            if best is None or (cur[1] - cur[0]) > (best[1] - best[0]):
                best = cur
            cur = None
    if cur is not None and (best is None or (cur[1] - cur[0]) > (best[1] - best[0])):
        best = cur
    return (best[0], best[1]) if best else None


def card_bands(px, W: int, H: int, thr: int = 100, y_max=None, gap_px: int = 8):
    """逐行统计「卡片色 #2B2B2E」命中列数，返回连续行带（像素）。

    只认卡片色、不认「非背景」，才能把卡片组与顶栏 / 状态栏 / 底部胶囊区分开
    （§61.175 首版把「非背景」当卡片，索引整体偏移 2 位，行高被算成 17.0）。

    组内行之间有 **1dp 分隔线** `#3C3C3F`，会把一个组切成 N 段；
    因此按 `gap_px`（默认 8px ≈ 2.3dp）把相邻段合并回组。
    """
    limit = H if y_max is None else min(H, y_max)
    segs = []
    cur = None
    for y in range(0, limit):
        n = sum(1 for x in range(0, W, 4) if is_card(px[x, y]))
        if n > thr:
            cur = [y, y] if cur is None else [cur[0], y]
        elif cur is not None:
            segs.append(tuple(cur))
            cur = None
    if cur is not None:
        segs.append(tuple(cur))

    merged = []
    for a, b in segs:
        if merged and a - merged[-1][1] <= gap_px:
            merged[-1] = (merged[-1][0], b)
        else:
            merged.append((a, b))
    return merged


# ── 设置页 ──────────────────────────────────────────────────────────────────


def measure_settings() -> dict:
    im, px = load(SETTINGS)
    W, H = im.size

    raw = card_bands(px, W, H)
    cards = [
        {"top": dp(a), "bottom": dp(b), "height": dp(b - a + 1)}
        for a, b in raw
        if (b - a + 1) > 30 * DENSITY
    ]

    def row_edges(y: int):
        """该行「非背景」的最左 / 最右像素（卡片的物理边缘）。"""
        xs = [x for x in range(W) if not is_bg(px[x, y])]
        return (xs[0], xs[-1]) if xs else None

    card_x = None
    row_h = None
    group_rows = None
    if len(cards) >= 2:
        g2 = cards[1]
        # 组 2 是 4 行组（修改密码 / 外观 / 音质偏好 / 自动缓存歌曲）
        group_rows = 4
        row_h = round(g2["height"] / group_rows, 1)
        # 卡宽必须用「非背景」逐行扫再取极值：行内有红图标 / 文字 / chevron，
        # 用「卡片色连续段」会被切成多截，最长段不含真实右缘（§61.175 踩过两次）。
        lefts_x, rights_x = [], []
        for y in range(int(g2["top"] * DENSITY) + 2, int(g2["bottom"] * DENSITY) - 2):
            e = row_edges(y)
            if e:
                lefts_x.append(e[0])
                rights_x.append(e[1])
        if lefts_x:
            card_x = [dp(min(lefts_x)), dp(max(rights_x))]

    divider = None
    if len(cards) >= 2:
        g2 = cards[1]
        ys = []
        for y in range(int(g2["top"] * DENSITY), int(g2["bottom"] * DENSITY)):
            if is_divider(px[int(200 * DENSITY), y]):
                ys.append(y)
        if ys:
            xs = [x for x in range(W) if is_divider(px[x, ys[len(ys) // 2]])]
            # 组 2 有 4 行 ⇒ 3 条分隔线；ys 是「行」不是「线」，逐段统计
            segs = []
            cur = None
            for y in ys:
                cur = [y, y] if cur is None else [cur[0], y]
                if cur is not None:
                    pass
            # 连续行合并成线
            lines = []
            cur = None
            for y in ys:
                if cur is None:
                    cur = [y, y]
                elif y - cur[1] <= 1:
                    cur[1] = y
                else:
                    lines.append(cur)
                    cur = [y, y]
            if cur is not None:
                lines.append(cur)
            divider = {
                "lines_in_group": len(lines),
                "height_dp": round(sum(b - a + 1 for a, b in lines) / max(1, len(lines)) / DENSITY, 1),
                "left_dp": dp(min(xs)),
                "right_dp": dp(max(xs)),
                "color": "#%02X%02X%02X" % DIVIDER,
            }

    corner = None
    if cards and card_x:
        top_px = int(cards[0]["top"] * DENSITY)
        target = card_x[0]
        # 采样各 dy 的内缩量，再枚举半径做最小二乘。
        # 单点「左缘首次到达稳定值的深度」会被顶部 1~2px 抗锯齿带偏（实测偏小 2.6dp）。
        samples = []
        for dy_px in range(1, int(20 * DENSITY)):
            e = row_edges(top_px + dy_px)
            if e:
                inset = dp(e[0]) - target
                if inset >= 0:
                    samples.append((dy_px / DENSITY, inset))
        best_r, best_err = None, None
        r = 4.0
        while r <= 24.0:
            err = 0.0
            n = 0
            for dy, inset in samples:
                if dy <= r:
                    pred = r - (r * r - (r - dy) ** 2) ** 0.5
                else:
                    pred = 0.0
                err += (pred - inset) ** 2
                n += 1
            if n and (best_err is None or err < best_err):
                best_err, best_r = err, r
            r += 0.1
        if best_r is not None:
            corner = round(best_r, 1)

    hdr = None
    if cards:
        y_hi = int(cards[0]["top"] * DENSITY) - 1
        rows = []
        for y in range(int(30 * DENSITY), y_hi):
            n = sum(1 for x in range(0, W, 4) if not is_bg(px[x, y]))
            if n > 3:
                rows.append(y)
        if rows:
            hdr = {"top": dp(min(rows)), "bottom": dp(max(rows))}

    btn = None
    title_adv = None
    if hdr:
        y0, y1 = int(hdr["top"] * DENSITY), int(hdr["bottom"] * DENSITY)
        cols = ink_cols(
            px, 0, int(90 * DENSITY), y0, y1,
            lambda c: 40 < c[0] < 62 and abs(c[0] - c[1]) < 6 and abs(c[1] - c[2]) < 6,
            min_w=10,
        )
        if cols:
            btn = {"left": dp(cols[0][0]), "right": dp(cols[-1][1])}
        cols = ink_cols(px, int(150 * DENSITY), int(270 * DENSITY), y0, y1, lambda c: min(c) > 150)
        if len(cols) >= 2:
            title_adv = advance([dp(a) for a, _ in cols])

    row_icon = None
    row_text_left = None
    row_title_adv = None
    if len(cards) >= 2 and row_h:
        g2 = cards[1]
        y0 = int((g2["top"] + 3) * DENSITY)
        y1 = int((g2["top"] + row_h - 3) * DENSITY)
        red = lambda c: c[0] > 110 and c[0] - c[1] > 45 and c[0] - c[2] > 45
        rcols = ink_cols(px, int(20 * DENSITY), int(60 * DENSITY), y0, y1, red)
        if rcols:
            row_icon = {"left": dp(rcols[0][0]), "right": dp(rcols[-1][1])}
        tcols = ink_cols(px, int(55 * DENSITY), int(170 * DENSITY), y0, y1, lambda c: min(c) > 70)
        if tcols:
            row_text_left = dp(tcols[0][0])
            row_title_adv = advance([dp(a) for a, _ in tcols])

    return {
        "source": str(SETTINGS.relative_to(ROOT)).replace("\\", "/"),
        "density": DENSITY,
        "screen_dp": [round(SCREEN_W_DP, 1), round(SCREEN_H_DP, 1)],
        "colors": {
            "page_bg": "#%02X%02X%02X" % BG,
            "card_bg": "#%02X%02X%02X" % CARD,
        },
        "cards_dp": cards,
        "card_x_dp": card_x,
        "card_corner_dp": corner,
        "group_rows": group_rows,
        "row_height_dp": row_h,
        "row_divider": divider,
        "group_gaps_dp": [
            round(cards[i + 1]["top"] - cards[i]["bottom"], 1) for i in range(len(cards) - 1)
        ],
        "header": hdr,
        "header_button_dp": btn,
        "header_title_advance_sp": title_adv,
        "row_icon_dp": row_icon,
        "row_text_left_dp": row_text_left,
        "row_title_advance_sp": row_title_adv,
    }


# ── 搜索页 ──────────────────────────────────────────────────────────────────


def measure_search() -> dict:
    im, px = load(SEARCH)
    W, H = im.size

    # 框体判据用「亮度 ≥ 40」而不是「非背景」：搜索框带 elevation 阴影，
    # 阴影像素在 #1E1E21 与 #2B2B2E 之间（30~42），「非背景」会把阴影算进框里
    # 把左缘量成 16.0dp（真值 22.3）。
    def is_box(c) -> bool:
        return c[0] >= 40 and c[1] >= 40 and c[2] >= 42

    box = None
    raw = card_bands(px, W, int(200 * DENSITY), thr=60)
    if raw:
        y0, y1 = raw[0]
        # 逐行取「框体最长连续段」再汇总：框内有放大镜和占位文字会截断色段，
        # 而逐行取 min/max 又会把右侧「取消」并进来（§61.175 踩过两次）。
        lefts_x, rights_x = [], []
        for y in range(y0 + 2, y1 - 2):
            run = longest_run([is_box(px[x, y]) for x in range(W)])
            if run:
                lefts_x.append(run[0])
                rights_x.append(run[1])
        if lefts_x:
            box_left = dp(min(lefts_x))
            # 圆角：枚举半径做最小二乘（与卡片同一套方法）
            sb_samples = []
            for dy_px in range(1, int(10 * DENSITY)):
                run = longest_run([is_box(px[x, y0 + dy_px]) for x in range(W)])
                if run:
                    inset = dp(run[0]) - box_left
                    if inset >= 0:
                        sb_samples.append((dy_px / DENSITY, inset))
            sb_best, sb_err = None, None
            rr = 3.0
            while rr <= 22.0:
                err = 0.0
                for dy, inset in sb_samples:
                    pred = (rr - (rr * rr - (rr - dy) ** 2) ** 0.5) if dy <= rr else 0.0
                    err += (pred - inset) ** 2
                if sb_samples and (sb_err is None or err < sb_err):
                    sb_err, sb_best = err, rr
                rr += 0.1
            box = {
                "top": dp(y0),
                "bottom": dp(y1),
                "height": dp(y1 - y0 + 1),
                "left": box_left,
                "right": dp(max(rights_x)),
                "corner_dp": round(sb_best, 1) if sb_best is not None else None,
            }

    glyph = None
    hint_left = None
    hint_adv = None
    if box:
        y0 = int(box["top"] * DENSITY) + 4
        y1 = int(box["bottom"] * DENSITY) - 4
        cols = ink_cols(
            px, int(box["left"] * DENSITY), int((box["right"] - 4) * DENSITY),
            y0, y1, lambda c: min(c) > 60,
        )
        if cols:
            glyph = {"left": dp(cols[0][0]), "right": dp(cols[0][1])}
            if len(cols) >= 3:
                hint_left = dp(cols[1][0])
                hint_adv = advance([dp(a) for a, _ in cols[1:]])

    cancel = None
    if box:
        y0 = int(box["top"] * DENSITY)
        y1 = int(box["bottom"] * DENSITY)
        cols = ink_cols(px, int((box["right"] + 4) * DENSITY), W, y0, y1, lambda c: min(c) > 90)
        if cols:
            cancel = {
                "left": dp(cols[0][0]),
                "right": dp(cols[-1][1]),
                "advance_sp": advance([dp(a) for a, _ in cols]),
            }

    return {
        "source": str(SEARCH.relative_to(ROOT)).replace("\\", "/"),
        "search_box_dp": box,
        "glyph_dp": glyph,
        "hint_left_dp": hint_left,
        "hint_advance_sp": hint_adv,
        "cancel": cancel,
    }


def main() -> int:
    try:
        settings = measure_settings()
        search = measure_search()
    except ModuleNotFoundError as exc:  # pragma: no cover - 环境缺 Pillow
        print(f"缺少依赖：{exc}；先 `python -m pip install pillow`")
        return 2

    result = {
        "method": "feiniu_page_pixel_measure",
        "note": "字号 = 同排汉字相邻左缘步进（=1em）；左侧文字限定列窗口扫描（避免右侧图标粘连）",
        "settings": settings,
        "search": search,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    s = settings
    print(f"卡片 x {s['card_x_dp']}  圆角 {s['card_corner_dp']}  行高 {s['row_height_dp']}")
    print(f"组间距 {s['group_gaps_dp']}  分隔线 {s['row_divider']}")
    print(f"顶栏 {s['header']}  钮 {s['header_button_dp']}  标题字号 {s['header_title_advance_sp']}")
    print(f"行图标 {s['row_icon_dp']}  文字左 {s['row_text_left_dp']}  标题字号 {s['row_title_advance_sp']}")
    q = search
    print(f"搜索框 {q['search_box_dp']}")
    print(f"放大镜 {q['glyph_dp']}  占位左 {q['hint_left_dp']}  字号 {q['hint_advance_sp']}")
    print(f"取消 {q['cancel']}")
    print(f"证据 -> {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
