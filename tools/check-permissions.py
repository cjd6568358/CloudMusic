#!/usr/bin/env python3
"""C3 · 权限与 Manifest 基线（docs/03-12 §3 C3 / 03-11 §1.1 / 10 §2 R4）。

检查：合并后 Manifest 权限 ≤ 12、无 PRD §9 禁止项、含 android:allowBackup="false"。
当前 :app 未落地时，dev 模式记 WARN；release 模式阻断。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_common import ROOT, Report, add_mode_arg  # noqa: E402

MANIFEST_CANDIDATES = (
    ROOT / "app" / "src" / "main" / "AndroidManifest.xml",
    ROOT / "app" / "build" / "intermediates" / "merged_manifests" / "release" / "AndroidManifest.xml",
)

# PRD §9 允许清单（11 项，≤12）
ALLOWED = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.WAKE_LOCK",
}

# PRD §9 / 03-11 §1.2 明确不申请
FORBIDDEN = {
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "android.permission.READ_CALENDAR",
    "android.permission.WRITE_CALENDAR",
    "android.permission.READ_PHONE_STATE",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
}

PERMISSION_RE = re.compile(r"<uses-permission\b[^>]*android:name\s*=\s*\"([^\"]+)\"")
BACKUP_RE = re.compile(r"android:allowBackup\s*=\s*\"false\"")


def main() -> int:
    parser = argparse.ArgumentParser(description="C3 · 权限与 allowBackup 检查")
    add_mode_arg(parser)
    args = parser.parse_args()
    report = Report("C3", "Manifest 权限 ≤12 / 禁止项 / allowBackup=false", args.mode)

    manifest = next((p for p in MANIFEST_CANDIDATES if p.is_file()), None)
    if manifest is None:
        report.warn(
            "未找到 :app 的 AndroidManifest.xml（本轮只落地纯自研模块）；"
            "release 前须含 android:allowBackup=\"false\"、minSdk=26 / targetSdk=35、abiFilters arm64-v8a"
        )
        return report.emit()

    text = manifest.read_text(encoding="utf-8", errors="replace")
    found = PERMISSION_RE.findall(text)
    if len(found) > 12:
        report.fail(f"权限 {len(found)} 项，超过 12 项上限")
    else:
        report.ok(f"权限 {len(found)} 项 ≤ 12")

    unexpected = sorted(set(found) - ALLOWED)
    forbidden = sorted(set(found) & FORBIDDEN)
    if unexpected:
        report.fail(f"PRD §9 允许清单之外的权限：{unexpected}")
    if forbidden:
        report.fail(f"明确禁止的权限：{forbidden}")
    if not unexpected and not forbidden:
        report.ok("全部权限均在 PRD §9 允许清单内")

    if BACKUP_RE.search(text):
        report.ok('android:allowBackup="false" 已设置（R4 拍板基线）')
    else:
        report.fail('合并后 Manifest 缺少 android:allowBackup="false"：明文凭据会随系统备份上云')
    return report.emit()


if __name__ == "__main__":
    raise SystemExit(main())
