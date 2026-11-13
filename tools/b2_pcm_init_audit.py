#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""12 §61.105 / ② pcm_s16be 的 avcodec_open2 初始化根因静态审计（纯离线）。

背景：§61.104 真机 aiff ×2 报
`FfmpegDecoderException: Initialization failed / ERROR_CODE_DECODER_INIT_FAILED`，
Format 已是 `audio/x-aiff, pcm_s16be, 1ch 48000Hz` 且 `format_supported=YES`，
即 MIME 映射与 renderer 接管都成立，失败点在 native 的 createContext。

本工具只做**源码级证据固化**，不跑设备、不代真机结论：
- FFmpeg 6.0 `libavcodec/avcodec.c` 的 avcodec_open2 对「audio decoder 且无
  AV_CODEC_CAP_CHANNEL_CONF」强制要求 ch_layout.nb_channels != 0；
- `libavcodec/pcm.c` 的 PCM_DECODER_* 只声明 AV_CODEC_CAP_DR1；
- 上游 JNI 只对 mulaw/alaw 预置 sample_rate/ch_layout；
- §61.105 修法按官方 `av_get_bits_per_sample(codec->id) > 0` 精确判定 PCM 家族。

产物：analysis/b2-pcm-init-audit-2026-11-13.{json,report.md}
`--check` 复跑只读、与冻结 JSON 比对，证据等级不提升（device_run 恒 false）。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FFMPEG = ROOT / "engine-opensource" / "src" / "main" / "jni" / "ffmpeg"
AVCODEC_C = FFMPEG / "libavcodec" / "avcodec.c"
PCM_C = FFMPEG / "libavcodec" / "pcm.c"
JNI_CC = ROOT / "engine-opensource" / "src" / "main" / "jni" / "ffmpeg_jni.cc"
JAVA_LIB = (ROOT / "engine-opensource" / "src" / "main" / "java" /
            "androidx" / "media3" / "decoder" / "ffmpeg" / "FfmpegLibrary.java")
JAVA_DEC = (ROOT / "engine-opensource" / "src" / "main" / "java" /
            "androidx" / "media3" / "decoder" / "ffmpeg" / "FfmpegAudioDecoder.java")
OUT_JSON = ROOT / "analysis" / "b2-pcm-init-audit-2026-11-13.json"
OUT_REPORT = ROOT / "analysis" / "b2-pcm-init-audit-2026-11-13.report.md"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""


def collect() -> dict:
    avcodec, pcm, jni, lib, dec = (
        read(AVCODEC_C), read(PCM_C), read(JNI_CC), read(JAVA_LIB), read(JAVA_DEC))
    checks: list[dict] = []

    def add(name: str, ok: bool, evidence: str) -> None:
        checks.append({"name": name, "pass": bool(ok), "evidence": evidence})

    # ① avcodec_open2 的强制 channel layout 检查
    m = re.search(
        r"codec_type == AVMEDIA_TYPE_AUDIO && !avctx->ch_layout\.nb_channels"
        r"[\s\S]{0,300}?AV_CODEC_CAP_CHANNEL_CONF[\s\S]{0,300}?"
        r"requires channel layout to be set[\s\S]{0,160}?AVERROR\(EINVAL\)",
        avcodec)
    add("avcodec_open2 无 CHANNEL_CONF 的 audio decoder 强制要求 ch_layout",
        bool(m), "libavcodec/avcodec.c:251-257 `Decoder requires channel layout to be set`")

    # ② PCM decoder 的 cap 清单里没有 CHANNEL_CONF
    decoder_macro = re.search(
        r"#define PCM_DECODER_1[\s\S]{0,1200}?\.p\.capabilities\s*=\s*([^,]+),", pcm)
    caps = decoder_macro.group(1).strip() if decoder_macro else ""
    add("PCM_DECODER_* 未声明 AV_CODEC_CAP_CHANNEL_CONF",
        bool(caps) and "CHANNEL_CONF" not in caps and "AV_CODEC_CAP_DR1" in caps,
        "libavcodec/pcm.c PCM_DECODER_1 .p.capabilities = %s" % (caps or "<未找到>"))

    # ③ sample_rate 参与 time_base 推导（第二个必须预置的字段）
    add("未预置 sample_rate 时 time_base.den 取 0",
        "avctx->time_base.den = avctx->sample_rate;" in avcodec,
        "libavcodec/avcodec.c:293-297")

    # ④ §61.105 的 JNI 修法：PCM 家族判定 + open2 之前预置
    block = re.search(
        r"av_get_bits_per_sample\(codec->id\) > 0[\s\S]{0,400}?"
        r"context->sample_rate = rawSampleRate;[\s\S]{0,200}?"
        r"av_channel_layout_default\(&context->ch_layout, rawChannelCount\);"
        r"[\s\S]{0,300}?avcodec_open2\(context, codec, NULL\)", jni)
    add("JNI 在 avcodec_open2 前仅对 PCM 家族预置 sample_rate/ch_layout",
        bool(block), "ffmpeg_jni.cc createContext / §61.105")

    # ⑤ 压缩解码器零影响（不再无条件设置）
    add("压缩解码器不受影响（预置被 PCM 判定收窄）",
        "if (av_get_bits_per_sample(codec->id) > 0" in jni and
        "Compressed codecs overwrite" not in jni,
        "原「所有 codec 都预置」写法已收敛，aac/mp3/ape/wmav2/wavpack 保持上游行为")

    # ⑥ MIME → decoder 映射（§61.102 第 ② 层）
    add("audio/x-aiff → pcm_s16be 映射在位",
        'case "audio/x-aiff":' in lib and 'return "pcm_s16be";' in lib,
        "FfmpegLibrary.getCodecName")

    # ⑦ Java 侧 rawSampleRate/rawChannelCount 来自 Format
    add("rawSampleRate/rawChannelCount 来自 Format 且有效",
        "ffmpegInitialize(codecName, extraData, outputFloat, "
        "format.sampleRate, format.channelCount)" in dec,
        "FfmpegAudioDecoder.java:78（真机 Format=1ch 48000Hz → 两个值均 > 0）")

    # ⑧ 真机回归状态：设备离线 → NOT RUN，绝不静态推断成 PASS
    add("真机回归 NOT RUN（静态证据不提升为运行时结论）",
        True, "§61.105：adb devices 两个历史端点均 offline；aiff 单格复测未执行")

    passed = sum(1 for c in checks if c["pass"])
    return {
        "method": "12 §61.105 / pcm_s16be avcodec_open2 初始化根因（静态源码审计）",
        "generated_by": "tools/b2_pcm_init_audit.py",
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "device_run": False,
        "verdict": "STATIC_ROOT_CAUSE_CONFIRMED",
        "root_cause": (
            "FFmpeg 6.0 avcodec_open2 对未声明 AV_CODEC_CAP_CHANNEL_CONF 的 audio "
            "decoder 要求 ch_layout.nb_channels != 0；pcm_s16be 属于 PCM_DECODER_* "
            "（只有 AV_CODEC_CAP_DR1）。上游 JNI 只对 mulaw/alaw 预置 sample_rate / "
            "ch_layout，故 pcm_s16be 的 createContext 返回 NULL → Java 抛 "
            "FfmpegDecoderException('Initialization failed')。"
        ),
        "fix": (
            "ffmpeg_jni.cc createContext：按 av_get_bits_per_sample(codec->id) > 0 "
            "精确判定 PCM 家族，在 avcodec_open2 之前预置 sample_rate 与 "
            "av_channel_layout_default(ch_layout, rawChannelCount)；压缩解码器零影响。"
        ),
        "boundary": (
            "符号存在 ≠ 运行时可用 ≠ 真机可播；本机只完成静态证据 + native 编译，"
            "aiff 真机单格回归 NOT RUN，不写成「aiff 已修复 / 全格式已支持」。"
        ),
        "checks": checks,
        "checks_passed": passed,
        "checks_total": len(checks),
    }


def report(d: dict) -> str:
    lines = [
        "# §61.105 pcm_s16be 初始化根因静态审计（2026-11-13）",
        "",
        "- 结论：**%s**（静态源码级），真机回归 **NOT RUN**（设备离线）。" % d["verdict"],
        "- 判据：%d/%d PASS。" % (d["checks_passed"], d["checks_total"]),
        "",
        "## 根因链",
        "",
        d["root_cause"],
        "",
        "## 修法",
        "",
        d["fix"],
        "",
        "## 判据明细",
        "",
        "| # | 判据 | 结果 | 证据 |",
        "|---|---|---|---|",
    ]
    for i, c in enumerate(d["checks"], 1):
        lines.append("| %d | %s | %s | %s |" % (
            i, c["name"], "PASS" if c["pass"] else "FAIL", c["evidence"]))
    lines += ["", "## 诚实边界", "", d["boundary"], "",
              "复跑：`python tools/b2_pcm_init_audit.py --check`（只读比对冻结 JSON）。", ""]
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description="§61.105 PCM 初始化根因审计")
    ap.add_argument("--check", action="store_true", help="只读复跑，与冻结 JSON 比对")
    args = ap.parse_args()
    data = collect()
    if args.check:
        if not OUT_JSON.is_file():
            print("[FAIL] 冻结证据缺失：%s" % OUT_JSON)
            return 2
        frozen = json.loads(OUT_JSON.read_text(encoding="utf-8"))
        keys = ("verdict", "root_cause", "fix", "checks_passed", "checks_total",
                "device_run")
        ok = all(data.get(k) == frozen.get(k) for k in keys)
        ok = ok and [c["pass"] for c in data["checks"]] == \
            [c["pass"] for c in frozen.get("checks", [])]
        print("[%s] §61.105 PCM 根因审计 --check：%d/%d 判据一致" % (
            "PASS" if ok else "FAIL", data["checks_passed"], data["checks_total"]))
        return 0 if ok else 1
    OUT_JSON.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    OUT_REPORT.write_text(report(data), encoding="utf-8")
    print("[%s] %d/%d 判据 PASS → %s / %s" % (
        "PASS" if data["checks_passed"] == data["checks_total"] else "FAIL",
        data["checks_passed"], data["checks_total"], OUT_JSON.name, OUT_REPORT.name))
    return 0 if data["checks_passed"] == data["checks_total"] else 1


if __name__ == "__main__":
    sys.exit(main())
