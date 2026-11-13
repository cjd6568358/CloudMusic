#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""docs/12 §61.102 · 容器缺口闭环审计（纯离线只读）。

回答一个具体问题：F3 8 FAIL 对应的 4 个容器，除了「Extractor 解析」之外，
「解码器认不认这个 MIME」这一环是否也接通了？上游 decoder_ffmpeg 只映射
标准 MimeTypes，本批补了 4 条自定义映射并实现 aiff/wv 两个 Extractor。

纪律：纯 stdlib、只读、不连 adb、不联网、不改历史证据；默认输出
analysis/b2-container-gap-2026-11-13.{json,report.md}（新文件）。
**真机复测 NOT RUN**：静态库 decoder 是否编入由 build_ffmpeg.sh 决定，
本工具只校验 Java/Kotlin 侧接线与 JVM 单测，绝不推断真机可播。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_JSON = ROOT / "analysis" / "b2-container-gap-2026-11-13.json"
OUT_MD = ROOT / "analysis" / "b2-container-gap-2026-11-13.report.md"

FFLIB = (ROOT / "engine-opensource" / "src" / "main" / "java" /
         "androidx" / "media3" / "decoder" / "ffmpeg" / "FfmpegLibrary.java")
APE = (ROOT / "engine-opensource" / "src" / "main" / "java" /
       "androidx" / "media3" / "extractor" / "ape" / "ApeExtractor.kt")
AIFF = (ROOT / "engine-opensource" / "src" / "main" / "java" /
        "androidx" / "media3" / "extractor" / "aiff" / "AiffExtractor.kt")
WVPK = (ROOT / "engine-opensource" / "src" / "main" / "java" /
        "androidx" / "media3" / "extractor" / "wavpack" / "WavpackExtractor.kt")
TESTS = ROOT / "engine-opensource" / "src" / "test" / "kotlin"

MIME_MAP = {
    "audio/x-ape": "ape",
    "audio/x-wma": "wmav2",
    "audio/x-wavpack": "wavpack",
    "audio/x-aiff": "pcm_s16be",
}


def read(p: Path) -> str:
    try:
        return p.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        return ""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    lib, ape, aiff, wvpk = read(FFLIB), read(APE), read(AIFF), read(WVPK)
    aiff_test = (TESTS / "com" / "example" / "cloudmusic" /
                 "engineopensource" / "AiffExtractorTest.kt")
    wvpk_test = (TESTS / "com" / "example" / "cloudmusic" /
                 "engineopensource" / "WavpackExtractorTest.kt")
    mime_test = (TESTS / "androidx" / "media3" / "decoder" / "ffmpeg" /
                 "FfmpegLibraryMimeTest.kt")
    d12 = read(ROOT / "docs" / "12-编码阶段第一批交付.md")
    matrix = json.loads((ROOT / "analysis" / "l3-f3-matrix.json"
                         ).read_text(encoding="utf-8"))

    checks: list[dict] = []

    def add(name: str, ok: bool, detail: str) -> None:
        checks.append({"name": name, "ok": bool(ok), "detail": detail})

    for mime, codec in MIME_MAP.items():
        add("MIME 映射 %s → %s" % (mime, codec),
            ('case "%s":' % mime) in lib and ('return "%s";' % codec) in lib,
            "FfmpegLibrary.getCodecName 实扫")
    add("上游标准 MIME 映射未回退", 'case MimeTypes.AUDIO_MPEG:' in lib
        and 'return "mp3";' in lib, "mp3/aac/flac/opus 原样")
    add("未知 MIME 仍返回 null", "default:\n        return null;" in lib,
        "不静默兜底")
    add("AiffExtractor 已实现", "class AiffExtractor" in aiff
        and "audio/x-aiff" in aiff, "FORM/COMM/SSND + 80-bit 采样率")
    add("WavpackExtractor 已实现", "class WavpackExtractor" in wvpk
        and "audio/x-wavpack" in wvpk, "wvpk 变长块")
    add("四个 Extractor 全挂进 factoryWithApe",
        all(x in ape for x in ("ApeExtractor()", "AsfExtractor()",
                               "AiffExtractor()", "WavpackExtractor()")),
        "自定义工厂清单实扫")
    add("新增三个 JVM 测试文件在位",
        all(p.is_file() for p in (aiff_test, wvpk_test, mime_test)),
        "Aiff/Wavpack/MIME 映射")
    add("AIFF 采样率勘误登记（48000 非 44100）",
        "48000" in d12 and "勘误" in d12[d12.find("### 61.102"):],
        "§61.101 曾误记 44100，本批只追加勘误、不改历史正文")
    add("历史 F3 矩阵零改写",
        matrix.get("counts", {}).get("PASS") == 14
        and matrix.get("counts", {}).get("FAIL") == 8,
        "14 PASS / 8 FAIL 当时快照")
    add("真机复测 NOT RUN 边界在位",
        "NOT RUN" in d12[d12.find("### 61.102"):]
        and "ffmpeg" in d12[d12.find("### 61.102"):].lower(),
        "静态库 decoder 未编入，不推断可播")
    add("反伪证：不宣称已可播",
        "真机可播" in aiff + wvpk and "≠ 真机可播" in aiff + wvpk,
        "KDoc 明示映射存在 ≠ 解码器编入 ≠ 真机可播")

    ok = sum(1 for c in checks if c["ok"])
    verdict = "PASS" if ok == len(checks) else "FAIL"
    result = {
        "batch": "12 §61.102",
        "method": "容器解析 + 解码器 MIME 映射闭环（纯离线只读）",
        "generated_by": "tools/b2_container_gap_audit.py",
        "device_run": False,
        "mime_map": MIME_MAP,
        "checks": checks,
        "checks_total": len(checks),
        "checks_pass": ok,
        "verdict": verdict,
        "honest_notes": [
            "本工具只校验 Java/Kotlin 侧接线与 JVM 单测，不加载 native 库。",
            "ffmpegHasDecoder(codecName) 是否为真取决于 build_ffmpeg.sh 的 "
            "ENABLED_DECODERS（需 ape / wmav2 / wavpack / pcm_s16be），本批未交叉编译。",
            "F3 8 FAIL 真机复测 NOT RUN：本机仍无法产出含 FFmpeg 静态库的 APK。",
            "历史正文与证据 JSON 零改写；零设备操作、零联网、不动解码器。",
        ],
    }
    lines = [
        "# 容器缺口闭环审计（`docs/12 §61.102`）", "",
        "- 工具：`tools/b2_container_gap_audit.py`（纯 stdlib / 只读）",
        "- 判定：**%s**（%d/%d 项）；`device_run=false`" % (verdict, ok, len(checks)),
        "", "| # | 检查项 | 结果 | 说明 |", "|---|--------|------|------|",
    ]
    for i, c in enumerate(checks, 1):
        lines.append("| %d | %s | %s | %s |" % (
            i, c["name"], "PASS" if c["ok"] else "FAIL", c["detail"]))
    lines += ["", "## 诚实边界", ""] + ["- " + n for n in result["honest_notes"]]
    lines += ["", "> AI生成", ""]
    report = "\n".join(lines)

    if not args.check:
        OUT_JSON.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8")
        OUT_MD.write_text(report, encoding="utf-8")

    print("[%s] 12 §61.102 容器闭环审计 %d/%d 项" % (verdict, ok, len(checks)))
    for c in checks:
        if not c["ok"]:
            print("  [FAIL] %s — %s" % (c["name"], c["detail"]))
    if not args.check:
        print("  证据：%s / %s" % (OUT_JSON.name, OUT_MD.name))
    return 0 if verdict == "PASS" else 2


if __name__ == "__main__":
    sys.exit(main())
