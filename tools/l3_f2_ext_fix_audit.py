#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.48 / F2-b + F2-c · ResourceInfo.extension 推断链修正与真机复验审计
（只读、纯标准库、可复跑、不调用 adb、不覆盖历史证据）。

产出 analysis/l3-f2-ext-fix.json，钉死：
  - 推断逻辑收敛在 :core-model 的 AudioFormats.extOfSource（单一真相源）；
  - EngineAdapter 已接线、旧的「MIME 当扩展名」Elvis 链已移除；
  - :engine-legacy 本就依赖 :core-model → 零新增 Gradle 依赖；
  - 历史证据（review §4.2 / A3 E4 / §61.47 真机）在位；
  - F2-c 真机复验日志的 extension 值与解码结果（读取已落盘的日志，不重采设备）；
  - 发布基线（19 模块 / 11 权限 / 20 so / 31 docs / 61 行 csv）零变化；
  - 诚实边界：截图为通知栏、REMOTE 路径未在本批重跑、F3 未做。

不修改任何生产文件、不猜测 native 行为、不伪造真机验证结论。
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AUDIO_FORMATS = ROOT / "core-model/src/main/kotlin/com/example/cloudmusic/model/AudioFormats.kt"
AUDIO_FORMATS_TEST = ROOT / "core-model/src/test/kotlin/com/example/cloudmusic/model/AudioFormatsTest.kt"
ENGINE_ADAPTER = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/EngineAdapter.kt"
ENGINE_GRADLE = ROOT / "engine-legacy/build.gradle.kts"
SETTINGS = ROOT / "settings.gradle.kts"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
ASSETS_CSV = ROOT / "docs/assets-mapping.csv"
FINGERPRINT = ROOT / "assets/ENGINE-FINGERPRINT"
SO_DIR = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a"
DOCS_DIR = ROOT / "docs"
OVERVIEW = ROOT / "docs/09-文档闭环总览.md"
RES_DIR = ROOT / "app/src/main/res"

REVIEW = ROOT / "analysis/review-and-diagnosis-2026-11-07.md"
A3_EVIDENCE = ROOT / "analysis/a3-phase-ab-evidence-2026-11-07.md"
S6147_LOG = ROOT / "analysis/l3-f2-device-probe-logcat.txt"
F2C_LOG = ROOT / "analysis/l3-f2c-extension-verify-logcat.txt"
F2C_KEY = ROOT / "analysis/l3-f2c-key-logcat.txt"
F2C_SHOT = ROOT / "analysis/l3-reference/self-ui-f2c-extension-mp3.png"
OUT = ROOT / "analysis/l3-f2-ext-fix.json"

# F2-b 源码锚点：缺一即视为推断链被拆散或回退。
FORMATS_ANCHORS = (
    "fun extOfSource(url: String?, localPath: String?, mimeHint: String?): String",
    "private val EXT_BY_MIME",
    "inverse.putIfAbsent(mime, ext)",
    "for (candidate in listOfNotNull(url, localPath))",
    "ext.length <= 5",
    "ext.all { it.isLetterOrDigit() }",
    "mime != OCTET_STREAM",
    'return ""',
)
ADAPTER_ANCHORS = (
    "com.example.cloudmusic.model.AudioFormats.extOfSource(",
    "source.url, source.localPath, source.mimeHint,",
    "resourceInfo extension=$extension fileSize=$length",
)
ADAPTER_FORBIDDEN = (
    "?: source.mimeHint.orEmpty()",
    "待真机验证的假设",
)
HISTORY_ANCHORS = {
    "review_4_2_calibration": (REVIEW, "### 4.2 第二轮：`ResourceInfo.extension` 校准实验"),
    "review_4_2_extension_mp3": (REVIEW, "resourceInfo extension=mp3 fileSize=0"),
    "a3_e4_extension_mp3_37824": (A3_EVIDENCE, "resourceInfo extension=mp3 fileSize=37824"),
    "s61_47_mime_as_extension": (S6147_LOG, "resourceInfo extension=audio/mpeg fileSize=-1"),
}
F2C_REQUIRED = (
    "resourceInfo extension=mp3 fileSize=-1",
    "prepare trackId=",
    "remote=false url=null",
    "getLocalPath path=/data/data/com.example.cloudmusic/files/music/fixture.mp3",
    "CreateLocalSource: route XorLocalSource",
    "XorLocalSource::Open success",
    "BufferSource open:",
    "Open codec for mp3",
    "mDuration 4704",
    "Play status 4",
    "Write start play nErr:0",
)
F2C_FORBIDDEN = (
    "resourceInfo extension=audio/mpeg",
    "resourceInfo extension=audio/",
    "SourceCallback.onError code=",
    "FATAL EXCEPTION",
    "ANR in com.example.cloudmusic",
)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def main() -> int:
    required = (
        AUDIO_FORMATS, AUDIO_FORMATS_TEST, ENGINE_ADAPTER, ENGINE_GRADLE,
        SETTINGS, MANIFEST, ASSETS_CSV, FINGERPRINT, REVIEW, A3_EVIDENCE,
        S6147_LOG, F2C_LOG, F2C_KEY, F2C_SHOT,
    )
    missing = [str(p.relative_to(ROOT)).replace("\\", "/") for p in required if not p.is_file()]
    if missing:
        print("[FAIL] 12 §61.48 审计：缺少文件 %s" % missing)
        return 1

    formats = read(AUDIO_FORMATS)
    tests = read(AUDIO_FORMATS_TEST)
    adapter = read(ENGINE_ADAPTER)
    gradle = read(ENGINE_GRADLE)
    settings = read(SETTINGS)
    manifest = read(MANIFEST)
    csv = read(ASSETS_CSV)
    f2c = read(F2C_LOG)

    format_hits = {a: (a in formats) for a in FORMATS_ANCHORS}
    adapter_hits = {a: (a in adapter) for a in ADAPTER_ANCHORS}
    adapter_absent = {a: (a not in adapter) for a in ADAPTER_FORBIDDEN}
    history_hits = {k: (anchor in read(path)) for k, (path, anchor) in HISTORY_ANCHORS.items()}
    f2c_hits = {a: (a in f2c) for a in F2C_REQUIRED}
    f2c_absent = {a: (a not in f2c) for a in F2C_FORBIDDEN}

    project_deps = re.findall(r'implementation\(project\(":[^"]+"\)\)', gradle)
    # 09 §3 的「31 个文件」= docs/ 递归 29 份 md + 1 份 csv + 项目根 NOTICE.md。
    doc_count = len(list(DOCS_DIR.rglob("*.md")))
    index_ok = "31 个文件" in read(OVERVIEW)
    so_count = len(list(SO_DIR.glob("*.so"))) if SO_DIR.is_dir() else 0
    csv_rows = [ln for ln in csv.splitlines()[1:] if ln.strip()]
    perm_count = len(re.findall(r"<uses-permission\b", manifest))
    module_count = len(re.findall(r'^\s*include\(":', settings, re.MULTILINE))

    checks = {
        "formats_anchors_all_present": all(format_hits.values()),
        "ext_by_mime_derived_from_mime_by_ext": "for ((ext, mime) in MIME_BY_EXT)" in formats,
        "adapter_wired_to_single_source": all(adapter_hits.values()),
        "old_mime_elvis_chain_removed": all(adapter_absent.values()),
        "zero_new_gradle_dependency": (
            'implementation(project(":core-model"))' in gradle and len(project_deps) == 3
        ),
        "f2b_jvm_cases": (
            len(re.findall(r'@DisplayName\("F2-b-', tests)) == 4
            and len(re.findall(r"@Test\b", tests)) == 11
        ),
        "historical_evidence_in_place": all(history_hits.values()),
        "f2c_extension_is_real_ext": all(f2c_hits.values()) and all(f2c_absent.values()),
        "p0_native_cabin_unchanged": (
            "-> -1 (V0.1 未实现)" in adapter
            and FINGERPRINT.is_file()
            and so_count == 18
        ),
        "baseline_unchanged": (
            module_count == 19
            and perm_count == 11
            and doc_count == 29
            and index_ok is True
            and so_count == 18
            and len(csv_rows) == 61
            and all("replaced=false" in ln or ln.rstrip().endswith(",false") for ln in csv_rows)
            # settings.gradle.kts 第 94 行注释里出现过 ":ui-legacy" 字样，
            # 基线口径是「未 include」，故只匹配真实 include 行。
            and re.search(r'^\s*include\(":ui-legacy"\)', settings, re.MULTILINE) is None
            and not RES_DIR.is_dir()
        ),
    }

    data = {
        "method": "12 §61.48 / F2-b + F2-c：extension 推断链修正与真机复验（只读审计）",
        "generated_by": "tools/l3_f2_ext_fix_audit.py",
        "fix": {
            "single_source": "core-model/.../AudioFormats.kt#extOfSource",
            "priority": ["url 后缀", "localPath 真实后缀", "mimeHint 逆查已登记 MIME", "空串（不编造）"],
            "ext_by_mime_derived": True,
            "engine_adapter_wired": checks["adapter_wired_to_single_source"],
            "old_chain_removed": checks["old_mime_elvis_chain_removed"],
            "engine_legacy_core_model_dep": 'implementation(project(":core-model"))',
            "gradle_project_deps": project_deps,
            "zero_new_gradle_dependency": checks["zero_new_gradle_dependency"],
            "jvm_cases": {"f1": 7, "f2b": 4, "total": 11},
        },
        "historical_evidence": {
            **history_hits,
            "review_4_2_conclusion": "extension 空不是 prepare 失败原因；按 URL 后缀填 mp3 的校准实验已有真机日志",
            "a3_e4_conclusion": "resourceInfo extension=mp3 fileSize=37824 → Open codec for mp3 / doPrepareAsync openSource 0",
        },
        "device_verification": {
            "endpoint": "10.0.0.2:39853",
            "device": "23013RK75C / Android 13",
            "verdict": "PASS" if checks["f2c_extension_is_real_ext"] else "FAIL",
            "path": "LOCAL（零权限注入通道，fixture.mp3 37,824 B）",
            "extension_before": "audio/mpeg（§61.47，MIME 字符串）",
            "extension_after": "mp3（本批 F2-c）",
            "decode_chain": "XorLocalSource::Open → BufferSource open ret=0 → Open codec for mp3 → mDuration 4704 → Play status 4 → Write start play nErr:0",
            "zero_crash": all(f2c_absent.values()),
            "log_full": "analysis/l3-f2c-extension-verify-logcat.txt",
            "log_key": "analysis/l3-f2c-key-logcat.txt",
            "screenshot": "analysis/l3-reference/self-ui-f2c-extension-mp3.png",
            "required_log_anchors": f2c_hits,
            "forbidden_log_anchors_absent": f2c_absent,
        },
        "baseline": {
            "module_count": module_count,
            "permission_count": perm_count,
            "so_in_engine_legacy": so_count,
            "docs_md_count_recursive": doc_count,
            "docs_index_31_ok": index_ok,
            "assets_csv_rows": len(csv_rows),
            "ui_legacy_included": re.search(r'^\s*include\(":ui-legacy"\)', settings, re.MULTILINE) is not None,
            "app_res_created": RES_DIR.is_dir(),
        },
        "checks": checks,
        "format_anchors": format_hits,
        "adapter_anchors": adapter_hits,
        "adapter_forbidden_absent": adapter_absent,
        "all_pass": all(checks.values()),
        "honest_notes": [
            "F2-c 只复验 LOCAL 路径；REMOTE 路径本批未重跑真机，其 URL 分支由 4 个 F2-b JVM 用例与 A3 E4 历史真机日志（extension=mp3 fileSize=37824）覆盖，不伪造新真机结论。",
            "截图为设备下拉通知栏（MIUI 无线 adb 拒 input，无法收起），只作 FGS 媒体通知在位 / 无错误弹窗旁证，不冒充播放页证据；播放页证据仍以 §61.47 的 self-ui-with-local-track-2.png 为准。",
            "通知栏显示「曲库就绪」而非曲名，是 §61.47 已挂账的 MediaSession metadata: null / durationMs=0 缺口，本批未修。",
            "只改自研 Java/Kotlin 适配层与 :core-model 纯 JVM 真相源；20 so / ENGINE-FINGERPRINT / native 舱零改动。",
            "mp3 可播不代表全格式可播；extension 是否参与非 mp3 格式的 demuxer 选择须由 F3 逐格式 × REMOTE/LOCAL 双路径矩阵判定，不预断。",
            "本工具不调用 adb、不重新采集设备状态、不覆盖 §61.46 / §61.47 历史证据。",
        ],
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    print("[INFO] fix 接线=%s / 旧链移除=%s / 零新增依赖=%s" %
          (checks["adapter_wired_to_single_source"], checks["old_mime_elvis_chain_removed"],
           checks["zero_new_gradle_dependency"]))
    print("[INFO] F2-c extension=mp3 真机日志=%s / 解码链=%s / 零崩溃=%s" %
          ("resourceInfo extension=mp3 fileSize=-1" in f2c,
           "Open codec for mp3" in f2c and "Write start play nErr:0" in f2c,
           all(f2c_absent.values())))
    print("[INFO] 基线：%d 模块 / %d 权限 / %d so / %d docs / %d csv 行" %
          (module_count, perm_count, so_count, doc_count, len(csv_rows)))
    failed = [k for k, v in checks.items() if not v]
    if failed:
        print("[FAIL] 12 §61.48 审计未通过：%s" % failed)
        return 1
    print("[PASS] 12 §61.48 审计：10 项 checks 全 PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
