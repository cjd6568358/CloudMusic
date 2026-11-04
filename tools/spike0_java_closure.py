#!/usr/bin/env python3
"""spike #0 — 引擎舱 Java 层闭包实测（不依赖 jadx）。

背景：本机无 jadx，但 analysis/decoded/classes*.dex 齐全。jadx 的作用是把
dex 还原成 Java 源码再看 import；而「类引用闭包」在 dex 层是精确可得的：
class_def 的 superclass / interfaces / fields / method protos，加上 code_item
里 const-class / check-cast / new-instance / iget / invoke 等指令引用的
type / field / method id，共同构成一张有向引用图。本脚本直接解析该图，
从 playerengine 等 JNI 入口包族做 BFS，产出与 jadx 等价（且更精确）的
闭包清单，用于钉死 A1 可行性、R8 keep 包名、so 打包名单。

用法：
    python tools/spike0_java_closure.py                    # 全量分析，写 JSON + 控制台摘要
    python tools/spike0_java_closure.py --probe           # 只列种子包族，快速探查
    python tools/spike0_java_closure.py --json out.json   # 指定 JSON 输出路径

纯 Python 3，零第三方依赖，Windows / Linux 均可运行。
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import struct
import sys
from collections import Counter, defaultdict, deque
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
DEX_GLOB = str(ROOT / "analysis" / "decoded" / "classes*.dex")
DEFAULT_JSON = ROOT / "analysis" / "spike0-java-closure.json"

# --- Dalvik 指令长度表（单位：16-bit code unit） ---------------------------
# 只需要「长度」用于跳过指令，以及「哪些 opcode 携带索引」用于取引用。
LEN = [1] * 256


def _set(ops, n):
    for op in ops:
        LEN[op] = n


def _range(a, b, n):
    _set(range(a, b + 1), n)


_set([0x02, 0x05, 0x08], 2)
_set([0x03, 0x06, 0x09], 3)
_set([0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C, 0x1F, 0x20, 0x22, 0x23, 0x29], 2)
# nop / monitor-enter / monitor-exit / array-length / throw / goto 均为 1 unit
LEN[0x00] = LEN[0x1D] = LEN[0x1E] = LEN[0x21] = LEN[0x27] = LEN[0x28] = 1
_set([0x14, 0x17, 0x1B, 0x24, 0x25, 0x26, 0x2A, 0x2B, 0x2C], 3)
LEN[0x18] = 5
_range(0x2D, 0x31, 2)
_range(0x32, 0x37, 2)
_range(0x38, 0x3D, 2)
_range(0x44, 0x51, 2)   # aget* / aput*
_range(0x52, 0x6D, 2)   # iget* / iput* / sget* / sput* → field id
_set([0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78], 3)
_range(0x90, 0xAF, 2)   # binop
_range(0xD0, 0xD7, 2)   # binop/lit16
_range(0xD8, 0xE2, 2)   # binop/lit8
_set([0xFA, 0xFB], 4)   # invoke-polymorphic
_set([0xFC, 0xFD], 3)   # invoke-custom
_set([0xFE, 0xFF], 2)   # const-method-handle / const-method-type

TYPE_OPS = {0x1C, 0x1F, 0x20, 0x22, 0x23, 0x24, 0x25}      # → type id
FIELD_OPS = set(range(0x52, 0x6E))                           # → field id
METHOD_OPS = {0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77,
              0x78, 0xFA, 0xFB}                              # → method id
STRING_OPS = {0x1A, 0x1B}                                   # → string id

ACC_NATIVE = 0x100

# --- dex 解析 ---------------------------------------------------------------


def uleb(data, pos):
    result = 0
    shift = 0
    while True:
        b = data[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7


class DexFile:
    def __init__(self, path):
        self.path = Path(path)
        with open(path, "rb") as f:
            self.d = f.read()
        d = self.d
        if d[:4] != b"dex\n":
            raise ValueError(f"not a dex: {path}")
        (self.n_str, self.off_str) = struct.unpack_from("<II", d, 56)
        (self.n_type, self.off_type) = struct.unpack_from("<II", d, 64)
        (self.n_proto, self.off_proto) = struct.unpack_from("<II", d, 72)
        (self.n_field, self.off_field) = struct.unpack_from("<II", d, 80)
        (self.n_method, self.off_method) = struct.unpack_from("<II", d, 88)
        (self.n_class, self.off_class) = struct.unpack_from("<II", d, 96)
        self._strings = {}

    def string(self, i):
        if i in self._strings:
            return self._strings[i]
        if i >= self.n_str:
            return ""
        (off,) = struct.unpack_from("<I", self.d, self.off_str + 4 * i)
        try:
            _, p = uleb(self.d, off)
            end = self.d.index(b"\x00", p)
            s = self.d[p:end].decode("utf-8", errors="replace")
        except (IndexError, ValueError, struct.error):
            s = ""
        self._strings[i] = s
        return s

    def type_desc(self, i):
        if i >= self.n_type:
            return ""
        (si,) = struct.unpack_from("<I", self.d, self.off_type + 4 * i)
        return self.string(si)

    def type_list(self, off):
        if not off:
            return []
        (n,) = struct.unpack_from("<I", self.d, off)
        return [struct.unpack_from("<H", self.d, off + 4 + 2 * i)[0]
                for i in range(n)]

    def proto_types(self, idx):
        off = self.off_proto + 12 * idx
        ret, params_off = struct.unpack_from("<II", self.d, off + 4)
        return [ret] + self.type_list(params_off)

    def field(self, idx):
        off = self.off_field + 8 * idx
        cls, typ, name = struct.unpack_from("<HHI", self.d, off)
        return cls, typ, self.string(name)

    def method(self, idx):
        off = self.off_method + 8 * idx
        cls, proto, name = struct.unpack_from("<HHI", self.d, off)
        return cls, proto, self.string(name)

    def class_defs(self):
        for i in range(self.n_class):
            off = self.off_class + 32 * i
            (class_idx, flags, super_idx, ifaces_off, _src, _ann,
             class_data_off, _static) = struct.unpack_from("<IIIIIIII", self.d, off)
            yield i, class_idx, super_idx, ifaces_off, class_data_off

    def refs_of(self, class_def_index):
        """返回 (引用的类描述符集合, native 方法数, const-string 值列表)。"""
        d = self.d
        off = self.off_class + 32 * class_def_index
        (class_idx, _flags, super_idx, ifaces_off, _src, _ann,
         class_data_off, _static) = struct.unpack_from("<IIIIIIII", d, off)

        refs = set()
        if super_idx != 0xFFFFFFFF:
            refs.add(self.type_desc(super_idx))
        for t in self.type_list(ifaces_off):
            refs.add(self.type_desc(t))

        native = 0
        strings = []
        if not class_data_off:
            return refs, native, strings

        pos = class_data_off
        n_sf, pos = uleb(d, pos)
        n_if, pos = uleb(d, pos)
        n_dm, pos = uleb(d, pos)
        n_vm, pos = uleb(d, pos)

        # encoded_field: field_idx_diff, access_flags
        fidx = 0
        for _ in range(n_sf + n_if):
            diff, pos = uleb(d, pos)
            _acc, pos = uleb(d, pos)
            fidx += diff
            if fidx < self.n_field:
                cls, typ, _name = self.field(fidx)
                refs.add(self.type_desc(cls))
                refs.add(self.type_desc(typ))

        # encoded_method: method_idx_diff, access_flags, code_off
        midx = 0
        code_offsets = []
        for _ in range(n_dm + n_vm):
            diff, pos = uleb(d, pos)
            acc, pos = uleb(d, pos)
            code_off, pos = uleb(d, pos)
            midx += diff
            if midx >= self.n_method:
                continue
            cls, proto, _name = self.method(midx)
            refs.add(self.type_desc(cls))
            for t in self.proto_types(proto):
                refs.add(self.type_desc(t))
            if acc & ACC_NATIVE:
                native += 1
            if code_off:
                code_offsets.append(code_off)

        for code_off in code_offsets:
            refs.update(self._code_type_refs(code_off))
            strings.extend(self._code_strings(code_off))
        return refs, native, strings

    def _iter_insns(self, code_off):
        """产出 (pc, opcode, unit1) —— 只遍历真实指令区。"""
        d = self.d
        try:
            (insns_size,) = struct.unpack_from("<I", d, code_off + 12)
        except struct.error:
            return
        insns_off = code_off + 16
        pc = 0
        while pc < insns_size:
            op = d[insns_off + 2 * pc]  # opcode 在 code unit 低字节
            n = LEN[op]
            if n == 0 or pc + n > insns_size:
                break
            unit1 = struct.unpack_from("<H", d, insns_off + 2 * (pc + 1))[0] \
                if n >= 2 else 0
            yield pc, op, unit1
            pc += n

    def _code_type_refs(self, code_off):
        out = set()
        for pc, op, u1 in self._iter_insns(code_off):
            if op in TYPE_OPS:
                out.add(self.type_desc(u1))
            elif op in FIELD_OPS:
                if u1 < self.n_field:
                    cls, typ, _ = self.field(u1)
                    out.add(self.type_desc(cls))
                    out.add(self.type_desc(typ))
            elif op in METHOD_OPS:
                if u1 < self.n_method:
                    cls, proto, _ = self.method(u1)
                    out.add(self.type_desc(cls))
                    for t in self.proto_types(proto):
                        out.add(self.type_desc(t))
        return out

    def _code_strings(self, code_off):
        out = []
        d = self.d
        insns_off = code_off + 16
        for pc, op, u1 in self._iter_insns(code_off):
            if op == 0x1A:
                out.append(self.string(u1))
            elif op == 0x1B:
                u2 = struct.unpack_from("<H", d, insns_off + 2 * (pc + 2))[0]
                out.append(self.string(u1 | (u2 << 16)))
        return out


# --- 分类与闭包 -------------------------------------------------------------

# 分类一律基于点分类名（dotted() 的输出），不要混用 dex 描述符写法。
FRAMEWORK_PKG = (
    "java.", "javax.", "android.", "androidx.", "dalvik.", "sun.",
    "org.w3c.", "org.xml.", "org.json.", "org.apache.", "junit.",
    "kotlin.", "kotlinx.", "libcore.", "com.android.",
    "org.intellij.", "org.jetbrains.",
)
THIRD_PARTY_PKG = (
    "okhttp3.", "retrofit2.", "com.google.", "com.facebook.", "com.tencent.",
    "io.reactivex.", "rx.", "org.greenrobot.", "com.squareup.",
    "com.bumptech.", "org.slf4j.", "org.jaudiotagger.", "com.alibaba.",
    "com.bytedance.", "com.huawei.", "com.meizu.", "com.xiaomi.",
    "org.chromium.", "com.getkeepsafe.", "butterknife.", "dagger.",
    # 厂商 / 运营商 SDK：随系统或独立 jar 提供，不是需要照搬的竞品源码
    "cn.com.chinatelecom.", "com.hihonor.", "com.heytap.", "com.vivo.",
    "com.samsung.", "com.coloros.", "com.oppo.", "com.bun.",
)

# 「裁剪边界」实验：遇到这些包族即停止展开，记为需要 stub / 替换的外部依赖。
# 判据来自 10 §2 R1：闭包不得拖入 redux / 在线业务。
STOP_PKG = (
    "com.netease.cloudmusic.activity.", "com.netease.cloudmusic.fragment.",
    "com.netease.cloudmusic.module.", "com.netease.cloudmusic.discovery.",
    "com.netease.cloudmusic.ui.", "com.netease.cloudmusic.meta.",
    "com.netease.cloudmusic.network.", "com.netease.cloudmusic.api.",
    "com.netease.cloudmusic.biz.", "com.netease.cloudmusic.music.biz.",
    "com.netease.cloudmusic.login", "com.netease.cloudmusic.account",
    "com.netease.cloudmusic.search", "com.netease.cloudmusic.comment",
    "com.netease.cloudmusic.radio", "com.netease.cloudmusic.mv.",
    "com.netease.cloudmusic.ad", "com.netease.cloudmusic.process.",
    "com.netease.play.", "com.netease.nmvideocreator.", "com.netease.epay.",
    "com.netease.cloudmusic.redux", "com.netease.cloudmusic.automix",
)
# 混淆短包（如 eb3 / jo0 / ca1）单独按「无顶层包名」判定
CORE_KEEP_PKG = (
    "com.netease.cloudmusic.android.sdk.playerengine.",
    "com.netease.cloudmusic.playlist.core.",
    "com.netease.cloudmusic.sdk.NMCommonCache.",
    "com.netease.cloudmusic.media.NEAudioDevice",  # 只含 05 实测的 2 个 JNI 类
)


def is_obfuscated(name):
    parts = name.split(".")
    return len(parts) <= 2 and len(parts[0]) <= 4 and not parts[0].startswith("com")

SEED_MARKERS = (
    "playerengine",
    "playlist.core",
    "NMCommonCache",
    "NMVirtualFileManager",
    "NEAudioDevice",       # 05 §2：audioplayer 仅 2 个 JNI 导出，在此类；不要扩到整个 media 包族
)
# 判据里的「在线业务 / redux」信号词（10 §2 R1）
BUSINESS_MARKERS = (
    "redux", "automix", "BizMusicMeta", "NMVirtualFileManager",
    "cloudmusic.module.", "cloudmusic.activity.", "cloudmusic.fragment.",
    "cloudmusic.network.", "cloudmusic.api.", "cloudmusic.biz.",
    ".login", ".account", ".search.", ".comment", ".mv.", ".radio",
)


def dotted(desc):
    """Lcom/netease/x/Y; → com.netease.x.Y；数组/原始类型也做基本归一。"""
    if not desc:
        return ""
    while desc.startswith("["):
        desc = desc[1:]
    if desc.startswith("L") and desc.endswith(";"):
        return desc[1:-1].replace("/", ".")
    return desc


def kind_of(dotted_name):
    if dotted_name.startswith(FRAMEWORK_PKG):
        return "framework"
    if dotted_name.startswith(THIRD_PARTY_PKG):
        return "thirdparty"
    # 混淆短包（如 jo0.c / p10.a）视作竞品自有业务类，必须展开
    return "own"


def build_index(dex_files):
    """返回 desc → (dex, class_def_index)，以及全部类名列表。"""
    index = {}
    all_names = []
    class_count = 0
    for dex in dex_files:
        for ci, class_idx, *_ in dex.class_defs():
            desc = dex.type_desc(class_idx)
            name = dotted(desc)
            if not name:
                continue
            all_names.append(name)
            index.setdefault(name, (dex, ci))
            class_count += 1
    return index, all_names, class_count


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--probe", action="store_true", help="只探查种子包族")
    ap.add_argument("--json", default=str(DEFAULT_JSON))
    ap.add_argument("--max-depth", type=int, default=64)
    args = ap.parse_args()

    paths = sorted(glob.glob(DEX_GLOB),
                   key=lambda p: (len(p), p))
    if not paths:
        print("找不到 dex：", DEX_GLOB)
        return 2
    print(f"加载 {len(paths)} 个 dex ...", flush=True)
    dex_files = [DexFile(p) for p in paths]

    index, all_names, class_count = build_index(dex_files)
    print(f"类定义总数: {class_count}，唯一类名: {len(index)}", flush=True)

    seeds = sorted(n for n in index
                   if any(m in n for m in SEED_MARKERS))
    print(f"种子类（playerengine / playlist.core / NMCommonCache / NMVirtualFileManager）: {len(seeds)}")
    seed_pkgs = Counter(".".join(n.split(".")[:6]) for n in seeds)
    for pkg, cnt in seed_pkgs.most_common(30):
        print(f"  {cnt:5d}  {pkg}")
    if args.probe:
        return 0

    # refs_of 结果缓存：full BFS 与 core BFS 共用，避免重复解析 code_item
    refs_cache = {}

    def get_refs(name):
        if name in refs_cache:
            return refs_cache[name]
        entry = index.get(name)
        if entry is None:
            refs_cache[name] = (set(), 0)
            return refs_cache[name]
        dex, ci = entry
        refs, native, _ = dex.refs_of(ci)
        refs_cache[name] = (refs, native)
        return refs_cache[name]

    # native 方法分布（R8 keep 包名校准）
    native_classes = {}
    for n in seeds:
        _, native = get_refs(n)
        if native:
            native_classes[n] = native

    # ---- full BFS：不裁剪，等价于 jadx 的 import 可达闭包 ----
    visited = set()
    external = set()
    framework_hits = 0
    edges_in = defaultdict(list)
    depth_of = {}
    parent = {}
    queue = deque((n, 0) for n in seeds)
    for n in seeds:
        depth_of[n] = 0
    scanned = 0
    native_in_closure = {}
    while queue:
        name, depth = queue.popleft()
        if name in visited or depth > args.max_depth:
            continue
        visited.add(name)
        if index.get(name) is None:
            continue
        refs, native = get_refs(name)
        scanned += 1
        if native:
            native_in_closure[name] = native
        for r in refs:
            rn = dotted(r)
            if not rn:
                continue
            k = kind_of(rn)
            if k == "framework":
                framework_hits += 1
                continue
            if k == "thirdparty":
                external.add(rn)
                continue
            edges_in[rn].append(name)
            if rn not in visited:
                depth_of[rn] = min(depth_of.get(rn, 999), depth + 1)
                parent.setdefault(rn, name)
                queue.append((rn, depth + 1))

    # ---- core BFS：遇到在线业务包族即停止，统计「裁剪后核心闭包 + stub 边界」 ----
    core_visited = set()
    stub_boundary = set()
    core_queue = deque(seeds)
    while core_queue:
        name = core_queue.popleft()
        if name in core_visited:
            continue
        core_visited.add(name)
        refs, _ = get_refs(name)
        for r in refs:
            rn = dotted(r)
            if not rn or kind_of(rn) != "own":
                continue
            blocked = rn.startswith(STOP_PKG) or is_obfuscated(rn)
            if blocked:
                stub_boundary.add(rn)
                continue
            if rn not in core_visited:
                core_queue.append(rn)

    # ---- minimal BFS：只展开四个 JNI 包族内部，其余全部视为「需 stub/替换的边界」 ----
    # 这是「照搬舱 Java 层实际必须搬的源码量」的最接近估计：
    # 舱内类 + 一层边界接口（后者可用同名空壳 / 接口替换，不必搬实现）。
    min_visited = set()
    min_boundary = set()
    min_queue = deque(seeds)
    while min_queue:
        name = min_queue.popleft()
        if name in min_visited:
            continue
        min_visited.add(name)
        refs, _ = get_refs(name)
        for r in refs:
            rn = dotted(r)
            if not rn or kind_of(rn) != "own":
                continue
            if rn.startswith(CORE_KEEP_PKG) or rn in seeds:
                if rn not in min_visited:
                    min_queue.append(rn)
            else:
                min_boundary.add(rn)

    min_boundary_pkg = Counter()
    for n in min_boundary:
        parts = n.split(".")
        min_boundary_pkg[".".join(parts[:6]) if len(parts) > 6
                         else ".".join(parts[:-1])] += 1

    def path_to(name):
        chain = [name]
        seen = set()
        while chain[-1] in parent and chain[-1] not in seen:
            seen.add(chain[-1])
            chain.append(parent[chain[-1]])
        return list(reversed(chain))

    depth_hist = Counter(depth_of.get(n, -1) for n in visited)

    own = sorted(n for n in visited if n in index)
    ghost = sorted(n for n in visited if n not in index)  # 被引用但无类定义（SDK/生成类）
    # 业务耦合信号只统计竞品自有包，避免厂商 SDK 类名里的 search/media 等词误报
    business = sorted(n for n in own
                      if n.startswith("com.netease.")
                      and any(m.lower() in n.lower() for m in BUSINESS_MARKERS))
    pkg_dist = Counter()
    for n in own:
        parts = n.split(".")
        pkg_dist[".".join(parts[:5]) if len(parts) > 5 else ".".join(parts[:-1])] += 1
    core_own = sorted(n for n in core_visited if n in index)
    stub_pkg = Counter()
    for n in stub_boundary:
        parts = n.split(".")
        stub_pkg[".".join(parts[:5]) if len(parts) > 5 else ".".join(parts[:-1])] += 1
    biz_example = next((n for n in business if "module.player" in n), None) or \
        (business[0] if business else None)

    result = {
        "method": "dex 类引用图 BFS（等价于 jadx import 闭包，且包含签名/字节码引用）",
        "dex_count": len(dex_files),
        "class_defs": class_count,
        "seed_count": len(seeds),
        "seeds": seeds,
        "native_seed_classes": native_classes,
        "closure_own_class_count": len(own),
        "closure_ghost_class_count": len(ghost),
        "closure_thirdparty_count": len(external),
        "framework_reference_hits": framework_hits,
        "scanned_classes": scanned,
        "max_depth": max(depth_of.values() or [0]),
        "depth_histogram": dict(sorted(depth_hist.items())),
        "direct_refs_depth1": sorted(n for n in visited if depth_of.get(n) == 1),
        "business_signal_count": len(business),
        "business_signal_examples": business[:40],
        "business_coupling_path_example": path_to(biz_example) if biz_example else [],
        "minimal_closure_count": len(min_visited),
        "minimal_closure_classes": sorted(min_visited),
        "minimal_boundary_count": len(min_boundary),
        "minimal_boundary_by_package": min_boundary_pkg.most_common(),
        "minimal_boundary_classes": sorted(min_boundary),
        "core_closure_count": len(core_own),
        "core_closure_classes": core_own,
        "stub_boundary_count": len(stub_boundary),
        "stub_boundary_by_package": stub_pkg.most_common(),
        "stub_boundary_examples": sorted(stub_boundary)[:80],
        "package_distribution": pkg_dist.most_common(),
        "thirdparty_examples": sorted(external)[:60],
        "closure_own_classes": own,
        "ghost_classes": ghost,
        "native_classes_in_closure": native_in_closure,
        "top_referenced": sorted(
            ((n, len(v)) for n, v in edges_in.items()),
            key=lambda x: -x[1])[:40],
    }
    out = Path(args.json)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n===== spike #0 实测摘要 =====")
    print(f"闭包内竞品自有类     : {len(own)}")
    print(f"其中带 native 方法   : {len(native_in_closure)}")
    print(f"闭包内无定义的类     : {len(ghost)}（多为生成类 / SDK 桩）")
    print(f"外部第三方库引用     : {len(external)} 个类")
    print(f"框架引用次数         : {framework_hits}")
    print(f"在线业务/redux 信号  : {len(business)} 个类")
    print(f"BFS 最大深度         : {result['max_depth']}")
    print(f"最小舱内闭包         : {len(min_visited)} 个类")
    print(f"其中一层边界需 stub  : {len(min_boundary)} 个类")
    print(f"裁剪后核心闭包       : {len(core_own)} 个类")
    print(f"需要 stub 的边界类   : {len(stub_boundary)} 个类")
    print("\nminimal 边界包族（top 12）:")
    for pkg, cnt in min_boundary_pkg.most_common(12):
        print(f"  {cnt:5d}  {pkg}")
    print("\n深度直方图（前 8 层）:")
    for d, c in sorted(depth_hist.items())[:8]:
        print(f"  depth {d:2d}: {c:6d} 个类")
    print("\nstub 边界包族（top 12）:")
    for pkg, cnt in stub_pkg.most_common(12):
        print(f"  {cnt:5d}  {pkg}")
    if biz_example:
        print("\n业务耦合最短路径示例（种子 → 在线业务）:")
        print("  " + "\n    → ".join(path_to(biz_example)[:12]))
    print("\n包族分布（top 25）:")
    for pkg, cnt in pkg_dist.most_common(25):
        print(f"  {cnt:5d}  {pkg}")
    if business:
        print("\n业务耦合样例（前 20）:")
        for n in business[:20]:
            print("  ", n)
    print(f"\n完整清单: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
