#!/usr/bin/env python
"""B2 设备流水线（§61.86 后续 · 一键装包 + F3 矩阵 + M2 门禁判定）。

用途：MIUI 拒绝无线 adb 静默安装（INSTALL_FAILED_USER_RESTRICTED，
§61.69/§61.86 两度实证），USB 连接不受此限。本脚本把「装包 → F3 22 格 →
M2 门禁判定」收口成一条命令：

    python tools/b2_device_pipeline.py              # 等待任意设备上线（默认 300s）
    python tools/b2_device_pipeline.py --serial X   # 指定端点直接执行

步骤（全部自动，无人工介入）：
  1. `adb devices` 等待设备（无线 mDNS / USB 均可）；
  2. `install -r`（已装同签名包）或全新安装；
  3. 跑 `tools/l3_f3_matrix_run.py`（11 格式 × REMOTE/LOCAL）；
  4. 按 §61.49 基线判 M2 门禁：B2 路线 PASS 数 ≥ 11 即 PASS。

诚实边界：矩阵结果如实登记，不伪造；F4 仍 BLOCKED 与本流水线无关。
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = ROOT / "tools" / "bin" / "adb.exe"
PKG = "com.example.cloudmusic"
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
M2_BASELINE_PASS = 11          # §61.49 A 路线基线（11 PASS / 3 FAIL / 7 NO_SAMPLE）


def adb(serial: str | None, *args: str, timeout: int = 120) -> subprocess.CompletedProcess:
    cmd = [str(ADB)]
    if serial:
        cmd += ["-s", serial]
    return subprocess.run(cmd + list(args), capture_output=True, timeout=timeout)


def wait_device(wait_s: int) -> str | None:
    deadline = time.time() + wait_s
    while time.time() < deadline:
        r = adb(None, "devices")
        for line in r.stdout.decode("utf-8", "replace").splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device" and "._adb-tls-connect._tcp" not in parts[0]:
                return parts[0]          # 优先 USB / 普通 TCP 端点
            if len(parts) >= 2 and parts[1] == "device":
                return parts[0]
        time.sleep(5)
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=None, help="指定端点；缺省等待任意设备上线")
    ap.add_argument("--wait", type=int, default=300, help="等设备秒数（默认 300）")
    ap.add_argument("--matrix-wait", type=float, default=8.0, help="每格注入后等待秒数")
    args = ap.parse_args()

    serial = args.serial or wait_device(args.wait)
    if not serial:
        print("[FAIL] %ds 内无设备上线" % args.wait)
        return 1
    print("[OK] 设备：%s" % serial)

    if not APK.is_file():
        print("[FAIL] APK 不存在：%s" % APK)
        return 1

    st = adb(serial, "shell", "pm", "path", PKG).stdout.decode().strip()
    if st:
        print("[INFO] 包已存在（install -r 更新）")
    else:
        print("[INFO] 全新安装（USB / 已授权通道）")
    r = adb(serial, "install", "-r", str(APK), timeout=300)
    out = (r.stdout + r.stderr).decode("utf-8", "replace").strip()
    print("[INSTALL] %s" % out.splitlines()[-1] if out else "[INSTALL] (无输出)")
    if "Success" not in out:
        print("[FAIL] 安装未成功：%s" % out)
        return 1

    print("[RUN ] F3 矩阵（11 格式 × REMOTE/LOCAL，每格 %.0fs）……" % args.matrix_wait)
    m = subprocess.run(
        [sys.executable, "-I", "-X", "utf8",
         str(ROOT / "tools" / "l3_f3_matrix_run.py"),
         "--serial", serial, "--wait", str(args.matrix_wait)],
        capture_output=True, timeout=1800)
    tail = (m.stdout + m.stderr).decode("utf-8", "replace")
    print(tail[-3000:])

    result_p = ROOT / "analysis" / "l3-f3-matrix.json"
    verdict_line = ""
    if result_p.is_file():
        import json
        d = json.loads(result_p.read_text(encoding="utf-8"))
        counts = d.get("counts", {})
        passed = counts.get("PASS", 0)
        verdict_line = ("M2 门禁：PASS %d / 基线 %d → %s"
                        % (passed, M2_BASELINE_PASS,
                           "PASS（解锁 M3）" if passed >= M2_BASELINE_PASS
                           else "NOT PASSED（如实登记，不进入 M3）"))
        print("\n[VERDICT] %s" % verdict_line)
        print("[DETAIL] counts=%s / engines=%s" % (
            counts,
            sorted({c.get("engine", "?") for c in d.get("cells", [])})))
    else:
        print("[WARN] 矩阵结果 JSON 未生成，请查上方日志")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
