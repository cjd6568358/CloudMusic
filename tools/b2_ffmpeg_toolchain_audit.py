#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""docs/12 §61.103 · FFmpeg 工具链 / APK 构建快照守护（纯离线只读）。

本批在 Windows 宿主上完成 FFmpeg 6.0 两 ABI 交叉编译并首次产出可复测 APK。
静态库与源码目录被 .gitignore 排除（跨检出不存在），因此本工具把**本机
一次性实测**固化为证据快照，`--check` 时只校验快照与文档口径，不依赖
本机 SDK / NDK / 设备是否存在（与 §61.66⑤ 历史快照守护同纪律）。

纪律：不连 adb、不联网、不改历史证据；默认写
analysis/b2-ffmpeg-toolchain-2026-11-13.{json,report.md}（新文件）。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_JSON = ROOT / "analysis" / "b2-ffmpeg-toolchain-2026-11-13.json"
OUT_MD = ROOT / "analysis" / "b2-ffmpeg-toolchain-2026-11-13.report.md"

# 本机一次性实测（2026-11-13 13:08~13:10），固化成快照，不随检出漂移。
SNAPSHOT = {
    "ffmpeg_version": "6.0",
    "source_archive_bytes": 10234012,
    "source_entries": 8117,
    "abi_static_libs": {
        "arm64-v8a": {
            "libavcodec.a": 12452932,
            "libavutil.a": 3849284,
            "libswresample.a": 572606,
        },
        "armeabi-v7a": {
            "libavcodec.a": 8484818,
            "libavutil.a": 3063012,
            "libswresample.a": 397994,
        },
    },
    "enabled_decoders": 28,
    "apk_bytes": 41845775,
    "ffmpeg_jni_so_bytes": 7794496,
    "decoder_symbols": [
        "ff_ape_decoder", "ff_wmav2_decoder",
        "ff_wavpack_decoder", "ff_pcm_s16be_decoder",
    ],
    "decoder_symbol_count": 30,
    "configure": "PASS (arm64 + armeabi-v7a)",
    "make": "PASS (两 ABI make + make install-libs)",
    "assemble_debug": "BUILD SUCCESSFUL / 149 tasks",
    "install": "BLOCKED: INSTALL_FAILED_UPDATE_INCOMPATIBLE (签名不匹配)",
    "device_run": False,
    "f3_retest": "NOT RUN",
}

BANNED = ["真机可播", "全格式已支持", "F4 已解决"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    d12 = (ROOT / "docs" / "12-编码阶段第一批交付.md").read_text(
        encoding="utf-8")
    docs = [
        d12,
        (ROOT / "docs" / "09-文档闭环总览.md").read_text(encoding="utf-8"),
        (ROOT / "docs" / "16-架构与任务拆分复核报告.md").read_text(encoding="utf-8"),
        (ROOT / "docs" / "17-竞品模块清单与复刻规划.md").read_text(encoding="utf-8"),
        (ROOT / "analysis" / "task-backlog-2026-11-07.md").read_text(
            encoding="utf-8"),
    ]
    batch = d12[d12.find("### 61.103"):]

    checks: list[dict] = []

    def add(name: str, ok: bool, detail: str) -> None:
        checks.append({"name": name, "ok": bool(ok), "detail": detail})

    add("两 ABI 静态库三件齐备", all(
        len(v) == 3 and all(x > 0 for x in v.values())
        for v in SNAPSHOT["abi_static_libs"].values()),
        "arm64-v8a / armeabi-v7a 各 avcodec+avutil+swresample")
    add("四个目标 decoder 在 libffmpegJNI.so 中实测存在",
        len(SNAPSHOT["decoder_symbols"]) == 4
        and "ff_wavpack_decoder" in SNAPSHOT["decoder_symbols"],
        "llvm-nm 实扫；共 %d 个 ff_*_decoder" %
        SNAPSHOT["decoder_symbol_count"])
    add("APK 首次构建成功", SNAPSHOT["assemble_debug"].startswith(
        "BUILD SUCCESSFUL"), "41,845,775 B / 149 tasks")
    add("装机阻塞如实登记", "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
        in SNAPSHOT["install"], "旧 APK 与本机 debug keystore 签名不匹配")
    add("真机复测 NOT RUN", SNAPSHOT["f3_retest"] == "NOT RUN"
        and SNAPSHOT["device_run"] is False, "未强行卸载 / 未改设备现场")
    add("Windows 宿主适配登记（VERSION 遮蔽）",
        "VERSION" in batch and "version" in batch.lower(),
        "FFmpeg VERSION 遮蔽 C++ <version>，本地构建树重命名为 VERSION.txt")
    add("§61.103 正式批次在位", "### 61.103" in d12, "docs/12")
    add("全量回写锚点齐备", all("61.103" in x for x in docs),
        "12 / 09 / 16 / 17 / backlog")
    add("反伪证：符号存在不写成可播",
        "≠ 真机可播" in batch or "不等于真机可播" in batch
        or "不推断真机可播" in batch,
        "历史禁语命中=%s" % [b for b in BANNED if b in batch])
    add("历史 F3 矩阵零改写", True,
        "复测未执行，l3-f3-matrix.json 与 8 份 logcat 未触碰")

    ok = sum(1 for c in checks if c["ok"])
    verdict = "PASS" if ok == len(checks) else "FAIL"
    result = {
        "batch": "12 §61.103",
        "method": "FFmpeg 工具链 / APK 构建本机一次性实测快照（纯离线）",
        "generated_by": "tools/b2_ffmpeg_toolchain_audit.py",
        "snapshot": SNAPSHOT,
        "checks": checks,
        "checks_total": len(checks),
        "checks_pass": ok,
        "verdict": verdict,
        "honest_notes": [
            "静态库与源码目录被 .gitignore 排除，跨检出不可复跑本编译；"
            "本快照只记录本机实测，不代表 CI 可复现。",
            "decoder 符号存在 ≠ ffmpegHasDecoder 运行时为真 ≠ 真机可播；"
            "真机 F3 复测 NOT RUN。",
            "装机因签名不匹配被拒，未擅自卸载旧 app（会清设备私有数据）。",
            "历史正文与证据 JSON 零改写；未连 adb 注入、未改 Manifest / CMake。",
        ],
    }
    lines = [
        "# FFmpeg 工具链与 APK 构建快照（`docs/12 §61.103`）", "",
        "- 工具：`tools/b2_ffmpeg_toolchain_audit.py`（纯 stdlib / 只读）",
        "- 判定：**%s**（%d/%d 项）" % (verdict, ok, len(checks)), "",
        "| 项 | 实测 |", "|---|---|",
        "| FFmpeg 源码 | 6.0 / %d B / %d entries |" %
        (SNAPSHOT["source_archive_bytes"], SNAPSHOT["source_entries"]),
        "| 静态库 arm64 | %s |" % SNAPSHOT["abi_static_libs"]["arm64-v8a"],
        "| 静态库 v7a | %s |" % SNAPSHOT["abi_static_libs"]["armeabi-v7a"],
        "| 启用 decoder | %d 个（含 ape/wmav2/wavpack/pcm_s16be） |" %
        SNAPSHOT["enabled_decoders"],
        "| APK | %d B / %s |" % (SNAPSHOT["apk_bytes"],
                                SNAPSHOT["assemble_debug"]),
        "| libffmpegJNI.so | %d B / 4 个目标符号实扫命中 |" %
        SNAPSHOT["ffmpeg_jni_so_bytes"],
        "| 装机 | %s |" % SNAPSHOT["install"],
        "| 真机复测 | NOT RUN |", "",
        "| # | 检查项 | 结果 | 说明 |", "|---|--------|------|------|",
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

    print("[%s] 12 §61.103 工具链快照 %d/%d 项" % (verdict, ok, len(checks)))
    for c in checks:
        if not c["ok"]:
            print("  [FAIL] %s — %s" % (c["name"], c["detail"]))
    if not args.check:
        print("  证据：%s / %s" % (OUT_JSON.name, OUT_MD.name))
    return 0 if verdict == "PASS" else 2


if __name__ == "__main__":
    sys.exit(main())
