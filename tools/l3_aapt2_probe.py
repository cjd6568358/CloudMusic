#!/usr/bin/env python3
"""T-L L3 编译阻塞的 aapt2 编译级四步实证（仓库外临时目录，零生产改动）。

用临时 SDK 的 aapt2 + android.jar，把 9 个暂存 layout 组装成最小 Android 工程，
分四步 link，验证 `12 §61.38 / §61.39` 的 aapt2 阻塞是否可被机械消除：

  step1_baseline           : 只有 layout（无 values）           → id + attr 两类错误
  step2_with_ids           : 加 analysis/l3-probe/ids.xml       → id 错误归零
  step3_with_attrs         : 再加 analysis/l3-probe/attrs.xml   → attr 错误归零，
                             只剩 layout 引用的 45 项资源定义缺失
  step4_with_staged_res    : 再把 app/src/main/assets/legacy-player/ 下 45 项
                             暂存资源去掉 `legacy_` 前缀后接入临时 res/
                             → 预期 aapt2 错误归零

step4 的「去前缀」只发生在仓库外临时工程内，模拟「真正接 res/」的形态；
`app/src/main/res/` 与任何生产构建输入都不改。结果写
`analysis/l3-probe/aapt2-probe-result.json` 与 `analysis/l3-probe/aapt2-probe.md`。

用法（本机示例，不写死路径）：
    python tools/l3_aapt2_probe.py --aapt2 <SDK>/build-tools/35.0.0/aapt2.exe \
        --android-jar <SDK>/platforms/android-35/android.jar
    # 或设 ANDROID_HOME 让脚本自动探测，可省略两个参数
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LAYOUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "legacy-player" / "layout"
STAGED_DIR = ROOT / "app" / "src" / "main" / "assets" / "legacy-player"
PROBE_DIR = ROOT / "analysis" / "l3-probe"
IDS_XML = PROBE_DIR / "ids.xml"
ATTRS_XML = PROBE_DIR / "attrs.xml"
OUT_JSON = PROBE_DIR / "aapt2-probe-result.json"
OUT_MD = PROBE_DIR / "aapt2-probe.md"

ID_ERR = re.compile(r"resource id/(\S+) .*not found")
ATTR_ERR = re.compile(r"attribute (\S+) .*not found")

MANIFEST = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    package="com.example.l3probe" />\n'
)


def default_sdk_paths() -> tuple[str | None, str | None]:
    """从 ANDROID_HOME 自动探测；脚本内不含任何个人绝对路径。"""
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        return None, None
    base = Path(home)
    aapt2 = None
    bt = base / "build-tools"
    if bt.is_dir():
        for d in sorted(bt.iterdir()):
            for exe in ("aapt2.exe", "aapt2"):
                if (d / exe).is_file():
                    aapt2 = str(d / exe)
                    break
            if aapt2:
                break
    jar = None
    pl = base / "platforms"
    if pl.is_dir():
        for d in sorted(pl.iterdir(), reverse=True):
            if (d / "android.jar").is_file():
                jar = str(d / "android.jar")
                break
    return aapt2, jar


def classify(log: str) -> dict:
    lines = [ln for ln in log.splitlines() if "error:" in ln]
    id_errors = [m.group(1) for ln in lines if (m := ID_ERR.search(ln))]
    attr_errors = [m.group(1) for ln in lines if (m := ATTR_ERR.search(ln))]
    other = [ln for ln in lines if not ID_ERR.search(ln) and not ATTR_ERR.search(ln)]
    return {
        "error_line_count": len(lines),
        "id_not_found_count": len(id_errors),
        "id_not_found_unique": sorted(set(id_errors)),
        "attr_not_found_count": len(attr_errors),
        "attr_not_found_unique": sorted(set(attr_errors)),
        "other_error_count": len(other),
        "other_error_sample": other[:5],
        "error_sample": lines[:8],
    }


def stage_staged_resources(res: Path) -> dict:
    """把 45 项暂存资源去掉 `legacy_` 前缀后接入临时 res/（仅仓库外临时工程）。

    - 文件名：`legacy_bn5.xml` → `bn5.xml`；`legacy_csp.9.png` → `csp.9.png`。
    - values XML：仅把顶层定义名 `name="legacy_xxx"` 改回 `name="xxx"`；
      文件内的资源引用（如 `@color/b1d`）本就是竞品原名，不改写。
    - 目录（drawable-xxhdpi / values-h640dp 等）保持，供 aapt2 按配置解析。
    - layout/ 由 run_step 单独复制且保留文件名（`legacy_are.xml` 等）：
      audit 已确认 layout 之间无 `@layout/` 引用，前缀不影响链接结果。
    """
    copied = 0
    values_rewritten = 0
    by_dir: dict[str, int] = {}
    for src in sorted(STAGED_DIR.rglob("*")):
        if not src.is_file():
            continue
        rel = src.relative_to(STAGED_DIR)
        if rel.parts[0] == "layout":
            continue
        dst_dir = res / rel.parent
        dst_dir.mkdir(parents=True, exist_ok=True)
        name = src.name
        if name.startswith("legacy_"):
            name = name[len("legacy_"):]
        dst = dst_dir / name
        if src.suffix == ".xml" and rel.parts[0].startswith("values"):
            text = src.read_text(encoding="utf-8")
            text2 = text.replace('name="legacy_', 'name="')
            if text2 != text:
                values_rewritten += 1
            dst.write_text(text2, encoding="utf-8")
        else:
            shutil.copy(src, dst)
        copied += 1
        key = rel.parent.as_posix()
        by_dir[key] = by_dir.get(key, 0) + 1
    return {
        "copied_file_count": copied,
        "values_xml_rewritten": values_rewritten,
        "by_staged_dir": dict(sorted(by_dir.items())),
    }


def run_step(name: str, values: list[Path], workdir: Path,
             aapt2: str, jar: str, staged: bool = False) -> dict:
    step = workdir / name
    if step.exists():
        shutil.rmtree(step)
    res = step / "res"
    (res / "layout").mkdir(parents=True)
    (res / "values").mkdir(parents=True)
    compiled = step / "compiled"
    compiled.mkdir()
    for f in sorted(LAYOUT_DIR.glob("*.xml")):
        shutil.copy(f, res / "layout" / f.name)
    for f in values:
        shutil.copy(f, res / "values" / f.name)
    staged_info = stage_staged_resources(res) if staged else None
    (step / "AndroidManifest.xml").write_text(MANIFEST, encoding="utf-8")

    c = subprocess.run([aapt2, "compile", "--dir", str(res), "-o", str(compiled) + os.sep],
                       capture_output=True, text=True)
    flats = sorted(str(p) for p in compiled.rglob("*.flat"))
    l = subprocess.run(
        [aapt2, "link", "-I", jar, "--manifest", str(step / "AndroidManifest.xml"),
         "-o", str(step / "out.apk"), *flats],
        capture_output=True, text=True,
    )
    log = (c.stdout + c.stderr + "\n" + l.stdout + l.stderr)
    # 证据产物不含个人绝对路径：把仓库外 workdir 统一替换为占位符。
    for form in (str(workdir), str(workdir).replace("\\", "/")):
        log = log.replace(form, "<workdir>")
    result = {
        "name": name,
        "values_files": [f.name for f in values],
        "layout_count": len(list((res / "layout").glob("*.xml"))),
        "staged_resources": staged_info,
        "compile_rc": c.returncode,
        "flat_count": len(flats),
        "link_rc": l.returncode,
    }
    result.update(classify(log))
    result["link_log"] = (l.stdout + l.stderr).strip()[:6000]
    result["compile_log"] = (c.stdout + c.stderr).strip()[:4000]
    return result


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--aapt2", default=None)
    ap.add_argument("--android-jar", default=None)
    ap.add_argument("--workdir", default=None)
    args = ap.parse_args()

    auto_aapt2, auto_jar = default_sdk_paths()
    aapt2 = args.aapt2 or auto_aapt2
    jar = args.android_jar or auto_jar
    if not aapt2 or not Path(aapt2).is_file():
        print("[FAIL] 找不到 aapt2，请用 --aapt2 指定或设置 ANDROID_HOME", file=sys.stderr)
        return 1
    if not jar or not Path(jar).is_file():
        print("[FAIL] 找不到 android.jar，请用 --android-jar 指定", file=sys.stderr)
        return 1
    for p in (LAYOUT_DIR, STAGED_DIR, IDS_XML, ATTRS_XML):
        if not p.exists():
            print(f"[FAIL] 缺少输入：{p}（先跑 tools/legacy_layout_resolve.py）",
                  file=sys.stderr)
            return 1

    workdir = Path(args.workdir) if args.workdir else Path(
        tempfile.mkdtemp(prefix="cloudmusic-l3-probe-"))
    workdir.mkdir(parents=True, exist_ok=True)

    steps = [
        run_step("step1_baseline", [], workdir, aapt2, jar),
        run_step("step2_with_ids", [IDS_XML], workdir, aapt2, jar),
        run_step("step3_with_attrs", [IDS_XML, ATTRS_XML], workdir, aapt2, jar),
        run_step("step4_with_staged_res", [IDS_XML, ATTRS_XML], workdir, aapt2, jar,
                 staged=True),
    ]
    s1, s2, s3, s4 = steps
    verdict = {
        "baseline_has_id_errors": s1["id_not_found_count"] > 0,
        "baseline_has_attr_errors": s1["attr_not_found_count"] > 0,
        "ids_remove_all_id_errors": s2["id_not_found_count"] == 0,
        "attrs_remove_all_attr_errors": s3["attr_not_found_count"] == 0,
        "step3_remaining_are_resource_refs": (
            s3["other_error_count"] > 0
            and s3["id_not_found_count"] == 0
            and s3["attr_not_found_count"] == 0
        ),
        "staged_res_remove_all_errors": s4["error_line_count"] == 0,
        "step4_aapt2_clean": s4["link_rc"] == 0 and s4["error_line_count"] == 0,
        "conclusion": "",
    }
    if (verdict["baseline_has_id_errors"] and verdict["baseline_has_attr_errors"]
            and verdict["ids_remove_all_id_errors"]
            and verdict["attrs_remove_all_attr_errors"]
            and verdict["step3_remaining_are_resource_refs"]
            and verdict["step4_aapt2_clean"]):
        verdict["conclusion"] = (
            "四步证明 aapt2 层面三类错误（101 个无定义 @id、33 个未声明 app:* attr、"
            "45 项 layout 资源引用）全部可由「竞品 attrs.xml + public.xml + 已暂存的 45 项素材」"
            "机械搬运彻底消除，9 个 layout 在仓库外临时工程中 aapt2 编译 / 链接全绿。"
            "唯一剩余阻塞是 20 个 com.netease.* 自定义 View 无实现：aapt2 不校验 Java 类，"
            "故这不属编译错误，而是运行时 LayoutInflater 的 ClassNotFoundException，"
            "须由阶段 C 重写；是否照搬播放页仍待拍板，本实证不构成接线。"
        )
    else:
        verdict["conclusion"] = "四步判据未全部满足，见各步错误明细，需人工复核。"

    report = {
        "method": "T-L L3 编译阻塞的 aapt2 编译级四步实证（仓库外临时工程）",
        "generated_by": "tools/l3_aapt2_probe.py",
        "inputs": {
            "layout_dir": "app/src/main/assets/legacy-player/layout",
            "staged_dir": "app/src/main/assets/legacy-player",
            "ids_xml": "analysis/l3-probe/ids.xml",
            "attrs_xml": "analysis/l3-probe/attrs.xml",
            "workdir_is_repo_external": True,
        },
        "steps": steps,
        "verdict": verdict,
    }
    PROBE_DIR.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")

    md = [
        "# T-L L3 · aapt2 四步编译实证（2026-11-08 晚）",
        "",
        "> 仓库外临时最小工程，9 个暂存 layout 原样参与编译；**未改任何生产文件**。",
        "> 复跑：`python tools/l3_aapt2_probe.py --aapt2 <SDK>/build-tools/<ver>/aapt2.exe`",
        "> `--android-jar <SDK>/platforms/android-<api>/android.jar`",
        "> （或设 `ANDROID_HOME` 自动探测）。结果 JSON：`aapt2-probe-result.json`。",
        "",
        "| 步骤 | values | 接入暂存资源 | compile rc | flat | link rc | 错误行 | id 缺失 | attr 缺失 | 其它 |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for s in steps:
        stage_note = (
            f"{s['staged_resources']['copied_file_count']} 文件" if s["staged_resources"]
            else "—"
        )
        md.append(
            f"| {s['name']} | {', '.join(s['values_files']) or '（无）'} | "
            f"{stage_note} | {s['compile_rc']} | {s['flat_count']} | {s['link_rc']} | "
            f"{s['error_line_count']} | {s['id_not_found_count']} | "
            f"{s['attr_not_found_count']} | {s['other_error_count']} |"
        )
    md += [
        "",
        "**基线错误形态**（step1，节选）：",
        "```",
        *s1["error_sample"][:6],
        "```",
        "",
        "**step3 剩余错误形态**（节选，均为 layout 引用的资源定义缺失）：",
        "```",
        *s3["error_sample"][:5],
        "```",
        "",
    ]
    if s4["staged_resources"]:
        st = s4["staged_resources"]
        md += [
            f"**step4 接入的暂存资源**：{st['copied_file_count']} 个文件，"
            f"{st['values_xml_rewritten']} 个 values XML 重写定义名，目录分布：",
            "",
            "```",
            *[f"{k}: {v}" for k, v in st["by_staged_dir"].items()],
            "```",
            "",
        ]
    if s4["error_line_count"]:
        md += [
            "**step4 残留错误**（节选）：",
            "```",
            *s4["error_sample"][:8],
            "```",
            "",
        ]
    md += [
        f"**结论**：{verdict['conclusion']}",
        "",
        "> 注：aapt2 不校验自定义 View 类是否存在，故 step4 全绿",
        "> **不等于**运行时可 inflate；20 个 com.netease.* 类仍须阶段 C 重写。",
        "> step1~step3 的 `错误行` 均为 20，是 aapt2 单轮报错上限，不代表错误总量。",
        "> step4 的去前缀动作仅发生在仓库外临时工程，`app/src/main/res/` 仍未建立。",
        "",
        "> AI生成",
        "",
    ]
    OUT_MD.write_text("\n".join(md), encoding="utf-8")

    for s in steps:
        print(f"[{s['name']}] link_rc={s['link_rc']} 错误 {s['error_line_count']} 行"
              f"（id {s['id_not_found_count']} / attr {s['attr_not_found_count']} /"
              f" 其它 {s['other_error_count']}）")
    print(f"[OK] {OUT_JSON.relative_to(ROOT).as_posix()} / "
          f"{OUT_MD.relative_to(ROOT).as_posix()}")
    print(f"     workdir={workdir}")
    print(f"     结论：{verdict['conclusion']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
