#!/usr/bin/env python3
"""12 §61.47 / F2：零权限本地曲库注入通道 + 真机取证证据的可复跑审计（只读、纯标准库）。

本工具**不连 adb、不重放真机实验**：真机证据以 `analysis/l3-f2-device-probe-logcat.txt`
（原始日志）与 `analysis/l3-f2-device-probe.md`（取证报告）为准，工具只校验它们存在、
关键断言行在位，再结合源码静态锚点产出 `analysis/l3-f2-zero-permission.json`。

目的：
1. 钉死「自研 APP 没有音乐」的解决通道——复用 ACTION_PLAY + EXTRA_LOCAL_PATH，
   零新增权限 / action / 依赖 / 模块，P0 引擎舱零改动；
2. 钉死 F2 真机结论——LOCAL 源 `extension` 落 MIME（语义错配被证实），
   但 native 经 XorLocalSource 打开本地文件、mp3 仍进解码 / 播放链；
3. 防伪造：F2-b 未做、调试入口非生产形态、metadata / duration 缺口如实挂账。
"""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

SERVICE = ROOT / "app/src/main/kotlin/com/example/cloudmusic/PlaybackService.kt"
RUNTIME = ROOT / "core-di/src/main/kotlin/com/example/cloudmusic/di/playback/PlaybackComposition.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
SETTINGS = ROOT / "settings.gradle.kts"
ENGINE = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/EngineAdapter.kt"

PROBE_MD = ROOT / "analysis/l3-f2-device-probe.md"
PROBE_LOG = ROOT / "analysis/l3-f2-device-probe-logcat.txt"
SHOT = ROOT / "analysis/l3-reference/self-ui-with-local-track-2.png"
OUT = ROOT / "analysis/l3-f2-zero-permission.json"

EXTRA = "com.example.cloudmusic.extra.LOCAL_PATH"
ACTION_PLAY = "com.example.cloudmusic.action.PLAY"

REQUIRED_FILES = (
    SERVICE, RUNTIME, MANIFEST, SETTINGS, ENGINE, PROBE_MD, PROBE_LOG, SHOT,
)

SERVICE_ANCHORS = (
    f'const val EXTRA_LOCAL_PATH = "{EXTRA}"',
    "pendingLocalPath = intent?.getStringExtra(EXTRA_LOCAL_PATH)",
    # 12 §61.49 起 performPlay() 由 if/else 演进为 when（URL 优先、
    # 本地路径次之、两者皆空回原播放链）；锚点同步到当前等价写法，
    # 语义不变、不改写 §61.47 的历史真机结论。
    "!injectedUrl.isNullOrBlank() -> runtime.playUrl(injectedUrl)",
    "!injectedPath.isNullOrBlank() -> runtime.playLocal(injectedPath)",
    "else -> runtime.play()",
    "ACTION_PLAY -> performPlay()",
)

RUNTIME_ANCHORS = (
    "fun playLocal(path: String)",
    "private val tracks: TrackRepository",
    "java.io.File",
    'LOCAL_FIXTURE_STORAGE_ID = "debug-local-fixture"',
    "ensureLocalFixtureDriver(root)",
    "DriverRegistry.put(LOCAL_FIXTURE_STORAGE_ID, driver)",
    "private object FileDirectorySource : LocalDirectorySource",
    "tracks.upsertBatch(listOf(track))",
    "queueRepository.setAll(listOf(track))",
    "controller.setQueue(items, PlayMode.SEQUENCE, 0)",
    "controller.playAt(0)",
    "SourceType.LOCAL",
)

# 12 §61.48 语义演进：F2-b 已按 §61.47 真机结论修正 extension 推断链，
# 当前源码改由 check_source_contracts.py §61.48 守护约束。本工具是 §61.47
# 历史证据的可复跑审计，只钉死 P0 舱体「openLocalFd 仍返回 -1」这一未改动项，
# 不再要求 extension 链保持原样（否则会阻断已授权的 F2-b 施工）。
ENGINE_UNCHANGED_ANCHORS = (
    "-> -1 (V0.1 未实现)",
)

LOG_ASSERTIONS = {
    "prepare_local": "prepare trackId=",
    "remote_false_url_null": "remote=false url=null",
    "extension_is_mime": "resourceInfo extension=audio/mpeg fileSize=-1",
    "get_local_path": (
        "getLocalPath path=/data/data/com.example.cloudmusic/files/music/fixture.mp3"
    ),
    "xor_local_source": "CreateLocalSource: route XorLocalSource",
    "xor_open_success": "XorLocalSource::Open success",
    "buffer_source_open": "BufferSource open:",
    "open_codec_mp3": "Open codec for mp3",
    "duration": "mDuration 4704",
    "play_status": "Play status 4",
    "write_start": "Write start play nErr:0",
}

MD_ASSERTIONS = (
    "12 §61.47",
    "自研 APP 没有音乐",
    "1 首",
    "fixture",
    "F2-b 未做",
    "P0 引擎舱零改动",
    "F2 的语义错配",
    "XorLocalSource",
)


def main() -> int:
    missing = [str(p.relative_to(ROOT)) for p in REQUIRED_FILES if not p.is_file()]
    if missing:
        print(f"[FAIL] 12 §61.47 审计：缺少文件 {missing}")
        return 1

    service = SERVICE.read_text(encoding="utf-8")
    runtime = RUNTIME.read_text(encoding="utf-8")
    manifest = MANIFEST.read_text(encoding="utf-8")
    settings = SETTINGS.read_text(encoding="utf-8")
    engine = ENGINE.read_text(encoding="utf-8")
    probe_md = PROBE_MD.read_text(encoding="utf-8")
    log = PROBE_LOG.read_text(encoding="utf-8")
    log_lines = [ln for ln in log.splitlines() if ln.strip()]

    service_hooks = {a: (a in service) for a in SERVICE_ANCHORS}
    runtime_hooks = {a: (a in runtime) for a in RUNTIME_ANCHORS}
    engine_unchanged = {a: (a in engine) for a in ENGINE_UNCHANGED_ANCHORS}

    permission_count = len(re.findall(r"<uses-permission\b", manifest))
    # settings.gradle.kts 里 :app / :core-di / :engine-legacy 三行带缩进，须允许前导空白；
    # includeBuild("build-logic") 不匹配 include(":" 故不计入（19 模块口径）。
    module_count = len(re.findall(r'^\s*include\(":', settings, re.MULTILINE))
    custom_actions = sorted(set(re.findall(r"com\.example\.cloudmusic\.action\.\w+", manifest)))

    zero_baseline = {
        "permission_count": permission_count == 11,
        "module_count": module_count == 19,
        "action_play_present": ACTION_PLAY in manifest,
        "no_extra_declared_in_manifest": EXTRA not in manifest,
        "no_new_custom_action": custom_actions == [ACTION_PLAY],
    }

    log_hits = {k: (v in log) for k, v in LOG_ASSERTIONS.items()}
    md_hits = {k: (k in probe_md) for k in MD_ASSERTIONS}
    shot_ok = SHOT.stat().st_size > 1000

    checks = {
        "service_hooks_all": all(service_hooks.values()),
        "runtime_hooks_all": all(runtime_hooks.values()),
        "engine_unchanged_all": all(engine_unchanged.values()),
        "zero_baseline_all": all(zero_baseline.values()),
        "log_assertions_all": all(log_hits.values()),
        "md_assertions_all": all(md_hits.values()),
        "screenshot_present": shot_ok,
        "log_nonempty": len(log_lines) >= 30,
    }

    payload = {
        "method": (
            "12 §61.47 / F2：零权限本地曲库注入通道静态审计 + 真机取证证据校验"
            "（只读、纯标准库、不连 adb、不重放实验）"
        ),
        "generated_by": "tools/l3_f2_zero_permission_audit.py",
        "batch": "12 §61.47 / 第七十四批 / 2026-11-09 14:50",
        "device": {
            "endpoint": "10.0.0.2:37827",
            "model": "23013RK75C / mondrian / Redmi K60",
            "android": 13,
            "abi": "arm64-v8a",
            "pid": 15572,
            "uid": 10327,
        },
        "injection_channel": {
            "extra": EXTRA,
            "reuses_action": ACTION_PLAY,
            "service_hooks": service_hooks,
            "runtime_hooks": runtime_hooks,
            "zero_baseline": zero_baseline,
            "permission_count": permission_count,
            "module_count": module_count,
            "custom_actions": custom_actions,
            "p0_engine_unchanged": all(engine_unchanged.values()),
            "engine_unchanged_anchors": engine_unchanged,
            "fixture": {
                "source": "/sdcard/Music/cloudmusic_g01.mp3",
                "private_path": "/data/data/com.example.cloudmusic/files/music/fixture.mp3",
                "bytes": 37824,
                "room_db_bytes": 151552,
            },
        },
        "device_probe": {
            "log_lines": len(log_lines),
            "assertions": log_hits,
            "extension_value": "audio/mpeg",
            "extension_is_mime": log_hits["extension_is_mime"],
            "local_source_opens_natively": (
                log_hits["xor_local_source"]
                and log_hits["xor_open_success"]
                and log_hits["buffer_source_open"]
            ),
            "native_decode_ok": (
                log_hits["open_codec_mp3"]
                and log_hits["duration"]
                and log_hits["play_status"]
                and log_hits["write_start"]
            ),
            "ui_track_visible": shot_ok and md_hits["1 首"],
            "report_assertions": md_hits,
        },
        "verdict": {
            "f2_assumption_verified": log_hits["extension_is_mime"],
            "mp3_local_playable_despite_mismatch": (
                log_hits["open_codec_mp3"] and log_hits["write_start"]
            ),
            "no_music_resolved": (
                checks["runtime_hooks_all"] and shot_ok and md_hits["1 首"]
            ),
            "f2b_pending": md_hits["F2-b 未做"] and all(engine_unchanged.values()),
            "p0_engine_unchanged": all(engine_unchanged.values()),
        },
        "artifacts": [
            str(p.relative_to(ROOT)) for p in (PROBE_MD, PROBE_LOG, SHOT, OUT)
        ],
        "checks": checks,
        "all_pass": all(checks.values()),
    }

    OUT.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    for name, ok in checks.items():
        print(f"[INFO] {name}: {'PASS' if ok else 'FAIL'}")
    print(
        "[INFO] 真机判据：extension=audio/mpeg（MIME 非扩展名）"
        f" / XorLocalSource 打开本地文件={payload['device_probe']['local_source_opens_natively']}"
        f" / mp3 解码播放={payload['device_probe']['native_decode_ok']}"
        f" / 曲库 UI 可见={payload['device_probe']['ui_track_visible']}"
    )
    print(f"[INFO] 输出 {OUT.relative_to(ROOT)}")
    if not payload["all_pass"]:
        print("[FAIL] 12 §61.47 审计：存在未通过项，见上")
        return 1
    print("[PASS] 12 §61.47 审计：零权限注入通道 + F2 真机证据全在位")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
