#!/usr/bin/env python3
"""C1~C6 串联入口（docs/03-12 §3 / §11）。

Gradle 侧：./gradlew preReleaseCheck → 调用本脚本
本机直跑：python tools/pre_release_check.py [--mode dev|release]

dev 模式（默认）：未落地的 release 前置（jniLibs / Manifest / FINGERPRINT）只记 WARN；
release 模式：WARN 升级为 FAIL，任一检查非零即阻断。
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"

CHECKS: tuple[tuple[str, str], ...] = (
    ("C1", "check-engine-abi.py"),
    ("C2", "check-no-netease-import.py"),
    ("C3", "check-permissions.py"),
    ("C4", "check-assets.py"),
    ("C5", "check-credentials.py"),
    ("C6", "check-fingerprint.py"),
)


def main() -> int:
    parser = argparse.ArgumentParser(description="C1~C6 静态检查串联")
    parser.add_argument("--mode", choices=["dev", "release"], default="dev")
    args = parser.parse_args()

    print(
        f"CloudMusic preReleaseCheck · mode={args.mode} · python={sys.version.split()[0]}",
        flush=True,
    )
    results: list[tuple[str, int]] = []
    for check_id, script in CHECKS:
        path = TOOLS / script
        if not path.is_file():
            print(f"[{check_id}] FAIL 脚本缺失：{script}")
            results.append((check_id, 1))
            continue
        proc = subprocess.run(
            [sys.executable, str(path), "--mode", args.mode],
            cwd=ROOT,
            check=False,
        )
        results.append((check_id, proc.returncode))

    print("\n" + "=" * 62)
    print("汇总")
    print("=" * 62)
    for check_id, rc in results:
        print(f"  {check_id}: {'PASS' if rc == 0 else 'FAIL'}")
    failed = [cid for cid, rc in results if rc != 0]
    if failed:
        print(f"\n结果：FAIL（{', '.join(failed)}），release 已阻断")
        return 1
    print(f"\n结果：PASS（{len(results)}/{len(results)}）；"
          f"{'release 允许' if args.mode == 'release' else 'dev 静态自检，见各检查的 WARN 项'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
