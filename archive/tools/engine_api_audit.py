#!/usr/bin/env python3
"""播放内核 API 签名 + JNI 双向校验（docs/13 §7 的可复现脚本）。

两项职责：
  ① 从 20 个 dex 里 dump `playerengine.*` / `PlaylistJniBridge` 等目标类的
     精确方法签名（返回类型 / 参数 / native 标记），用于校正 `03-06 §5` 映射表；
  ② 把 dex 侧 native 方法名与 4 个入口 so 的 `Java_*` 导出逐类做差集，
     解释 C1 的「JNI 导出 202 vs dex 侧 210 差 8」。

复用 `spike0_java_closure.DexFile` / `spike1_engine_audit.methods_of` / `elf_probe`，
纯 Python 3 零第三方依赖。产物写 `analysis/engine-api-audit.json`。

用法：
    python tools/engine_api_audit.py
    python tools/engine_api_audit.py --out analysis/other.json
"""
from __future__ import annotations

import argparse
import glob
import json
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from elf_probe import parse_elf  # noqa: E402
from spike0_java_closure import DexFile  # noqa: E402
from spike1_engine_audit import methods_of  # noqa: E402

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "NeteaseCloudMusic.apk"
ABI = "lib/arm64-v8a/"
DEX_GLOB = str(ROOT / "analysis" / "decoded" / "classes*.dex")
DEFAULT_OUT = ROOT / "analysis" / "engine-api-audit.json"

PKG = "Lcom/netease/cloudmusic/android/sdk/playerengine/"
TARGET_CLASSES = {
    PKG + n + ";" for n in (
        "AudioPlayerUnit", "Playback", "PlaybackEngine", "PlaybackDataSource",
        "NativeDataRequestErrorCallback", "DataProvider", "PlayerDataSource",
        "PlaybackListener", "PlaybackEngineListener", "PlayerUnitListener",
        "UrlResult", "MusicInfo",
    )
} | {
    "Lcom/netease/cloudmusic/playlist/core/PlaylistJniBridge;",
    "Lcom/netease/cloudmusic/sdk/NMCommonCache/NMBufferSource;",
    "Lcom/netease/cloudmusic/sdk/NMCommonCache/NMCacheBase;",
    "Lcom/netease/cloudmusic/sdk/NMCommonCache/NMVirtualFile;",
    "Lcom/netease/cloudmusic/sdk/NMCommonCache/NMVirtualFileManager;",
    "Lcom/netease/cloudmusic/sdk/NMCommonCache/SourceDataRequest;",
    "Lcom/netease/cloudmusic/media/NEAudioDevice$NEAudioDeviceOnPlaybackPositionUpdateListener;",
}
JNI_LIBS = (
    "libncmplayerengine.so", "libaudioplayer.so",
    "libnmplaylist.so", "libNMCommonCache.so",
)
JNI_PREFIX = "Java_com_netease_cloudmusic_android_sdk_playerengine_"


def collect_dex():
    """返回 {类描述符: {dex, methods:[{name,return,params,native}]}}。"""
    out: dict[str, dict] = {}
    for path in sorted(glob.glob(DEX_GLOB)):
        df = DexFile(path)
        dex = Path(path).name
        for _cdi, cidx, _super, _ifaces, cdo in df.class_defs():
            cls = df.type_desc(cidx)
            if cls not in TARGET_CLASSES or cls in out:
                continue
            methods = []
            for name, proto, acc, _code in methods_of(df, cdo, cidx):
                types = [df.type_desc(t) for t in df.proto_types(proto)]
                methods.append({
                    "name": name,
                    "return": types[0],
                    "params": types[1:],
                    "native": bool(acc & 0x100),
                })
            # 差集用与 spike #0 相同的不带 class_idx 过滤口径；注意部分
            # NMCommonCache 类的方法名解析存在偏移（native 计数正确、名字错位），
            # 故这些组只用于计数比对，不参与「差 8」的结论推导，见 docs/13 §7.4。
            all_methods = methods_of(df, cdo, None)
            out[cls] = {
                "dex": dex,
                "methods": methods,
                "native_names_all": sorted({
                    n for n, _pr, a, _co in all_methods if a & 0x100
                }),
            }
    return out


def collect_so():
    """返回 {so 名: [Java_* 符号]}。"""
    out = {}
    with zipfile.ZipFile(APK) as z:
        for lib in JNI_LIBS:
            out[lib] = sorted(parse_elf(z.read(ABI + lib)).get("jni_symbols", []))
    return out


def known_segments():
    """类描述符 → JNI 符号里的类段（`/`→`_`，内部类 `$`→`_00024`），长按优先。"""
    segs = []
    for cls in TARGET_CLASSES:
        seg = cls[1:-1].replace("/", "_").replace("$", "_00024")
        segs.append((seg, cls))
    return sorted(segs, key=lambda x: -len(x[0]))


def class_from_symbol(sym: str, segments=None):
    """把 JNI 符号映射到类描述符 + 方法名（按已知类名最长前缀匹配）。"""
    for seg, cls in (segments if segments is not None else known_segments()):
        prefix = "Java_" + seg + "_"
        if sym.startswith(prefix):
            return cls, sym[len(prefix):]
    return "", sym


def main() -> int:
    ap = argparse.ArgumentParser(description="播放内核 API 签名 + JNI 双向校验")
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    args = ap.parse_args()

    dex = collect_dex()
    so = collect_so()

    segments = known_segments()
    so_by_class: dict[str, set[str]] = defaultdict(set)
    for syms in so.values():
        for sym in syms:
            cls, meth = class_from_symbol(sym, segments)
            if cls:
                so_by_class[cls].add(meth)

    diff = {}
    for cls in sorted(set(dex) | set(so_by_class)):
        d = set(dex.get(cls, {}).get("native_names_all", []))
        s = so_by_class.get(cls, set())
        if d or s:
            diff[cls] = {
                "dex_native": len(d), "so_exports": len(s),
                "dex_only": sorted(d - s), "so_only": sorted(s - d),
                # NMCommonCache / NEAudioDevice 的 dex 方法名解析有偏移，差集不可靠
                "method_name_resolution": (
                    "reliable" if ("playerengine/" in cls or "PlaylistJniBridge" in cls)
                    else "unreliable-count-only"
                ),
            }

    total_so = sum(len(v) for v in so.values())
    # 差 8 的口径来自 11 §3.2 的分组基线（native 计数），不是本工具重新累加的方法名集合。
    jni_balance = {
        "dex_baseline": {"playerengine": 119, "nmplaylist": 31,
                         "NMCommonCache": 58, "NEAudioDevice": 2, "total": 210},
        "so_exports": {"playerengine": 118, "nmplaylist": 24,
                       "NMCommonCache": 58, "audioplayer": 2, "total": total_so},
        "delta": total_so - 210,
        "delta_sources": [
            "Playback.nativeNotifyAudioEffectChanged (1)",
            "PlaylistJniBridge *Raw / 日志 / 更新类方法 (7)",
        ],
        "source": "docs/11 §3.2 / analysis/engine-jni-exports.log / docs/13 §7.4",
    }
    payload = {
        "method": "播放内核 API 签名 + JNI 双向校验（docs/13 §7）",
        "classes": dex,
        "so_export_counts": {k: len(v) for k, v in so.items()},
        "diff": diff,
        "jni_balance": jni_balance,
    }
    Path(args.out).write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                              encoding="utf-8")

    print(f"目标类 {len(dex)} 个；so `Java_*` 导出 {total_so} 个；"
          f"dex 基线 210（`11 §3.2`），差 {total_so - 210}")
    print("\n=== 可靠组逐类差集（playerengine / PlaylistJniBridge） ===")
    for cls, v in diff.items():
        if v["method_name_resolution"] != "reliable":
            continue
        if v["dex_only"] or v["so_only"]:
            print(f"  {cls.split('/')[-1].rstrip(';'):<48} dex={v['dex_native']:<3} "
                  f"so={v['so_exports']:<3} dex-only={len(v['dex_only'])} "
                  f"so-only={len(v['so_only'])}")
            for m in v["dex_only"][:4]:
                print(f"      dex-only: {m}")
    print("  （NMCommonCache / NEAudioDevice 组的方法名解析存在偏移，"
          "只比对计数，差集见 JSON 的 unreliable-count-only 标记）")
    print("\n=== 三层播放 API native 数 ===")
    for n in ("AudioPlayerUnit", "Playback", "PlaybackEngine", "PlaybackDataSource"):
        key = PKG + n + ";"
        if key in dex:
            cnt = sum(1 for m in dex[key]["methods"] if m["native"])
            print(f"  {n:<24} {cnt}")
    print(f"\nJSON -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
