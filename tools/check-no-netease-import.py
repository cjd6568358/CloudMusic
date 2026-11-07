#!/usr/bin/env python3
"""C2 · 自研模块禁止出现 com.netease.*（docs/03-12 §3 C2 / §5）。

例外 1：三个照搬舱 :engine-legacy / :media-legacy / :ui-legacy 内保持竞品原包名，
        因为 JNI 符号与 R 引用硬绑定（03-12 §1.3）。
例外 2（12 §47，用户确认 D-145 后）：app/src/main/assets/legacy-player/ 是
        ⑦ 导出的**未替换竞品素材暂存区**，由 C4 单独追踪（release 阻断）。
        其中 layout 的 com.netease.* 自定义 View 类名正是待替换内容，
        不属于「自研源码外泄」；待 L3 转 res 并全量替换后一并收口。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_common import ROOT, Report, add_mode_arg, source_files  # noqa: E402

OWN_MODULES = (
    "core-common", "core-model", "core-di", "core-storage", "core-media",
    "datasource-api", "datasource-local", "datasource-webdav",
    "datasource-strm", "datasource-playlist",
    "scanner", "feature-library", "feature-player",
    "output-router", "output-dlna", "output-localproxy", "output-bluetooth",
    "engine-api", "engine-bridge", "engine-opensource",
    "app", "build-logic",
)
LEGACY_MARKERS = ("engine-legacy", "media-legacy", "ui-legacy")
# 12 §47：C4 追踪的未替换竞品素材区，不按「自研源码」判定（见模块 docstring）。
C4_STAGED_ASSETS = "app/src/main/assets/legacy-player/"
REFERENCE = re.compile(r"com\.netease\.", re.IGNORECASE)


def main() -> int:
    parser = argparse.ArgumentParser(description="C2 · 自研模块 com.netease.* 外泄检查")
    add_mode_arg(parser)
    args = parser.parse_args()
    report = Report("C2", "自研模块无 com.netease.* 引用", args.mode)

    scanned = 0
    staged_skipped = 0
    violations: list[str] = []
    for path in source_files(*OWN_MODULES):
        rel = path.relative_to(ROOT).as_posix()
        if any(marker in rel for marker in LEGACY_MARKERS):
            continue
        if rel.startswith(C4_STAGED_ASSETS):
            staged_skipped += 1
            continue
        scanned += 1
        for line_no, line in enumerate(
            path.read_text(encoding="utf-8", errors="replace").splitlines(), 1
        ):
            code = line.split("//", 1)[0]
            if REFERENCE.search(code):
                violations.append(f"{rel}:{line_no}: {line.strip()[:120]}")

    if violations:
        for v in violations[:20]:
            report.fail(v)
        if len(violations) > 20:
            report.fail(f"……另有 {len(violations) - 20} 处")
    else:
        report.ok(
            f"已扫描 {scanned} 个自研源码文件，无 com.netease.* 引用"
            f"（另跳过 {staged_skipped} 个 C4 追踪的未替换素材文件）"
        )

    present = [m for m in OWN_MODULES if (ROOT / m).exists()]
    report.ok(f"已落地自研模块目录：{', '.join(present)}")
    return report.emit()


if __name__ == "__main__":
    raise SystemExit(main())
