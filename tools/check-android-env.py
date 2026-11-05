#!/usr/bin/env python3
"""Android SDK 就绪度自检 + 仓库「SDK 路径零硬编码」守护（多人协作）。

本脚本零第三方依赖，两种用途：

  python tools/check-android-env.py            解析本机 SDK 并核对组件是否齐全
  python tools/check-android-env.py --guard    扫描受版本控制的文件，禁止写死 SDK 路径

SDK 定位遵循 AGP 标准优先级（与 local.properties.example 一致）：
  1. local.properties 的 sdk.dir      2. ANDROID_HOME      3. ANDROID_SDK_ROOT
脚本不生成、不修改 local.properties，也不把解析结果写入任何文件；
默认输出只显示「解析来源」不显示个人路径，便于安全地贴到 CI 日志 / issue。
需要看实际路径时加 --verbose。

版本号全部从 gradle/libs.versions.toml 读取，不在本脚本重复定义。
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 受「路径零硬编码」守护约束的构建/脚本类文件；文档 md 不在此列（历史叙述另见 15 §7.4）。
GUARD_SUFFIXES = (".kts", ".kt", ".toml", ".properties", ".gradle", ".py", ".yml", ".yaml")

# 用拼接构造，避免这些模式在脚本自身源码里被自己匹配到。
_WIN_ABS = re.compile(r"[A-Za-z]:" + re.escape("\\") + r"(?:Users|Program Files|tools|Android)" + re.escape("\\"))
# 任意盘符绝对路径（正反斜杠均可），覆盖 sdk.dir 之外的写法，如 val sdkRoot = "D:/Android/Sdk"。
_ABS_ANY = re.compile(
    r"[A-Za-z]:[\\/](?:Users|Android|Program(?: Files|Data)?|tools|workspace|sdk)", re.I
)
_SDK_DIR_ABS = re.compile(r"sdk\.dir\s*=\s*[^#\n]*[A-Za-z]:[\\/]")
_UNIX_ABS = re.compile(r"/(?:Users|home)/[A-Za-z0-9._-]+/(?:Library/Android|Android/Sdk)")
_MOUNTED_ABS = re.compile(r"(?:^|[=\"'\s])/[cCdDeEfF]/Users/")


def read_catalog_versions() -> dict[str, str]:
    """从 version catalog 的 [versions] 段读取 compileSdk / minSdk / targetSdk / buildTools。"""
    text = (ROOT / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
    match = re.search(r"^\[versions\](.*?)^\[", text, re.M | re.S)
    block = match.group(1) if match else ""
    return dict(re.findall(r'^\s*(\w+)\s*=\s*"([^"]+)"', block, re.M))


def resolve_sdk() -> tuple[Path | None, str]:
    """返回 (SDK 根目录, 解析来源)。任何一步都不写入文件。"""
    props = ROOT / "local.properties"
    if props.is_file():
        for line in props.read_text(encoding="utf-8", errors="replace").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            key, _, raw = stripped.partition("=")
            if key.strip() != "sdk.dir":
                continue
            value = raw.strip().replace("\\:", ":").replace("\\\\", "\\")
            if value:
                return Path(value), "local.properties (sdk.dir)"
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(var)
        if value:
            return Path(value), f"环境变量 {var}"
    return None, "未解析"


def is_tracked(rel: str) -> bool:
    result = subprocess.run(
        ["git", "ls-files", "--error-unmatch", rel],
        cwd=ROOT, capture_output=True, text=True, check=False,
    )
    return result.returncode == 0


def guard() -> list[str]:
    """守护：仓库内不得出现个人 SDK 绝对路径，且 local.properties 不得被跟踪。"""
    violations: list[str] = []

    if is_tracked("local.properties"):
        violations.append("local.properties 已被 git 跟踪：该文件含个人 SDK 路径，必须 git rm --cached")

    ignore = ROOT / ".gitignore"
    if ignore.is_file() and not re.search(
        r"^\s*local\.properties\s*$", ignore.read_text(encoding="utf-8"), re.M
    ):
        violations.append(".gitignore 缺少 local.properties 规则")

    result = subprocess.run(
        ["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=False,
    )
    for rel in result.stdout.splitlines():
        path = ROOT / rel
        if not path.is_file() or not rel.endswith(GUARD_SUFFIXES):
            continue
        if rel.endswith(".example"):
            continue  # 模板里的路径都是注释示例
        for lineno, line in enumerate(
            path.read_text(encoding="utf-8", errors="replace").splitlines(), start=1
        ):
            stripped = line.lstrip()
            if not stripped or stripped.startswith(("#", "//", "*", "<!--")):
                continue
            if _SDK_DIR_ABS.search(line) or _ABS_ANY.search(line) or \
                    _UNIX_ABS.search(line) or _MOUNTED_ABS.search(line):
                violations.append(f"{rel}:{lineno} 疑似个人绝对路径：{stripped[:100]}")
    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description="Android SDK 环境自检 / 路径守护")
    parser.add_argument("--guard", action="store_true", help="只跑「仓库零硬编码路径」守护")
    parser.add_argument("--verbose", action="store_true", help="显示解析到的本机 SDK 绝对路径")
    args = parser.parse_args()

    violations = guard()
    print("=== [guard] 仓库 SDK 路径零硬编码 ===")
    if violations:
        for item in violations:
            print(f"  [FAIL] {item}")
    else:
        print("  [PASS] 无个人 SDK 路径入库，local.properties 未被跟踪且已被忽略")

    if args.guard:
        return 1 if violations else 0

    versions = read_catalog_versions()
    sdk, source = resolve_sdk()
    print("\n=== [env] 本机 Android SDK ===")
    print(f"  解析来源：{source}")
    if args.verbose and sdk is not None:
        print(f"  路径：{sdk}")
    elif sdk is not None:
        print("  路径：已解析（加 --verbose 显示）")

    expected = {
        "compileSdk": versions.get("compileSdk"),
        "minSdk": versions.get("minSdk"),
        "targetSdk": versions.get("targetSdk"),
        "buildTools": versions.get("buildTools"),
    }
    print(f"  version catalog：{expected}")
    if any(v is None for v in expected.values()):
        print("  [FAIL] gradle/libs.versions.toml 缺少 Android 版本号")
        return 1

    missing: list[str] = []
    if sdk is None or not sdk.is_dir():
        missing.append("SDK 根目录不存在或未配置（见 local.properties.example）")
    else:
        checks = (
            (f"platforms/android-{expected['compileSdk']}/android.jar",
             sdk / "platforms" / f"android-{expected['compileSdk']}" / "android.jar"),
            (f"build-tools/{expected['buildTools']}/aapt2",
             sdk / "build-tools" / str(expected["buildTools"]) / "aapt2.exe"),
            (f"build-tools/{expected['buildTools']}/aapt2 (posix)",
             sdk / "build-tools" / str(expected["buildTools"]) / "aapt2"),
            ("platform-tools/adb", sdk / "platform-tools" / "adb.exe"),
        )
        # aapt2 / adb 在 Windows 与 Unix 下后缀不同，两者有一即可。
        for label, path in checks:
            if "posix" in label:
                continue
            alt = path.with_suffix("") if path.suffix == ".exe" else None
            ok = path.is_file() or (alt is not None and alt.is_file())
            print(f"  [{'PASS' if ok else 'FAIL'}] {label}")
            if not ok:
                missing.append(label)
        if not (sdk / "licenses").is_dir():
            missing.append("licenses/（未接受 SDK 许可）")

    if missing:
        for item in missing:
            print(f"  [FAIL] {item}")
        print("\n结果：FAIL。请按 local.properties.example 配置后重跑（勿提交该文件）")
        return 1
    print("\n结果：PASS。SDK 由环境变量 / 本地配置解析，仓库内无任何个人路径")
    return 0 if not violations else 1


if __name__ == "__main__":
    raise SystemExit(main())
