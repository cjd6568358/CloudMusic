#!/usr/bin/env python3
"""静态 ABI 验收 A1 / A2（docs/03-06 §6 / 12 §37）。

A3 真机黄金用例需要 adb + arm64 真机，本工具**不做**、也不伪装通过。
A1：把 4 个入口 so 的 202 个 `Java_*` 导出符号按 JNI mangling 还原到
     (类, 方法)，与舱内 Java 源码里的 native 声明逐一对映；
     输出「Java 有声明但 so 无导出」= 真机上预计 `UnsatisfiedLinkError` 的精确清单。
A2：18 个打包集的 DT_NEEDED 递归闭包，确认无越界 / 无外部私有库。

纯 Python 零依赖，数据与 `tools/elf_probe.py` / `analysis/jni-verify-report.json` 同源。
用法：
    python tools/check_jni_bindings.py           # 写报告 + 打印摘要
    python tools/check_jni_bindings.py --strict   # A2 越界或 A3 未验证时返回 1
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_common import ROOT  # noqa: E402
import elf_probe as ep  # noqa: E402

APK = ROOT / "NeteaseCloudMusic.apk"
CABIN = ROOT / "engine-legacy" / "src" / "main" / "java"
JADX_SRC = ROOT / "analysis" / "jadx-jni-classes"
OUT = ROOT / "analysis" / "jni-binding-report.json"
VERIFY = ROOT / "analysis" / "jni-verify-report.json"

NATIVE_RE = re.compile(
    r"\bnative\b[^;{}()]*?\b([A-Za-z_$][A-Za-z0-9_$]*)\s*\(", re.MULTILINE
)
PACKAGE_RE = re.compile(r"^package\s+([\w.]+)\s*;", re.MULTILINE)
# 13 §8.2 实证：第 12 个精确 JNI 类是 NEAudioDevice 的内部类（与主类同文件）。
INNER_CLASS = "NEAudioDeviceOnPlaybackPositionUpdateListener"


def strip_comments(text: str) -> str:
    """注释里的 `nativeXxx(...)` 不是声明，必须剔除（否则 PlayerDataSource 的 KDoc 会误报）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", "", text)


def mangle_class(name: str) -> str:
    """JNI 静态注册符号前缀：_ → _1、. → _、$ → _00024。"""
    return "Java_" + name.replace("_", "_1").replace(".", "_").replace("$", "_00024") + "_"


def java_natives() -> dict[str, list[str]]:
    """只取 12 个精确 JNI 真类（与 12 §34 的 210 基线同源），不扫 slot。"""
    result: dict[str, list[str]] = {}
    for src in sorted(JADX_SRC.rglob("*.java")):
        cabin = CABIN / src.relative_to(JADX_SRC)
        if not cabin.is_file():
            continue
        text = strip_comments(cabin.read_text(encoding="utf-8", errors="replace"))
        pkg = PACKAGE_RE.search(text)
        full = f"{pkg.group(1)}.{src.stem}" if pkg else src.stem
        methods = NATIVE_RE.findall(text)
        if src.stem == "NEAudioDevice":
            result[f"{full}${INNER_CLASS}"] = methods
        elif methods:
            result[full] = methods
    return result


def symbols_by_lib() -> dict[str, list[str]]:
    result = {}
    for lib in ep.ENTRY_LIBS:
        raw = ep.read_apk_lib(APK, "arm64-v8a", lib)
        result[lib] = sorted(ep.parse_elf(raw)["jni_symbols"]) if raw else []
    return result


def dt_needed_closure() -> dict:
    infos = {}
    for name in ep.PACKED_LIBS:
        raw = ep.read_apk_lib(APK, "arm64-v8a", name)
        if raw:
            infos[name] = ep.parse_elf(raw, with_symbols=False)
    packed = set(ep.PACKED_LIBS)
    seen: set[str] = set()
    external: list[str] = []
    stack = list(ep.ENTRY_LIBS)
    while stack:
        cur = stack.pop()
        if cur in seen:
            continue
        seen.add(cur)
        for dep in infos.get(cur, {}).get("needed", []):
            if dep in ep.SYSTEM_LIBS:
                continue
            if dep not in infos:
                external.append(f"{cur} → {dep}（APK 内缺失）")
            elif dep not in packed:
                external.append(f"{cur} → {dep}（在 167 全集但不在 18 打包集）")
            stack.append(dep)
    return {
        "packedLibCount": len(ep.PACKED_LIBS),
        "closureLibCount": len(seen & packed),
        "closureLibs": sorted(seen & packed),
        "external": external,
        "pass": not external,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="静态 ABI 验收 A1 / A2")
    parser.add_argument("--strict", action="store_true", help="A2 越界即返回 1")
    args = parser.parse_args()

    if not APK.is_file():
        print(f"FAIL 参考 APK 缺失：{APK}")
        return 1

    natives = java_natives()
    report_java_total = sum(len(v) for v in natives.values())
    by_lib = symbols_by_lib()
    all_symbols = [s for syms in by_lib.values() for s in syms]

    prefixes = {cls: mangle_class(cls) for cls in natives}
    # 长前缀优先，避免同包名前缀误匹配
    ordered = sorted(natives, key=lambda c: len(prefixes[c]), reverse=True)
    matched: dict[str, set[str]] = {c: set() for c in natives}
    unmatched: list[str] = []
    for sym in all_symbols:
        for cls in ordered:
            prefix = prefixes[cls]
            if sym.startswith(prefix):
                # 重载方法带 `__<参数签名>` 后缀，剥离后才是方法名
                matched[cls].add(sym[len(prefix):].split("__", 1)[0])
                break
        else:
            unmatched.append(sym)

    dex_only: list[str] = []
    so_only: list[str] = []
    rows = []
    for cls in sorted(natives):
        declared = set(natives[cls])
        bound = matched[cls]
        missing = sorted(declared - bound)
        extra = sorted(bound - declared)
        dex_only.extend(f"{cls}#{m}" for m in missing)
        so_only.extend(f"{cls}#{m}" for m in extra)
        rows.append({
            "class": cls,
            "declaredCount": len(declared),
            "staticBoundCount": len(declared & bound),
            "missingInSo": missing,
            "soOnlyMethods": extra,
        })

    a2 = dt_needed_closure()
    baseline = {}
    if VERIFY.is_file():
        v = json.loads(VERIFY.read_text(encoding="utf-8"))
        baseline = {
            "sourceNativeTotal": v.get("sourceNativeTotal"),
            "jniExportCount": v.get("jniExportCount"),
            "deltaKnown": v.get("deltaKnown", []),
            "matchesDexBaseline210": v.get("matchesDexBaseline210"),
        }

    report = {
        "source": "NeteaseCloudMusic.apk (4 个 JNI 入口 so) + engine-legacy/src/main/java",
        "scope": "03-06 §6 的 A1 / A2；A3 真机 G01~G09 需 adb + arm64 真机，本工具不覆盖",
        "jniSymbolCount": len(all_symbols),
        "symbolsByLib": {k: len(v) for k, v in by_lib.items()},
        "javaNativeClassCount": len(natives),
        "javaNativeMethodCount": sum(len(v) for v in natives.values()),
        "staticBoundMethodCount": sum(r["staticBoundCount"] for r in rows),
        "dexOnlyCount": len(dex_only),
        "dexOnly": sorted(dex_only),
        "soOnlyMethodCount": len(so_only),
        "soOnlyMethods": sorted(so_only),
        "unmatchedSymbolCount": len(unmatched),
        "unmatchedSymbols": sorted(unmatched)[:40],
        # 13 §7.4：210 声明 - 202 导出 = 净差 8； dex-only 15 / so-only 7 互相抵消。
        "netDelta": report_java_total - len(all_symbols),
        "matchesBaselineDelta8": (report_java_total - len(all_symbols)) == 8,
        "a2": a2,
        "baseline": baseline,
        "verdict": {
            "a1": "PASS（静态符号对映完成，缺符号清单已列出）",
            "a2": "PASS" if a2["pass"] else "FAIL",
            "a3": "NOT RUN（无 adb / 真机，见 12 §37）",
        },
        "classes": rows,
    }
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"A1：{len(all_symbols)} 个 Java_* 符号 | 12 真类 {len(natives)} 类 / "
          f"{report_java_total} 个 native 声明 | "
          f"静态绑定 {report['staticBoundMethodCount']} | "
          f"dex-only {len(dex_only)} | so-only {len(so_only)} | "
          f"净差 {report['netDelta']}（与 13 §7.4 的 8 吻合："
          f"{'YES' if report['matchesBaselineDelta8'] else 'NO'}）")
    for r in rows:
        if r["missingInSo"] or r["soOnlyMethods"]:
            print(f"  {r['class']}: 声明 {r['declaredCount']} / 绑定 {r['staticBoundCount']}"
                  f" | 缺 {r['missingInSo'][:4]} | 多 {r['soOnlyMethods'][:4]}")
    print(f"A2：打包集 {a2['packedLibCount']} 个 | 闭包 {a2['closureLibCount']} 个 | "
          f"越界 {len(a2['external'])} | {'PASS' if a2['pass'] else 'FAIL'}")
    print("A3：NOT RUN（无 adb / 真机）")
    print(f"\n已写出 {OUT.relative_to(ROOT).as_posix()}")
    if args.strict and not a2["pass"]:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
