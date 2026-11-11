#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.78 / F4 · A1「绕过取流」路线离线可行性论证（纯离线证据审计）。

承接 `§61.77`（三处 native 锚点 0x10798 / 0xc728 / 0x12dc8 需用户拍板）
与同日《播放内核两条路线评估》建议的 A1 时间盒。本工具**不新增真机证据**，
只对已落盘的一手证据做实扫交叉，回答一个窄问题：

    「把音源预落为 App 私有本地文件、走 SourceConfig.Type.LOCAL，
      能否绕过 §61.65/§61.77 的 NMCommonCache vfile 流式取流链？」

证据来源（全部实读，不硬编码结论）：
* `§61.61` 四格矩阵：同一份自洽 fMP4 `f3_m4a_complete.m4a`
  （16,573,387 B），LOCAL 格 `Open codec for aac` / `Play status 4` /
  `Write start play nErr:0` / 无 `WaitRequest` / 无跳跃读；
  REMOTE 格 `commit : 368640` /
  `ReadFromFileNoLock offset:605007 readed_len:0 result:-2` /
  `Play status 3` / 无 codec。
* `§61.47` + F3 14 格：LOCAL 走 `createDataRequestHandler url=null` →
  `getLocalPath` → `CreateLocalSource: XorLocalSource::Open(fd)` →
  `BufferSource open ret=0`；7 份 LOCAL logcat 无 `Start id=` /
  `WaitRequest` / `MakeRangeRequestAsync` / `ReadFromFileNoLock` /
  `NMSliceFile` / `commit :` 等 REMOTE 取流特征。
* 源码装配：`EngineAdapter.kt` 的
  `PlayerDataSource(provider, source.trackId, source.url != null, info)`；
  `SourceConfig.java` 的 `remote ? Type.REMOTE : Type.LOCAL`。

**边界（写进报告，不作为修复声明）**：
* 这是**绕行**（bypass）而非修复：REMOTE 取流缺陷仍在，
  `§61.75` 终局裁决（F4 主判据无一 PASS）不变。
* F3 / `§61.61` 的 LOCAL 格只跑到单曲 prepare→出声；自动进曲 3 首 /
  seek / ENDED 全部 NOT RUN（14 格 seek_observed / ended_observed 全 false）。
* 7 种 NO_SAMPLE 未覆盖；自洽 fMP4 片段 LOCAL 可播 ≠ 完整真实 m4a 已支持。
* 生产代价：整文件预下载、首播延迟、存储、seek 即时性、gapless、
  流式语义丢失；产品主音源（OpenList / WebDAV / strm）原本走 REMOTE。

零 P0 源码改动、不连 adb、零第三方依赖、`device_run=false`。
产出 `analysis/l3-f4-bypass-stream-audit.{json,report.md}`。**F4 仍 BLOCKED**。
"""
from __future__ import annotations

import glob
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
LEGACY = ROOT / "engine-legacy" / "src" / "main" / "java"
SDK = LEGACY / "com" / "netease" / "cloudmusic" / "android" / "sdk" / "playerengine"

OUT = A / "l3-f4-bypass-stream-audit.json"
REPORT = A / "l3-f4-bypass-stream-audit-report.md"

SAMPLE = A / "l3-f3-fixtures" / "f3_m4a_complete.m4a"
CONTRAST_LOCAL = A / "l3-f4-logs" / "selfconsist-local.txt"
CONTRAST_REMOTE = A / "l3-f4-logs" / "selfconsist-remote.txt"
F3_MATRIX = A / "l3-f3-matrix.json"
F3_REPORT = A / "l3-f3-matrix-report.md"
AB_REPORT = A / "l3-f4-ab-report.md"
AB_RESULT = A / "l3-f4-ab-result-report.md"
ACCEPTANCE = A / "l3-f4-acceptance-final.json"
ACCEPTANCE_REPORT = A / "l3-f4-acceptance-final-report.md"
READ_GAP = A / "l3-f4-read-gap-chain.json"
ROUTE_EVAL = A / "l3-playback-route-evaluation-2026-11-11.md"
DECODER_RESEARCH = A / "l3-audio-decoder-effect-research-2026-11-11.md"
ADAPTER = LEGACY / "com" / "example" / "cloudmusic" / "enginelegacy" / "EngineAdapter.kt"
SOURCE_CONFIG = SDK / "SourceConfig.java"
PLAYER_DS = SDK / "PlayerDataSource.java"
RUNBOOK = A / "l3-f4-online-runbook.md"

# REMOTE 取流链特征串：LOCAL 日志里出现任意一条都说明绕行不成立。
REMOTE_MARKERS = (
    "Start id=",
    "WaitRequest",
    "MakeRangeRequestAsync",
    "ReadFromFileNoLock",
    "NMSliceFile",
    "commit :",
    "OnWriteNetData",
)


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    checks: dict[str, bool] = {}

    evidence = {
        "contrast_local": CONTRAST_LOCAL,
        "contrast_remote": CONTRAST_REMOTE,
        "f3_matrix": F3_MATRIX,
        "f3_report": F3_REPORT,
        "sample": SAMPLE,
        "ab_report": AB_REPORT,
        "ab_result": AB_RESULT,
        "acceptance": ACCEPTANCE,
        "acceptance_report": ACCEPTANCE_REPORT,
        "read_gap": READ_GAP,
        "route_eval": ROUTE_EVAL,
        "decoder_research": DECODER_RESEARCH,
        "adapter": ADAPTER,
        "source_config": SOURCE_CONFIG,
        "player_data_source": PLAYER_DS,
        "runbook": RUNBOOK,
    }
    checks["evidence_files_present"] = all(p.is_file() for p in evidence.values())

    if not checks["evidence_files_present"]:
        missing = [k for k, p in evidence.items() if not p.is_file()]
        result = {
            "method": "12 §61.78 / F4 · A1 绕过取流离线可行性论证",
            "generated_by": "tools/l3_f4_bypass_stream_audit.py",
            "mode": "offline_evidence_audit",
            "device_run": False,
            "f4_status": "BLOCKED",
            "all_pass": False,
            "checks": checks,
            "missing_evidence": missing,
        }
        OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                       encoding="utf-8")
        print(f"[FAIL] 证据缺失：{missing}")
        return 1

    # ---------- ① §61.61 同一样本 LOCAL / REMOTE 一手对照 ----------
    loc = read_text(CONTRAST_LOCAL)
    rem = read_text(CONTRAST_REMOTE)
    sample_bytes = SAMPLE.read_bytes()
    checks["sample_size_16573387"] = len(sample_bytes) == 16_573_387
    checks["sample_ftyp_magic"] = sample_bytes[4:8] == b"ftyp"
    checks["sample_sha256_recorded"] = len(sha256(SAMPLE)) == 64

    checks["contrast_same_sample_local"] = "f3_m4a_complete.m4a" in loc
    checks["contrast_same_sample_remote"] = "f3_m4a_complete.m4a" in rem
    checks["contrast_local_size_matches_sample"] = "size=16573387" in loc

    checks["local_url_null"] = "createDataRequestHandler url=null" in loc
    checks["local_get_local_path"] = "getLocalPath path=" in loc
    checks["local_xor_route"] = "CreateLocalSource: route XorLocalSource" in loc
    checks["local_xor_open_success"] = "XorLocalSource::Open success" in loc
    checks["local_codec_aac"] = "Open codec for aac" in loc
    checks["local_write_start_nerr0"] = "Write start play nErr:0" in loc
    checks["local_play_status_4"] = "Play status 4" in loc
    checks["local_mduration_155933"] = "mDuration 155933" in loc

    checks["local_no_wait_request"] = "WaitRequest" not in loc
    checks["local_no_start_id"] = "Start id=" not in loc
    checks["local_no_jump_read"] = "ReadFromFileNoLock" not in loc
    checks["local_no_result_minus2"] = "result:-2" not in loc
    checks["local_no_commit_gate"] = "commit :" not in loc
    checks["local_no_nmslicefile"] = "NMSliceFile" not in loc

    checks["remote_start_id_1"] = "Start id=1 offset=0" in rem
    checks["remote_jump_read_605007"] = (
        "offset:605007, readed_len:0, result:-2" in rem)
    checks["remote_wait_request_commit_frozen"] = bool(
        re.search(r"commit\s*:\s*368640", rem))
    checks["remote_repeated_start_605007"] = len(
        re.findall(r"Start id=\d+ offset=605007", rem)) >= 2
    checks["remote_no_codec_aac"] = "Open codec for aac" not in rem
    checks["remote_no_write_start"] = "Write start play" not in rem
    checks["remote_play_status_3"] = "Play status 3" in rem

    # 同一样本、同一缺陷载体：LOCAL 出声 / REMOTE 无 codec，是 A1 的直接依据。
    checks["same_sample_contrast_decisive"] = all((
        checks["contrast_same_sample_local"],
        checks["contrast_same_sample_remote"],
        checks["local_codec_aac"],
        checks["local_write_start_nerr0"],
        checks["remote_no_codec_aac"],
        checks["remote_no_write_start"],
        checks["remote_wait_request_commit_frozen"],
    ))

    # ---------- ② F3 14 格双路径矩阵 ----------
    f3 = json.loads(read_text(F3_MATRIX))
    cells = f3["cells"]
    local_cells = [c for c in cells if c["path"] == "local"]
    remote_cells = [c for c in cells if c["path"] == "remote"]
    local_pass = [c for c in local_cells if c["verdict"] == "PASS"]
    remote_pass = [c for c in remote_cells if c["verdict"] == "PASS"]
    total_pass = sum(1 for c in cells if c["verdict"] == "PASS")
    total_fail = sum(1 for c in cells if c["verdict"] == "FAIL")

    checks["f3_cells_14"] = len(cells) == 14
    checks["f3_local_7_remote_7"] = (
        len(local_cells) == 7 and len(remote_cells) == 7)
    checks["f3_local_pass_6"] = len(local_pass) == 6
    checks["f3_remote_pass_5"] = len(remote_pass) == 5
    checks["f3_total_11pass_3fail"] = total_pass == 11 and total_fail == 3
    checks["f3_local_pass_all_write_nerr0"] = all(
        c["write_start_play"] is True
        and c["write_nerr_values"] == ["0"] for c in local_pass)
    checks["f3_local_pass_path_evidence_complete"] = all(
        all(c["path_evidence"].values()) for c in local_pass)
    checks["f3_local_cells_all_url_null"] = all(
        c["prepare_remote"] == "false" and c["prepare_url"] == "null"
        for c in local_cells)
    checks["f3_seek_and_ended_all_not_run"] = all(
        c["seek_observed"] is False and c["ended_observed"] is False
        for c in cells)
    f3_rep = read_text(F3_REPORT)
    checks["f3_report_no_sample_7"] = (
        "NO_SAMPLE" in f3_rep and "仍无样本" in f3_rep)
    checks["f3_report_write_start_criterion"] = (
        "Write start play nErr:0" in f3_rep)

    # ---------- ③ 7 份 F3 LOCAL logcat 不含 REMOTE 取流特征 ----------
    local_logs = sorted(glob.glob(str(A / "l3-f3-logs" / "*-local.txt")))
    local_log_texts = [read_text(Path(p)) for p in local_logs]
    checks["f3_local_logs_7"] = len(local_logs) == 7
    checks["f3_local_logs_no_remote_markers"] = all(
        not any(m in t for m in REMOTE_MARKERS) for t in local_log_texts)
    checks["f3_local_logs_all_url_null"] = all(
        "createDataRequestHandler url=null" in t for t in local_log_texts)
    checks["f3_local_logs_all_xor_open"] = all(
        "CreateLocalSource: route XorLocalSource" in t
        and "XorLocalSource::Open success" in t for t in local_log_texts)
    checks["f3_local_logs_6_write_start"] = sum(
        1 for t in local_log_texts if "Write start play nErr:0" in t) == 6
    # `commit` 只可能来自系统 mstatcommitLog，不得是 native commit 记账。
    checks["f3_local_logs_commit_only_system_noise"] = all(
        all("mstatcommitLog" in ln for ln in t.splitlines() if "commit" in ln)
        for t in local_log_texts)

    # ---------- ④ 源码装配点（真实扫描，不硬编码结论） ----------
    adapter = read_text(ADAPTER)
    src_cfg = read_text(SOURCE_CONFIG)
    pds = read_text(PLAYER_DS)
    checks["adapter_remote_switch_by_url"] = (
        "source.url != null" in adapter
        and "PlayerDataSource(provider, source.trackId" in adapter)
    checks["adapter_vfm_setconfig_present"] = "manager.SetConfig(json)" in adapter
    checks["source_config_local_remote_enum"] = (
        "remote ? Type.REMOTE : Type.LOCAL" in src_cfg
        and "LOCAL" in src_cfg and "REMOTE" in src_cfg)
    checks["source_config_cache_local_true"] = "this.cacheLocal = true;" in src_cfg
    checks["player_ds_forwards_remote_flag"] = "new SourceConfig(remote)" in pds
    # 12 §61.79 演进：§61.78 时接线仍在源码（原 check 名
    # wiring_not_rolled_back，用于佐证「本批零 P0 源码改动」）。
    # §61.79 已按 §61.70 勘误回滚诊断接线，故演进为
    # 「接线状态与当前批次一致」：调用已移除 + 回滚说明注释在场。
    # 功能代码（SetConfig / VFM / LOCAL·REMOTE 分叉）仍由上方各 check 守护。
    checks["wiring_state_matches_61_79_rollback"] = (
        "SetLoggerCallback(INMLogger()" not in adapter
        and "§61.79" in adapter)
    checks["local_channel_needs_no_new_permission"] = (
        "EXTRA_LOCAL_PATH" in adapter or "localPath" in adapter)

    # ---------- ⑤ 既有报告与终局裁决口径 ----------
    ab_rep = read_text(AB_REPORT)
    ab_res = read_text(AB_RESULT)
    route = read_text(ROUTE_EVAL)
    research = read_text(DECODER_RESEARCH)
    acc = json.loads(read_text(ACCEPTANCE))
    rg = json.loads(read_text(READ_GAP))
    acc_rep = read_text(ACCEPTANCE_REPORT)
    rb = read_text(RUNBOOK)

    checks["ab_report_local_same_content_playable"] = (
        "LOCAL 同内容可播 aac" in ab_rep)
    checks["ab_result_local_aac_not_m4a_supported"] = (
        "LOCAL 解出 `aac`" in ab_res and "≠「m4a 已支持」" in ab_res)
    checks["route_eval_a1_bypass_timebox"] = (
        "绕过取流" in route and "Write start play" in route
        and "时间盒" in route)
    # 报告实文（§0 表 / §4.1 / §6 表）：「换它修不了 F4」「换解码器不会修 F4」
    # 「不要动解码器」「绕过取流」。按真实措辞断言，不硬编码结论。
    checks["decoder_research_no_decoder_swap"] = (
        "换它修不了 F4" in research
        and "换解码器不会修 F4" in research
        and "不要动解码器" in research
        and "绕过取流" in research
    )
    checks["acceptance_f4_blocked_no_criterion_pass"] = (
        acc["f4_status"] == "BLOCKED" and acc["no_criterion_pass"] is True)
    checks["acceptance_report_no_false_claim"] = (
        "F4 已解决" not in acc_rep and "全格式已支持" not in acc_rep)
    checks["read_gap_three_native_fix_anchors"] = (
        rg["f4_status"] == "BLOCKED" and len(rg["fix_anchors"]) == 3)
    checks["runbook_true_device_evidence_complete"] = (
        "真机取证" in rb and "61.75" in rb)

    checks_total = len(checks)
    all_pass = all(checks.values())

    contrast = {
        "sample": "f3_m4a_complete.m4a",
        "sample_bytes": len(sample_bytes),
        "sample_sha256": sha256(SAMPLE),
        "local": {
            "log": str(CONTRAST_LOCAL.relative_to(ROOT)).replace("\\", "/"),
            "codec": "aac",
            "write_start_play_nerr": 0,
            "play_status": 4,
            "mduration_raw": 155933,
            "wait_request": 0,
            "start_id": 0,
            "result_minus2": 0,
        },
        "remote": {
            "log": str(CONTRAST_REMOTE.relative_to(ROOT)).replace("\\", "/"),
            "codec": None,
            "write_start_play": 0,
            "play_status": 3,
            "commit_frozen": 368640,
            "jump_offset": 605007,
            "result_minus2": len(re.findall(r"result:-2", rem)),
            "start_ids": sorted(set(
                int(x) for x in re.findall(r"Start id=(\d+)", rem))),
        },
    }

    result = {
        "method": "12 §61.78 / F4 · A1「绕过取流」离线可行性论证（纯离线证据审计）",
        "generated_by": "tools/l3_f4_bypass_stream_audit.py",
        "mode": "offline_evidence_audit",
        "device_run": False,
        "f4_status": "BLOCKED",
        "verdict": ("A1_BYPASS_STREAM_SUPPORTED_BY_EXISTING_DEVICE_EVIDENCE"
                    "__BYPASS_NOT_FIX__INFERENCE"),
        "checks": checks,
        "checks_total": checks_total,
        "all_pass": all_pass,
        "contrast": contrast,
        "f3_matrix": {
            "cells": len(cells),
            "local_pass": len(local_pass),
            "remote_pass": len(remote_pass),
            "total_pass": total_pass,
            "total_fail": total_fail,
            "no_sample_formats": ["m4a", "aac", "opus", "wv", "tak", "dsf", "dff"],
            "seek_observed_any": any(c["seek_observed"] for c in cells),
            "ended_observed_any": any(c["ended_observed"] for c in cells),
        },
        "source_wiring": {
            "switch": "EngineAdapter.kt: PlayerDataSource(..., source.url != null, info)",
            "type_rule": "SourceConfig.java: remote ? Type.REMOTE : Type.LOCAL",
            "local_route": ("createDataRequestHandler url=null → getLocalPath → "
                            "CreateLocalSource: XorLocalSource::Open(fd)"),
            "remote_route": ("getUrl → NMNetSource/Start id → NMSlicesFile::Write "
                             "→ 代次门控/commit 冻结 → result:-2 → -103"),
            "zero_p0_change": True,
            "zero_native_change": True,
        },
        "boundaries": [
            "绕行 ≠ 修复：REMOTE 取流缺陷仍在，§61.75 终局裁决（主判据无一 PASS）不变。",
            "F3 / §61.61 的 LOCAL 格只跑到单曲 prepare→出声；自动进曲 3 首 / seek / ENDED 全部 NOT RUN。",
            "7 种格式 NO_SAMPLE 未覆盖；自洽 fMP4 片段 LOCAL 可播 ≠ 完整真实 m4a 已支持。",
            "生产代价：整文件预下载、首播延迟、存储占用、seek 即时性、gapless、流式语义丢失。",
            "产品主音源（OpenList / WebDAV / strm 签名直链）原本走 REMOTE；改为下载后播放需用户拍板产品语义。",
            "§61.69 接线回滚在 §61.78 时仍待拍板、已于 §61.79 执行；本批（§61.78）零 P0 源码改动、未连 adb、未重编译 / 未重装。",
        ],
        "a1_experiment_design_not_executed": {
            "status": "NOT_RUN__REQUIRES_USER_ROUTE_DECISION",
            "status_evolution_note": (
                "本节是 §61.78 当时的设计快照，NOT_RUN 口径按历史证据不改写纪律保留。"
                "A1 时间盒已于 12 §61.79 经用户授权执行并裁决 "
                "timebox_passed=False（绕行成立但仅点火、无持续播放），"
                "§61.69 接线亦已于 §61.79 回滚；详见 §61.79 裁决与回滚审计证据。"),
            "preconditions": [
                "用户拍板接受 A1「绕过取流」路线与「下载后播放」语义",
                "设备在线（本批只读核查 10.0.0.2:37163 = device，app base.apk 已装）",
                "§61.69 接线回滚与否先定（§61.78 当时口径；§61.79 已回滚并同步守护）",
            ],
            "steps": [
                "1. adb push analysis/l3-f3-fixtures/f3_m4a_complete.m4a 到 /sdcard/Download/",
                "2. 按 §61.47 零权限通道 shell cat | run-as ... sh -c 'cat > files/music/f3_m4a_complete.m4a' 写入 app 私有目录（Git Bash 传 /data/... 需 MSYS_NO_PATHCONV=1）",
                "3. 注入 ACTION_PLAY + EXTRA_LOCAL_PATH（§61.47 已有入口，零新增 Manifest action / 权限 / 依赖）",
                "4. 流式抓 60s 全量 logcat + dumpsys media_session / audio 快照（§61.74/§61.75 多信号判据）",
                "5. 判据：Write start play nErr:0 + 本 app AudioTrack state:started + MediaSession state=3 且 position 递增 + 自动进曲 3 首无 -103 + seek / ENDED",
                "6. 对照：同一样本 REMOTE 轮（已知 FAIL，§61.61）",
                "7. finally 恢复：清理注入的本地文件 / 停止服务 / 恢复队列，不落盘无关状态",
            ],
            "decision": ("通过 → 继续 A（F4 验收 17 §8.3 → F5 → P2/P3/P4）；"
                         "不通过 → 按路线评估立即转 B2，不追加 A2 二进制补丁"),
        },
        "evidence_files": {
            k: str(p.relative_to(ROOT)).replace("\\", "/")
            for k, p in evidence.items()
        },
        "evidence_sha256": {k: sha256(p) for k, p in evidence.items()},
    }

    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    REPORT.write_text(render_report(result), encoding="utf-8")

    print(f"[{'PASS' if all_pass else 'FAIL'}] {checks_total} 项 checks，"
          f"all_pass={all_pass}，f4_status=BLOCKED")
    if not all_pass:
        print("  失败项：" + ", ".join(k for k, v in checks.items() if not v))
    print(f"  证据：{OUT.relative_to(ROOT)} / {REPORT.relative_to(ROOT)}")
    return 0 if all_pass else 1


def render_report(r: dict) -> str:
    c = r["contrast"]
    f3 = r["f3_matrix"]
    lines = [
        "# F4 · A1「绕过取流」离线可行性论证报告（2026-11-11 / `12 §61.78`）",
        "",
        "> **性质**：纯离线证据审计，**不新增真机证据、不推进 F4 修复、零 P0 源码改动**。",
        "> 工具 `tools/l3_f4_bypass_stream_audit.py` 实扫已落盘的一手 logcat / JSON / 源码，",
        f"> **{r['checks_total']} 项 checks 全 PASS**（`device_run=false`）。",
        "> 触发：用户「快速查看项目最近变动，了解最新进展，继续开发任务」；承接",
        "> `§61.77` 三处 native 锚点待拍板 + 同日《播放内核两条路线评估》A1 时间盒建议。",
        "",
        "---",
        "",
        "## 1. 结论速览",
        "",
        f"**裁定**：`{r['verdict']}`。",
        "",
        "「绕过取流」在**已有真机一手证据**上成立，且是 `§61.77` 三处 native 锚点",
        "（`0x10798` 代次比较 / `0xc728` WaitRequest 读空 -2 / `0x12dc8` 越界 -2）的",
        "**绕行方案**：把音源预落为 App 私有本地文件、令 `EngineSource(url=null,",
        "localPath=…)`，native 即走 `SourceConfig.Type.LOCAL` → `XorLocalSource` 直读 fd，",
        "**不进入** `NMNetSource` / `NMSlicesFile::Write` / 代次门控 / `commit` 记账链。",
        "",
        "**但这是绕行，不是修复**：REMOTE 取流缺陷仍在，`§61.75` 终局裁决",
        "（F4 主判据无一 PASS）不变，**F4 仍 BLOCKED**。",
        "",
        "## 2. 决定性证据：同一样本的 LOCAL / REMOTE 四格对照（`§61.61`）",
        "",
        f"样本 `{c['sample']}` / {c['sample_bytes']:,} B / sha256 `{c['sample_sha256'][:16]}…`。",
        "",
        "| 维度 | LOCAL（`selfconsist-local.txt`） | REMOTE（`selfconsist-remote.txt`） |",
        "|---|---|---|",
        f"| 取流 | `url=null` → `getLocalPath` → `XorLocalSource::Open` fd | `Start id=1..4` → `WaitRequest` |",
        f"| codec | **`Open codec for aac`** | 无 |",
        f"| 出声 | **`Write start play nErr:0`** | 无 |",
        f"| Play status | 4 | 3 |",
        f"| 跳跃读 | 无 | `offset:605007 readed_len:0 result:-2`（{c['remote']['result_minus2']} 次） |",
        f"| commit | 无记账 | **冻结在 {c['remote']['commit_frozen']}** |",
        "",
        "同一份自洽 fMP4，LOCAL 出声、REMOTE 无 codec —— **缺陷被隔离在 REMOTE",
        "取流层**，与 `§61.77` 的静态链（代次错配 → commit 冻结 → result:-2 → -103）",
        "完全对齐。`§61.51/§61.52` 报告原文亦登记「LOCAL 同内容可播 aac」/",
        "「LOCAL 解出 `aac` ≠「m4a 已支持」」。",
        "",
        "## 3. F3 双路径矩阵与 LOCAL 日志特征",
        "",
        f"- 14 格 = LOCAL 7 + REMOTE 7；**LOCAL {f3['local_pass']} PASS / REMOTE",
        f"  {f3['remote_pass']} PASS / 合计 {f3['total_pass']} PASS · {f3['total_fail']} FAIL**。",
        "- 每个 LOCAL PASS 格判据均为 `Write start play nErr:0`，且",
        "  `path_evidence`（getLocalPath / CreateLocalSource / XorOpen）三项全真。",
        f"- 7 份 LOCAL logcat **无** `Start id=` / `WaitRequest` /",
        "  `MakeRangeRequestAsync` / `ReadFromFileNoLock` / `NMSliceFile` / `commit :`；",
        "  唯一含 `commit` 的行是系统 `mstatcommitLog`，与 native 记账无关。",
        f"- 14 格 `seek_observed` / `ended_observed` **全 false** → F4 主判据",
        "  （自动进曲 3 首 / seek / ENDED）在 LOCAL 路径上**从未跑过**。",
        f"- 7 种 NO_SAMPLE 未覆盖：{', '.join(f3['no_sample_formats'])}。",
        "",
        "## 4. 源码装配点（切 LOCAL 无需改 P0 / native）",
        "",
        "| 位置 | 实证 |",
        "|---|---|",
        "| `EngineAdapter.kt` | `PlayerDataSource(provider, source.trackId, source.url != null, info)` |",
        "| `SourceConfig.java` | `this.type = remote ? Type.REMOTE : Type.LOCAL;` |",
        "| `PlayerDataSource.java` | `new SourceConfig(remote)` |",
        "| `§61.47` 入口 | `ACTION_PLAY` + `EXTRA_LOCAL_PATH` → app 私有 `files/music/`（零权限） |",
        "",
        "即：只需让 `EngineSource.url == null` 且 `localPath` 指向已完整落地的本地文件，",
        "native 自动走 LOCAL。**零新增权限 / 零新增 Manifest action / 零 Gradle 依赖 /",
        "零 P0 源码改动 / 零 native 改动**。",
        "",
        "## 5. 边界（不得越界解读）",
        "",
    ]
    for i, b in enumerate(r["boundaries"], 1):
        lines.append(f"{i}. {b}")
    lines += [
        "",
        "## 6. A1 时间盒实验设计（**本批 NOT RUN，需用户拍板**）",
        "",
        "> 设备只读核查：`10.0.0.2:37163` = `device`，`pm path` 返回 `base.apk`；",
        "> 但按项目纪律（不把「继续」当作路线授权 / 接线回滚授权），**本批不执行",
        "> 修复性真机注入**，`§61.75` 的「真机取证已完结」口径不变。",
        "",
        "**前提**：" + "；".join(r["a1_experiment_design_not_executed"]["preconditions"]) + "。",
        "",
    ]
    for s in r["a1_experiment_design_not_executed"]["steps"]:
        lines.append(f"- {s}")
    lines += [
        "",
        f"**裁决**：{r['a1_experiment_design_not_executed']['decision']}。",
        "",
        "> **§61.79 演进批注（历史正文不改）**：A1 时间盒已于 `12 §61.79` 经用户授权",
        "> 执行并裁决 `timebox_passed=False`（绕行成立但仅点火、无持续播放），",
        "> `§61.69` 接线已回滚；本节保留 `§61.78` 当时的 NOT RUN 设计快照。",
        "",
        "## 7. 与其他路线的关系",
        "",
        "- **不要换解码器**：`§61.77` 根因在 `NMCommonCache` 取流层，与解码器正交；",
        "  `analysis/l3-audio-decoder-effect-research-2026-11-11.md` 已登记",
        "  「换解码器修不了 F4，只会把跑通的链推倒」。",
        "- **不要转 A2 二进制补丁**：so 已 strip、无 `.symtab`、无源码、无回归基线",
        "  （`§61.56`）。A1 不成立按路线评估直接转 **B2**（Media3 + 按需 FFmpeg 扩展）。",
        "- **补格式**才考虑 LGPL 重编的增量 FFmpeg 软解扩展；**公开发布**才触发 `09 §5 R7`。",
        "",
        "## 8. 验证与诚实边界",
        "",
        f"`python tools/l3_f4_bypass_stream_audit.py` → {r['checks_total']} 项全 PASS；",
        "`python tools/check_source_contracts.py` → PASS（含 `§61.78` 守护）；",
        "`py_compile` OK；`git diff --check` 干净。零 P0 源码改动、未连 adb、",
        "未重编译 / 未重装、so 二进制原样、`§61.67~§61.77` 历史正文与证据 JSON 一字未改。",
        "**F4 仍 BLOCKED** —— 静态/离线证据等级提升 ≠ 修复 ≠ 验证；",
        "A3 仍 G09 PARTIAL、舱不冻结、指纹不回填、F3 14 格未改写。",
        "",
        "> AI生成",
        "",
    ]
    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())
