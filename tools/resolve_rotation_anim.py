#!/usr/bin/env python3
"""唱片旋转 / 唱针动画的静态收口（docs/14 §4.1 挂账 / 03-10 §3.3 / 12 §39）。

上一轮（12 §38）解析了 `AnimationUtils.loadAnimation` 链路，得到 2 个 scale 动画，
并推测唱片旋转走属性动画。本轮用 jadx 定向反编译的 3 个类**证实**该推测，
并给出精确视觉参数：

  - `RotationRelativeLayout.init()`：ValueAnimator.ofFloat(0,1) + LinearInterpolator
    + setRepeatCount(-1) + setDuration(yh0.m.b())
  - `AnimationHolder.onAnimationUpdate()`：getAnimatedFraction() * 360f → View.setRotation
  - `PlayerVinylViewHolder`：ObjectAnimator 做唱针起落（discHeadUp/DownRotateAnimation）
  - `yh0.m.b()` = 20000.0f / playSpeed；`c(f)` = 20000.0f / f

结论：**唱片旋转与唱针全部是纯代码属性动画，不加载任何 res/anim XML**。
因此 `14 §4.1` 的「40 个无限循环 rotate 候选中挑一个」是伪命题，
播放页资源闭包无需 anim 项（与 XML 闭包观察一致），⑦ 导出不再被 anim 阻塞。

输入均为仓库内 jadx 产物：`analysis/jadx-player-views/*.java`。
产出 `analysis/rotation-anim-resolution.json`；**不改 assets-mapping.csv**（C4 空表语义）。

用法：
    python tools/resolve_rotation_anim.py
"""
from __future__ import annotations

import glob
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VIEWS = ROOT / "analysis" / "jadx-player-views"
RES = ROOT / "analysis" / "decoded" / "res"
OUT = ROOT / "analysis" / "rotation-anim-resolution.json"

EVIDENCE_PATTERNS = (
    ("ValueAnimator.ofFloat(0.0f, 1.0f)", r"ValueAnimator\.ofFloat\(0\.0f,\s*1\.0f\)"),
    ("setDuration(yh0.m.b())", r"setDuration\(yh0\.m\.b\(\)\)"),
    ("LinearInterpolator", r"new LinearInterpolator\(\)"),
    ("setRepeatCount(-1) 无限循环", r"setRepeatCount\(-1\)"),
    ("setRepeatMode(1) RESTART", r"setRepeatMode\(1\)"),
    ("getAnimatedFraction() * 360.0f", r"getAnimatedFraction\(\)\s*\*\s*360\.0f"),
    ("View.setRotation", r"\.setRotation\("),
    ("唱针 ObjectAnimator 字段", r"ObjectAnimator\s+discHead(?:Up|Down)RotateAnimation"),
    ("旋转容器字段", r"RotationRelativeLayout\s+disc\b"),
    ("yh0.m.b() 周期公式 20000/speed", r"20000\.0f\s*/\s*.*getPlaySpeed"),
    ("yh0.m.c(f) 速度公式 20000/f", r"20000\.0f\s*/\s*f\b"),
)
DURATION_RE = re.compile(r"20000\.0f")


def collect_evidence() -> list[dict]:
    rows = []
    for label, pattern in EVIDENCE_PATTERNS:
        hits = []
        for path in sorted(VIEWS.glob("*.java")):
            for no, line in enumerate(
                path.read_text(encoding="utf-8", errors="replace").splitlines(), 1
            ):
                if re.search(pattern, line):
                    hits.append({
                        "file": path.relative_to(ROOT).as_posix(),
                        "line": no,
                        "snippet": line.strip()[:160],
                    })
        rows.append({"claim": label, "hits": hits[:4], "hitCount": len(hits)})
    return rows


def duration_baseline() -> int | None:
    p = VIEWS / "yh0_m.java"
    if not p.is_file():
        return None
    vals = [int(v) for v in re.findall(r"(\d+)\.0f", p.read_text(encoding="utf-8"))]
    return vals[0] if vals else None


def rotate_candidates() -> dict:
    files, durations = [], set()
    for p in glob.glob(str(RES / "anim*" / "*.xml")):
        t = Path(p).read_text(encoding="utf-8", errors="replace")
        if "<rotate" in t and 'repeatCount="infinite"' in t:
            files.append(Path(p).name)
            durations.update(re.findall(r'android:duration="(\d+)"', t))
    return {
        "count": len(files),
        "durations": sorted(durations, key=int),
        "has12000": "12000" in durations,
        "has20000": "20000" in durations,
    }


def main() -> int:
    evidence = collect_evidence()
    missing = [e["claim"] for e in evidence if e["hitCount"] == 0]
    baseline = duration_baseline()
    rot = rotate_candidates()
    pure_code = not missing and baseline == 20000

    report = {
        "source": "analysis/jadx-player-views/*.java（jadx 1.5.1 定向反编译，已剥离 @Metadata）",
        "scope": "14 §4.1 挂账收口：唱片旋转 / 唱针动画的实现方式与视觉参数",
        "rotationIsPureCode": pure_code,
        "missingEvidence": missing,
        "visualParams": {
            "angleDegrees": 360,
            "interpolator": "LinearInterpolator",
            "repeatCount": -1,
            "repeatMode": 1,
            "baselineDurationMs": baseline,
            "durationFormula": "20000 / playSpeed（yh0.m.b()；c(f)=20000/f）",
            "fromAnimXml": False,
        },
        "needleAnimation": "ObjectAnimator：discHeadUpRotateAnimation / discHeadDownRotateAnimation",
        "evidence": evidence,
        "rotateCandidates": rot,
        "conclusion": (
            "唱片旋转与唱针起落均为纯代码属性动画（ValueAnimator/ObjectAnimator + "
            "View.setRotation），不加载任何 res/anim XML；基准周期 20000ms ÷ 播放速度，"
            "0→360° 线性无限循环。14 §4.1 的「40 个 rotate 候选中挑一个」是伪命题："
            "播放页资源闭包无需 anim 项，⑦ 素材导出不再被 anim 资源阻塞。"
        ),
        "l3Impact": "14 §4.1 / §6 #1 挂账收口；03-10 §3.3 的 12s 示例值作废（实测基准 20s 且随速度变化）",
        "coincidenceNote": (
            "40 个 rotate 候选中恰有 duration=20000 的项，但**不能据此认作唱片旋转**："
            "实证已证明旋转由 ValueAnimator 在代码中计算角度，不加载任何 anim XML；"
            "duration 数值相同只是巧合（14 §4.1 早已记录 20 个成对出现的 duration 值）。"
        ),
        "assetMappingChanged": False,
        "c4Note": "docs/assets-mapping.csv 保持空表（06 §4 / C4 语义不变）",
    }
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")

    print(f"纯代码属性动画 = {pure_code} | 基准周期 = {baseline} ms | "
          f"缺失证据 = {len(missing)}")
    for e in evidence:
        print(f"  [{'OK' if e['hitCount'] else 'MISS'}] {e['claim']} ({e['hitCount']} 处)")
    print(f"rotate 候选 {rot['count']} 个 | 含 12000 = {rot['has12000']} | "
          f"含 20000 = {rot['has20000']}")
    print(f"结论：{report['conclusion']}")
    print(f"\n已写出 {OUT.relative_to(ROOT).as_posix()}")
    return 0 if pure_code else 1


if __name__ == "__main__":
    raise SystemExit(main())
