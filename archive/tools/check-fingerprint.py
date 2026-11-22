#!/usr/bin/env python3
"""C6 · ENGINE-FINGERPRINT 校验（docs/03-12 §3 C6 / §4）。

指纹基准是 APK 内 lib/arm64-v8a/ 的 18 个打包集原始字节（不是解包产物）。
--emit 只打印 sha256sum 格式清单到 stdout，不写文件，供生成指纹时重定向使用。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_common import ROOT, Report, add_mode_arg, sha256_bytes  # noqa: E402
import elf_probe as ep  # noqa: E402

APK = ROOT / "NeteaseCloudMusic.apk"
FINGERPRINT_CANDIDATES = (
    ROOT / "assets" / "ENGINE-FINGERPRINT",
    ROOT / "app" / "src" / "main" / "assets" / "ENGINE-FINGERPRINT",
)


def compute() -> dict[str, str]:
    result: dict[str, str] = {}
    for name in ep.PACKED_LIBS:
        raw = ep.read_apk_lib(APK, "arm64-v8a", name)
        if raw is not None:
            result[name] = sha256_bytes(raw)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="C6 · ENGINE-FINGERPRINT 校验")
    add_mode_arg(parser)
    parser.add_argument("--emit", action="store_true", help="打印 18 个 so 的 sha256 清单（不写文件）")
    args = parser.parse_args()

    if not APK.exists():
        print(f"[C6] FAIL 参考 APK 缺失：{APK}")
        return 1

    actual = compute()
    if args.emit:
        print("# ENGINE-FINGERPRINT · NeteaseCloudMusic 9.6.20 · lib/arm64-v8a · 18 个打包集")
        for name, digest in actual.items():
            print(f"{digest}  {name}")
        return 0

    report = Report("C6", "ENGINE-FINGERPRINT 与实际 so 一致", args.mode)
    if len(actual) != len(ep.PACKED_LIBS):
        report.fail(f"只解析到 {len(actual)}/{len(ep.PACKED_LIBS)} 个打包集 so")
        return report.emit()

    fingerprint = next((p for p in FINGERPRINT_CANDIDATES if p.is_file()), None)
    if fingerprint is None:
        report.warn("ENGINE-FINGERPRINT 尚未生成（路线 A 已拍板，后期开工时按 12 §10 生成）；可用 --emit 输出清单")
        return report.emit()

    recorded: dict[str, str] = {}
    for line in fingerprint.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) >= 2 and len(parts[0]) == 64:
            recorded[parts[-1]] = parts[0]

    missing = [n for n in ep.PACKED_LIBS if n not in recorded]
    mismatch = [n for n, digest in actual.items() if n in recorded and recorded[n] != digest]
    extra = [n for n in recorded if n not in ep.PACKED_LIBS]
    if missing:
        report.fail(f"指纹缺条目：{missing}")
    if mismatch:
        report.fail(f"指纹不一致：{mismatch}")
    if extra:
        report.warn(f"指纹含打包集之外的条目：{extra}")
    if not (missing or mismatch):
        report.ok(f"{len(actual)} 个 so 的 SHA-256 与指纹一致")
    return report.emit()


if __name__ == "__main__":
    raise SystemExit(main())
