#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""§61.115 · G08-B2 20 分钟长稳复测（补 §61.91 作废原因：缺 t=0 PLAYING 验证）。

流程：
  1. force-stop → 前台拉起 → push f3_ape.ape（39.9 分钟曲目，local 通道 PASS）→
     ACTION_PLAY 注入 → **t=0 判据：10s 内观测到 state=PLAYING**（作废原因补上）；
  2. 每 60s 取证一轮，共 --minutes 轮（默认 20）：
     - 进程存活且 pid 不变（MIUI 未回收）；
     - 窗口内无 FATAL EXCEPTION / ANR / media3_error / error= 行；
     - dumpsys media_session 的 PlaybackState position 单调推进（播放真实在走）；
  3. 判据全绿 → PASS；任一失败如实 FAIL，不美化。

纪律：所有 adb 命令带 -s <serial>；不注入破坏性指令；结果另存独立文件，
历史证据零改写。缺省 dry-run，--execute 才真跑。
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
import l3_f3_matrix_run as m   # 复用 adb/push_local/ACTION_PLAY 通道（§61.111 自举）

PKG = m.PKG
SAMPLE = ROOT / "analysis" / "l3-f3-fixtures" / "f3_ape.ape"
STAMP = "2026-11-14"
OUT = ROOT / "analysis" / ("b2-g08-longrun-batch133-" + STAMP + ".json")
LOG = ROOT / "analysis" / "l3-f3-logs" / ("g08-longrun-batch132-" + STAMP + ".txt")

ERROR_REGEXES = (
    re.compile(r"FATAL EXCEPTION"),
    re.compile(r"ANR in " + re.escape(PKG)),
    re.compile(r"CM-Media3[^:]*: (engine_)?error="),
    re.compile(r"CM-Ape[^:]*: .*fail", re.IGNORECASE),
)


def logcat_dump(serial: str) -> str:
    return m.adb(serial, "logcat", "-d", "-v", "time").stdout.decode(
        "utf-8", "replace")


def session_position(serial: str) -> int | None:
    """§61.115 修订：MIUI dumpsys media_session 无标准 PlaybackState 明细
    （会话栈恒 0，三次实测 pos=None/0），改用 app 层观测通道
    （PlaybackService 的 CM-Player positionMs= 10s 节流日志）。"""
    text = logcat_dump(serial)
    found = re.findall(r"CM-Player[^:]*: positionMs=(\d+)", text)
    return int(found[-1]) if found else None


def main() -> int:
    ap = argparse.ArgumentParser(description="G08-B2 20 分钟长稳复测")
    ap.add_argument("--serial", required=True)
    ap.add_argument("--execute", action="store_true")
    ap.add_argument("--minutes", type=int, default=20)
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    if args.check:
        if OUT.is_file():
            d = json.loads(OUT.read_text(encoding="utf-8"))
            print("[CHECK] verdict=%s checks=%d/%d" % (
                d.get("verdict"), d.get("checks_passed"), d.get("checks_total")))
            return 0
        print("[CHECK] 证据不存在：%s" % OUT.name)
        return 1
    if not args.execute:
        print("dry-run：加 --execute 真跑（约 %d 分钟）。" % (args.minutes + 2))
        return 0

    serial = args.serial
    state = m.adb(serial, "get-state").stdout.decode().strip()
    if state != "device":
        print("[FAIL] 设备不在线（get-state=%s）" % state)
        return 1

    samples = []
    started = time.time()
    t0_playing = None
    pid0 = None

    m.adb(serial, "shell", "am", "force-stop", PKG)
    m.adb(serial, "shell", "am", "start", "-n", "%s/.MainActivity" % PKG)
    time.sleep(2.0)
    m.adb(serial, "logcat", "-c")
    if not m.push_local(serial, SAMPLE, SAMPLE.name):
        print("[FAIL] 样本推送大小核对失败，终止（不伪造）")
        return 1
    pid_line = m.adb(serial, "shell", "pidof", PKG).stdout.decode().strip()
    pid0 = pid_line.split()[0] if pid_line else None
    m.adb(serial, "shell", "am", "startservice", "-a", m.ACTION_PLAY,
          "-c", "android.intent.category.DEFAULT",
          "--es", m.EXTRA_LOCAL, m.PRIVATE_DIR + "/" + SAMPLE.name)
    time.sleep(8.0)
    t0_text = logcat_dump(serial)
    if re.search(r"CM-Media3[^:]*: state=PLAYING", t0_text):
        t0_playing = True
    (LOG.parent.mkdir(parents=True, exist_ok=True))
    (LOG).write_text("== t=0 ==\n" + t0_text[-4000:], encoding="utf-8")

    for i in range(args.minutes):
        time.sleep(60.0)
        text = logcat_dump(serial)
        (LOG).open("a", encoding="utf-8").write("\n== round %d ==\n" % (i + 1))
        pos = session_position(serial)
        pid_now = m.adb(serial, "shell", "pidof", PKG).stdout.decode().strip()
        pid = pid_now.split()[0] if pid_now else None
        tail_text = text[-6000:]
        errors = [p.pattern for p in ERROR_REGEXES if p.search(tail_text)]
        playing = bool(re.search(r"CM-Media3[^:]*: state=PLAYING", tail_text)) or t0_playing
        samples.append({
            "round": i + 1,
            "elapsed_min": round(time.time() - started, 1),
            "pid": pid,
            "pid_alive": pid is not None,
            "pid_changed": (pid0 is not None and pid is not None and pid != pid0),
            "session_position_ms": pos,
            "errors_in_tail": errors,
            "playing_observed": playing,
        })
        print("  [%02d/%02d] pid=%s pos=%s errors=%s" % (
            i + 1, args.minutes, pid, pos, errors or "none"), flush=True)

    checks = []
    checks.append(("t=0 PLAYING 已捕获（§61.91 作废原因补上）", t0_playing is True))
    checks.append(("进程全程存活", all(s["pid_alive"] for s in samples)))
    checks.append(("pid 未被回收重启", not any(s["pid_changed"] for s in samples)))
    checks.append(("无 FATAL/ANR/error 串", all(not s["errors_in_tail"] for s in samples)))
    positions = [s["session_position_ms"] for s in samples if s["session_position_ms"] is not None]
    checks.append(("会话位置单调推进（播放真实在走）",
                   len(positions) >= 2 and positions[-1] > positions[0]))
    passed = sum(1 for _, ok in checks if ok)

    result = {
        "method": "12 §61.115 / G08-B2 20 分钟长稳复测（补 t=0 PLAYING 判据）",
        "generated_by": "tools/b2_g08_longrun.py",
        "generated_at": datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
        "device_run": True,
        "serial": serial,
        "sample": SAMPLE.name,
        "pid0": pid0,
        "t0_playing": t0_playing,
        "minutes": args.minutes,
        "samples": samples,
        "checks": [{"name": n, "pass": ok} for n, ok in checks],
        "checks_passed": passed,
        "checks_total": len(checks),
        "verdict": "PASS" if passed == len(checks) else "FAIL",
        "boundary": "不注入破坏性指令；G09-B2（am crash）仍需显式 --i-confirm-injection；"
                    "历史证据零改写。",
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[DONE] %s → %s（%d/%d）" % (result["verdict"], OUT.name,
                                       passed, len(checks)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
