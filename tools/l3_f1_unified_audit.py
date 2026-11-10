#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.45 / F1 · 单一格式真相源 `AudioFormats` 接线审计（只读、纯标准库、可复跑）。

产出 analysis/l3-f1-unified.json：
  - `AudioFormats.kt` 的 4 个集合 / 映射表（全部从源码正则解析，不硬编码清单）；
  - 6 处消费方是否全部引用 `AudioFormats`、是否仍残留旧的硬编码字面量清单；
  - F1 带来的行为变更（如实登记，不写成"已支持"）。

与 `tools/l3_audio_format_audit.py` 的关系：后者是 **F1 前**历史基线生成器，
已在检测到 `AudioFormats.kt` 后拒绝重跑（保护 §61.44 证据）；本工具是 **F1 后**
现状证据，二者互不覆盖。

不修改任何生产文件、不猜测 native 能力、不伪造真机验证结论。
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "core-model/src/main/kotlin/com/example/cloudmusic/model/AudioFormats.kt"
UNIT_TEST = ROOT / "core-model/src/test/kotlin/com/example/cloudmusic/model/AudioFormatsTest.kt"
OUT = ROOT / "analysis" / "l3-f1-unified.json"

# 6 处消费方 → (源码路径, 必须出现的 AudioFormats 引用)
CONSUMERS = {
    "core-model/ObjectType.AUDIO_EXT": (
        ROOT / "core-model/src/main/kotlin/com/example/cloudmusic/model/ObjectType.kt",
        "AudioFormats.AUDIO_EXT",
    ),
    "scanner/ScanRules.audioExt": (
        ROOT / "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ExcludeRule.kt",
        "AudioFormats.AUDIO_EXT",
    ),
    "datasource-strm/StrmPaths.defaultFilterTypes": (
        ROOT / "datasource-strm/src/main/kotlin/com/example/cloudmusic/strm/StrmPaths.kt",
        "AudioFormats.STREAM_FILTER_TYPES",
    ),
    "engine-bridge/MimeHint.MAP": (
        ROOT / "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/BaseDataSource.kt",
        "AudioFormats.MIME_BY_EXT",
    ),
    "feature-player/PlayerController.mimeHintOf": (
        ROOT / "feature-player/src/main/kotlin/com/example/cloudmusic/player/PlayerController.kt",
        "AudioFormats.mimeOfName",
    ),
    "output-dlna/DidlLiteBuilder.mimeOf": (
        ROOT / "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DidlLiteBuilder.kt",
        "AudioFormats.mimeOfName",
    ),
}

# F1 前的硬编码字面量模式；命中任一即说明该消费方没有真正接线。
LEGACY_PATTERNS = (
    r'setOf\("mp3"',
    r'const val defaultFilterTypes\s*=\s*"mp3',
    r'"mp3"\s*to\s*"audio/mpeg"',
    r'"mp3"\s*->\s*"audio/mpeg"',
)


def block(text: str, start: str) -> str:
    """取 start 之后到第一个右括号之间的片段（用于解析 linkedSetOf / linkedMapOf）。"""
    i = text.index(start)
    j = text.index(")", i)
    return text[i:j]


def quoted_exts(text: str) -> list[str]:
    return re.findall(r'"([a-z0-9]+)"', text)


def quoted_pairs(text: str) -> dict[str, str]:
    return {k: v for k, v in re.findall(r'"([a-z0-9]+)"\s*to\s*"([^"]+)"', text)}


def parse_source(t: str) -> dict:
    # 锚点必须带 `val ` 前缀：`TEXT_MIME_BY_EXT` 与 `EXTRA_AUDIO_EXT` 都包含
    # `MIME_BY_EXT` / `AUDIO_EXT` 子串，不带前缀时 t.index() 会先命中前者，
    # 把 MIME 表误解析成只有 2 项的文本表（§61.45 首跑实证修正）。
    audio = quoted_exts(block(t, "val AUDIO_EXT: Set<String> = linkedSetOf("))
    extra = quoted_exts(block(t, "val EXTRA_AUDIO_EXT: Set<String> = linkedSetOf("))
    text_mime = quoted_pairs(block(t, "val TEXT_MIME_BY_EXT: Map<String, String> = linkedMapOf("))
    audio_mime = quoted_pairs(block(t, "val MIME_BY_EXT: Map<String, String> = linkedMapOf("))
    mime = {**audio_mime, **text_mime}
    stream = list(dict.fromkeys(audio + extra))
    return {
        "audio_ext": {"count": len(audio), "exts": audio},
        "extra_audio_ext": {"count": len(extra), "exts": extra},
        "text_mime_by_ext": {"count": len(text_mime), "pairs": text_mime},
        "mime_by_ext": {"count": len(mime), "pairs": mime},
        "stream_filter_ext": {
            "count": len(stream),
            "exts": stream,
            "derived_from": "AUDIO_EXT + EXTRA_AUDIO_EXT（STREAM_FILTER_TYPES 由真相源运行时派生）",
        },
    }


def consumers() -> dict:
    out = {}
    for name, (path, needle) in CONSUMERS.items():
        t = path.read_text(encoding="utf-8")
        legacy = [p for p in LEGACY_PATTERNS if re.search(p, t)]
        out[name] = {
            "file": str(path.relative_to(ROOT)).replace("\\", "/"),
            "references_audio_formats": needle in t,
            "needle": needle,
            "legacy_literal_present": bool(legacy),
            "legacy_patterns_hit": legacy,
        }
    return out


def build() -> dict:
    parsed = parse_source(SOURCE.read_text(encoding="utf-8"))
    cons = consumers()
    unified = sum(
        1 for v in cons.values()
        if v["references_audio_formats"] and not v["legacy_literal_present"]
    )
    residual = sum(1 for v in cons.values() if v["legacy_literal_present"])
    return {
        "method": "12 §61.45 / F1：单一格式真相源 AudioFormats 接线审计（6 处消费方 + 集合口径）",
        "generated_by": "tools/l3_f1_unified_audit.py",
        "single_source": str(SOURCE.relative_to(ROOT)).replace("\\", "/"),
        "unit_test": str(UNIT_TEST.relative_to(ROOT)).replace("\\", "/"),
        **parsed,
        "consumers": cons,
        "consumers_total": len(cons),
        "consumers_unified": unified,
        "residual_hardcoded_lists": residual,
        "behavior_changes": [
            "ScanRules.audioExt 默认值 8 → 14 项：扫描器开始收录 aiff/ape/dff/dsf/tak/wv，"
            "能否真机播放待 F3 双路径矩阵判定，F1 不声称支持。",
            "StrmPaths.defaultFilterTypes 9 → 15 项（14 + alac）：保持原有 alac 支持不回退，"
            "并补上此前被误过滤的 6 种格式；const val → val 因需运行时派生。",
            "MIME 口径统一到 03-06 §7 的 MimeHint.MAP：.aac 由 audio/mp4 变 audio/aac，"
            "PlayerController / DidlLiteBuilder 的 .wma/.alac 由 octet-stream 变各自 MIME。",
            "6 种新收录格式（ape/dsf/dff/aiff/wv/tak）在竞品与契约中无已确认 MIME，"
            "F1 不编造，仍落 application/octet-stream。",
            "P0 引擎舱（20 so / EngineAdapter / EngineModule）、assets / Manifest / DI 图 / "
            "指纹、模块数 19、权限 11 全部零变化。",
        ],
        "honest_notes": [
            "统一的是「格式清单与 MIME 表的来源」，不是「native 已支持这些格式」；"
            "demuxer 存在 ≠ decoder 编入 ≠ 真机可播。",
            "alac 是否并入 AUDIO_EXT 留待 F3 真机矩阵给出结论，F1 不擅自扩大收录集。",
            "F1 只做自研层接线与纯 JVM 测试，未触碰设备、未跑真机格式矩阵（那是 F3）。",
            "新增格式进入扫描/取流链路后是否引发播放失败，由 F3 逐格如实登记，不预先判 PASS。",
        ],
    }


def main() -> int:
    missing = [str(p) for p in (SOURCE, UNIT_TEST, *[v[0] for v in CONSUMERS.values()])
               if not p.is_file()]
    if missing:
        print("[FAIL] F1 证据文件缺失：%s" % missing)
        return 1
    data = build()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    a = data["audio_ext"]
    m = data["mime_by_ext"]
    s = data["stream_filter_ext"]
    print("[INFO] 单一真相源 %s" % data["single_source"])
    print("[INFO] AUDIO_EXT=%d 项 / EXTRA=%s / MIME 表=%d 项 / STREAM_FILTER=%d 项" %
          (a["count"], ",".join(data["extra_audio_ext"]["exts"]), m["count"], s["count"]))
    print("[INFO] 消费方 %d 处，已统一 %d 处，残留硬编码清单 %d 处" %
          (data["consumers_total"], data["consumers_unified"], data["residual_hardcoded_lists"]))
    for name, v in data["consumers"].items():
        print("[INFO]   %-52s ref=%-5s legacy=%s" %
              (name, v["references_audio_formats"], v["legacy_literal_present"]))
    print("[INFO] 行为变更 %d 条已如实登记；输出 %s" %
          (len(data["behavior_changes"]), OUT.relative_to(ROOT)))
    return 0 if data["consumers_unified"] == data["consumers_total"] and \
            data["residual_hardcoded_lists"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
