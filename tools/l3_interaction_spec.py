#!/usr/bin/env python3
"""T-L L3 / 12 §61.40：播放页「UI 交互严格复刻竞品」的交互规格实证。

纯 Python 零依赖、只读生产文件，产出 analysis/l3-interaction-spec.json。

取证口径（按项目真相源层级）：
  1. `docs/14 §4 / §4.1`：20 个竞品自定义 View 的语义分类；唱片旋转 / 唱针是
     纯代码属性动画，参数 `20000.0f / getPlaySpeed(...)`、LinearInterpolator、
     `getAnimatedFraction() * 360.0f` → `View.setRotation`，不加载 res/anim XML。
  2. `03-10 §3.3 / §6 / §11`：视觉度量照搬、PlayerStateBridge 唯一通道、
     禁止 Compose Canvas 重写唱片动画 / 禁止 animateFloatAsState 近似参数。
  3. `analysis/legacy-layout-audit.json`：20 个竞品类名 / 9 个 layout 语义。
  4. 本批新增的 3 个 Kotlin 文件：逐项核对动画参数与接线是否在位。

同时核对「零发布基线变更」：19 模块 / 11 权限 / 51 素材 / 61 行 csv /
无新增依赖 / app/src/main/res/ 未建立 / :ui-legacy 未 include。
"""

from __future__ import annotations

import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "analysis" / "l3-interaction-spec.json"

PLAYER_DIR = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "ui" / "player"
VINYL = PLAYER_DIR / "VinylDiscView.kt"
BRIDGE = PLAYER_DIR / "PlayerStateBridge.kt"
PAGE = PLAYER_DIR / "LegacyPlayerPage.kt"
APP_UI = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "ui" / "CloudMusicApp.kt"
MAIN_ACTIVITY = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "MainActivity.kt"
SETTINGS = ROOT / "settings.gradle.kts"
APP_GRADLE = ROOT / "app" / "build.gradle.kts"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
ASSETS_DIR = ROOT / "app" / "src" / "main" / "assets" / "legacy-player"
MAPPING_CSV = ROOT / "docs" / "assets-mapping.csv"
AUDIT_JSON = ROOT / "analysis" / "legacy-layout-audit.json"

# docs/14 §4 的 20 个竞品自定义 View 语义分类。
NETEASE_VIEW_GROUPS: dict[str, list[str]] = {
    "唱片 / 旋转": [
        "com.netease.cloudmusic.ui.PlayerDiscViewFlipper",
        "com.netease.cloudmusic.ui.RotationRelativeLayout",
        "com.netease.cloudmusic.ui.AnimableDrawerImageView",
        "com.netease.cloudmusic.ui.GestureImageSwitcher",
        "com.netease.cloudmusic.ui.PlayerStarAnimImageView",
    ],
    "横屏黑胶": [
        "com.netease.cloudmusic.land.vinyl.RotationVinylView",
        "com.netease.cloudmusic.land.vinyl.CircleTouchView",
        "com.netease.cloudmusic.land.vinyl.TouchInterceptorRecyclerView",
        "com.netease.cloudmusic.land.plugin.PlayerLandscapeViewGroup",
    ],
    "迷你播放条": [
        "com.netease.cloudmusic.module.miminiplayer.MiAnimViewRender",
        "com.netease.cloudmusic.module.miminiplayer.MiLyricViewContainer",
    ],
    "跑马灯 / 旋转容器": [
        "com.netease.play.ui.MarqueeTextView",
        "com.netease.play.ui.MarqueTextView",
        "com.netease.play.ui.RotateFrameLayout",
        "com.netease.play.livepage.music2.CircleProgressView",
    ],
    "进度 / 图片": [
        "com.netease.cloudmusic.ui.PlayerSeekBarNew",
        "com.netease.cloudmusic.ui.PlayerLandScapePageImageView",
        "com.netease.cloudmusic.ui.PlayerLandScapePageMarqueeTextView",
        "com.netease.cloudmusic.ui.NeteaseMusicSimpleDraweeView",
        "com.netease.cloudmusic.ui.CommonSimpleDraweeView",
    ],
}

# 12 §39 / docs/14 §4.1 / 03-10 §3.3 反编译实证参数。
ANIMATION_PARAMETERS = {
    "base_period_ms": 20000,
    "period_formula": "20000.0f / getPlaySpeed(...)",
    "interpolator": "LinearInterpolator",
    "value_animator": "ValueAnimator.ofFloat(0.0f, 1.0f)",
    "repeat_count": -1,
    "repeat_mode": 1,
    "angle_expression": "getAnimatedFraction() * 360.0f",
    "rotation_target": "View.setRotation",
    "needle": "PlayerVinylViewHolder 的 discHeadUpRotateAnimation / discHeadDownRotateAnimation（两个 ObjectAnimator）",
    "anim_xml_loaded": False,
    "source": "12 §39 / docs/14 §4.1 / analysis/rotation-anim-resolution.json",
}

REPLICATED_INTERACTIONS = [
    {"interaction": "唱片旋转（0→360° 线性无限循环，20s÷倍速，暂停冻结/恢复续转）",
     "competitor_view": "RotationRelativeLayout / PlayerDiscViewFlipper / RotationVinylView",
     "implementation": "VinylDiscView.startRotation() / pauseRotation()"},
    {"interaction": "唱针起落（播放落下、暂停抬起）",
     "competitor_view": "PlayerVinylViewHolder discHeadUp/DownRotateAnimation",
     "implementation": "VinylDiscView.animateNeedle()"},
    {"interaction": "标题跑马灯（单行超长滚动、无限循环）",
     "competitor_view": "MarqueeTextView / MarqueTextView / PlayerLandScapePageMarqueeTextView",
     "implementation": "LegacyPlayerPage 的 TextView(ellipsize=MARQUEE, marqueeRepeatLimit=-1)"},
    {"interaction": "横向滑动切歌（上一首 / 下一首）",
     "competitor_view": "GestureImageSwitcher / CircleTouchView / PlayerDiscViewFlipper",
     "implementation": "LegacyPlayerPage 的 detectHorizontalDragGestures 覆盖层（72dp 阈值）"},
    {"interaction": "进度条拖动 seek",
     "competitor_view": "PlayerSeekBarNew / CircleProgressView",
     "implementation": "LegacyPlayerPage 的 Slider → PlayerStateBridge.onSeek → PlaybackRuntime.seekTo"},
    {"interaction": "播放 / 暂停 / 上一首 / 下一首",
     "competitor_view": "播放页控制按钮",
     "implementation": "PlayerStateBridge.onPlayPause / onNext / onPrev"},
    {"interaction": "迷你播放条点击展开整页、整页返回",
     "competitor_view": "fragment_mi_miniplayer → PlayerActivity",
     "implementation": "CloudMusicApp.showPlayer / LegacyPlayerPage.onClose"},
]

# 03-10 §6 契约里定义、但当前生产侧无真实后端的交互 —— 不伪造完成。
UNWIRED_INTERACTIONS = [
    {"name": "onModeToggle()", "reason": "PlayerController 只有 setQueue(items, mode, startAt)，切换会 sync.push 全量下发 native 队列；不擅自改变真机运行时行为", "ui_form": "模式图标只读展示"},
    {"name": "onFavorite()", "reason": "Track 无收藏字段、无收藏 Repository", "ui_form": "♡ 只读占位"},
    {"name": "歌词（MiLyricViewContainer）", "reason": "生产侧无歌词数据源", "ui_form": "「· 暂无歌词 ·」占位"},
    {"name": "onOpenOutput()", "reason": "OutputRouter.setActive 生产调用方为 0（12 §48），不伪造投屏状态", "ui_form": "不显示切换入口"},
    {"name": "onOpenQueue()", "reason": "03-10 §5 队列弹窗属 V0.2 完善项", "ui_form": "队列数量只读展示"},
    {"name": "封面图（DraweeView 系列）", "reason": "未引 coil-compose（12 §33 V0.1 口径）；生产侧仍无真实封面数据源", "ui_form": "竞品真实位图 ga6/g9f/ga0/ga9/ga8 叠层作默认占位（12 §61.42）"},
]

# 9 个已导出 layout 只是播放页变体/子组件；完整严格像素级照搬仍未做 —— 如实挂账。
NOT_REPLICATED_SCOPES = [
    "48 个播放页相关 layout 中的其余 39 个（含标准竖屏主播放页、横屏黑胶页、行车电台页、歌词页、播放列表 sheet 等）未补导",
    "20 个竞品 View 的像素级视觉（混淆 layout XML 未搬进生产 res/）",
    "横屏黑胶页 / 行车电台页 / 音频播放页变体的专属交互",
    "aapt2 四步实证（12 §61.39）仍只在仓库外临时工程，未接生产 res/",
]


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def count_active_includes(text: str) -> int:
    """只数非注释行里的 include( ... )，避免把注释里的 :ui-legacy 计入。"""
    lines = [ln for ln in text.splitlines() if not ln.lstrip().startswith("//")]
    return sum(1 for ln in lines if re.match(r"^\s*include\(", ln))


def count_permissions(text: str) -> int:
    return len(re.findall(r"<uses-permission\b", text))


def new_dependency_hits(text: str) -> list[str]:
    """只检查 implementation(...) 行，避免误伤 androidx.compose.material3。"""
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


def main() -> int:
    missing = [p for p in (
        VINYL, BRIDGE, PAGE, APP_UI, MAIN_ACTIVITY, SETTINGS, APP_GRADLE,
        MANIFEST, MAPPING_CSV, AUDIT_JSON,
    ) if not p.is_file()]
    if missing:
        print(f"[FAIL] 必需文件缺失：{[str(p.relative_to(ROOT)) for p in missing]}")
        return 1

    vinyl = read(VINYL)
    bridge = read(BRIDGE)
    page = read(PAGE)
    app_ui = read(APP_UI)
    activity = read(MAIN_ACTIVITY)
    settings = read(SETTINGS)
    gradle = read(APP_GRADLE)
    manifest = read(MANIFEST)

    rotation_markers = {
        "base_period_20000": "20_000L" in vinyl,
        "linear_interpolator": "LinearInterpolator" in vinyl,
        "infinite_repeat": "ValueAnimator.INFINITE" in vinyl,
        "restart_repeat_mode": "ValueAnimator.RESTART" in vinyl,
        "fraction_360": "animatedFraction * 360f" in vinyl,
        "set_rotation": "discContainer.rotation =" in vinyl,
        "needle_object_animator": "ObjectAnimator.ofFloat" in vinyl,
        "period_formula": "BASE_PERIOD_MS / playSpeed" in vinyl,
        "no_anim_xml": "res/anim" not in vinyl or "不加载" in vinyl,
    }

    banned = ("androidx.compose.ui.graphics.Canvas", "animateFloatAsState", "drawBehind")
    scanned_code = "\n".join(code_lines(vinyl) + code_lines(page))
    banned_hits = [b for b in banned if b in scanned_code]

    bridge_forbidden_imports = [
        ln.strip() for ln in bridge.splitlines()
        if ln.startswith("import ") and ("PlayerController" in ln or "IMusicEngine" in ln)
    ]

    audit = json.loads(AUDIT_JSON.read_text(encoding="utf-8"))
    netease_classes = sorted(audit["netease_classes_unique"])
    mapping_rows, mapping_false = mapping_stats()
    staged_files = sorted(p for p in ASSETS_DIR.rglob("*") if p.is_file()) if ASSETS_DIR.is_dir() else []

    baseline = {
        "module_count": count_active_includes(settings),
        "permission_count": count_permissions(manifest),
        "staged_assets_files": len(staged_files),
        "mapping_rows": mapping_rows,
        "mapping_replaced_false": mapping_false,
        "new_dependencies": new_dependency_hits(gradle),
        "res_dir_exists": (ROOT / "app" / "src" / "main" / "res").is_dir(),
        "ui_legacy_included": bool(re.search(r'^\s*include\(":ui-legacy"\)', settings, re.M)),
    }

    checks = {
        "player_files_present": True,
        "rotation_parameters_exact": all(rotation_markers.values()),
        "no_compose_canvas_or_approximation": not banned_hits,
        "bridge_no_engine_import": not bridge_forbidden_imports,
        "entry_wired": "LegacyPlayerPage" in app_ui and "PlayerStateBridge" in activity,
        "single_channel_bridge": "class PlayerStateBridge" in bridge and "PlayerStateBridge(runtime, tracks, queue)" in activity,
        "netease_class_count_20": len(netease_classes) == 20
            and sum(len(v) for v in NETEASE_VIEW_GROUPS.values()) == 20,
        "baseline_19_modules": baseline["module_count"] == 19,
        "baseline_11_permissions": baseline["permission_count"] == 11,
        "baseline_51_assets": baseline["staged_assets_files"] == 51,
        "baseline_61_rows_false": baseline["mapping_rows"] == 61
            and baseline["mapping_replaced_false"] == 61,
        "no_new_dependencies": not baseline["new_dependencies"],
        "res_dir_not_created": not baseline["res_dir_exists"],
        "ui_legacy_not_included": not baseline["ui_legacy_included"],
    }

    spec = {
        "method": "12 §61.40：按 12 §39 / docs/14 §4.1 / 03-10 §3.3 §6 的实证参数核对播放页交互复刻，并守护零发布基线变更",
        "generated_by": "tools/l3_interaction_spec.py",
        "sources": [
            "docs/14-播放页资源反查.md §4 / §4.1",
            "docs/03-模块开发文档/03-10 UI 组件与主题.md §3.3 / §6 / §11",
            "docs/12-编码阶段第一批交付.md §39 / §61.38 / §61.39",
            "analysis/legacy-layout-audit.json",
            "app/src/main/kotlin/com/example/cloudmusic/ui/player/*.kt",
        ],
        "animation_parameters": ANIMATION_PARAMETERS,
        "rotation_markers": rotation_markers,
        "netease_view_groups": NETEASE_VIEW_GROUPS,
        "netease_classes_from_audit": netease_classes,
        "replicated_interactions": REPLICATED_INTERACTIONS,
        "unwired_interactions": UNWIRED_INTERACTIONS,
        "not_replicated_scopes": NOT_REPLICATED_SCOPES,
        "baseline": baseline,
        "checks": checks,
        "verdict": {
            "all_checks_pass": all(checks.values()),
            "banned_api_hits": banned_hits,
            "bridge_forbidden_imports": bridge_forbidden_imports,
        },
        "conclusion": (
            "严格复刻的是「UI 交互」而非「像素级 XML 照搬」：交互行为参数逐项来自竞品反编译实证，"
            "用平台属性动画 + 自研 View 落地，零新增模块 / 依赖 / 权限、不动 assets 与 Manifest；"
            "契约 §6 中无生产后端的 6 项交互只读占位、不伪造；"
            "48 个播放页 layout 的完整像素级照搬与 :ui-legacy 舱体仍未做，需另行拍板（12 §61.40）。"
        ),
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(spec, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    for key, value in checks.items():
        print(f"[{'PASS' if value else 'FAIL'}] {key}")
    print(f"[INFO] 20 个竞品 View / {len(REPLICATED_INTERACTIONS)} 项已复刻交互 / "
          f"{len(UNWIRED_INTERACTIONS)} 项只读占位 / {len(NOT_REPLICATED_SCOPES)} 项范围挂账")
    print(f"[INFO] 证据 -> {OUT.relative_to(ROOT)}")
    print("结果：" + ("PASS" if all(checks.values()) else "FAIL"))
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
