#!/usr/bin/env python3
"""T-L L3 / 12 §61.42：竞品视觉规格、素材复用与主题一致性的实证 + 零基线守护。

纯 Python 零依赖、只读生产文件，产出 analysis/l3-visual-replica-spec.json。

取证口径（按项目真相源层级）：
  1. `app/src/main/assets/legacy-player/values/legacy_colors.xml`：竞品色值一手来源；
     `layout/legacy_c5y.xml` / `legacy_are.xml`：播放页 / 迷你播放页背景色。
  2. `values/legacy_dimens.xml` + 9 个导出 layout：竞品 dp / sp 规格。
  3. `layout/legacy_gc.xml` / `legacy_bv5.xml`：唱片位图叠层 z 序。
  4. `analysis/l3-reference/legacy-icons-sheet.png` / `legacy-control-zoom.png` /
     `legacy-disc-layers.png`：27 张光栅素材的语义实证。
  5. 本批 4 个 Kotlin 文件：色值 / 尺寸 / 素材路径是否真的从单一真相源取值。

**诚实口径**：`pixel_level_1to1` 恒为 false —— 竞品不存在标准竖屏主播放页 layout
（12 §61.41 实证）、20 个竞品 View 无绘制源码、81 个混淆 attr 语义不可逆、
无线 adb 无法注入点击取竞品播放页真机截图。本工具只证明「按竞品可观测证据
自研实现」，不伪造 1:1 已达成。
"""
from __future__ import annotations

import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "analysis" / "l3-visual-replica-spec.json"

PLAYER = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "ui" / "player"
UI = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "ui"
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "legacy-player"
SETTINGS = ROOT / "settings.gradle.kts"
APP_GRADLE = ROOT / "app" / "build.gradle.kts"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
MAPPING_CSV = ROOT / "docs" / "assets-mapping.csv"

LEGACY_ASSETS = PLAYER / "LegacyAssets.kt"
VINYL = PLAYER / "VinylDiscView.kt"
PAGE = PLAYER / "LegacyPlayerPage.kt"
BRIDGE = PLAYER / "PlayerStateBridge.kt"
APP_UI = UI / "CloudMusicApp.kt"
THEME = UI / "CloudMusicTheme.kt"
MAIN_ACTIVITY = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "MainActivity.kt"

# `legacy_colors.xml` 的色名 → `LegacyPalette` 常量（避免自造颜色产生色差）。
COLOR_MAP: dict[str, str] = {
    "legacy_ew": "PrimaryRed",
    "legacy_ff": "DeepRed",
    "legacy_anm": "AccentRed",
    "legacy_b1e": "TextPrimary",
    "legacy_b1w": "TextSecondary",
    "legacy_b1n": "TextTertiary",
    "legacy_b1q": "TextHint",
    "legacy_b1z": "IconTint",
    "legacy_b1d": "TrackBackground",
    "legacy_b1h": "TrackBuffer",
    "legacy_b1t": "TrackProgressSoft",
    "legacy_dv": "ScrimTop",
    "legacy_dz": "ScrimBottom",
    "legacy_du": "Transparent",
}

# 竞品 layout 的精确度量（dp / sp），逐项有出处。
EXPECTED_METRICS: dict[str, str] = {
    "PagePaddingDp": "10f",       # legacy_dimens.xml legacy_auh
    "ControlSizeDp": "54f",       # legacy_dimens.xml legacy_aui / legacy_gr.xml btnPlay
    "TrackCornerDp": "3f",        # legacy_bn5.xml
    "TrackHeightDp": "8f",        # legacy_h0o.xml
    "MiniBarCornerDp": "18f",     # legacy_bnx.xml
    "MiniCoverDp": "36f",         # legacy_e0g.xml cover
    "MiniCoverImageDp": "22f",    # legacy_e0g.xml coverImage
    "MiniProgressDp": "37f",      # legacy_e0g.xml progressView
    "RedDotDp": "9f",             # legacy_bp1.xml / legacy_e0g.xml redDot
    "BackArrowDp": "26f",         # legacy_coy.xml
    "BackBarHeightDp": "44f",     # legacy_gr.xml ivFinish
    "ControlPrevNextDp": "30f",   # legacy_are.xml miPrev/miNext
    "ControlPlayDp": "36f",       # legacy_are.xml miPlayBtn
    "ControlModeDp": "40f",       # legacy_are.xml miModeBtn
    "ControlLikeDp": "28f",       # legacy_are.xml miLikeBtn
    "TitleSp": "18f",             # legacy_gr.xml tvTitle
    "TimeSp": "12f",              # legacy_gr.xml durationTime/totalTime
    "MarqueeTextSp": "14f",       # legacy_e0g.xml slogan
    "MarqueeMaxWidthDp": "125f",  # legacy_e0g.xml maxWidth
    "MarqueeHeightDp": "38f",     # legacy_e0g.xml
}

# 唱片叠层：`legacy_gc.xml` + `legacy_bv5.xml` 的 addView 先后即 z 序。
DISC_LAYERS = [
    ("discBacking", "DiscBacking"),   # ga6 浅色底盘
    ("albumLabel", "DiscRed"),        # g9f 红色唱片盘（中心）
    ("vinylPlate", "DiscVinyl"),      # ga0 黑胶环
    ("vinylRing", "DiscRing"),        # ga9 外圈
    ("discFrame", "DiscFrame"),       # ga8 带阴影框
]

# `12 §61.40` 的 9 个属性动画参数标记，本批不得改动。
ROTATION_MARKERS = {
    "base_period_20000": "20_000L",
    "linear_interpolator": "LinearInterpolator",
    "infinite_repeat": "ValueAnimator.INFINITE",
    "restart_repeat_mode": "ValueAnimator.RESTART",
    "fraction_360": "animatedFraction * 360f",
    "set_rotation": "discContainer.rotation =",
    "needle_object_animator": "ObjectAnimator.ofFloat",
    "period_formula": "BASE_PERIOD_MS / playSpeed",
}

BANNED_APIS = ("androidx.compose.ui.graphics.Canvas", "animateFloatAsState", "drawBehind")

# 达成像素级 1:1 仍缺的条件（如实挂账，不伪造完成）。
PIXEL_GAPS = [
    "竞品无标准竖屏主播放页 layout tag（12 §61.41：48 个闭包 layout 中 11 个非播放页误命中，"
    "主播放页由 Activity / Presenter 代码动态组合），无可逐像素对照的 XML 骨架",
    "20 个竞品自定义 View 的 onDraw / 状态机无源码（12 §61.39：aapt2 不校验 Java 类，"
    "XML 进 res/ 会 LayoutInflater ClassNotFoundException）",
    "81 个混淆 app:* attr 语义不可逆（layout_constraint* 被混淆成 a5f/a5l/a65/a69，"
    "重声明同名 attr 只会分配新资源 ID，ConstraintLayout 读不到值）",
    "12 §61.42 实证：竞品 com.netease.cloudmusic.activity.PlayerActivity 在 Manifest "
    "exported=false，am start -n 被 SecurityException ... not exported 拒绝；"
    "AppwidgetPlayerRedirectActivity / MiMiniPlayerActivity 直达后前台仍停在自研 app；"
    "无线 adb 受 MIUI INJECT_EVENTS 限制 input tap / keyevent 被拒 → "
    "无法自动取竞品播放页真机截图做逐像素比对（属手工 / USB 在场硬阻塞，不是待办）",
    "离线 Gradle 缓存缺 constraintlayout / appcompat / material / recyclerview，"
    "且 app/src/main/res/ 未建立、:ui-legacy 未 include（12 §61.40 / §61.41 基线）",
]


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def code_lines(text: str) -> list[str]:
    """去掉 KDoc / 行注释，避免把「禁止项名称」的注释文字误判为真实调用。"""
    out: list[str] = []
    for ln in text.splitlines():
        s = ln.strip()
        if not s or s.startswith(("//", "/*", "*", "*/")):
            continue
        if "//" in s:
            s = s.split("//", 1)[0].strip()
        if s:
            out.append(s)
    return out


def count_active_includes(text: str) -> int:
    lines = [ln for ln in text.splitlines() if not ln.lstrip().startswith("//")]
    return sum(1 for ln in lines if re.match(r"^\s*include\(", ln))


def new_dependency_hits(text: str) -> list[str]:
    hits: list[str] = []
    forbidden = ("constraintlayout", "androidx.appcompat", "com.google.android.material", "recyclerview")
    for line in text.splitlines():
        if re.match(r"^\s*implementation\(", line):
            for token in forbidden:
                if token in line:
                    hits.append(f"{token}: {line.strip()}")
    return hits


def mapping_stats() -> tuple[int, int]:
    with MAPPING_CSV.open(encoding="utf-8-sig", newline="") as fh:
        rows = list(csv.DictReader(fh))
    false_count = sum(1 for r in rows if (r.get("replaced") or "").strip().lower() == "false")
    return len(rows), false_count


def parse_colors_xml() -> dict[str, str]:
    text = read(ASSETS / "values" / "legacy_colors.xml")
    return dict(re.findall(r'<color name="(\w+)">([^<]+)</color>', text))


def parse_palette(text: str) -> dict[str, str]:
    # 只大写 hex 数字部分，保留小写 `0x` 前缀，便于与 XML 推出的期望值逐字比较。
    return {
        k: "0x" + v[2:].upper()
        for k, v in re.findall(r"val (\w+) = (0x[0-9A-Fa-f]{8})(?:\.toInt\(\))?", text)
    }


def parse_asset_constants(text: str) -> dict[str, Path]:
    """把 `const val X = "$XX/legacy_y.ext"` 解析成 assets 下的真实相对路径。"""
    prefix = {"ROOT": "legacy-player", "XX": "legacy-player/drawable-xxhdpi"}
    out: dict[str, Path] = {}
    for name, scope, rel in re.findall(r'const val (\w+) = "\$(ROOT|XX)/([^"]+)"', text):
        # `private const val XX = "$ROOT/drawable-xxhdpi"` 是前缀定义，不是素材常量。
        if name in ("ROOT", "XX") or "$" in rel:
            continue
        out[name] = Path(prefix[scope]) / rel
    return out


def main() -> int:
    required = [
        LEGACY_ASSETS, VINYL, PAGE, BRIDGE, APP_UI, THEME, MAIN_ACTIVITY,
        SETTINGS, APP_GRADLE, MANIFEST, MAPPING_CSV,
        ASSETS / "values" / "legacy_colors.xml",
        ASSETS / "layout" / "legacy_c5y.xml",
        ASSETS / "layout" / "legacy_are.xml",
    ]
    missing = [str(p.relative_to(ROOT)) for p in required if not p.is_file()]
    if missing:
        print(f"[FAIL] 必需文件缺失：{missing}")
        return 1

    assets_src = read(LEGACY_ASSETS)
    vinyl = read(VINYL)
    page = read(PAGE)
    app_ui = read(APP_UI)
    theme = read(THEME)
    activity = read(MAIN_ACTIVITY)
    bridge = read(BRIDGE)
    settings = read(SETTINGS)
    gradle = read(APP_GRADLE)
    manifest = read(MANIFEST)

    # ── 1. 色值逐项与竞品 XML 对照 ────────────────────────────────────────
    xml_colors = parse_colors_xml()
    palette = parse_palette(assets_src)
    color_mismatches: list[str] = []
    for xml_name, const_name in COLOR_MAP.items():
        expected = "0x" + xml_colors.get(xml_name, "").lstrip("#").upper()
        actual = palette.get(const_name)
        if actual != expected:
            color_mismatches.append(f"{const_name}: 期望 {expected} / 实际 {actual}")

    c5y = read(ASSETS / "layout" / "legacy_c5y.xml")
    are = read(ASSETS / "layout" / "legacy_are.xml")
    page_bg = re.search(r'android:background="(#[0-9a-fA-F]{8})"', c5y)
    mini_bg = re.search(r'android:background="(#[0-9a-fA-F]{8})"', are)
    if page_bg and "0x" + page_bg.group(1)[1:].upper() != palette.get("PageBackground"):
        color_mismatches.append(f"PageBackground 与 legacy_c5y.xml 不符：{page_bg.group(1)}")
    if mini_bg and "0x" + mini_bg.group(1)[1:].upper() != palette.get("MiniPageBackground"):
        color_mismatches.append(f"MiniPageBackground 与 legacy_are.xml 不符：{mini_bg.group(1)}")
    if palette.get("BrandRed") != "0xFFA22016":
        color_mismatches.append("BrandRed 与真机启动屏六点采样 #A22016 不符")

    # ── 2. 尺寸常量逐项 ───────────────────────────────────────────────────
    metric_mismatches = [
        f"{k}={v}" for k, v in EXPECTED_METRICS.items()
        if f"{k} = {v}" not in assets_src
    ]

    # ── 3. 素材常量全部指向真实文件 ───────────────────────────────────────
    asset_paths = parse_asset_constants(assets_src)
    missing_assets = sorted(
        name for name, rel in asset_paths.items()
        if not (ROOT / "app" / "src" / "main" / "assets" / rel).is_file()
    )

    # ── 4. 唱片叠层 z 序与真实位图 ────────────────────────────────────────
    init_block = vinyl.split("init {", 1)[1].split("override fun onSizeChanged", 1)[0]
    layer_positions = [init_block.find(f"addView({var_name},") for var_name, _ in DISC_LAYERS]
    disc_order_ok = all(p >= 0 for p in layer_positions) and layer_positions == sorted(layer_positions)
    disc_bitmaps_ok = all(
        f"private val {var_name} = ImageView" in vinyl
        and f"loadLegacyBitmap(context, LegacyAsset.{const_name})" in vinyl
        for var_name, const_name in DISC_LAYERS
    )

    # ── 5. 播放页 / 迷你条 / 主题 ─────────────────────────────────────────
    hardcoded_color_hits = re.findall(r"Color\(0x[0-9A-Fa-f]{6,8}\)", page)
    page_icons = ["BackArrow", "Prev", "Play", "Next", "ModeLoop", "Favorite"]
    page_ok = (
        "Color(LegacyPalette.PageBackground)" in page
        and "SliderDefaults.colors(" in page
        and "LegacyPalette.PrimaryRed" in page
        and "detectHorizontalDragGestures" in page
        and "AndroidView(" in page
        and all(f"LegacyAsset.{x}" in page for x in page_icons)
        and not hardcoded_color_hits
    )
    mini_tokens = [
        "MiniPageBackground", "MiniBarCornerDp", "MiniCoverDp", "MiniProgressDp",
        "MarqueeTextSp", "MarqueeMaxWidthDp", "RedDotDp", "NoteGlyph",
        "ObjectAnimator.ofFloat", "View.ROTATION", "AndroidView(",
    ]
    mini_ok = all(t in app_ui for t in mini_tokens)
    theme_ok = (
        "darkColorScheme(" in theme
        and "isSystemInDarkTheme" not in theme
        and "LegacyPalette.PageBackground" in theme
        and "LegacyPalette.PrimaryRed" in theme
    )
    launch_ok = bool(re.search(r"var showPlayer by rememberSaveable \{ mutableStateOf\(true\) \}", app_ui))

    # ── 6. 禁止 API / 动画参数 / 单通道 ───────────────────────────────────
    scanned = "\n".join(code_lines(vinyl) + code_lines(page) + code_lines(app_ui))
    banned_hits = [b for b in BANNED_APIS if b in scanned]
    rotation_ok = all(marker in vinyl for marker in ROTATION_MARKERS.values())
    bridge_forbidden = [
        ln.strip() for ln in bridge.splitlines()
        if ln.startswith("import ") and ("PlayerController" in ln or "IMusicEngine" in ln)
    ]
    bridge_ok = (
        not bridge_forbidden
        and "class PlayerStateBridge" in bridge
        and "PlayerStateBridge(runtime, tracks, queue)" in activity
        and "LegacyPlayerPage" in app_ui
    )

    # ── 7. 零发布基线 ─────────────────────────────────────────────────────
    staged = [p for p in ASSETS.rglob("*") if p.is_file()] if ASSETS.is_dir() else []
    rows, false_rows = mapping_stats()
    baseline = {
        "module_count": count_active_includes(settings),
        "permission_count": len(re.findall(r"<uses-permission\b", manifest)),
        "staged_assets_files": len(staged),
        "mapping_rows": rows,
        "mapping_replaced_false": false_rows,
        "new_dependencies": new_dependency_hits(gradle),
        "res_dir_exists": (ROOT / "app" / "src" / "main" / "res").is_dir(),
        "ui_legacy_included": bool(re.search(r'^\s*include\(":ui-legacy"\)', settings, re.M)),
    }
    baseline_ok = (
        baseline["module_count"] == 19
        and baseline["permission_count"] == 11
        and baseline["staged_assets_files"] == 51
        and baseline["mapping_rows"] == 61
        and baseline["mapping_replaced_false"] == 61
        and not baseline["new_dependencies"]
        and not baseline["res_dir_exists"]
        and not baseline["ui_legacy_included"]
    )

    checks = {
        "visual_spec_source_present": all(
            s in assets_src for s in (
                "object LegacyPalette", "object LegacyMetrics", "object LegacyAsset",
                "loadLegacyBitmap", "loadLegacyTypeface", "rememberLegacyImageBitmap",
            )
        ),
        "palette_matches_competitor_xml": not color_mismatches,
        "metrics_match_competitor_layout": not metric_mismatches,
        "asset_constants_all_exist": not missing_assets and len(asset_paths) >= 18,
        "disc_layer_order_from_layout_xml": disc_order_ok and disc_bitmaps_ok,
        "page_uses_competitor_assets_and_colors": page_ok,
        "mini_bar_matches_e0g_spec": mini_ok,
        "theme_fixed_dark_competitor": theme_ok,
        "player_is_launch_screen_no_tab_bar": launch_ok,
        "no_banned_animation_api": not banned_hits,
        "rotation_parameters_intact": rotation_ok,
        "single_channel_bridge_intact": bridge_ok,
        "baseline_unchanged": baseline_ok,
    }

    spec = {
        "method": "12 §61.42：按竞品反编译色值 / 尺寸 / 位图 / layout 层级逐项核对自研 UI，"
                  "并守护 12 §61.40 动画参数与零发布基线",
        "generated_by": "tools/l3_visual_replica_spec.py",
        "sources": [
            "app/src/main/assets/legacy-player/values/legacy_colors.xml",
            "app/src/main/assets/legacy-player/values/legacy_dimens.xml",
            "app/src/main/assets/legacy-player/layout/legacy_c5y.xml / legacy_are.xml / "
            "legacy_e0g.xml / legacy_gr.xml / legacy_gc.xml / legacy_bv5.xml",
            "app/src/main/assets/legacy-player/drawable/legacy_bn5.xml / legacy_bnx.xml / legacy_bp1.xml",
            "analysis/l3-reference/legacy-icons-sheet.png / legacy-control-zoom.png / legacy-disc-layers.png",
            "app/src/main/kotlin/com/example/cloudmusic/ui/**.kt",
        ],
        "competitor_palette": {k: {"xml": xml_colors.get(k), "constant": v, "value": palette.get(v)}
                               for k, v in COLOR_MAP.items()},
        "competitor_metrics": EXPECTED_METRICS,
        "asset_constants": {k: str(v) for k, v in sorted(asset_paths.items())},
        "disc_layer_order": [{"view": v, "asset": a} for v, a in DISC_LAYERS],
        "rotation_markers": {k: (v in vinyl) for k, v in ROTATION_MARKERS.items()},
        "baseline": baseline,
        "checks": checks,
        "mismatches": {
            "palette": color_mismatches,
            "metrics": metric_mismatches,
            "missing_assets": missing_assets,
            "hardcoded_colors_in_page": hardcoded_color_hits,
            "banned_apis": banned_hits,
        },
        "verdict": {
            "all_checks_pass": all(checks.values()),
            # 诚实口径：本批是「按竞品证据自研」，不是逐像素 1:1。
            "pixel_level_1to1": False,
            "one_to_one_conclusion": "未达成 1:1 像素级复刻。本批达成的是「色值 / 尺寸 / 位图 / "
                                    "控件顺序全部取自竞品一手证据的自研实现」，"
                                    "不含竞品自定义 View 的绘制代码与 XML 骨架。",
            "pixel_level_gaps": PIXEL_GAPS,
            "conditions_for_1to1": [
                "用户手动把竞品切到播放页后由本助手 adb screencap 取真机截图，作为逐像素比对基准",
                "或按 12 §61.41 三项授权补导其余 39 个 layout + 4 类依赖 + :ui-legacy 舱体",
                "无论哪条路径，20 个竞品 View 的 onDraw / 状态机仍需按截图自研重绘（不搬 XML）",
            ],
        },
        "conclusion": (
            "12 §61.42：用户把口径升级为「1:1 像素级复刻」并指出 V0.1 五页 tab 导航栏不是竞品组件。"
            "本批把竞品 27 张真实位图（含 g_o/g_k/g_a/gc6/gca 控制图标、ga6/g9f/ga0/ga9/ga8 唱片叠层）"
            "与竞品色值 / dp 规格接入播放页与迷你条，主题固定为竞品深色、启动即全屏播放页（无 tab bar）。"
            "核心播放逻辑（PlaybackRuntime / native 引擎 / PlayerStateBridge 单通道）零改动；"
            "19 模块 / 11 权限 / 51 素材 / 61 行 csv 全 false / 零新增依赖 / res 未建 / :ui-legacy 未 include 零变化。"
            "像素级 1:1 仍受竞品无主播放页 layout、20 个 View 无绘制源码、81 个混淆 attr 不可逆、"
            "无线 adb 无法注入点击取竞品截图四项约束，如实挂账不伪造。"
        ),
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(spec, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    for key, value in checks.items():
        print(f"[{'PASS' if value else 'FAIL'}] {key}")
    print(f"[INFO] 色值对照 {len(COLOR_MAP)} 项 / 尺寸 {len(EXPECTED_METRICS)} 项 / "
          f"素材常量 {len(asset_paths)} 项 / 唱片叠层 {len(DISC_LAYERS)} 层")
    print(f"[INFO] pixel_level_1to1 = False（如实挂账，缺口 {len(PIXEL_GAPS)} 项）")
    print(f"[INFO] 证据 -> {OUT.relative_to(ROOT)}")
    print("结果：" + ("PASS" if all(checks.values()) else "FAIL"))
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
