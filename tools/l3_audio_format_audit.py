#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.44 · 全格式播放内核现状盘点（只读、纯标准库、可复跑）。

产出 analysis/l3-audio-format-audit.json：
  - 6 处生产格式白名单的并集 / 差集 / 不一致项（全部从源码正则解析，不硬编码清单）；
  - native libffmpeg.so 实际编入的 demuxer / 音频 decoder；
  - A3 G01 真机已验证格式（URL 回环源）与证据出处；
  - EngineAdapter 的 ResourceInfo.extension 推断链与 LOCAL 源语义错配；
  - 两条取流路径（REMOTE / LOCAL）与已知格式专属问题；
  - 分阶段施工 todo（F1~F5）。

不修改任何生产文件、不猜测 native 能力、不伪造真机验证结论。

**F1（12 §61.45）之后的定位**：本工具是「F1 前现状」的历史基线生成器。
6 处白名单已统一改为引用 `AudioFormats`，本工具依赖的源码字面量集合不再存在，
故 [main] 检测到 `AudioFormats.kt` 落地后**拒绝重跑、不覆盖本 JSON**，
以免把 `check_source_contracts.py §61.44` 钉死的历史证据冲掉。
F1 后现状证据由 `tools/l3_f1_unified_audit.py` → `analysis/l3-f1-unified.json` 产出。
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "analysis" / "l3-audio-format-audit.json"

FILES = {
    "core-model/ObjectType.AUDIO_EXT":
        ROOT / "core-model/src/main/kotlin/com/example/cloudmusic/model/ObjectType.kt",
    "scanner/ScanRules.audioExt":
        ROOT / "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ExcludeRule.kt",
    "datasource-strm/StrmPaths.defaultFilterTypes":
        ROOT / "datasource-strm/src/main/kotlin/com/example/cloudmusic/strm/StrmPaths.kt",
    "engine-bridge/MimeHint.MAP":
        ROOT / "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/BaseDataSource.kt",
    "feature-player/PlayerController.mimeHintOf":
        ROOT / "feature-player/src/main/kotlin/com/example/cloudmusic/player/PlayerController.kt",
    "output-dlna/DidlLiteBuilder.mimeOf":
        ROOT / "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DidlLiteBuilder.kt",
}

FFMPEG = ROOT / "engine-legacy/src/main/jniLibs/arm64-v8a/libffmpeg.so"
ENGINE_ADAPTER = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/EngineAdapter.kt"
URL_DATA_REQUEST = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
A3_EVIDENCE = ROOT / "analysis/a3-phase-ab-evidence-2026-11-07.md"
# F1 落地标记：存在即说明 6 处白名单已改为引用单一真相源，本历史工具不再适用。
AUDIO_FORMATS = ROOT / "core-model/src/main/kotlin/com/example/cloudmusic/model/AudioFormats.kt"

DEDICATED_DECODER_LIBS = (
    "libnecmFlacDec.so", "libnecmMP3Dec.so", "libnecmAACDec.so",
)

AUDIO_DECODER_HINTS = (
    "mp3", "aac", "flac", "ape", "alac", "vorbis", "opus", "wma", "pcm",
    "amr", "truehd", "mlp", "shorten", "celt", "dts", "tta", "wavpack",
    "tak", "dsf", "dff", "aiff", "adpcm",
)


def quoted_exts(text: str) -> list[str]:
    """提取形如 "mp3" / "m4a", "aac" 的纯小写字母数字字面量（排除 MIME 值）。"""
    return re.findall(r'"([a-z0-9]+)"', text)


def section(text: str, start: str, end: str) -> str:
    i = text.index(start)
    j = text.index(end, i)
    return text[i:j]


def collect_lists() -> dict[str, list[str]]:
    t = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}

    obj = section(t["core-model/ObjectType.AUDIO_EXT"], "AUDIO_EXT = setOf(", ")")
    scan = section(t["scanner/ScanRules.audioExt"], "audioExt: Set<String> = setOf(", ")")
    strm = re.search(
        r'defaultFilterTypes\s*=\s*"([^"]+)"',
        t["datasource-strm/StrmPaths.defaultFilterTypes"],
    ).group(1)
    mime = section(t["engine-bridge/MimeHint.MAP"], "MAP = mapOf(", ")")
    pc = section(
        t["feature-player/PlayerController.mimeHintOf"],
        "private fun mimeHintOf", "private fun fail",
    )
    didl = section(
        t["output-dlna/DidlLiteBuilder.mimeOf"],
        "fun mimeOf(", "fun build(",
    )

    mime_keys = re.findall(r'"([a-z0-9]+)"\s*to\s*"', mime)

    return {
        "core-model/ObjectType.AUDIO_EXT": quoted_exts(obj),
        "scanner/ScanRules.audioExt": quoted_exts(scan),
        "datasource-strm/StrmPaths.defaultFilterTypes":
            [x.strip() for x in strm.split(",") if x.strip()],
        "engine-bridge/MimeHint.MAP": mime_keys,
        "feature-player/PlayerController.mimeHintOf": quoted_exts(pc),
        "output-dlna/DidlLiteBuilder.mimeOf": quoted_exts(didl),
    }


def native_formats() -> dict:
    data = FFMPEG.read_bytes()
    strs = {s.decode("ascii", "ignore") for s in re.findall(rb"[ -~]{3,}", data)}
    demuxers = sorted(s[3:-8] for s in strs if re.fullmatch(r"ff_[a-z0-9_]+_demuxer", s))
    decoders = sorted(s[3:-8] for s in strs if re.fullmatch(r"ff_[a-z0-9_]+_decoder", s))
    audio_decoders = sorted({d for d in decoders if any(h in d for h in AUDIO_DECODER_HINTS)})
    jni_dir = FFMPEG.parent
    dedicated = [n for n in DEDICATED_DECODER_LIBS if (jni_dir / n).is_file()]
    return {
        "decoder_library": FFMPEG.name,
        "library_bytes": len(data),
        "demuxer_count": len(demuxers),
        "demuxers": demuxers,
        "decoder_count": len(decoders),
        "audio_decoder_names": audio_decoders,
        "dedicated_decoder_libs": dedicated,
        "note": "格式能力以 libffmpeg.so 内 ff_*_demuxer / ff_*_decoder 符号名为准；"
                "libncmplayerengine.so 只有少量格式字符串，不是解码器所在库。",
    }


def device_verified() -> dict:
    t = A3_EVIDENCE.read_text(encoding="utf-8")
    i = t.index("## 26.")
    j = t.index("**G05", i)
    block = t[i:j]
    g = re.findall(r"g0\d-([a-z0-9]+)", block)
    formats = ["mp3"] + g                      # mp3 前证（E17~E24 七轮）
    formats = list(dict.fromkeys(formats))
    return {
        "formats": formats,
        "count": len(formats),
        "source_kind": "URL（adb reverse + python -m http.server 回环，12s 正弦音）",
        "evidence": "analysis/a3-phase-ab-evidence-2026-11-07.md §26 G01 四格式",
        "local_path_verified": False,
        "note": "G01 只覆盖 REMOTE 取流路径；LOCAL 源与其余格式均未真机验证。",
    }


def engine_chain() -> dict:
    a = ENGINE_ADAPTER.read_text(encoding="utf-8")
    u = URL_DATA_REQUEST.read_text(encoding="utf-8")
    return {
        "engine_adapter": "engine-legacy/.../EngineAdapter.kt:405-416",
        "remote_extension": "source.url 后缀（substringBefore('?')，length <= 5）",
        "local_extension_fallback": "source.mimeHint.orEmpty()（值是 audio/mpeg 这类 MIME 字符串，不是扩展名）",
        "resource_info": "ResourceInfo(extension, length, source.trackId, \"\")",
        # 「代码自述为待真机验证的假设、不是已证实的修复」这一诚实状态在位 = True。
        # 源码注释是「这是**待真机验证的假设**，不是已证实的修复。」，中间有 Markdown
        # 加粗标记，故用两个独立子串跨标记匹配，不能拿带逗号的整句做精确串（会因
        # 排版漂移误判为 False，把「代码已诚实自述」错记成「未自述」）。
        "adapter_self_declared_status": (
            "待真机验证的假设" in a and "不是已证实的修复" in a
        ),
        "create_data_request_handler_url_only": "val url = source.url ?: return null" in a,
        "data_paths": {
            "REMOTE": "createDataRequestHandler() -> UrlDataRequest -> RangeFetcher HTTP Range "
                      "-> OnNetSourceData* 回传",
            "LOCAL": "createDataRequestHandler() 返回 null；native 走 getLocalPath() / openLocalFd() 直读",
            "device_verified": "仅 REMOTE",
        },
        "known_format_issue": {
            "file": "engine-legacy/.../UrlDataRequest.kt:80-86",
            "issue": "m4a（moov-at-end）触发 Start→push→onEnd→Read 0→重试 死循环（-103）",
            "current_mitigation": "成功 EOF 延迟 2s 回传 + cancelled 检查（掩盖，非根治）",
            "evidence_present": "moov-at-end" in u and "延迟 2s" in u,
        },
    }


def build() -> dict:
    lists = collect_lists()
    canonical = lists["core-model/ObjectType.AUDIO_EXT"]
    canonical_set = set(canonical)

    inconsistencies = []
    for name, items in lists.items():
        s = set(items)
        if name == "engine-bridge/MimeHint.MAP":
            s = {x for x in s if x not in ("m3u8", "lrc")}   # 文本项不属于音频格式
        missing = sorted(canonical_set - s)
        extra = sorted(s - canonical_set)
        inconsistencies.append({
            "list": name,
            "count": len(s),
            "missing_vs_object_type": missing,
            "extra_vs_object_type": extra,
        })

    native = native_formats()
    verified = device_verified()
    chain = engine_chain()

    todo = [
        {
            "phase": "F1",
            "title": "建立单一格式真相源 AudioFormats",
            "detail": "以 libffmpeg.so 实际编入的 demuxer+decoder 为客观边界，统一 6 处白名单；"
                      "新增纯 JVM 契约与测试，消除 14/8/9/9/6/6 漂移",
            "blocks": ["F2", "F3"],
        },
        {
            "phase": "F2",
            "title": "修正 EngineAdapter 的 ResourceInfo.extension 推断链",
            "detail": "LOCAL 源不得拿 MIME 字符串当扩展名；URL 无后缀 / 签名直链需内容嗅探或显式扩展名；"
                      "先真机证伪/证实代码注释里的「待验证假设」",
            "blocks": ["F3"],
        },
        {
            "phase": "F3",
            "title": "逐格式 × 双路径真机验收矩阵",
            "detail": "每种候选格式分别跑 REMOTE（URL/回环）与 LOCAL（SAF/MediaStore）两条路径，"
                      "记录 Open codec / Write start play / 时长 / seek / ENDED",
            "blocks": ["F4"],
        },
        {
            "phase": "F4",
            "title": "根治 m4a EOF 2s 延迟掩盖",
            "detail": "定位 moov-at-end 死循环根因（vfile 落盘窗口 vs 读侧重试），"
                      "替换 2s sleep 掩盖，保证自动进曲无释放后回调",
            "blocks": ["F5"],
        },
        {
            "phase": "F5",
            "title": "A3 全绿后 ABI 验收回填 + 冻结舱",
            "detail": "G01~G09 全 PASS 后回填 engine_compat_test、冻结引擎舱、"
                      "落 §61.33 Manifest 生产形态施工单",
            "blocks": [],
        },
    ]

    return {
        "method": "12 §61.44：全格式播放内核现状实证（6 处生产白名单 + libffmpeg.so 符号 + A3 G01 真机证据）",
        "generated_by": "tools/l3_audio_format_audit.py",
        "format_lists": lists,
        "canonical": {
            "source": "core-model/ObjectType.AUDIO_EXT",
            "count": len(canonical),
            "exts": canonical,
        },
        "list_count": len(lists),
        "inconsistencies": inconsistencies,
        "native": native,
        "verified_on_device": verified,
        "engine_extension_chain": chain,
        "todo": todo,
        "honest_notes": [
            "本工具只读生产文件，不修改源码、不新增依赖、不触碰设备。",
            "「全格式」客观边界必须以 libffmpeg.so 编入的 demuxer/decoder 为准，"
            "不能把 ObjectType 的 14 个扩展名直接当作 native 支持清单。",
            "demuxer 存在不等于对应 decoder 编入、更不等于真机可播；逐格式结论只能由 F3 真机矩阵给出。",
            "G01 四格式全部是 URL 回环源的 12s 正弦音夹具，不是真实曲库文件，也不是 LOCAL 源。",
        ],
    }


def main() -> int:
    if AUDIO_FORMATS.is_file():
        print("[GUARD] 12 §61.44 历史基线工具：检测到 F1 单一真相源 AudioFormats.kt 已落地。")
        print("[GUARD] 6 处白名单已改为引用它，本工具的源码正则解析目标不再存在；"
              "为保护 F1 前证据，拒绝重跑、不覆盖 analysis/l3-audio-format-audit.json。")
        print("[GUARD] F1 后现状证据请改用 tools/l3_f1_unified_audit.py "
              "→ analysis/l3-f1-unified.json。")
        return 2
    missing = [str(p) for p in (*FILES.values(), FFMPEG, ENGINE_ADAPTER,
                               URL_DATA_REQUEST, A3_EVIDENCE) if not p.is_file()]
    if missing:
        print("[FAIL] 证据文件缺失：%s" % missing)
        return 1
    data = build()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    c = data["canonical"]
    n = data["native"]
    v = data["verified_on_device"]
    print("[INFO] 白名单 %d 处，基准 %s = %d 项：%s" %
          (data["list_count"], c["source"], c["count"], ",".join(c["exts"])))
    for inc in data["inconsistencies"]:
        print("[INFO]   %-52s count=%-3d 缺=%s" %
              (inc["list"], inc["count"], ",".join(inc["missing_vs_object_type"]) or "无"))
    print("[INFO] libffmpeg.so demuxer=%d decoder=%d 音频 decoder=%d 专用解码库=%d" %
          (n["demuxer_count"], n["decoder_count"], len(n["audio_decoder_names"]),
           len(n["dedicated_decoder_libs"])))
    print("[INFO] A3 G01 真机已验证=%d 种：%s（仅 REMOTE）" %
          (v["count"], ",".join(v["formats"])))
    print("[INFO] todo F1~F5 共 %d 阶段；输出 %s" % (len(data["todo"]), OUT.relative_to(ROOT)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
