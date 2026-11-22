#!/usr/bin/env python3
"""spike #1 · 引擎舱实证复核（不依赖 jadx / Android SDK）。

对应 docs/11 §7 未竟事项 #1 / #2 与 docs/10 §4 #5：
  ① `System.loadLibrary` 与统一封装器的**全局**调用点复核（钉死 03-12 §4 的打包集名单，
     2026-11-05 实证后由 17 修正为 18 个，见 docs/13 §2）；
  ② `playerengine.PlaybackDataSource` 的精确方法签名（03-06 §5 适配层此前是假设）；
  ③ 交叉验证：dex 字符串池里所有疑似 native 库名（含 ReLinker / SoLoader 间接加载线索）。

复用 tools/spike0_java_closure.py 的 DexFile / 指令长度表，纯 Python 3 零第三方依赖。
用法：
    python tools/spike1_engine_audit.py                    # 控制台摘要 + 写 analysis/spike1-engine-audit.json
    python tools/spike1_engine_audit.py --json out.json   # 指定 JSON 输出
"""
from __future__ import annotations

import argparse
import glob
import json
import re
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from spike0_java_closure import ACC_NATIVE, DexFile, LEN, uleb  # noqa: E402

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
DEX_GLOB = str(ROOT / "analysis" / "decoded" / "classes*.dex")
DEFAULT_JSON = ROOT / "analysis" / "spike1-engine-audit.json"

TARGET_CLASS = "Lcom/netease/cloudmusic/android/sdk/playerengine/PlaybackDataSource;"

# 03-12 §4 的打包集（spike #1 修正后 18 个），按「去掉 lib 前缀 / .so 后缀」的裸名比对。
PACKED = (
    "ncmplayerengine", "ncmaudioplayer", "audioplayer", "nmplaylist",
    "NMCommonCache", "ffmpeg", "neaudioeffects", "neaudioeffect_core",
    "neaudioaiif", "ncmusbaudio", "sqlite3x", "MNN", "MNN_Express",
    "necmMP3Dec", "necmAACDec", "necmFlacDec", "necmMediaCodecJDec",
    "c++_shared",  # spike #1 补入：Java 侧裸名，对应打包集 libc++_shared.so
)
SO_LIKE = re.compile(r"^(?:lib)?([A-Za-z0-9_+.-]{3,60})\.so$")


def methods_of(df: DexFile, class_data_off: int, class_idx: int | None = None):
    """产出 (method_name, proto_idx, access_flags, code_off)。

    `class_idx` 非空时只保留 method_id.class_idx 与本类一致的方法 ——
    class_data 的 uleb 序列若因混淆/对齐出现偏差，越界读到相邻类的方法会被过滤掉。
    """
    if not class_data_off:
        return []
    d = df.d
    pos = class_data_off
    n_sf, pos = uleb(d, pos)
    n_if, pos = uleb(d, pos)
    n_dm, pos = uleb(d, pos)
    n_vm, pos = uleb(d, pos)
    for _ in range(n_sf + n_if):
        _diff, pos = uleb(d, pos)
        _acc, pos = uleb(d, pos)
    midx = 0
    out = []
    for _ in range(n_dm + n_vm):
        diff, pos = uleb(d, pos)
        acc, pos = uleb(d, pos)
        code_off, pos = uleb(d, pos)
        midx += diff
        if midx >= df.n_method:
            continue
        cls, proto, name = df.method(midx)
        if class_idx is not None and cls != class_idx:
            continue
        out.append((name, proto, acc, code_off))
    return out


# 加载目标：(声明类描述符, 方法名, 库名参数下标, 结果类型)
# 竞品主 App 的 native 库几乎不走 System.loadLibrary 直接调用，而是统一经
# 混淆封装器 zv.a.b(Context, String libName, String stack) 加载（baksmali 实证）。
LOADER_TARGETS = (
    ("Ljava/lang/System;", "loadLibrary", 0, "system-loadLibrary"),
    ("Lzv/a;", "b", 1, "wrapper-zv.a.b"),
)
WATCHED_NAMES = set(PACKED) | {
    "necmMP3Dec", "necmAACDec", "necmFlacDec", "necmMediaCodecJDec",
}


def scan_code(df: DexFile, code_off: int):
    """扫描一个 code_item：加载器调用点 + 出现过的 const-string 常量。

    支持 35c (invoke-static, 0x71) 与 3rc (invoke-static/range, 0x77)；
    寄存器只做「最近一次 const-string 赋值」的线性回溯，分支合并处可能有偏差，
    因此结果同时给出 dex/class/method 上下文供人工复核。
    """
    d = df.d
    try:
        (size,) = struct.unpack_from("<I", d, code_off + 12)
    except struct.error:
        return [], []
    base = code_off + 16
    regs: dict[int, str] = {}
    hits: list[dict] = []
    consts: list[str] = []
    pc = 0
    while pc < size:
        u0 = struct.unpack_from("<H", d, base + 2 * pc)[0]
        op = u0 & 0xFF
        n = LEN[op]
        if n == 0 or pc + n > size:
            break
        u1 = struct.unpack_from("<H", d, base + 2 * (pc + 1))[0] if n >= 2 else 0
        u2 = struct.unpack_from("<H", d, base + 2 * (pc + 2))[0] if n >= 3 else 0
        if op in (0x1A, 0x1B):  # const-string / const-string/jumbo
            sidx = u1 | (u2 << 16) if op == 0x1B else u1
            value = df.string(sidx)
            regs[(u0 >> 8) & 0xFF] = value
            if value in WATCHED_NAMES:
                consts.append(value)
        elif op in (0x71, 0x77):  # invoke-static / invoke-static/range
            if u1 < df.n_method:
                cls, _proto, name = df.method(u1)
                cls_desc = df.type_desc(cls)
                for target_cls, target_name, arg_index, kind in LOADER_TARGETS:
                    if cls_desc != target_cls or name != target_name:
                        continue
                    # 35c 的 A 在 bits 12~15；3rc 的 A 是整个高字节。
                    argc = (u0 >> 12) & 0x0F if op == 0x71 else (u0 >> 8) & 0xFF
                    if argc <= arg_index:
                        break
                    if op == 0x71:
                        reg_list = [u2 & 0x0F, (u2 >> 4) & 0x0F, (u2 >> 8) & 0x0F,
                                    (u2 >> 12) & 0x0F, (u0 >> 8) & 0x0F]
                    else:
                        reg_list = [u2 + i for i in range(argc)]
                    reg = reg_list[arg_index]
                    hits.append({
                        "kind": kind,
                        "library": regs.get(reg, f"<unresolved:v{reg}>"),
                    })
                    break
        pc += n
    return hits, consts


def main() -> int:
    ap = argparse.ArgumentParser(description="spike #1 · 引擎舱 loadLibrary / PlaybackDataSource 实证")
    ap.add_argument("--json", default=str(DEFAULT_JSON), help="JSON 输出路径")
    args = ap.parse_args()

    calls: list[dict] = []
    target: dict | None = None
    so_strings: dict[str, list[str]] = {}
    packed_hits: dict[str, list[str]] = {}
    const_hits: dict[str, set[str]] = {}
    dex_files = sorted(glob.glob(DEX_GLOB))
    if not dex_files:
        print(f"[FAIL] 未找到 dex：{DEX_GLOB}")
        return 1

    for path in dex_files:
        dex = Path(path).name
        df = DexFile(path)
        local_so: set[str] = set()
        for i in range(df.n_str):
            s = df.string(i)
            if SO_LIKE.match(s):
                local_so.add(s)
            # 裸库名（loadLibrary 常用，不带 lib/.so）
            if s in PACKED:
                packed_hits.setdefault(s, []).append(dex)
        so_strings[dex] = sorted(local_so)

        for _cdi, class_idx, _super, _ifaces, class_data_off in df.class_defs():
            cls = df.type_desc(class_idx)
            for name, proto, acc, code_off in methods_of(df, class_data_off, class_idx):
                if code_off:
                    found, consts = scan_code(df, code_off)
                    for item in found:
                        calls.append({
                            "dex": dex, "class": cls, "method": name, **item,
                        })
                    for value in consts:
                        const_hits.setdefault(value, set()).add(f"{dex}:{cls}.{name}")
            if cls == TARGET_CLASS and target is None:
                sigs = []
                for name, proto, acc, _code in methods_of(df, class_data_off, class_idx):
                    types = [df.type_desc(t) for t in df.proto_types(proto)]
                    sigs.append({
                        "name": name,
                        "return": types[0],
                        "params": types[1:],
                        "native": bool(acc & ACC_NATIVE),
                    })
                target = {"dex": dex, "class": cls, "methods": sigs}

    # 去重（同一 class/method/library 可能跨 dex 或多次调用）
    uniq: dict[tuple, dict] = {}
    for c in calls:
        uniq.setdefault((c["kind"], c["dex"], c["class"], c["method"], c["library"]), c)
    calls = sorted(uniq.values(), key=lambda c: (c["kind"], c["library"], c["dex"], c["class"]))

    system_calls = [c for c in calls if c["kind"] == "system-loadLibrary"]
    wrapper_calls = [c for c in calls if c["kind"] == "wrapper-zv.a.b"]
    lib_names = sorted({c["library"] for c in system_calls})
    wrapper_names = sorted({c["library"] for c in wrapper_calls})
    unresolved = [c for c in calls if c["library"].startswith("<")]
    so_all = sorted({s for v in so_strings.values() for s in v})

    result = {
        "method": "spike #1: dex 指令级加载器调用点 + PlaybackDataSource / 打包集实证",
        "dex_count": len(dex_files),
        "system_load_library_calls": system_calls,
        "system_load_library_unique_names": lib_names,
        "wrapper_load_calls": wrapper_calls,
        "wrapper_load_unique_names": wrapper_names,
        "unresolved_calls": unresolved,
        "watched_const_string_hits": {k: sorted(v) for k, v in const_hits.items()},
        "packed_name_hits": {k: sorted(set(v)) for k, v in packed_hits.items()},
        "so_like_strings": so_all,
        "playback_data_source": target,
    }
    Path(args.json).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"扫描 {len(dex_files)} 个 dex；System.loadLibrary {len(system_calls)} 处 / "
          f"统一封装器 zv.a.b {len(wrapper_calls)} 处，未解析参数 {len(unresolved)} 个")
    print(f"\n=== 封装器按名加载清单（{len(wrapper_names)} 个裸库名） ===")
    for lib in wrapper_names:
        places = [f"{c['dex']}:{c['class']}" for c in wrapper_calls if c["library"] == lib]
        print(f"  {lib:<32} <- {places[0]}" + (f"  (+{len(places)-1} 处)" if len(places) > 1 else ""))
    print(f"\n=== System.loadLibrary 直接调用（{len(lib_names)} 个） ===")
    for lib in lib_names:
        print(f"  {lib}")
    if unresolved:
        print("\n=== 参数未解析（需人工复核） ===")
        for c in unresolved[:20]:
            print(f"  {c['kind']} {c['dex']} {c['class']}.{c['method']} -> {c['library']}")
    print("\n=== 关注库名出现在 const-string 的类 ===")
    for k in sorted(const_hits):
        v = sorted(const_hits[k])
        print(f"  {k:<28} {len(v)} 处, 例: {v[0][:110]}")
    print(f"\n=== dex 字符串池中的 *.so 名称（{len(so_all)} 个） ===")
    for s in so_all:
        print(f"  {s}")
    print("\n=== 打包集裸名在 string_ids 中的命中 ===")
    for k in PACKED:
        v = packed_hits.get(k, [])
        print(f"  {k:<28} {len(set(v))} 个 dex" + (f": {', '.join(sorted(set(v)))}" if v else "  (未作为独立字符串出现)"))
    if target:
        print(f"\n=== PlaybackDataSource（{target['dex']}）方法签名 ===")
        for m in target["methods"]:
            flag = " [native]" if m["native"] else ""
            print(f"  {m['return']} {m['name']}({', '.join(m['params'])}){flag}")
    else:
        print("\n[WARN] 未找到 PlaybackDataSource 类定义")
    print(f"\nJSON -> {args.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
