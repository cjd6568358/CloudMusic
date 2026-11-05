#!/usr/bin/env python3
"""C5 · 凭据泄漏检查（docs/03-12 §3 C5 / §10 / 07 §8）。

① 源码/资源无硬编码账号密码；② 日志语句不含 additionRaw / password / Authorization / 签名 URL。
只扫自研模块的 src/main，测试夹具（src/test 里的模拟凭据）不计入。
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
    "engine-api", "engine-bridge", "engine-opensource", "app",
)

LOG_MARKERS = re.compile(r"\b(Log\.\w+|Timber\.\w+|logger\.\w+|println|print|Logcat)\s*\(", re.IGNORECASE)
SENSITIVE = re.compile(
    r"(password|passwd|additionRaw|Authorization|private[_-]?key|sign=|token=)", re.IGNORECASE
)
HARDCODED = re.compile(
    r"""(?i)\b(password|passwd|secret|api[_-]?key|access[_-]?token)\b\s*[=:]\s*["']([^"']{3,})["']"""
)


def main() -> int:
    parser = argparse.ArgumentParser(description="C5 · 凭据泄漏检查")
    add_mode_arg(parser)
    args = parser.parse_args()
    report = Report("C5", "无硬编码凭据 / 日志不落敏感信息", args.mode)

    scanned = 0
    leaks: list[str] = []
    for path in source_files(*OWN_MODULES):
        rel = path.relative_to(ROOT).as_posix()
        if "/src/test/" in rel or "/src/androidTest/" in rel:
            continue
        if any(m in rel for m in ("engine-legacy", "media-legacy", "ui-legacy")):
            continue
        scanned += 1
        for line_no, line in enumerate(
            path.read_text(encoding="utf-8", errors="replace").splitlines(), 1
        ):
            code = line.split("//", 1)[0]
            if LOG_MARKERS.search(code) and SENSITIVE.search(code):
                leaks.append(f"日志泄漏 {rel}:{line_no}: {line.strip()[:110]}")
            if HARDCODED.search(code):
                leaks.append(f"硬编码凭据 {rel}:{line_no}: {line.strip()[:110]}")

    for item in leaks[:20]:
        report.fail(item)
    if len(leaks) > 20:
        report.fail(f"……另有 {len(leaks) - 20} 处")
    if not leaks:
        report.ok(f"已扫描 {scanned} 个自研 src/main 文件，无硬编码凭据、日志无敏感字段")
    return report.emit()


if __name__ == "__main__":
    raise SystemExit(main())
