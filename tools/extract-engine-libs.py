#!/usr/bin/env python3
"""按 03-12 §4 从参考 APK 提取 18 个 arm64 so 到 :engine-legacy/jniLibs。

背景：apktool 解包产物 analysis/decoded/lib/arm64-v8a/ 只剩 1 个文件，
APK 包内的 lib/arm64-v8a/ 才是唯一完整来源（167 个 / 125.6 MiB）。

默认 **dry-run**，只有 --apply 才写文件。引擎舱路线已于 2026-11-05 拍板为
**A（薄门面 + 边界 slot，slot 后期按需实现；11 §6 / 12 §10）**：本轮不执行；
后期开工时按 12 §10.2 步骤 1 运行。若 A 时间盒失败转 C，则无需任何竞品 so。

用法：
    python tools/extract-engine-libs.py                  # 预演，只打印
    python tools/extract-engine-libs.py --apply          # 提取 18 个 so
    python tools/extract-engine-libs.py --apply --fingerprint-out assets/ENGINE-FINGERPRINT
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_common import ROOT, sha256_bytes  # noqa: E402
import elf_probe as ep  # noqa: E402

APK = ROOT / "NeteaseCloudMusic.apk"
DEST = ROOT / "engine-legacy" / "src" / "main" / "jniLibs" / "arm64-v8a"


def main() -> int:
    parser = argparse.ArgumentParser(description="提取 18 个 arm64 so（默认 dry-run）")
    parser.add_argument("--apply", action="store_true", help="实际写入 jniLibs")
    parser.add_argument("--fingerprint-out", type=Path, help="同时写出 sha256sum 格式指纹")
    args = parser.parse_args()

    if not APK.exists():
        print(f"FAIL 参考 APK 缺失：{APK}")
        return 1

    payload: dict[str, bytes] = {}
    problems: list[str] = []
    for name in ep.PACKED_LIBS:
        raw = ep.read_apk_lib(APK, "arm64-v8a", name)
        if raw is None:
            problems.append(f"缺文件 {name}")
            continue
        try:
            info = ep.parse_elf(raw, with_symbols=False)
        except ValueError as exc:
            problems.append(f"{name}: {exc}")
            continue
        if info["machine"] != 183:
            problems.append(f"{name}: machine={info['machine']} 非 AARCH64")
        payload[name] = raw

    # DT_NEEDED 闭包必须全部落在打包集或系统库白名单内
    known: dict[str, dict] = {}
    for name, raw in payload.items():
        known[name] = ep.parse_elf(raw, with_symbols=False)
    stack, seen = list(ep.ENTRY_LIBS), set()
    while stack:
        cur = stack.pop()
        if cur in seen:
            continue
        seen.add(cur)
        for dep in known.get(cur, {}).get("needed", []):
            if dep not in ep.SYSTEM_LIBS and dep not in payload:
                problems.append(f"闭包越界：{cur} → {dep}")
            stack.append(dep)

    if problems:
        print("FAIL 预检未通过，未写入任何文件：")
        for p in problems:
            print(f"  - {p}")
        return 1

    total = sum(len(v) for v in payload.values()) / 1048576
    print(f"预检通过：{len(payload)} 个 so / {total:.1f} MiB，DT_NEEDED 闭包无越界")
    print(f"目标目录：{DEST.relative_to(ROOT).as_posix()}")
    for name, raw in payload.items():
        print(f"  {sha256_bytes(raw)[:16]}…  {name:36s} {len(raw) / 1024:8.1f} KB")

    if not args.apply:
        print("\n[dry-run] 未写入。路线 A 已拍板；后期开工时按 12 §10.2 加 --apply 执行。")
        return 0

    DEST.mkdir(parents=True, exist_ok=True)
    for name, raw in payload.items():
        (DEST / name).write_bytes(raw)
    print(f"\n已写入 {len(payload)} 个 so 到 {DEST}")

    if args.fingerprint_out:
        out: Path = args.fingerprint_out
        if not out.is_absolute():
            out = ROOT / out
        out.parent.mkdir(parents=True, exist_ok=True)
        lines = [
            "# ENGINE-FINGERPRINT · NeteaseCloudMusic 9.6.20 · lib/arm64-v8a · 18 个打包集",
            *(f"{sha256_bytes(raw)}  {name}" for name, raw in payload.items()),
        ]
        out.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"指纹已写出：{out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
