#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.53 / F4 · `OnDataRequestEnd` kind 槽 A/B 可复跑脚本。

承接 `analysis/l3-f4-kind-slot-rootcause.md`（§61.52）的高置信假设：
`OnDataRequestEnd` 首参是 `OnNetSourceDataDone(int kind, String)` 的 kind 槽，
传 `requestId` 会让 `id=2` 被当完成标记 → `PreloadDoneTaskOnWriteThread` →
读侧 1~2ms 后重发同一区间 → fMP4 REMOTE 永远走不到 `Open codec`。
本脚本把首参恒改为 `1L`（flush 语义）做真机 A/B。

**安全设计（重要）**：
- **默认 dry-run**：只校验源码锚点唯一性 + 打印执行计划，不碰任何文件 / 设备；
- `--apply` 才改代码，且用 `try/finally` **无条件恢复基线**并重编译重装；
- 只替换一处字符串，不引入新依赖 / 权限 / 模块；
- 不连 OpenList、不写凭据、不改设备系统设置；
- 真机命令一律带 `-s <serial>`；跑完恢复 `wifiOnly` 与 `adb reverse`。

用法（设备重新上线后）：
    python tools/l3_f4_kind_slot_ab.py --serial <ip:port> --apply

产出 `analysis/l3-f4-kind-slot-ab.json`（--apply）
或 `analysis/l3-f4-kind-slot-ab-plan.json`（dry-run）。
"""
from __future__ import annotations

import argparse
import functools
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
FIXTURES = ROOT / "analysis" / "l3-f3-fixtures"
LOG_DIR = ROOT / "analysis" / "l3-f4-logs"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
GRADLE = ROOT / "gradlew"
ADB = ROOT / "tools" / "bin" / "adb.exe"
OUT_APPLY = ROOT / "analysis" / "l3-f4-kind-slot-ab.json"
OUT_PLAN = ROOT / "analysis" / "l3-f4-kind-slot-ab-plan.json"

OLD_CALL = 'OnDataRequestEnd(requestId, error ?: "")'
NEW_CALL = 'OnDataRequestEnd(NATIVE_DONE_KIND_FLUSH, error ?: "")'
ANCHOR_COMPANION = "const val NATIVE_DATA_KIND_NORMAL = 1L"
CONST_LINE = """        /** 12 §61.53 / F4 A/B：End 回调恒走 OnFlushNetData（kind!=2）。 */
        const val NATIVE_DONE_KIND_FLUSH = 1L
"""

FMP4 = "f3_m4a_fast.m4a"
REGRESSION = [
    ("mp3", "f3_mp3.mp3", "local"), ("mp3", "f3_mp3.mp3", "remote"),
    ("wma", "f3_wma.wma", "remote"), ("flac", "f3_flac.flac", "remote"),
    ("wav", "f3_wav.wav", "remote"),
]


def repeated_range(starts: list[str]) -> bool:
    """12 §61.59：判据应检测『id>=3 与 id=2 同 offset 的连锁重复』，
    不能硬编码某个 offset —— 真机实测首次请求位置会随运行变化
    （run1/run2 为 147456，基线对照为 442368，重复段为 605007）。"""
    parsed = []
    for s in starts:
        m = re.search(r"Start id=(\d+) offset=(\d+)", s)
        if m:
            parsed.append((int(m.group(1)), int(m.group(2))))
    off2 = next((off for rid, off in parsed if rid == 2), None)
    return off2 is not None and any(
        rid >= 3 and off == off2 for rid, off in parsed)


def load_runner():
    spec = importlib.util.spec_from_file_location(
        "f3runner", ROOT / "tools" / "l3_f3_matrix_run.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def gradle(*tasks: str) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    if "ANDROID_HOME" not in env:
        local = os.environ.get("LOCALAPPDATA", "")
        candidate = Path(local) / "Temp" / "cloudmusic-ta1-sdk" if local else None
        if candidate and candidate.is_dir():
            env["ANDROID_HOME"] = str(candidate)
    # 12 §61.57 编译期预检实证：Windows 上 ./gradlew 是无扩展名的 bash 脚本，
    # CreateProcess 会抛 OSError [WinError 193]；必须改用 gradlew.bat。
    launcher = GRADLE.with_name("gradlew.bat") if os.name == "nt" else GRADLE
    cmd = [str(launcher), *tasks, "--offline"]
    return subprocess.run(cmd, cwd=str(ROOT), env=env, capture_output=True,
                          text=True, timeout=600)


def plan_text() -> dict:
    src = SRC.read_text(encoding="utf-8")
    return {
        "method": "12 §61.53 / F4 kind 槽 A/B 执行计划（dry-run，零改动）",
        "source_file": str(SRC.relative_to(ROOT)).replace("\\", "/"),
        "anchor_checks": {
            "old_call_exactly_once": src.count(OLD_CALL) == 1,
            "companion_anchor_present": ANCHOR_COMPANION in src,
            "variant_not_already_applied": "NATIVE_DONE_KIND_FLUSH" not in src,
            "p0_baseline_present": "repeat(20)" in src
                                   and "delayed 2s or immediate" in src,
        },
        "variant_b_change": {
            "replace": OLD_CALL + "  →  " + NEW_CALL,
            "insert_after": ANCHOR_COMPANION,
            "insert": CONST_LINE.strip(),
            "files_touched": 1,
            "gradle_dependencies_added": 0,
        },
        "steps": [
            "1. 校验设备 get-state=device（所有 adb 命令带 -s）",
            "2. 备份 UrlDataRequest.kt 到系统临时目录（不落仓库）",
            "3. 应用变体 B（1 处替换 + 1 行常量）",
            "4. ./gradlew :app:assembleDebug --offline → adb install -r",
            "5. 主判据：f3_m4a_fast.m4a × REMOTE，等待 25s，抓 logcat",
            "6. 回归：5 格（mp3 local/remote、wma/flac/wav remote）",
            "7. finally：恢复基线文件 → 重编译 → 重装 → 校验源码与基线一致",
            "8. 恢复 wifiOnly / adb reverse；产出 JSON 证据",
        ],
        "primary_criterion": "fMP4 REMOTE 出现 Open codec for aac + Write start play nErr:0",
        "falsification": "仍出现 id>=3 与 id=2 同 offset 的连锁重复且无 codec → 假设证伪（§61.59 不再硬编码 offset）",
        "regression_criterion": "5 格全部 PASS，且 F3 14 格矩阵建议另行全量复跑",
        "safety": [
            "默认 dry-run，--apply 才执行",
            "try/finally 无条件恢复 P0 基线并重编译重装",
            "不连 OpenList、不写凭据、不改设备系统设置",
            "自动进曲 3 首连播本脚本未实现，须单独按 docs/17 §8.3 判据补测（NOT RUN）",
        ],
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="F4 kind 槽 A/B（默认 dry-run）")
    ap.add_argument("--serial", default="10.0.0.2:39853")
    ap.add_argument("--apply", action="store_true",
                    help="真正执行 A/B（会自动恢复 P0 基线）")
    ap.add_argument("--wait", type=float, default=25.0)
    ap.add_argument("--sample", default=FMP4,
                    help="主判据样本文件名（§61.60 自洽样本判别实验）")
    args = ap.parse_args()

    plan = plan_text()
    bad = [k for k, v in plan["anchor_checks"].items() if not v]
    print("[INFO] 源码锚点：%s" % plan["anchor_checks"])
    if bad:
        print("[FAIL] 锚点校验不通过：%s（可能已应用变体或基线被改动）" % bad)
        return 1

    if not args.apply:
        OUT_PLAN.write_text(json.dumps(plan, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8")
        print("[PASS] dry-run：计划已落盘 %s（零文件改动、零设备操作）"
              % OUT_PLAN.relative_to(ROOT))
        for s in plan["steps"]:
            print("   " + s)
        return 0

    # ---------------- --apply ----------------
    f3 = load_runner()
    state = f3.adb(args.serial, "get-state").stdout.decode().strip()
    if state != "device":
        print("[FAIL] 设备不在线（get-state=%s）；请重新给端点后重试" % state)
        return 1

    backup = Path(os.environ.get("TEMP", "/tmp")) / "UrlDataRequest.f4_ab.baseline.kt"
    shutil.copyfile(SRC, backup)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    cells = []
    restored = False
    build_log = ""
    try:
        src = SRC.read_text(encoding="utf-8")
        variant = src.replace(OLD_CALL, NEW_CALL, 1).replace(
            ANCHOR_COMPANION, ANCHOR_COMPANION + "\n" + CONST_LINE.rstrip("\n"), 1)
        SRC.write_text(variant, encoding="utf-8")
        print("[APPLY] 变体 B 已写入（1 处替换 + 1 行常量）", flush=True)

        b = gradle(":app:assembleDebug")
        build_log = b.stdout[-800:] + b.stderr[-400:]
        if b.returncode != 0:
            print("[FAIL] 编译失败：%s" % build_log[-500:])
            return 1
        r = subprocess.run([str(ADB), "-s", args.serial, "install", "-r", str(APK)],
                           capture_output=True, text=True, timeout=180)
        print("[APPLY] install: %s" % r.stdout.strip()[-120:], flush=True)

        class Quiet(SimpleHTTPRequestHandler):
            def log_message(self, *a):
                pass

        httpd = ThreadingHTTPServer(("127.0.0.1", 8765),
                                    functools.partial(Quiet, directory=str(FIXTURES)))
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        f3.adb(args.serial, "reverse", "tcp:8765", "tcp:8765")
        f3.set_network_gate(args.serial, True)
        try:
            jobs = [("m4a", args.sample, "remote", args.wait)] + [
                (ext, name, kind, 6.0) for ext, name, kind in REGRESSION]
            for ext, name, kind, wait in jobs:
                sample = FIXTURES / name
                f3.adb(args.serial, "shell", "am", "force-stop", f3.PKG)
                f3.adb(args.serial, "shell", "am", "start", "-n",
                       f3.PKG + "/.MainActivity")
                time.sleep(1.2)
                f3.adb(args.serial, "logcat", "-c")
                if kind == "local":
                    f3.push_local(args.serial, sample, name)
                    f3.adb(args.serial, "shell", "am", "startservice", "-a",
                           f3.ACTION_PLAY, "-c", "android.intent.category.DEFAULT",
                           "--es", f3.EXTRA_LOCAL, f3.PRIVATE_DIR + "/" + name)
                else:
                    f3.adb(args.serial, "shell", "am", "startservice", "-a",
                           f3.ACTION_PLAY, "-c", "android.intent.category.DEFAULT",
                           "--es", f3.EXTRA_URL, "http://127.0.0.1:8765/" + name)
                time.sleep(wait)
                log = f3.adb(args.serial, "logcat", "-d", "-v", "time").stdout.decode(
                    "utf-8", "replace")
                tag = "ksab-%s-%s" % (ext, kind)
                (LOG_DIR / (tag + ".txt")).write_text(log, encoding="utf-8")
                row = {
                    "tag": tag, "format": ext, "path": kind,
                    "extension": (re.findall(r"resourceInfo extension=(\S+)", log)
                                  or [None])[-1],
                    "codec": (re.findall(r"Open codec for (\S+)", log) or [None])[-1],
                    "write": "Write start play nErr:0" in log,
                    "m103": "-103" in log,
                    "starts": re.findall(r"Start id=\d+ offset=\d+ length=\d+",
                                         log)[-5:],
                    "on_end": re.findall(r"onEnd id=\d+ error=\S+[^\n]*", log)[-4:],
                }
                cells.append(row)
                print("[%s] codec=%s write=%s" % (tag, row["codec"], row["write"]),
                      flush=True)
        finally:
            httpd.shutdown()
            f3.adb(args.serial, "reverse", "--remove", "tcp:8765")
            f3.set_network_gate(args.serial, False)
    finally:
        shutil.copyfile(backup, SRC)
        b2 = gradle(":app:assembleDebug")
        if b2.returncode == 0:
            subprocess.run([str(ADB), "-s", args.serial, "install", "-r", str(APK)],
                           capture_output=True, text=True, timeout=180)
        # 12 §61.57：源码一致性必须独立判断，不能只在基线重编译成功时才算
        # 「已恢复」，否则 gradle 临时故障会误报 P0 未恢复。
        restored = SRC.read_text(encoding="utf-8") == backup.read_text(
            encoding="utf-8")
        baseline_rebuild_ok = b2.returncode == 0
        print("[RESTORE] P0 基线恢复=%s 重编译=%s" %
              (restored, baseline_rebuild_ok), flush=True)
        backup.unlink(missing_ok=True)

    primary = next((c for c in cells if c["tag"] == "ksab-m4a-remote"), {})
    regression = [c for c in cells if c["tag"] != "ksab-m4a-remote"]
    result = {
        "method": "12 §61.53 / F4 kind 槽 A/B 变体 B（OnDataRequestEnd 首参恒传 1L）",
        "serial": args.serial,
        "primary_sample": args.sample,
        "variant": "NATIVE_DONE_KIND_FLUSH = 1L；requestId 仅留日志",
        "cells": cells,
        "primary_criterion_met": bool(primary.get("codec") == "aac"
                                      and primary.get("write")),
        "repeated_range_seen": repeated_range(primary.get("starts", [])),
        "falsified": bool(not primary.get("codec")
                          and repeated_range(primary.get("starts", []))),
        "regression_all_pass": all(c["codec"] and c["write"] for c in regression),
        "p0_restored": restored,
        "baseline_rebuild_ok": baseline_rebuild_ok,
        "auto_advance_3_tracks": "NOT RUN（本脚本未实现连播注入，须单独补测）",
        "honest_notes": [
            "变体 B 只临时存在，finally 无条件恢复 P0 基线并重编译重装。",
            "§61.59：falsified 判据改为检测 id>=3 与 id=2 同 offset 的重复，不再硬编码 offset 值。",
            "自动进曲 3 首 / seek / ENDED 未测，不能据此收口 F4。",
            "fMP4 样本是人工截取片段，不是完整真实 m4a 音频。",
        ],
    }
    OUT_APPLY.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                         encoding="utf-8")
    print("[PASS] A/B 结果落盘 %s" % OUT_APPLY.relative_to(ROOT))
    print("[INFO] 主判据=%s / 假设证伪=%s / 回归全过=%s / P0恢复=%s"
          % (result["primary_criterion_met"], result["falsified"],
             result["regression_all_pass"], restored))
    return 0 if (restored and baseline_rebuild_ok) else 1


if __name__ == "__main__":
    sys.exit(main())
