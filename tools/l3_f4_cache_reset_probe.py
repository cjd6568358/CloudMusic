#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.63 / F4 后续：验证「陈旧 vfile 切片被反复 Use Old 复用」假说。

§61.62 的 60 s 观测 + 设备侧 .nmsfi 索引一手取证给出：
  - 自洽样本 f3_m4a_complete.m4a 的 vfile 只有 slices[0].trunks
    `[0, 368640)`，其余 15 个分片全空；`_0.nmsf` 大小正好 368640 B，
    时间戳是**前一晚 23:51**（即 §61.61 实验留下的陈旧缓存）。
  - 对比：mp3 / flac / wav 的 vfile 索引有多个**全满** 1 MiB trunk
    → vfile 落盘机制本身能正常工作。
  - 因此提出可证伪假说：F4 卡在 368640 是「同一份陈旧切片被反复
    `Use Old` 复用、后续回传数据不再落盘」，而不是代码层不会写。

本脚本**零生产代码改动**：导出清缓存前后的 .nmsfi 索引作为证据，
删除目标资源的缓存组（app 私有缓存、可重建，样本仍在 files/music/），
再复用 `tools/l3_f4_vfile_commit_probe.py` 跑一次同样条件 REMOTE 观测，
对比 trunk 增长 / commit / 播放链判据。

用法：
  python tools/l3_f4_cache_reset_probe.py --serial <ip:port> [--wait 60]
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = ROOT / "tools" / "bin" / "adb.exe"
A = ROOT / "analysis"
PKG = "com.example.cloudmusic"
PROBE_TOOL = ROOT / "tools" / "l3_f4_vfile_commit_probe.py"
OUT_INDEX_BEFORE = A / "l3-f4-vfile-index-before.json"
OUT_INDEX_AFTER = A / "l3-f4-vfile-index-after.json"
OUT_PROBE = A / "l3-f4-cache-reset-probe.json"
OUT_VERDICT = A / "l3-f4-cache-reset-verdict.json"

RE_SLICE_INIT = re.compile(r"NMSliceFile Open Init (\w+(?: \w+)*):(\S+)")
RE_COMMIT = re.compile(r"commit :\s*(\d+)")
RE_JUMP_FAIL = re.compile(r"offset:605007, readed_len:0, result:-2")
RE_CODEC = re.compile(r"Open codec for (\w+)")
RE_WRITE = re.compile(r"Write start play nErr:(\d+)")
RE_STATUS = re.compile(r"Play status (\d+)")
RE_WAIT_FORCE = re.compile(
    r"WaitRequest offset:605007, length:\d+, signal_size:\d+, force:true")


def adb(serial: str, *args: str, timeout: int = 90) -> subprocess.CompletedProcess:
    return subprocess.run(
        [str(ADB), "-s", serial, *args], capture_output=True, timeout=timeout)


def cat_app_file(serial: str, rel: str) -> str:
    r = subprocess.run(
        [str(ADB), "-s", serial, "exec-out",
         "run-as %s cat %s" % (PKG, rel)],
        capture_output=True, timeout=60)
    return r.stdout.decode("utf-8", "replace")


def list_indexes(serial: str) -> list[str]:
    r = adb(serial, "shell", "run-as %s sh -c "
            "'ls cache/legacy_engine_cache/*/*.nmsfi 2>/dev/null'" % PKG)
    return [x.strip() for x in
            r.stdout.decode("utf-8", "replace").splitlines() if x.strip()]


def dump_indexes(serial: str, out: Path, target_size: int) -> dict:
    entries = []
    for rel in list_indexes(serial):
        raw = cat_app_file(serial, rel)
        try:
            doc = json.loads(raw)
        except (ValueError, TypeError):
            doc = {"_raw": raw[:400]}
        entries.append({"path": rel, "index": doc})
    target = [e for e in entries
              if e["index"].get("file_size") == target_size]
    payload = {
        "serial": serial,
        "target_file_size": target_size,
        "index_count": len(entries),
        "entries": entries,
        "target_entries": target,
    }
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    return payload


def trunk_summary(entry: dict) -> list[dict]:
    idx = entry.get("index", {})
    return [
        {"slice_offset": s.get("offset"),
         "trunks": s.get("trunks", [])}
        for s in idx.get("slices", []) if s.get("trunks")]


def trunk_bytes(groups: list[list[dict]]) -> int:
    """清缓存后是新建文件，不能只看 JSON 是否变化；须比较实际落盘总字节数，
    否则 368640 → 180224（减少）会被误判成「增长」。"""
    return sum(t.get("size", 0)
               for group in groups for s in group
               for t in s.get("trunks", []))


def main() -> int:
    ap = argparse.ArgumentParser(description="F4 陈旧 vfile 切片假说验证")
    ap.add_argument("--serial", default="10.0.0.2:39737")
    ap.add_argument("--wait", type=float, default=60.0)
    ap.add_argument("--sample", default="f3_m4a_complete.m4a")
    ap.add_argument("--file-size", type=int, default=16573387)
    ap.add_argument("--skip-device", action="store_true",
                    help="只做离线复核，不连 adb（需已有 before/after/probe 产物）")
    args = ap.parse_args()

    if not args.skip_device:
        state = adb(args.serial, "get-state").stdout.decode().strip()
        if state != "device":
            print("[FAIL] 设备不在线（get-state=%s）" % state)
            return 1

        before = dump_indexes(args.serial, OUT_INDEX_BEFORE, args.file_size)
        print("[INFO] before: %d 个索引，命中目标 %d 个；trunk=%s"
              % (before["index_count"], len(before["target_entries"]),
                 [trunk_summary(e) for e in before["target_entries"]]), flush=True)

        cleared = []
        for e in before["target_entries"]:
            rel = e["path"]
            base = rel[:-6]  # 去掉 ".nmsfi"
            adb(args.serial, "shell", "run-as %s sh -c "
                "'rm -f %s.nmsfi %s_*.nmsf'" % (PKG, base, base))
            cleared.append(base)
        print("[INFO] 已删除 app 私有缓存组：%s" % cleared, flush=True)

        cmd = [sys.executable, str(PROBE_TOOL), "--serial", args.serial,
               "--wait", str(args.wait), "--sample", args.sample,
               "--out", str(OUT_PROBE.relative_to(ROOT)).replace("\\", "/")]
        print("[INFO] 复跑观测：%s" % " ".join(cmd[1:]), flush=True)
        rc = subprocess.run(cmd, cwd=str(ROOT), timeout=args.wait + 180).returncode
        if rc != 0:
            print("[FAIL] 观测脚本 rc=%d" % rc)
            return 1

        after = dump_indexes(args.serial, OUT_INDEX_AFTER, args.file_size)
        print("[INFO] after: %d 个索引，命中目标 %d 个；trunk=%s"
              % (after["index_count"], len(after["target_entries"]),
                 [trunk_summary(e) for e in after["target_entries"]]), flush=True)
    else:
        before = json.loads(OUT_INDEX_BEFORE.read_text(encoding="utf-8"))
        after = json.loads(OUT_INDEX_AFTER.read_text(encoding="utf-8"))

    probe = json.loads(OUT_PROBE.read_text(encoding="utf-8"))
    log_path = ROOT / probe["log_file"]
    text = log_path.read_text(encoding="utf-8", errors="replace")

    inits = RE_SLICE_INIT.findall(text)
    commits = [int(x) for x in RE_COMMIT.findall(text)]
    codecs = RE_CODEC.findall(text)
    writes = RE_WRITE.findall(text)
    statuses = RE_STATUS.findall(text)
    jump_fails = len(RE_JUMP_FAIL.findall(text))
    force_n = len(RE_WAIT_FORCE.findall(text))
    m103 = text.count("-103")

    before_trunks = [trunk_summary(e) for e in before.get("target_entries", [])]
    after_trunks = [trunk_summary(e) for e in after.get("target_entries", [])]
    before_bytes = trunk_bytes(before_trunks)
    after_bytes = trunk_bytes(after_trunks)
    # 新建文件的 trunk 数值变化不等于「落盘改善」；只认总字节数真实增加。
    trunk_grew = after_bytes > before_bytes
    audio_out = "0" in writes
    no_103 = m103 == 0

    if audio_out and no_103 and trunk_grew:
        verdict = "SUPPORTED（清缓存后 vfile 重建、数据落盘、出现 Write start play 且无 -103）"
    elif trunk_grew and not audio_out:
        verdict = "PARTIAL（trunk 有增长但未出声，落盘改善不等于判据通过）"
    elif not trunk_grew and commits and max(commits) < 605007:
        verdict = ("RULED_OUT（清缓存后新建 vfile 的落盘总字节数 %d → %d 未增加、"
                   "commit 仍停在 %d < 605007，且仍 -103 未出声 → "
                   "陈旧切片复用不是 F4 的根因，落盘失败另有原因）"
                   % (before_bytes, after_bytes, max(commits)))
    else:
        verdict = "INCONCLUSIVE（证据不足，需人工复核日志）"

    result = {
        "method": "12 §61.63 / F4 陈旧 vfile 切片假说：清缓存前后索引对比 + 60s REMOTE 复跑",
        "generated_by": "tools/l3_f4_cache_reset_probe.py",
        "serial": args.serial,
        "sample": args.sample,
        "target_file_size": args.file_size,
        "wait_seconds": args.wait,
        "log_file": probe.get("log_file"),
        "log_lines": len(text.splitlines()),
        "cache_indexes": {
            "before_file": str(OUT_INDEX_BEFORE.relative_to(ROOT)).replace("\\", "/"),
            "after_file": str(OUT_INDEX_AFTER.relative_to(ROOT)).replace("\\", "/"),
            "before_target_trunks": before_trunks,
            "after_target_trunks": after_trunks,
            "before_trunk_bytes": before_bytes,
            "after_trunk_bytes": after_bytes,
            "trunk_grew": trunk_grew,
            "cleared": [e["path"] for e in before.get("target_entries", [])],
        },
        "evidence": {
            "slice_init_lines": inits,
            "commit_values": commits,
            "commit_max": max(commits) if commits else None,
            "jump_read_failures": jump_fails,
            "force_wait_requests": force_n,
            "open_codec": codecs,
            "write_start_play_nerr": writes,
            "play_status": statuses,
            "minus_103_occurrences": m103,
        },
        "verdict": verdict,
        "criteria": {
            "audio_output": audio_out,
            "no_minus_103": no_103,
            "trunk_grew": trunk_grew,
        },
        "honest_notes": [
            "删除的是 app 私有 engine 缓存（可从 files/music/ 样本重建），不是用户数据；"
            "删除前已导出 .nmsfi 索引作为证据。",
            "零生产代码改动、未重编译、未重装 APK；P0 引擎舱不变。",
            "即使出现 Write start play，自动进曲 3 首 / seek / ENDED 仍未测，F4 不收口。",
            "清缓存改变的是运行状态而非代码；若 PASS 只说明陈旧切片是直接诱因，"
            "不等于 native / Java 层无需修复。",
            "本实验只跑一次，未做多次稳定性复验。",
        ],
    }
    OUT_VERDICT.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("[INFO] 输出 %s" % OUT_VERDICT.relative_to(ROOT))
    print("[INFO] 判读：%s" % verdict)
    print("[INFO] commit=%s / trunk_grew=%s / codec=%s / write=%s / -103=%d"
          % (commits, trunk_grew, codecs, writes, m103))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
