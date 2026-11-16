"""飞牛页面常量自校验：`FnBaseline` vs 基准图实测（`12 §61.175`）。

第一层证据是 `tools/measure_feiniu_baseline.py` 量竞品截图，
第二层是本脚本把 Kotlin 源码里的 `FnBaseline` 常量解析出来，逐项与
`analysis/l3-reference/feiniu-page-metrics.json` 比对。

为什么要有第二层：`FnBaseline` 是人手抄进代码的，抄错一个数字编译器不会报。
上一批（`§61.174`）就是靠这一层抓到「字号由字带高反推偏大 39%」这类常量级错误。
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
METRICS = ROOT / "analysis" / "l3-reference" / "feiniu-page-metrics.json"
OUT = ROOT / "analysis" / "l3-reference" / "feiniu-page-selfcheck.json"
UI_DIR = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "ui"

TOL_DP = 2.0
TOL_SP = 0.8


def parse_constants() -> dict[str, float]:
    """从 FeiniuUi.kt 的 `object FnBaseline` 里解析 `const val XxxYyy = 1.2f`。"""
    src = (UI_DIR / "FeiniuUi.kt").read_text(encoding="utf-8")
    start = src.index("object FnBaseline {")
    end = src.index("\n}", src.index("const val", start))
    body = src[start:end]
    out = {}
    for m in re.finditer(r"const val (\w+)\s*=\s*(-?\d+(?:\.\d+)?)f", body):
        out[m.group(1)] = float(m.group(2))
    return out


def build_rows(c: dict[str, float], ref: dict) -> list[dict]:
    st = ref["settings"]
    sq = ref["search"]
    cards = st["cards_dp"]
    box = sq["search_box_dp"]
    gaps = st["group_gaps_dp"]
    screen_w = st["screen_dp"][0]

    def px_delta(a, b):
        return None if a is None or b is None else round(b - a, 1)

    measured: dict[str, tuple[float | None, float, str]] = {
        # 字段: (实测值, 容差, 依据说明)
        "PagePaddingDp": (st["card_x_dp"][0], TOL_DP, "卡片左缘"),
        "CardCornerDp": (st["card_corner_dp"], TOL_DP, "圆角枚举拟合"),
        "RowHeightDp": (st["row_height_dp"], TOL_DP, "组 2 高 201.4 / 4 行"),
        "GroupGapDp": (gaps[1] if len(gaps) > 1 else None, TOL_DP, "组 3 顶 − 组 2 底"),
        "HeaderToCardGapDp": (
            px_delta(st["header"]["bottom"] if st["header"] else None, cards[0]["top"]),
            TOL_DP,
            "顶栏底 → 首卡顶",
        ),
        "StatToNextGroupDp": (
            px_delta(cards[0]["bottom"], cards[1]["top"]),
            TOL_DP,
            "统计卡底 → 组 2 顶",
        ),
        "RowDividerDp": (
            (st["row_divider"] or {}).get("height_dp"),
            0.8,
            "分隔线厚度（1px 抗锯齿摊薄为 0.29，故容差 0.8）",
        ),
        "RowDividerInsetDp": (
            px_delta(
                st["card_x_dp"][0],
                (st["row_divider"] or {}).get("left_dp"),
            ),
            TOL_DP,
            "分隔线左缘 − 卡左缘",
        ),
        "RowIconDp": (
            px_delta(st["row_icon_dp"]["left"], st["row_icon_dp"]["right"])
            if st["row_icon_dp"] else None,
            TOL_DP,
            "红色图标 bbox 宽",
        ),
        "RowIconLeftDp": (
            px_delta(st["card_x_dp"][0], st["row_icon_dp"]["left"]) if st["row_icon_dp"] else None,
            TOL_DP,
            "图标左缘 − 卡左缘",
        ),
        "RowTextLeftDp": (
            px_delta(st["card_x_dp"][0], st["row_text_left_dp"]),
            TOL_DP,
            "文字左缘 − 卡左缘",
        ),
        "RowTitleSp": (st["row_title_advance_sp"], TOL_SP, "汉字左缘步进 = 1em"),
        "HeaderButtonDp": (
            px_delta(st["header_button_dp"]["left"], st["header_button_dp"]["right"])
            if st["header_button_dp"] else None,
            TOL_DP,
            "顶栏圆钮直径",
        ),
        "HeaderTopGapDp": (
            round(st["header"]["top"] - 37.1, 1) if st["header"] else None,
            TOL_DP,
            "状态栏底 37.1 → 顶栏顶",
        ),
        "HeaderTitleSp": (st["header_title_advance_sp"], TOL_SP, "汉字左缘步进 = 1em"),
        "SearchBoxHeightDp": (box["height"], TOL_DP, "搜索框高"),
        "SearchBoxLeftDp": (box["left"], TOL_DP, "搜索框左缘"),
        "SearchBoxCornerDp": (box.get("corner_dp"), TOL_DP, "搜索框圆角枚举拟合"),
        "SearchGlyphDp": (
            px_delta(sq["glyph_dp"]["left"], sq["glyph_dp"]["right"]) if sq["glyph_dp"] else None,
            TOL_DP,
            "放大镜 bbox 宽",
        ),
        "SearchHintLeftDp": (px_delta(box["left"], sq["hint_left_dp"]), TOL_DP, "占位文字左缘 − 框左缘"),
        "SearchHintSp": (sq["hint_advance_sp"], TOL_SP, "汉字左缘步进 = 1em"),
        "SearchToCancelDp": (
            px_delta(box["right"], sq["cancel"]["left"]) if sq["cancel"] else None,
            TOL_DP,
            "框右缘 → 「取消」左缘",
        ),
        "CancelSp": (sq["cancel"]["advance_sp"] if sq["cancel"] else None, TOL_SP, "汉字左缘步进"),
        "CancelRightDp": (
            round(screen_w - sq["cancel"]["right"], 1) if sq["cancel"] else None,
            TOL_DP,
            "「取消」右缘 → 屏右缘",
        ),
    }

    rows = []
    for name, (meas, tol, why) in measured.items():
        if name not in c:
            rows.append(
                {
                    "field": name,
                    "derived": None,
                    "measured": meas,
                    "abs_error": None,
                    "tolerance": tol,
                    "reason": why,
                    "status": "SKIP（源码缺该常量）",
                }
            )
            continue
        if meas is None:
            rows.append(
                {
                    "field": name,
                    "derived": c[name],
                    "measured": None,
                    "abs_error": None,
                    "tolerance": tol,
                    "reason": why,
                    "status": "SKIP（基准图未测到）",
                }
            )
            continue
        err = round(abs(c[name] - meas), 1)
        rows.append(
            {
                "field": name,
                "derived": c[name],
                "measured": meas,
                "abs_error": err,
                "tolerance": tol,
                "reason": why,
                "status": "PASS" if err <= tol else "FAIL",
            }
        )
    return rows


def main() -> int:
    if not METRICS.exists():
        print(f"缺少基准量化：{METRICS.relative_to(ROOT)}；先跑 tools/measure_feiniu_baseline.py")
        return 2

    ref = json.loads(METRICS.read_text(encoding="utf-8"))
    consts = parse_constants()
    rows = build_rows(consts, ref)

    checked = [r for r in rows if r["status"] in ("PASS", "FAIL")]
    failed = [r for r in checked if r["status"] == "FAIL"]
    worst = max((r["abs_error"] for r in checked), default=0.0)

    result = {
        "method": "feiniu_baseline_selfcheck",
        "source_metrics": str(METRICS.relative_to(ROOT)).replace("\\", "/"),
        "constants_parsed": len(consts),
        "rows": rows,
        "checked": len(checked),
        "passed": len(checked) - len(failed),
        "worst_abs_error": worst,
        "pass": not failed,
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"{'字段':<22}{'源码':>8}{'实测':>8}{'误差':>7}{'容差':>6}  判定")
    for r in rows:
        d = "" if r["derived"] is None else r["derived"]
        m = "" if r["measured"] is None else r["measured"]
        e = "" if r["abs_error"] is None else r["abs_error"]
        print(f"{r['field']:<22}{d:>8}{m:>8}{e:>7}{r['tolerance']:>6}  {r['status']}")
    print(
        f"\n{result['passed']}/{result['checked']} PASS（最大误差 {worst} dp/sp）→ "
        f"结果：{'PASS' if result['pass'] else 'FAIL'}"
    )
    print(f"证据 -> {OUT.relative_to(ROOT)}")
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
