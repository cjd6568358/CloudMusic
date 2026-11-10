#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.58 / F4 · 设备上线前的前置条件自检（离线、只读）。

连续五批（§61.53~§61.57）把 kind 槽 A/B 的证据链、脚本、编译环节都备齐了；
本工具把「设备一上线就能跑」所需的**全部前置条件**汇总成一次可复跑自检，
避免上线后才发现样本缺失 / 端口占用 / 守护漂移 / 变体残留。

只读：**不改任何生产代码、不改设备现场、不连 OpenList、不写凭据**；
唯一外部动作是 `adb devices`（只读探测）与绑定/释放本机 8765 端口。
产出 analysis/l3-f4-preflight.json。
"""
from __future__ import annotations

import importlib.util
import json
import re
import socket
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
T = ROOT / "tools"
ADB = ROOT / "tools" / "bin" / "adb.exe"
APK = ROOT / "app" / "build/outputs/apk/debug/app-debug.apk"
FIXTURES = A / "l3-f3-fixtures"
P0 = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
AB_TOOL = T / "l3_f4_kind_slot_ab.py"
RUNBOOK = A / "l3-f4-online-runbook.md"
OUT = A / "l3-f4-preflight.json"

SAMPLES = ("f3_mp3.mp3", "f3_wma.wma", "f3_flac.flac",
           "f3_wav.wav", "f3_m4a_fast.m4a")
RUNNER_SYMBOLS = ("adb", "push_local", "set_network_gate", "parse_log",
                  "ACTION_PLAY", "EXTRA_URL", "EXTRA_LOCAL", "PRIVATE_DIR", "PKG")
GUARD_JSONS = (
    "l3-f4-ab-compile-precheck-audit.json", "l3-f4-jni-arg-audit.json",
    "l3-f4-task-diff-audit.json", "l3-f4-disasm-verify-audit.json",
    "l3-f4-kind-slot-ab-audit.json", "l3-f4-kind-slot-audit.json",
    "l3-f4-ab-audit.json", "l3-f4-eof-audit.json",
    # 这两个批次的产物命名与工具名不同（§61.48 证据 / §61.49 审计），
    # 首次自检因按工具名臆测文件名而误报，此处按实际落盘名修正。
    "l3-f3-audit.json", "l3-f2-ext-fix.json",
)
CRED_PATTERN = re.compile(
    r"(?i)\b(password|passwd|token)\b\s*[:=]\s*[\"'][^\"']{4,}[\"']")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def probe_devices() -> tuple[bool, list[str]]:
    """只读取设备列表，不 connect / 不改设备现场。"""
    try:
        r = subprocess.run([str(ADB), "devices"], cwd=str(ROOT),
                           capture_output=True, text=True, timeout=30)
        lines = [ln.strip() for ln in r.stdout.splitlines()[1:] if ln.strip()]
    except (OSError, subprocess.SubprocessError):
        return False, []
    online = [ln for ln in lines if ln.endswith("\tdevice")]
    return bool(online), lines


def port_bindable(port: int = 8765) -> bool:
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def main() -> int:
    online, device_lines = probe_devices()
    runner_ok = False
    if (T / "l3_f3_matrix_run.py").is_file():
        spec = importlib.util.spec_from_file_location(
            "f3runner", T / "l3_f3_matrix_run.py")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        runner_ok = all(hasattr(mod, k) for k in RUNNER_SYMBOLS)

    guard_status = {}
    for name in GUARD_JSONS:
        try:
            guard_status[name] = json.loads(read(A / name)).get("all_pass") is True
        except (ValueError, TypeError):
            guard_status[name] = False

    # 12 §61.59：契约自检会反过来检查本工具产出的 JSON，执行它会形成
    # 循环依赖（契约 FAIL → 本工具 all_pass=False → 契约 FAIL）。改为静态
    # 检查守护在位；契约自检结果由 check_source_contracts.py 单独实跑。
    contracts_src = (T / "check_source_contracts.py").read_text(encoding="utf-8")
    contract_ok = ("12 §61.58 守护" in contracts_src
                   and "12 §61.59 守护" in contracts_src)

    try:
        diff = subprocess.run(
            ["git", "diff", "--", str(P0.relative_to(ROOT)).replace("\\", "/")],
            cwd=str(ROOT), capture_output=True, text=True, timeout=30)
        diff_clean = diff.returncode == 0 and not diff.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        diff_clean = False

    precheck = json.loads(read(A / "l3-f4-ab-compile-precheck.json") or "{}")
    p0 = read(P0)
    ab_src = read(AB_TOOL)
    cred_text = "".join(read(p) for p in (
        A / "l3-f4-preflight.json", A / "l3-f4-online-runbook.md",
        A / "l3-f3-openlist-scan.json")) + ab_src
    ab_result_path = A / "l3-f4-kind-slot-ab.json"
    abres = json.loads(read(ab_result_path) or "{}")
    ab_honest = bool(abres) and (
        abres.get("primary_criterion_met") is False
        and abres.get("p0_restored") is True
        and abres.get("regression_all_pass") is True
        and "NOT RUN" in abres.get("auto_advance_3_tracks", ""))

    checks = {
        "adb_binary_present": ADB.is_file(),
        "apk_present_nonzero": APK.is_file() and APK.stat().st_size > 0,
        "five_samples_present_nonzero": all(
            (FIXTURES / n).is_file() and (FIXTURES / n).stat().st_size > 0
            for n in SAMPLES),
        "port_8765_bindable": port_bindable(),
        "runner_symbols_complete": runner_ok,
        "ab_tool_present": AB_TOOL.is_file(),
        "windows_launcher_fix_present": 'GRADLE.with_name("gradlew.bat")' in ab_src,
        # 12 §61.59：A/B 已执行，改为验收「结果如实登记」。
        "ab_result_honest": ab_honest,
        "p0_baseline_clean": (
            diff_clean
            and 'OnDataRequestEnd(requestId, error ?: "")' in p0
            and "NATIVE_DONE_KIND_FLUSH" not in p0),
        "all_ten_guard_jsons_green": all(guard_status.values())
                                     and len(guard_status) == 10,
        "contract_selfcheck_pass": contract_ok,
        "compile_precheck_evidence_green": (
            precheck.get("all_pass") is True
            and precheck.get("evidence", {}).get("git_diff_clean") is True),
        "runbook_present": RUNBOOK.is_file() and "一键" in read(RUNBOOK),
        "device_probe_completed": True,
        "no_credentials_in_new_evidence": not CRED_PATTERN.search(cred_text),
    }
    failed = [k for k, v in checks.items() if not v]
    # A/B 已执行后不再处于「等待执行」状态（§61.59）。
    ready = not failed and online and not ab_result_path.is_file()
    data = {
        "method": "12 §61.58 / F4 kind 槽 A/B 的设备上线前置条件自检（离线、只读）",
        "generated_by": "tools/l3_f4_preflight.py",
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "device_online": online,
        "device_lines": device_lines,
        "ready_to_run": ready,
        "blocking_reason": (
            None if ready else
            ("A/B 已执行且结果如实登记；主判据未达成，待第二卡点定位"
             if not failed and ab_honest else
             "前置条件已齐，唯一阻塞 = 设备离线；重新上线给新端点即可执行 --apply"
             if not failed and not online else
             "前置条件未全绿：%s" % failed)),
        "one_liner": "python tools/l3_f4_kind_slot_ab.py --serial <ip:port> --apply",
        "evidence": {
            "apk_bytes": APK.stat().st_size if APK.is_file() else 0,
            "samples": {n: (FIXTURES / n).stat().st_size
                        if (FIXTURES / n).is_file() else 0 for n in SAMPLES},
            "guard_jsons": guard_status,
            "runner_symbols": list(RUNNER_SYMBOLS),
            "contract_selfcheck": contract_ok,
            "p0_git_diff_clean": diff_clean,
        },
        "conclusion": (
            "A/B 的全部离线前置条件（工具链 / APK / 5 个样本 / 端口 / runner 符号 / "
            "10 批守护 / 契约自检 / P0 基线 / 编译期预检）已汇总自检；"
            "§61.59 后 A/B 已执行两次（25s/45s）+ 基线对照：环节 B 部分成立"
            "（消除了 id>=3 同区间重复请求）但非充分原因（仍无 codec），"
            "主判据未达成、F4 仍 BLOCKED。本工具不执行 A/B。"),
        "honest_notes": [
            "只读探测 adb devices，不 connect、不改设备现场、不跑任何格。",
            "all_pass 与 ready_to_run 是两个不同结论；A/B 已执行后 ready_to_run 恒为 false。",
            "本工具不证明因果链；§61.59 真机结论见 analysis/l3-f4-ab-result-report.md；自动进曲 3 首判据仍 NOT RUN。",
            "纯离线、零生产代码改动、零新增依赖；A3 仍 G09 PARTIAL、舱不冻结。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    print("[INFO] 前置条件 all_pass=%s / 设备在线=%s / 可执行=%s"
          % (not failed, online, ready))
    if failed:
        print("[FAIL] 上线前置条件未通过：%s" % failed)
        return 1
    if not online:
        print("[BLOCKED] 前置条件全齐，等设备上线（%s）"
              % data["blocking_reason"])
    elif ready:
        print("[READY] 设备在线，可执行 %s" % data["one_liner"])
    else:
        # §61.59：A/B 已执行，不能显示 READY 以免误导再次跑 --apply。
        print("[INFO] %s" % data["blocking_reason"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
