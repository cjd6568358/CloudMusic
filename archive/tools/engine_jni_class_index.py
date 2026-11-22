#!/usr/bin/env python3
"""T-L L1 步骤 2 的前置清单：JNI 真类 / native 方法可执行索引（12 §32）。

数据来源全部是仓库内既有实证产物，不联网、不依赖 jadx / binutils：
- `analysis/engine-api-audit.json`：`tools/engine_api_audit.py` 产的 19 个类、
  每方法 dex 描述符与 native 标志、JNI 双向差额（13 §7）。
- `NeteaseCloudMusic.apk` 内 4 个入口 so：202 个 `Java_*` 导出符号（C1 口径）。

产出 `analysis/engine-jni-classes.json`：按「类 → native 方法 → Java 声明」展开，
供 `12 §10.2` 步骤 2 反编译重建 84 个真类时逐个核对 JNI 绑定名与参数签名；
步骤 3 每补一个 slot 仍按 `12 §10.4` 登记簿就地登记，本工具**不批量生成类源码**。

用法：
    python tools/engine_jni_class_index.py                 # 写 JSON + 打印摘要
    python tools/engine_jni_class_index.py --stdout       # 只打印摘要，不写文件
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_common import ROOT  # noqa: E402
import elf_probe as ep  # noqa: E402

APK = ROOT / "NeteaseCloudMusic.apk"
AUDIT = ROOT / "analysis" / "engine-api-audit.json"
OUT = ROOT / "analysis" / "engine-jni-classes.json"

# 13 §8.2 / C1 JNI_CLASSES：12 个必须精确重建的 JNI 类（其余为一层边界 / 数据类）。
PRECISE_CLASSES = {
    "com.netease.cloudmusic.android.sdk.playerengine.AudioPlayerUnit",
    "com.netease.cloudmusic.android.sdk.playerengine.NativeDataRequestErrorCallback",
    "com.netease.cloudmusic.android.sdk.playerengine.Playback",
    "com.netease.cloudmusic.android.sdk.playerengine.PlaybackDataSource",
    "com.netease.cloudmusic.android.sdk.playerengine.PlaybackEngine",
    "com.netease.cloudmusic.playlist.core.PlaylistJniBridge",
    "com.netease.cloudmusic.sdk.NMCommonCache.NMBufferSource",
    "com.netease.cloudmusic.sdk.NMCommonCache.NMCacheBase",
    "com.netease.cloudmusic.sdk.NMCommonCache.NMVirtualFile",
    "com.netease.cloudmusic.sdk.NMCommonCache.NMVirtualFileManager",
    "com.netease.cloudmusic.sdk.NMCommonCache.SourceDataRequest",
    "com.netease.cloudmusic.media.NEAudioDevice$NEAudioDeviceOnPlaybackPositionUpdateListener",
}


def java_type(desc: str) -> str:
    """dex 描述符 → 可读 Java 类型（够重建声明用，不做泛型还原）。"""
    primitives = {
        "V": "void", "Z": "boolean", "B": "byte", "C": "char", "S": "short",
        "I": "int", "J": "long", "F": "float", "D": "double",
    }
    dims = 0
    while desc.startswith("["):
        dims += 1
        desc = desc[1:]
    if desc in primitives:
        base = primitives[desc]
    elif desc.startswith("L") and desc.endswith(";"):
        base = desc[1:-1].replace("/", ".").replace("$", ".")
    else:
        base = desc
    return base + "[]" * dims


def class_name(descriptor: str) -> str:
    return descriptor.strip("L;").replace("/", ".")


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 JNI 真类 / native 方法索引")
    parser.add_argument("--stdout", action="store_true", help="只打印摘要，不写 JSON")
    args = parser.parse_args()

    if not AUDIT.is_file():
        print(f"FAIL 缺少实证产物 {AUDIT}，请先跑 tools/engine_api_audit.py")
        return 1
    audit = json.loads(AUDIT.read_text(encoding="utf-8"))

    classes = []
    native_total = 0
    for descriptor, info in sorted(audit["classes"].items()):
        natives = []
        for m in info.get("methods", []):
            if not m.get("native"):
                continue
            params = m.get("params", [])
            natives.append({
                "name": m["name"],
                "javaReturn": java_type(m.get("return", "V")),
                "javaParams": [java_type(p) for p in params],
                "descriptorParams": params,
                "declaration": "%s %s(%s)" % (
                    java_type(m.get("return", "V")),
                    m["name"],
                    ", ".join(java_type(p) for p in params),
                ),
            })
        name = class_name(descriptor)
        classes.append({
            "descriptor": descriptor,
            "class": name,
            "dex": info.get("dex", ""),
            "preciseJniClass": name in PRECISE_CLASSES,
            "nativeMethodCount": len(natives),
            "nativeMethods": natives,
        })
        native_total += len(natives)

    symbols: dict[str, int] = {}
    for lib in ep.ENTRY_LIBS:
        raw = ep.read_apk_lib(APK, "arm64-v8a", lib)
        symbols[lib] = len(ep.parse_elf(raw)["jni_symbols"]) if raw else 0

    index = {
        "source": "analysis/engine-api-audit.json + NeteaseCloudMusic.apk (4 个 JNI 入口 so)",
        "purpose": "12 §10.2 步骤 2 的 84 真类重建清单；不生成类源码，不预生成 slot",
        "classCount": len(classes),
        "preciseJniClassCount": sum(1 for c in classes if c["preciseJniClass"]),
        "nativeMethodCount": native_total,
        "jniSymbolCount": sum(symbols.values()),
        "jniSymbolsByLib": symbols,
        "deltaKnown": audit.get("jni_balance", {}).get("delta_sources", []),
        "classes": classes,
    }

    print(f"类 {index['classCount']} 个（精确 JNI 类 {index['preciseJniClassCount']} 个）"
          f" / native 方法 {native_total} 个 / Java_* 符号 {index['jniSymbolCount']} 个")
    for c in classes:
        mark = "★" if c["preciseJniClass"] else " "
        print(f"  {mark} {c['class']:95s} native={c['nativeMethodCount']:3d}  {c['dex']}")
    print(f"已知差额（13 §7.4）：{index['deltaKnown']}")

    if not args.stdout:
        OUT.write_text(
            json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"\n已写出 {OUT.relative_to(ROOT).as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
