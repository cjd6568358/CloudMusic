#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从两份一手清单证据生成「全量脱敏」Kotlin catalog + 指纹 manifest。

源证据（只读，绝不改动）：
  analysis/engine-jni-classes.json          —— 19 类 / 162 native 方法 / 202 JNI 符号
  analysis/l3-b2-m2-decoder-symbols.json   —— 100 decoder / 75 demuxer + 查询矩阵

产物（每次运行整体覆盖）：
  kt/src/NmJniCatalog.kt
  kt/src/NmFormatCatalog.kt
  spec/catalog-manifest.json

脱敏口径（项目 C2 纪律：Kotlin 源码不得出现竞品包名）：
  - 类名只取简单类名（simpleName），完整 descriptor / 包路径只留在源 JSON；
  - Java 类型只取简单类型名（保留 [] 数组后缀），JVM 描述符 Lxxx/yyy/Z; -> Z；
  - declaration 由脱敏后的 return/params 重组，不照抄原串；
  - 生成器末尾硬断言：输出文本零 "com.netease" / "com/netease"。

用法：python tools/gen_catalog.py
随后必须跑：python tools/build.py && python tools/audit.py
"""

import hashlib
import json
from pathlib import Path

RE = Path(__file__).resolve().parent.parent
REPO = RE.parent
ANALYSIS = REPO / "analysis"
KT_DIR = RE / "kt" / "src"
SPEC_DIR = RE / "spec"

JNI_JSON = ANALYSIS / "engine-jni-classes.json"
DEC_JSON = ANALYSIS / "l3-b2-m2-decoder-symbols.json"

OUT_JNI = KT_DIR / "NmJniCatalog.kt"
OUT_FMT = KT_DIR / "NmFormatCatalog.kt"
OUT_MANIFEST = SPEC_DIR / "catalog-manifest.json"


# ---------------------------------------------------------------------------
# 脱敏
# ---------------------------------------------------------------------------

def simple_type(t: str) -> str:
    """Java 类型 -> 简单类型名（保留数组后缀）。"""
    arr = ""
    while t.endswith("[]"):
        arr += "[]"
        t = t[:-2]
    return t.rsplit(".", 1)[-1] + arr


def simple_desc(d: str) -> str:
    """JVM 描述符 -> 去包路径的简单形式，数组前缀保留。"""
    prefix = ""
    while d.startswith("["):
        prefix += "["
        d = d[1:]
    if d.startswith("L") and d.endswith(";"):
        return prefix + d[1:-1].rsplit("/", 1)[-1]
    return prefix + d


def make_declaration(java_return: str, name: str, java_params: list) -> str:
    """由脱敏后的类型重组声明串，避免照抄含包路径的原 declaration。"""
    params = ", ".join(simple_type(p) for p in java_params)
    return f"{simple_type(java_return)} {name}({params})"


def kstr(s: str) -> str:
    """Kotlin 字符串字面量转义（含 $ —— 类名里有内部类 $ 符号，必须转义）。"""
    return (
        '"' + s.replace("\\", "\\\\").replace('"', '\\"')
        .replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r") + '"'
    )


def klist(items, indent: str = "        ") -> str:
    if not items:
        return "emptyList()"
    return "listOf(\n" + "".join(f"{indent}{x},\n" for x in items) + "        )"


# ---------------------------------------------------------------------------
# 渲染 NmJniCatalog.kt
# ---------------------------------------------------------------------------

def render_jni(d: dict) -> str:
    classes = d["classes"]
    class_lines = []
    method_lines = []
    for c in classes:
        simple = c["class"].rsplit(".", 1)[-1]
        class_lines.append(
            f"        NmJniClassEntry({kstr(simple)}, {str(c['preciseJniClass']).lower()}, "
            f"{kstr(c['dex'])}, {c['nativeMethodCount']}),"
        )
        for m in c["nativeMethods"]:
            params = ", ".join(kstr(simple_type(p)) for p in m["javaParams"])
            descs = ", ".join(kstr(simple_desc(x)) for x in m["descriptorParams"])
            decl = make_declaration(m["javaReturn"], m["name"], m["javaParams"])
            method_lines.append(
                f"        NmNativeMethodEntry({kstr(simple)}, {kstr(m['name'])}, "
                f"{kstr(simple_type(m['javaReturn']))}, listOf({params}), "
                f"listOf({descs}), {kstr(decl)}),"
            )

    sym_lines = [
        f"        {kstr(k)} to {v}," for k, v in d["jniSymbolsByLib"].items()
    ]
    delta_lines = [f"        {kstr(x)}," for x in d["deltaKnown"]]

    return f'''@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * Java SDK 入口层 JNI 清单 —— 由 tools/gen_catalog.py 全量生成，勿手改。
 *
 * 证据：analysis/engine-jni-classes.json（19 类 / 162 native 方法 / 202 JNI 符号）。
 * 脱敏口径（项目 C2 纪律）：本文件只登记**简单类名 / 简单类型名**，
 * 完整 descriptor 与包路径只保留在源 JSON 与 spec/catalog-manifest.json 里，
 * Kotlin 源码零竞品包路径字面量。重新生成后必须跑 build.py + audit.py。
 *
 * 本文件是**数据层全量登记**，不是控制逻辑翻译；证据等级见 spec/LOGIC-SPEC.md C 组。
 */

data class NmJniClassEntry(
    val simpleName: String,
    val preciseJniClass: Boolean,
    val dex: String,
    val nativeMethodCount: Int,
)

data class NmNativeMethodEntry(
    val ownerSimpleName: String,
    val name: String,
    val javaReturn: String,
    val javaParams: List<String>,
    val descriptorParams: List<String>,
    val declaration: String,
)

object NmJniCatalog {{
    // @spec C-01：19 个真类，其中 12 个精确 JNI 类、7 个非精确类
    const val CLASS_COUNT: Int = {d["classCount"]}
    const val PRECISE_CLASS_COUNT: Int = {d["preciseJniClassCount"]}

    // @spec C-02：162 个 native 方法，按类全量登记
    const val METHOD_COUNT: Int = {d["nativeMethodCount"]}

    // @spec C-03：202 个 JNI 符号按 4 个 so 分布
    const val JNI_SYMBOL_COUNT: Int = {d["jniSymbolCount"]}
    val JNI_SYMBOLS_BY_LIB: Map<String, Int> = mapOf(
{chr(10).join(sym_lines)}
    )

    // @spec C-06：已知增量（2 处）
    val DELTA_KNOWN: List<String> = listOf(
{chr(10).join(delta_lines)}
    )

    // @spec C-01 / C-05：19 个类（simpleName / precise / dex / 方法数）
    val CLASSES: List<NmJniClassEntry> = listOf(
{chr(10).join(class_lines)}
    )

    // @spec C-02 / C-07：162 个方法，五字段全量（已脱敏）
    val METHODS: List<NmNativeMethodEntry> = listOf(
{chr(10).join(method_lines)}
    )

    fun methodsOf(ownerSimpleName: String): List<NmNativeMethodEntry> =
        METHODS.filter {{ it.ownerSimpleName == ownerSimpleName }}

    fun preciseClasses(): List<NmJniClassEntry> = CLASSES.filter {{ it.preciseJniClass }}

    fun distinctMethodNames(): Set<String> = METHODS.map {{ it.name }}.toSet()

    fun methodCountByClass(): Map<String, Int> =
        METHODS.groupingBy {{ it.ownerSimpleName }}.eachCount()
}}
'''


# ---------------------------------------------------------------------------
# 渲染 NmFormatCatalog.kt
# ---------------------------------------------------------------------------

def render_query(q: dict) -> str:
    def strlist(key: str) -> str:
        vals = ", ".join(kstr(x) for x in q.get(key, []))
        return f"listOf({vals})" if vals else "emptyList()"
    return (
        f"        NmFormatQuery({kstr(q['query'])}, "
        f"{str(q.get('decoder_symbol_exact', False)).lower()}, "
        f"{str(q.get('demuxer_symbol_exact', False)).lower()}, "
        f"{strlist('decoder_family_matches')}, "
        f"{strlist('demuxer_family_matches')}, "
        f"{kstr(q.get('symbol_verdict', ''))}),"
    )


def render_fmt(d: dict) -> str:
    libs = d["libraries"]
    lib_lines = []
    for abi, info in libs.items():
        lib_lines.append(
            f"        {kstr(abi)} to NmLibFingerprint("
            f"{kstr(abi)}, {info['bytes']}L, {kstr(info['sha256'])}, "
            f"{info['elf_class']}, {info['e_machine']}, {info['section_count']}, "
            f"{str(info['has_symtab']).lower()}, {info['dynsym_count']}, "
            f"{info['dynsym_nonempty_names']}, {info['decoder_symbol_count']}, "
            f"{info['demuxer_symbol_count']}),"
        )

    dec_lines = [f"        {kstr(x)}," for x in d["full_decoder_symbols"]]
    dem_lines = [f"        {kstr(x)}," for x in d["full_demuxer_symbols"]]
    q9 = [render_query(x) for x in d["unconfirmed_9"]]
    q15 = [render_query(x) for x in d["candidate_15"]]
    q19 = [render_query(x) for x in d["codec_queries"]]
    boundaries = [f"        {kstr(x)}," for x in d["boundaries"]]

    return f'''@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * libffmpeg.so 格式覆盖清单 —— 由 tools/gen_catalog.py 全量生成，勿手改。
 *
 * 证据：analysis/l3-b2-m2-decoder-symbols.json（12 §61.83 M2_PREFLIGHT）。
 * 口径：ELF .dynsym 导出符号**存在性**实证；symtab 已 strip；
 * 导出符号存在 != decoder 可用 != 真机可播（F-08 边界声明原样登记）。
 * 本文件是**数据层全量登记**，不实现 M2、不改 B2 阶段状态、不宣称 F4 已解决。
 */

data class NmLibFingerprint(
    val abi: String,
    val bytes: Long,
    val sha256: String,
    val elfClass: Int,
    val eMachine: Int,
    val sectionCount: Int,
    val hasSymtab: Boolean,
    val dynsymCount: Int,
    val dynsymNonemptyNames: Int,
    val decoderSymbolCount: Int,
    val demuxerSymbolCount: Int,
)

data class NmFormatQuery(
    val query: String,
    val decoderSymbolExact: Boolean,
    val demuxerSymbolExact: Boolean,
    val decoderFamilyMatches: List<String>,
    val demuxerFamilyMatches: List<String>,
    val symbolVerdict: String,
)

object NmFormatCatalog {{
    // @spec F-01：100 个 ff_*_decoder 导出符号（全量）
    const val DECODER_SYMBOL_COUNT: Int = {d["decoder_symbol_count"]}

    // @spec F-02：75 个 ff_*_demuxer 导出符号（全量）
    const val DEMUXER_SYMBOL_COUNT: Int = {d["demuxer_symbol_count"]}

    // @spec F-03 / F-04：双 ABI 完全一致；symtab 已 strip，仅覆盖 .dynsym
    const val CROSS_ABI_IDENTICAL: Boolean = {str(d["cross_abi_identical"]).lower()}
    const val SYMTAB_STRIPPED: Boolean = {str(d["symtab_stripped"]).lower()}
    const val CHECKS_TOTAL: Int = {d["checks_total"]}
    const val CHECKS_PASS: Int = {d["checks_pass"]}
    const val ALL_PASS: Boolean = {str(d["all_pass"]).lower()}
    val EVIDENCE_LEVEL: String = {kstr(d["evidence_level"])}
    val PRIOR_EVIDENCE_LEVEL: String = {kstr(d["prior_evidence_level"])}
    val VERDICT: String = {kstr(d["verdict"])}

    // 两个 ABI 的 ELF 指纹（不含工程路径，仅登记可核验的元数据）
    val LIBRARIES: Map<String, NmLibFingerprint> = mapOf(
{chr(10).join(lib_lines)}
    )

    // @spec F-01：全量 decoder 符号
    val DECODER_SYMBOLS: List<String> = listOf(
{chr(10).join(dec_lines)}
    )

    // @spec F-02：全量 demuxer 符号
    val DEMUXER_SYMBOLS: List<String> = listOf(
{chr(10).join(dem_lines)}
    )

    // @spec F-05：9 个「decoder 编入未确认」格式查询
    val UNCONFIRMED_9: List<NmFormatQuery> = listOf(
{chr(10).join(q9)}
    )

    // @spec F-06：15 个候选格式查询
    val CANDIDATE_15: List<NmFormatQuery> = listOf(
{chr(10).join(q15)}
    )

    // @spec F-07：19 个 codec 查询
    val CODEC_QUERIES: List<NmFormatQuery> = listOf(
{chr(10).join(q19)}
    )

    // @spec F-08：8 条边界声明（原样登记，不作法律/能力结论）
    val BOUNDARIES: List<String> = listOf(
{chr(10).join(boundaries)}
    )

    fun hasDecoder(symbol: String): Boolean = DECODER_SYMBOLS.contains(symbol)
    fun hasDemuxer(symbol: String): Boolean = DEMUXER_SYMBOLS.contains(symbol)

    fun queriesWithVerdict(verdict: String): List<NmFormatQuery> =
        (UNCONFIRMED_9 + CANDIDATE_15 + CODEC_QUERIES).filter {{ it.symbolVerdict == verdict }}
}}
'''


# ---------------------------------------------------------------------------
# manifest
# ---------------------------------------------------------------------------

def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_manifest(jni: dict, dec: dict) -> dict:
    return {
        "tool": "reverse-engine/tools/gen_catalog.py",
        "sanitize": {
            "rule": "simpleName / simpleType only; NO package paths in Kotlin",
            "forbiddenLiterals": ["com.netease", "com/netease"],
            "declaration": "rebuilt from sanitized return+params, never copied verbatim",
        },
        "sources": {
            JNI_JSON.name: {
                "sha256": sha256_of(JNI_JSON),
                "bytes": JNI_JSON.stat().st_size,
                "classCount": jni["classCount"],
                "preciseJniClassCount": jni["preciseJniClassCount"],
                "nativeMethodCount": jni["nativeMethodCount"],
                "jniSymbolCount": jni["jniSymbolCount"],
            },
            DEC_JSON.name: {
                "sha256": sha256_of(DEC_JSON),
                "bytes": DEC_JSON.stat().st_size,
                "decoderSymbolCount": dec["decoder_symbol_count"],
                "demuxerSymbolCount": dec["demuxer_symbol_count"],
                "unconfirmed": len(dec["unconfirmed_9"]),
                "candidate": len(dec["candidate_15"]),
                "codecQueries": len(dec["codec_queries"]),
                "boundaries": len(dec["boundaries"]),
            },
        },
        "outputs": {
            OUT_JNI.name: {
                "classes": jni["classCount"],
                "methods": jni["nativeMethodCount"],
                "symbolsByLib": jni["jniSymbolCount"],
                "deltaKnown": len(jni["deltaKnown"]),
            },
            OUT_FMT.name: {
                "decoders": dec["decoder_symbol_count"],
                "demuxers": dec["demuxer_symbol_count"],
                "unconfirmed": len(dec["unconfirmed_9"]),
                "candidate": len(dec["candidate_15"]),
                "codecQueries": len(dec["codec_queries"]),
                "boundaries": len(dec["boundaries"]),
            },
        },
        "kotlinConstants": {
            "CLASS_COUNT": jni["classCount"],
            "PRECISE_CLASS_COUNT": jni["preciseJniClassCount"],
            "METHOD_COUNT": jni["nativeMethodCount"],
            "JNI_SYMBOL_COUNT": jni["jniSymbolCount"],
            "DECODER_SYMBOL_COUNT": dec["decoder_symbol_count"],
            "DEMUXER_SYMBOL_COUNT": dec["demuxer_symbol_count"],
        },
        "specIds": {
            "C": [f"C-{i:02d}" for i in range(1, 9)],
            "F": [f"F-{i:02d}" for i in range(1, 9)],
        },
    }


def main() -> int:
    jni = json.loads(JNI_JSON.read_text(encoding="utf-8"))
    dec = json.loads(DEC_JSON.read_text(encoding="utf-8"))

    # 源数据自洽校验（防止上游 JSON 被改动后仍生成"看起来对"的 catalog）
    assert sum(len(c["nativeMethods"]) for c in jni["classes"]) == jni["nativeMethodCount"]
    assert len(jni["classes"]) == jni["classCount"]
    assert len(dec["full_decoder_symbols"]) == dec["decoder_symbol_count"]
    assert len(dec["full_demuxer_symbols"]) == dec["demuxer_symbol_count"]

    jni_kt = render_jni(jni)
    fmt_kt = render_fmt(dec)

    # 脱敏硬断言：生成的 Kotlin 里绝不能出现竞品包路径
    for name, text in ((OUT_JNI.name, jni_kt), (OUT_FMT.name, fmt_kt)):
        for banned in ("com.netease", "com/netease"):
            assert banned not in text, f"{name} contains forbidden literal: {banned}"

    KT_DIR.mkdir(parents=True, exist_ok=True)
    SPEC_DIR.mkdir(parents=True, exist_ok=True)
    # 两步写：先读进变量再写，避免 Windows 上同文件截断
    OUT_JNI.write_text(jni_kt, encoding="utf-8", newline="\n")
    OUT_FMT.write_text(fmt_kt, encoding="utf-8", newline="\n")
    manifest = build_manifest(jni, dec)
    OUT_MANIFEST.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n"
    )

    print(f"[GEN] {OUT_JNI.name}: {jni['classCount']} classes / {jni['nativeMethodCount']} methods")
    print(f"[GEN] {OUT_FMT.name}: {dec['decoder_symbol_count']} decoders / {dec['demuxer_symbol_count']} demuxers")
    print(f"[GEN] {OUT_MANIFEST.name}")
    print("[GEN] sanitize assertion: OK (zero com.netease / com/netease)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
