#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.63 / F4 变体 D：normal mode 下 `OnDataRequestData` 的 kind 槽 A/B。

**假说（§61.63 设备侧 .nmsfi 索引一手取证推出）**：§59 的 kind 校准是在
**Memory Mode**（cache_local=false）下做的 —— 那里 `OnWriteNetData` 恒被拒，
所以数据包恒传 `kind=1` 走 `NMMemoryCache::Write` 才能出声。但路线 2
（§61，用户拍板）已切到 **normal mode**（cache_local=true），读侧改走
vfile（`ReadFromFileNoLock`）。§59 注释明确写着：
  `kind==2` → 跳过内存写，**投递 `OnWriteNetData` 异步任务（vfile 落盘）**；
  `kind!=2` → 只写内存缓存。
→ 路线 2 切换后遗留的错配可能是：**数据包仍恒传 1，只进内存缓存，
vfile 永不落盘**。设备侧证据：mp3/flac/wav 的 vfile 索引有多个全满 1 MiB
trunk（落盘能工作），而自洽 fMP4 只有 `[0,368640)` / 清缓存后 `[0,180224)`，
后续 1 MiB 回传数据一个字节都没进 vfile → 跳跃读恒 `-2` → probe 失败 →
延迟打开 codec 后立即 `-103`。

本脚本把 `OnDataRequestData` 的 kind 从 `NATIVE_DATA_KIND_NORMAL(1L)`
临时改为 `2L`，编译重装后跑：
  - 主判据：自洽样本 `f3_m4a_complete.m4a` × REMOTE，60 s 全量 logcat；
  - 回归 2 格：`f3_mp3.mp3` / `f3_wav.wav` × REMOTE，各 12 s。
判据以 **`-103` 是否消除 + 是否出现 `Write start play nErr:0`** 为准，
不是只看 `Open codec`（§61.62 已证明 codec 打开 ≠ 出声）。

纪律：默认 dry-run；`--apply` 才改代码；`try/finally` **无条件恢复 P0
基线并重编译重装**，校验源码逐字节一致；零 native so 改动、零新增依赖。

用法：
  python tools/l3_f4_data_kind_ab.py --serial <ip:port> --apply
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
ADB = ROOT / "tools" / "bin" / "adb.exe"
PROBE = ROOT / "tools" / "l3_f4_vfile_commit_probe.py"
A = ROOT / "analysis"
PKG = "com.example.cloudmusic"
OUT = A / "l3-f4-data-kind-ab.json"
OUT_INDEX = A / "l3-f4-data-kind-ab-index.json"

OLD_CALL = "OnDataRequestData(NATIVE_DATA_KIND_NORMAL, bytes, offset, count)"
NEW_CALL = ("OnDataRequestData(2L, bytes, offset, count)"
            "  // 12 §61.63 变体 D：kind=2 → 投递 OnWriteNetData 落盘")

MAIN_SAMPLE = "f3_m4a_complete.m4a"
REGRESSION = [("mp3", "f3_mp3.mp3"), ("wav", "f3_wav.wav")]
TARGET_FILE_SIZE = 16573387

RE_CODEC = re.compile(r"Open codec for (\w+)")
RE_WRITE = re.compile(r"Write start play nErr:(\d+)")
RE_COMMIT = re.compile(r"commit :\s*(\d+)")
RE_INIT = re.compile(r"NMSliceFile Open Init ([^:]*):")


def adb(serial: str, *args: str, timeout: int = 90) -> subprocess.CompletedProcess:
    return subprocess.run([str(ADB), "-s", serial, *args],
                          capture_output=True, timeout=timeout)


def gradle(*tasks: str) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    # 与 §61.58~§61.62 同口径：仓库外临时目录，不改系统环境变量。
    env.setdefault("GRADLE_USER_HOME",
                   "C:/Users/Administrator/AppData/Local/Temp/cloudmusic-build/gradle-home")
    env.setdefault("ANDROID_HOME",
                   "C:/Users/Administrator/AppData/Local/Temp/cloudmusic-ta1-sdk")
    launcher = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return subprocess.run([str(launcher), *tasks, "--offline"],
                          cwd=str(ROOT), env=env, capture_output=True,
                          text=True, timeout=600)


def cat_app_file(serial: str, rel: str) -> str:
    r = subprocess.run([str(ADB), "-s", serial, "exec-out",
                        "run-as %s cat %s" % (PKG, rel)],
                       capture_output=True, timeout=60)
    return r.stdout.decode("utf-8", "replace")


def clear_target_cache(serial: str) -> list[str]:
    """删除目标资源的 app 私有 vfile 缓存组，保证与 §61.63 的新建状态一致。"""
    r = adb(serial, "shell", "run-as %s sh -c "
            "'ls cache/legacy_engine_cache/*/*.nmsfi 2>/dev/null'" % PKG)
    paths = [x.strip() for x in r.stdout.decode("utf-8", "replace").splitlines()
             if x.strip()]
    entries, cleared = [], []
    for rel in paths:
        raw = cat_app_file(serial, rel)
        try:
            doc = json.loads(raw)
        except (ValueError, TypeError):
            continue
        entries.append({"path": rel, "index": doc})
        if doc.get("file_size") == TARGET_FILE_SIZE:
            base = rel[:-6]
            adb(serial, "shell", "run-as %s sh -c "
                "'rm -f %s.nmsfi %s_*.nmsf'" % (PKG, base, base))
            cleared.append(base)
    OUT_INDEX.write_text(json.dumps(
        {"serial": serial, "indexes_before_clear": entries, "cleared": cleared},
        ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return cleared


def run_probe(serial: str, sample: str, wait: float, out_rel: str,
              warmup: float = 1.5) -> dict:
    cmd = [sys.executable, str(PROBE), "--serial", serial,
           "--wait", str(wait), "--sample", sample, "--out", out_rel,
           "--warmup", str(warmup)]
    r = subprocess.run(cmd, cwd=str(ROOT), timeout=wait + 180)
    if r.returncode != 0:
        return {"error": "probe rc=%d" % r.returncode}
    doc = json.loads((ROOT / out_rel).read_text(encoding="utf-8"))
    text = (ROOT / doc["log_file"]).read_text(encoding="utf-8", errors="replace")
    return {
        "log_file": doc.get("log_file"),
        "log_lines": len(text.splitlines()),
        "codec": RE_CODEC.findall(text),
        "write_start_play_nerr": RE_WRITE.findall(text),
        "minus_103_occurrences": text.count("-103"),
        "commit_values": [int(x) for x in RE_COMMIT.findall(text)],
        "slice_init": RE_INIT.findall(text),
        "audio_output": "0" in RE_WRITE.findall(text),
        # 12 §61.63 首跑教训：只有 prepare、没有取流/解码链时不得写结论。
        "prepare_seen": "prepare trackId=" in text,
        "chain_started": (
            "createDataRequestHandler" in text
            or "Start id=" in text
            or bool(RE_CODEC.findall(text))),
        "warmup_seconds": warmup,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="F4 变体 D：数据包 kind=2 A/B")
    ap.add_argument("--serial", default="10.0.0.2:39737")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--wait", type=float, default=60.0)
    ap.add_argument("--warmup", type=float, default=4.0,
                    help="install -r 后冷启动预热；首跑 1.5s 注入过早")
    args = ap.parse_args()

    src_text = SRC.read_text(encoding="utf-8")
    if OLD_CALL not in src_text:
        print("[FAIL] 变体锚点不在位：%s" % OLD_CALL)
        return 1
    if not args.apply:
        print(json.dumps({
            "dry_run": True,
            "old_call": OLD_CALL,
            "new_call": NEW_CALL,
            "main_sample": MAIN_SAMPLE,
            "regression": [s for _, s in REGRESSION],
            "note": "默认 dry-run；--apply 才改代码并在 finally 无条件恢复 P0 基线",
        }, ensure_ascii=False, indent=2))
        return 0

    state = adb(args.serial, "get-state").stdout.decode().strip()
    if state != "device":
        print("[FAIL] 设备不在线（get-state=%s）" % state)
        return 1

    backup = Path(os.environ.get("TEMP", "/tmp")) / "UrlDataRequest.f4_dkind.baseline.kt"
    shutil.copyfile(SRC, backup)
    main_cell: dict = {}
    regression: list[dict] = []
    restored = False
    baseline_rebuild_ok = False
    try:
        SRC.write_text(src_text.replace(OLD_CALL, NEW_CALL, 1), encoding="utf-8")
        print("[APPLY] 变体 D 已写入（%s → %s）" % (OLD_CALL, NEW_CALL), flush=True)
        b = gradle(":app:assembleDebug")
        print("[APPLY] assemble rc=%d %s" % (b.returncode, b.stdout[-160:].strip()),
              flush=True)
        if b.returncode != 0:
            print("[FAIL] 变体编译失败：%s" % b.stderr[-400:])
            return 1
        inst = adb(args.serial, "install", "-r", str(APK))
        print("[APPLY] install rc=%d %s" % (
            inst.returncode, inst.stdout.decode("utf-8", "replace").strip()[-120:]),
            flush=True)
        if inst.returncode != 0:
            print("[FAIL] 安装失败")
            return 1

        cleared = clear_target_cache(args.serial)
        print("[APPLY] 已清缓存组：%s" % cleared, flush=True)

        main_cell = run_probe(args.serial, MAIN_SAMPLE, args.wait,
                              "analysis/l3-f4-data-kind-ab-main.json",
                              args.warmup)
        main_cell["sample"] = MAIN_SAMPLE
        print("[APPLY] 主判据：codec=%s write=%s -103=%s commit=%s" % (
            main_cell.get("codec"), main_cell.get("write_start_play_nerr"),
            main_cell.get("minus_103_occurrences"), main_cell.get("commit_values")),
            flush=True)

        for ext, sample in REGRESSION:
            cell = run_probe(args.serial, sample, 12.0,
                             "analysis/l3-f4-data-kind-ab-%s.json" % ext,
                             args.warmup)
            cell["sample"] = sample
            cell["format"] = ext
            regression.append(cell)
            print("[APPLY] 回归 %s：codec=%s write=%s -103=%s" % (
                ext, cell.get("codec"), cell.get("write_start_play_nerr"),
                cell.get("minus_103_occurrences")), flush=True)
    finally:
        shutil.copyfile(backup, SRC)
        rb = gradle(":app:assembleDebug")
        baseline_rebuild_ok = rb.returncode == 0
        if baseline_rebuild_ok:
            adb(args.serial, "install", "-r", str(APK))
        restored = SRC.read_text(encoding="utf-8") == backup.read_text(encoding="utf-8")
        print("[RESTORE] 源码逐字节恢复=%s / 基线重编译=%s" % (
            restored, baseline_rebuild_ok), flush=True)
        backup.unlink(missing_ok=True)

    main_pass = (
        bool(main_cell.get("codec"))
        and main_cell.get("audio_output") is True
        and main_cell.get("minus_103_occurrences") == 0)
    regression_pass = all(
        bool(c.get("codec")) and c.get("audio_output") is True
        and c.get("minus_103_occurrences") == 0
        for c in regression) if regression else False
    chain_ok = (bool(main_cell) and main_cell.get("chain_started") is True
                and all(c.get("chain_started") is True for c in regression))
    if not chain_ok:
        verdict = ("NO_VALID_CRITERION（至少一格只有 prepare、取流/解码链未展开："
                   "不得写「假设证伪」也不写「PASS」；需复核冷启动/注入时机或变体是否"
                   "导致 prepare 后停摆）")
    elif main_pass:
        verdict = "PRIMARY_MET（变体 D 消除 -103 并出现 Write start play；仍需连播/seek/ENDED 判据，F4 不收口）"
    else:
        verdict = "NOT_MET（变体 D 未消除 -103 / 未出声；kind=2 落盘假说未获支持或需进一步定位）"

    result = {
        "method": "12 §61.63 / F4 变体 D：OnDataRequestData kind 1L→2L（normal mode 落盘）",
        "generated_by": "tools/l3_f4_data_kind_ab.py",
        "serial": args.serial,
        "old_call": OLD_CALL,
        "new_call": NEW_CALL,
        "wait_seconds": args.wait,
        "main": main_cell,
        "regression": regression,
        "regression_all_pass": regression_pass,
        "primary_criterion_met": main_pass,
        "verdict": verdict,
        "p0_restored": restored,
        "baseline_rebuild_ok": baseline_rebuild_ok,
        "honest_notes": [
            "变体 D 只临时存在，finally 无条件恢复 P0 基线并重编译重装。",
            "判据以 -103 消除 + Write start play nErr:0 为准；Open codec ≠ 出声（§61.62）。",
            "即使主判据达成，自动进曲 3 首 / seek / ENDED 未测，F4 仍判 BLOCKED。",
            "回归只跑 2 格 REMOTE（mp3/wav），不是 F3 14 格全量矩阵，矩阵未改写。",
            "零 native so 改动、零新增依赖；清的是 app 私有 vfile 缓存，可重建。",
        ],
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    print("[INFO] %s" % verdict)
    return 0 if (restored and baseline_rebuild_ok) else 1


if __name__ == "__main__":
    raise SystemExit(main())
