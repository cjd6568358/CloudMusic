#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
reverse-engine 交付链的长效审计守护（纯 Python 标准库）。

检查项：
  1. 编号闭环：kt/src/*.kt 与 test/*.kt 里每个 @spec 编号必须在 spec/LOGIC-SPEC.md 第 3 节出现；
  2. 零悬空：规格表格里的编号必须都在代码里出现；
  3. 等级完整：每条规格必须标注 L1 / L2 / L3；
  4. 禁项扫描：Kotlin 源码不得出现 com.netease / android.* / 绝对路径；
  5. 产物校验：build/test-report.json 的 overallOk=true 且契约测试失败数为 0；
  6. 清单指纹：spec/catalog-manifest.json 记录的源 JSON sha256 与当前磁盘一致（防证据回潮）；
  7. 清单计数：Kotlin catalog 的 *_COUNT 常量与 manifest 的 kotlinConstants 一致；
  8. D 组指纹：spec/done-chain-manifest.json 记录的两份 §61.54/§61.55 源 JSON sha256 与磁盘一致；
  9. D 组常量：NmBufferSourceImp.kt 的 D 组元数据常量与 manifest 的 kotlinConstants 一致；
  10. R 组指纹：spec/read-gap-manifest.json 记录的 §61.77 源 JSON sha256 与磁盘一致；
  11. R 组常量：NmReadGapCatalog.kt 的 33 项常量与 manifest 的 kotlinConstants 一致；
  12. 再清点裁定：uncovered-evidence-scan.json 的 highRisk 名单指纹与裁定表一致，且逐份裁定完整；
  13. U 组指纹：a3-meta-manifest.json 记录的 7 份源 sha256 与当前磁盘一致；
  14. U 组常量：NmDisasmMetaCatalog.kt 的 Int/String 常量与 manifest 一致；
  15. V 组指纹：chapter-matrix-manifest.json 记录的 2 份扫描快照 sha256 / bytes 与磁盘一致；
  16. V 组常量：NmChapterMatrixCatalog.kt 的 39 项 Int/String/Boolean 常量与 manifest 一致；
  17. W 组指纹：w-disasm-manifest.json 记录的一手反汇编证据 sha256/bytes 与磁盘一致，且证据锚点全通过、so 指纹一致；
  18. W 组常量：NmWDisasmCatalog.kt 的 21 项 Int / 2 项 String 常量与 manifest 一致。

输出：build/audit-report.json（含 18 项检查明细、清单/D 组/R 组/U 组/V 组/W 组指纹结果）；退出码 0 = 全通过。

用法：python tools/audit.py
"""

import hashlib
import json
import re
import sys
from pathlib import Path

RE = Path(__file__).resolve().parent.parent
KT_DIR = RE / "kt" / "src"
TEST_DIR = RE / "test"
SPEC_PATH = RE / "spec" / "LOGIC-SPEC.md"
BUILD_DIR = RE / "build"
REPORT_PATH = BUILD_DIR / "test-report.json"
OUT_PATH = BUILD_DIR / "audit-report.json"

ID_RE = re.compile(r"\b([A-Z]-\d+)\b")
SPEC_ROW_RE = re.compile(r"^\|\s*([A-Z]-\d+)\s*\|")

BANNED_PATTERNS = [
    ("com.netease namespace", re.compile(r"com\.netease", re.IGNORECASE)),
    ("android framework import", re.compile(r"^\s*import\s+android\.", re.MULTILINE)),
    ("windows absolute path", re.compile(r"[A-Za-z]:[\\/]")),
    ("posix absolute path", re.compile(r"(?<![\w])/(?:Users|home|usr|opt|var)/")),
]


def code_spec_ids() -> dict:
    """返回 {编号: [出现的文件名...]}，从含 @spec 的行里提取全部编号。"""
    result: dict = {}
    for p in sorted(list(KT_DIR.glob("*.kt")) + list(TEST_DIR.glob("*.kt"))):
        for line in p.read_text(encoding="utf-8").splitlines():
            if "@spec" in line:
                for m in ID_RE.finditer(line):
                    result.setdefault(m.group(1), set()).add(p.name)
    return {k: sorted(v) for k, v in result.items()}


def spec_table_ids() -> dict:
    """从 LOGIC-SPEC.md 第 3 节的表格行提取 {编号: 是否标注等级}。"""
    result: dict = {}
    for line in SPEC_PATH.read_text(encoding="utf-8").splitlines():
        m = SPEC_ROW_RE.match(line)
        if m:
            has_level = bool(re.search(r"\bL[123]\b", line))
            result[m.group(1)] = has_level
    return result


def banned_hits() -> list:
    hits = []
    for p in sorted(list(KT_DIR.glob("*.kt")) + list(TEST_DIR.glob("*.kt"))):
        text = p.read_text(encoding="utf-8")
        for label, pattern in BANNED_PATTERNS:
            for m in pattern.finditer(text):
                line_no = text[: m.start()].count("\n") + 1
                hits.append({"file": p.name, "line": line_no, "kind": label, "text": m.group(0)})
    return hits


def product_check() -> dict:
    if not REPORT_PATH.exists():
        return {"ok": False, "reason": "build/test-report.json missing"}
    data = json.loads(REPORT_PATH.read_text(encoding="utf-8"))
    overall = bool(data.get("overallOk"))
    stdout = (data.get("test") or {}).get("stdout", "")
    m = re.search(r"CONTRACT TESTS:\s*(\d+)/(\d+)\s+passed,\s*(\d+)\s+failed", stdout)
    if not m:
        return {"ok": False, "overallOk": overall, "reason": "test summary line not found"}
    passed, total, failed = int(m.group(1)), int(m.group(2)), int(m.group(3))
    return {
        "ok": overall and failed == 0 and passed == total,
        "overallOk": overall,
        "passed": passed,
        "total": total,
        "failed": failed,
    }


def catalog_check() -> dict:
    """⑥ 源 JSON sha256 与 manifest 一致；⑦ Kotlin *_COUNT 常量与 manifest 一致。"""
    manifest_path = RE / "spec" / "catalog-manifest.json"
    analysis_dir = RE.parent / "analysis"
    if not manifest_path.exists():
        return {"ok": False, "fingerprintOk": False, "countsOk": False,
                "reason": "catalog-manifest.json missing"}
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    src_results = {}
    for name, info in (manifest.get("sources") or {}).items():
        p = analysis_dir / name
        if not p.exists():
            src_results[name] = {"ok": False, "reason": "source file missing"}
            continue
        actual = hashlib.sha256(p.read_bytes()).hexdigest()
        src_results[name] = {
            "ok": actual == info.get("sha256"),
            "expected": info.get("sha256"),
            "actual": actual,
            "bytes": p.stat().st_size,
            "bytesMatch": p.stat().st_size == info.get("bytes"),
        }

    jni_kt = KT_DIR / "NmJniCatalog.kt"
    fmt_kt = KT_DIR / "NmFormatCatalog.kt"
    kt_text = jni_kt.read_text(encoding="utf-8") if jni_kt.exists() else ""
    kt_text += "\n" + (fmt_kt.read_text(encoding="utf-8") if fmt_kt.exists() else "")
    const_results = {}
    for name, expected in (manifest.get("kotlinConstants") or {}).items():
        m = re.search(r"const val " + re.escape(name) + r": Int = (\d+)", kt_text)
        const_results[name] = {
            "ok": bool(m) and int(m.group(1)) == expected,
            "expected": expected,
            "actual": int(m.group(1)) if m else None,
        }

    fingerprint_ok = bool(src_results) and all(v.get("ok") for v in src_results.values())
    counts_ok = bool(const_results) and all(v.get("ok") for v in const_results.values())
    return {
        "ok": fingerprint_ok and counts_ok,
        "fingerprintOk": fingerprint_ok,
        "countsOk": counts_ok,
        "sources": src_results,
        "constants": const_results,
    }


def parse_int_const(text: str, name: str):
    """读取 Kotlin `const val NAME: Int = <十进制|0x十六进制>`，返回 int 或 None。

    支持负立即数（W 组错误码 -6/-3/-2/-98/-102 等由 mov x0,#-N 直接登记）。
    """
    m = re.search(
        r"const val " + re.escape(name) + r": Int = (-?0[xX][0-9A-Fa-f]+|-?\d+)",
        text,
    )
    if not m:
        return None
    raw = m.group(1)
    neg = raw.startswith("-")
    if neg:
        raw = raw[1:]
    value = int(raw, 16) if raw.lower().startswith("0x") else int(raw)
    return -value if neg else value


def done_chain_check() -> dict:
    """⑧ §61.54/§61.55 源 JSON sha256 与 manifest 一致；⑨ D 组 Kotlin 常量与 manifest 一致。"""
    manifest_path = RE / "spec" / "done-chain-manifest.json"
    if not manifest_path.exists():
        return {"ok": False, "fingerprintOk": False, "constantsOk": False,
                "reason": "done-chain-manifest.json missing"}
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    analysis_dir = RE.parent / "analysis"

    src_results = {}
    for name, info in (manifest.get("sources") or {}).items():
        p = analysis_dir / name
        if not p.exists():
            src_results[name] = {"ok": False, "reason": "source file missing"}
            continue
        actual = hashlib.sha256(p.read_bytes()).hexdigest()
        size = p.stat().st_size
        src_results[name] = {
            "ok": actual == info.get("sha256") and size == info.get("bytes"),
            "expected": info.get("sha256"),
            "actual": actual,
            "bytes": size,
            "bytesMatch": size == info.get("bytes"),
        }

    kt_path = KT_DIR / "NmBufferSourceImp.kt"
    kt_text = kt_path.read_text(encoding="utf-8") if kt_path.exists() else ""
    const_results = {}
    for name, expected in (manifest.get("kotlinConstants") or {}).items():
        actual = parse_int_const(kt_text, name)
        const_results[name] = {
            "ok": actual is not None and actual == expected,
            "expected": expected,
            "actual": actual,
        }

    fingerprint_ok = bool(src_results) and all(v.get("ok") for v in src_results.values())
    constants_ok = bool(const_results) and all(v.get("ok") for v in const_results.values())
    return {
        "ok": fingerprint_ok and constants_ok,
        "fingerprintOk": fingerprint_ok,
        "constantsOk": constants_ok,
        "sources": src_results,
        "constants": const_results,
    }


def read_gap_check() -> dict:
    """⑩ §61.77 源 JSON sha256 与 manifest 一致；⑪ R 组 Kotlin 常量与 manifest 一致。"""
    manifest_path = RE / "spec" / "read-gap-manifest.json"
    if not manifest_path.exists():
        return {"ok": False, "fingerprintOk": False, "constantsOk": False,
                "reason": "read-gap-manifest.json missing"}
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    src = manifest.get("source") or {}
    src_rel = src.get("path", "")
    p = RE.parent / src_rel
    if not p.exists():
        return {"ok": False, "fingerprintOk": False, "constantsOk": False,
                "reason": "source file missing: %s" % src_rel}
    raw = p.read_bytes()
    actual_sha = hashlib.sha256(raw).hexdigest()
    src_results = {
        src_rel: {
            "ok": actual_sha == src.get("sha256") and len(raw) == src.get("bytes"),
            "expected": src.get("sha256"),
            "actual": actual_sha,
            "bytes": len(raw),
            "bytesMatch": len(raw) == src.get("bytes"),
        }
    }

    kt_path = KT_DIR / "NmReadGapCatalog.kt"
    kt_text = kt_path.read_text(encoding="utf-8") if kt_path.exists() else ""
    const_results = {}
    for name, expected in (manifest.get("kotlinConstants") or {}).items():
        actual = parse_int_const(kt_text, name)
        const_results[name] = {
            "ok": actual is not None and actual == expected,
            "expected": expected,
            "actual": actual,
        }

    fingerprint_ok = bool(src_results) and all(v.get("ok") for v in src_results.values())
    constants_ok = bool(const_results) and all(v.get("ok") for v in const_results.values())
    return {
        "ok": fingerprint_ok and constants_ok,
        "fingerprintOk": fingerprint_ok,
        "constantsOk": constants_ok,
        "sources": src_results,
        "constants": const_results,
    }

def adjudication_check() -> dict:
    """第 12 项：C 类再清点裁定表守护（名单指纹 + 逐份裁定完整）。"""
    scan_path = RE / "spec" / "uncovered-evidence-scan.json"
    adj_path = RE / "spec" / "uncovered-evidence-adjudications.json"
    if not (scan_path.is_file() and adj_path.is_file()):
        return {"ok": False, "reason": "missing scan or adjudications file"}
    scan = json.loads(scan_path.read_text(encoding="utf-8"))
    adj = json.loads(adj_path.read_text(encoding="utf-8"))
    high = scan.get("highRisk") or []
    payload = [[r["name"], r["score"]] for r in high]
    fp = hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    fingerprint_ok = (
        adj.get("highRiskCount") == len(high)
        and adj.get("scanFingerprint") == fp
    )
    rows = adj.get("adjudications") or []
    allowed = set(adj.get("allowedVerdicts") or [])
    covered = {r.get("name") for r in rows}
    all_covered = all(r["name"] in covered for r in high)
    verdicts_ok = all(
        r.get("verdict") in allowed and r.get("reason") and r.get("specId")
        for r in rows
    )
    return {
        "ok": bool(fingerprint_ok and all_covered and verdicts_ok),
        "fingerprintOk": fingerprint_ok,
        "allCovered": all_covered,
        "verdictsOk": verdicts_ok,
        "highRiskCount": len(high),
        "adjudicationCount": len(rows),
    }


def a3_meta_check() -> dict:
    """第 13/14 项：U 组 7 份源 sha256 指纹 + Kotlin catalog 常量一致性。"""
    m_path = RE / "spec" / "a3-meta-manifest.json"
    if not m_path.is_file():
        return {"ok": False, "fingerprintOk": False, "constantsOk": False,
                "reason": "missing a3-meta-manifest.json"}
    m = json.loads(m_path.read_text(encoding="utf-8"))
    fingerprint_ok = True
    for src in m.get("sources") or []:
        p = RE.parent / src["path"]
        if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest() != src["sha256"]:
            fingerprint_ok = False
    kt_path = KT_DIR / "NmDisasmMetaCatalog.kt"
    kt_text = kt_path.read_text(encoding="utf-8") if kt_path.is_file() else ""
    constants_ok = True
    for name, expected in (m.get("kotlinIntConstants") or {}).items():
        if parse_int_const(kt_text, name) != expected:
            constants_ok = False
    for name, expected in (m.get("kotlinStringConstants") or {}).items():
        if ('const val %s: String = "%s"' % (name, expected)) not in kt_text:
            constants_ok = False
    return {
        "ok": bool(fingerprint_ok and constants_ok),
        "fingerprintOk": fingerprint_ok,
        "constantsOk": constants_ok,
        "sourceCount": len(m.get("sources") or []),
        "intConstCount": len(m.get("kotlinIntConstants") or {}),
    }
def chapter_matrix_check() -> dict:
    """第 15/16 项：V 组 2 份扫描快照 sha256 + NmChapterMatrixCatalog.kt 39 项常量一致性。"""
    m_path = RE / "spec" / "chapter-matrix-manifest.json"
    if not m_path.is_file():
        return {"ok": False, "fingerprintOk": False, "constantsOk": False,
                "reason": "missing chapter-matrix-manifest.json"}
    m = json.loads(m_path.read_text(encoding="utf-8"))

    src_results = {}
    for src in m.get("sources") or []:
        rel = src.get("path", "")
        p = RE / rel
        if not p.is_file():
            src_results[rel] = {"ok": False, "reason": "source file missing"}
            continue
        raw = p.read_bytes()
        actual = hashlib.sha256(raw).hexdigest()
        src_results[rel] = {
            "ok": actual == src.get("sha256") and len(raw) == src.get("bytes"),
            "expected": src.get("sha256"),
            "actual": actual,
            "bytes": len(raw),
        }

    kt_path = KT_DIR / "NmChapterMatrixCatalog.kt"
    kt_text = kt_path.read_text(encoding="utf-8") if kt_path.is_file() else ""
    const_results = {}
    for name, expected in (m.get("kotlinIntConstants") or {}).items():
        actual = parse_int_const(kt_text, name)
        const_results[name] = {"ok": actual is not None and actual == expected,
                               "expected": expected, "actual": actual}
    for name, expected in (m.get("kotlinStringConstants") or {}).items():
        needle = 'const val %s: String = "%s"' % (name, expected)
        const_results[name] = {"ok": needle in kt_text, "expected": expected}
    for name, expected in (m.get("kotlinBooleanConstants") or {}).items():
        needle = "const val %s: Boolean = %s" % (name, "true" if expected else "false")
        const_results[name] = {"ok": needle in kt_text, "expected": expected}

    fingerprint_ok = bool(src_results) and all(v.get("ok") for v in src_results.values())
    constants_ok = bool(const_results) and all(v.get("ok") for v in const_results.values())
    return {
        "ok": bool(fingerprint_ok and constants_ok),
        "fingerprintOk": fingerprint_ok,
        "constantsOk": constants_ok,
        "sources": src_results,
        "constants": const_results,
    }


def w_disasm_check() -> dict:
    """第 17/18 项：W 组一手反汇编证据指纹 + NmWDisasmCatalog.kt 常量一致性。"""
    m_path = RE / "spec" / "w-disasm-manifest.json"
    if not m_path.is_file():
        return {"ok": False, "fingerprintOk": False, "constantsOk": False,
                "reason": "missing w-disasm-manifest.json"}
    m = json.loads(m_path.read_text(encoding="utf-8"))

    src_results = {}
    for src in m.get("sources") or []:
        rel = src.get("path", "")
        p = RE / rel
        if not p.is_file():
            src_results[rel] = {"ok": False, "reason": "source file missing"}
            continue
        raw = p.read_bytes()
        actual = hashlib.sha256(raw).hexdigest()
        src_results[rel] = {
            "ok": actual == src.get("sha256") and len(raw) == src.get("bytes"),
            "expected": src.get("sha256"),
            "actual": actual,
            "bytes": len(raw),
        }

    # 证据 JSON 内部：锚点全通过 + so 指纹与 manifest 记录一致
    ev_path = RE / "spec" / "w-disasm-evidence.json"
    ev_ok = False
    if ev_path.is_file():
        ev = json.loads(ev_path.read_text(encoding="utf-8"))
        ev_ok = bool(ev.get("all_pass")) and ev.get("so", {}).get("sha256") == m.get("so", {}).get("sha256")

    kt_path = KT_DIR / "NmWDisasmCatalog.kt"
    kt_text = kt_path.read_text(encoding="utf-8") if kt_path.is_file() else ""
    const_results = {}
    for name, expected in (m.get("kotlinIntConstants") or {}).items():
        actual = parse_int_const(kt_text, name)
        const_results[name] = {"ok": actual is not None and actual == expected,
                               "expected": expected, "actual": actual}
    for name, expected in (m.get("kotlinStringConstants") or {}).items():
        needle = 'const val %s: String = "%s"' % (name, expected)
        const_results[name] = {"ok": needle in kt_text, "expected": expected}

    fingerprint_ok = bool(src_results) and all(v.get("ok") for v in src_results.values()) and ev_ok
    constants_ok = bool(const_results) and all(v.get("ok") for v in const_results.values())
    return {
        "ok": bool(fingerprint_ok and constants_ok),
        "fingerprintOk": fingerprint_ok,
        "constantsOk": constants_ok,
        "evidenceAllPass": ev_ok,
        "sources": src_results,
        "constants": const_results,
    }


def main() -> int:
    code_ids = code_spec_ids()
    spec_ids = spec_table_ids()
    banned = banned_hits()
    product = product_check()
    catalog = catalog_check()
    done_chain = done_chain_check()
    read_gap = read_gap_check()
    adjudications = adjudication_check()
    a3_meta = a3_meta_check()
    chapter_matrix = chapter_matrix_check()
    w_disasm = w_disasm_check()

    missing_in_spec = sorted(set(code_ids) - set(spec_ids))
    orphan_in_spec = sorted(set(spec_ids) - set(code_ids))
    missing_level = sorted(k for k, v in spec_ids.items() if not v)

    checks = {
        "codeIdsCoveredBySpec": len(missing_in_spec) == 0,
        "noOrphanSpecIds": len(orphan_in_spec) == 0,
        "allSpecRowsHaveEvidenceLevel": len(missing_level) == 0,
        "noBannedPatterns": len(banned) == 0,
        "testProductValid": bool(product.get("ok")),
        "catalogFingerprintMatches": bool(catalog.get("fingerprintOk")),
        "catalogCountsMatchManifest": bool(catalog.get("countsOk")),
        "doneChainFingerprintMatches": bool(done_chain.get("fingerprintOk")),
        "doneChainConstantsMatch": bool(done_chain.get("constantsOk")),
        "readGapFingerprintMatches": bool(read_gap.get("fingerprintOk")),
        "readGapConstantsMatch": bool(read_gap.get("constantsOk")),
        "uncoveredEvidenceAdjudicationsClean": bool(adjudications.get("ok")),
        "a3MetaFingerprintMatches": bool(a3_meta.get("fingerprintOk")),
        "a3MetaConstantsMatch": bool(a3_meta.get("constantsOk")),
        "chapterMatrixFingerprintMatches": bool(chapter_matrix.get("fingerprintOk")),
        "chapterMatrixConstantsMatch": bool(chapter_matrix.get("constantsOk")),
        "wDisasmFingerprintMatches": bool(w_disasm.get("fingerprintOk")),
        "wDisasmConstantsMatch": bool(w_disasm.get("constantsOk")),
    }
    overall = all(checks.values())

    groups: dict = {}
    for k in code_ids:
        groups.setdefault(k[0], []).append(k)
    group_counts = {g: len(v) for g, v in sorted(groups.items())}

    report = {
        "tool": "reverse-engine/tools/audit.py",
        "pass": overall,
        "checks": checks,
        "specIdTotal": len(spec_ids),
        "codeIdTotal": len(code_ids),
        "groupCounts": group_counts,
        "missingInSpec": missing_in_spec,
        "orphanInSpec": orphan_in_spec,
        "missingEvidenceLevel": missing_level,
        "bannedHits": banned,
        "testProduct": product,
        "catalog": catalog,
        "doneChain": done_chain,
        "readGap": read_gap,
        "uncoveredEvidence": adjudications,
        "a3Meta": a3_meta,
        "chapterMatrix": chapter_matrix,
        "wDisasm": w_disasm,
    }
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")

    print(f"[AUDIT] pass={overall}")
    for name, ok in checks.items():
        print(f"  - {name}: {'OK' if ok else 'FAIL'}")
    print(f"  specIds={len(spec_ids)} codeIds={len(code_ids)} groups={group_counts}")
    if missing_in_spec:
        print(f"  missingInSpec={missing_in_spec}")
    if orphan_in_spec:
        print(f"  orphanInSpec={orphan_in_spec}")
    if missing_level:
        print(f"  missingEvidenceLevel={missing_level}")
    if banned:
        print(f"  bannedHits={banned}")
    print(f"  testProduct={product}")
    print(f"  catalog fingerprint={catalog.get('fingerprintOk')} counts={catalog.get('countsOk')}")
    print(f"  doneChain fingerprint={done_chain.get('fingerprintOk')} constants={done_chain.get('constantsOk')}")
    print(f"  readGap fingerprint={read_gap.get('fingerprintOk')} constants={read_gap.get('constantsOk')}")
    print(f"  adjudications clean={adjudications.get('ok')} highRisk={adjudications.get('highRiskCount')}")
    print(f"  a3Meta fingerprint={a3_meta.get('fingerprintOk')} constants={a3_meta.get('constantsOk')}")
    print(f"  chapterMatrix fingerprint={chapter_matrix.get('fingerprintOk')} constants={chapter_matrix.get('constantsOk')}")
    print(f"  wDisasm fingerprint={w_disasm.get('fingerprintOk')} constants={w_disasm.get('constantsOk')}")
    if not catalog.get("ok"):
        print(f"  catalogDetail={catalog}")
    if not done_chain.get("ok"):
        print(f"  doneChainDetail={done_chain}")
    if not read_gap.get("ok"):
        print(f"  readGapDetail={read_gap}")
    if not adjudications.get("ok"):
        print(f"  adjudicationsDetail={adjudications}")
    if not a3_meta.get("ok"):
        print(f"  a3MetaDetail={a3_meta}")
    if not chapter_matrix.get("ok"):
        print(f"  chapterMatrixDetail={chapter_matrix}")
    if not w_disasm.get("ok"):
        print(f"  wDisasmDetail={w_disasm}")
    print(f"[REPORT] {OUT_PATH}")
    return 0 if overall else 1


if __name__ == "__main__":
    sys.exit(main())
