#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.60 / F4 · 第二卡点的「样本侧」一手结构复核（纯离线、只读）。

§61.59 真机 A/B 后，变体 B 消除了 id>=3 重复请求但 fMP4 REMOTE 仍无 codec。
上一批把第二卡点归因为「fMP4 多 Range × normal mode vfile 落盘 / demuxer
probe 数据不足」。本工具直接解析 `f3_m4a_fast.m4a` 的 ISO-BMFF 顶层 box
布局，并对照 §61.59 的 `onData` 覆盖，判断究竟是「数据落盘丢了」还是
「人工重排的样本本身不自洽」。

只读、纯标准库，不连 adb / 不改任何文件。
产出 analysis/l3-f4-fmp4-structure.json。
"""
from __future__ import annotations

import json
import re
import struct
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "analysis"
SAMPLE = A / "l3-f3-fixtures" / "f3_m4a_fast.m4a"
LOG_RUN2 = A / "l3-f4-logs" / "ab-run2-m4a-remote.txt"
LOG_BASELINE = A / "l3-f4-logs" / "ab-control-baseline-remote.txt"
RESULT = A / "l3-f4-kind-slot-ab.json"
CONTROL = A / "l3-f4-ab-control-baseline.json"
OUT = A / "l3-f4-fmp4-structure.json"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


def top_boxes(data: bytes) -> list[dict]:
    out = []
    pos = 0
    while pos + 8 <= len(data):
        size = struct.unpack_from(">I", data, pos)[0]
        typ = data[pos + 4:pos + 8].decode("latin1")
        hdr = 8
        if size == 1:
            if pos + 16 > len(data):
                break
            size = struct.unpack_from(">Q", data, pos + 8)[0]
            hdr = 16
        elif size == 0:
            size = len(data) - pos
        if size < hdr or pos + size > len(data):
            break
        out.append({"type": typ, "offset": pos, "size": size,
                    "end": pos + size})
        pos += size
    return out


def coverage(text: str, rid: int) -> list[tuple[int, int]]:
    return [(int(a), int(c)) for a, c in re.findall(
        r"onData id=%d offset=(\d+) count=(\d+)" % rid, text)]


def main() -> int:
    if not SAMPLE.is_file():
        print("[FAIL] 缺少样本 %s" % SAMPLE)
        return 1
    data = SAMPLE.read_bytes()
    boxes = top_boxes(data)
    counts = Counter(x["type"] for x in boxes)
    last = boxes[-1] if boxes else {}
    log = read(LOG_RUN2)
    starts = [tuple(map(int, m)) for m in re.findall(
        r"Start id=(\d+) offset=(\d+) length=(\d+)", log)]
    # (id, offset, length)
    c1, c2 = coverage(log, 1), coverage(log, 2)
    ends = re.findall(r"onEnd id=(\d+) error=\S+", log)
    baseline = json.loads(read(CONTROL) or "{}")
    ab = json.loads(read(RESULT) or "{}")

    first = boxes[0]["type"] if boxes else ""
    second = boxes[1]["type"] if len(boxes) > 1 else ""
    tail_is_orphan_moof = (
        last.get("type") == "moof"
        and last.get("end") == len(data)
        and (len(boxes) < 2 or boxes[-2]["type"] != "mdat"))
    no_index = not any(counts.get(k) for k in ("sidx", "mfra", "mfro"))
    gap_middle = (327895, 605007)
    gap_head = (0, 147456)

    requested = [(off, off + ln) for _, off, ln in starts]
    c1_span = (min(o for o, _ in c1), max(o + c for o, c in c1)) if c1 else (0, 0)
    c2_span = (min(o for o, _ in c2), max(o + c for o, c in c2)) if c2 else (0, 0)
    data_complete = (
        c1_span == (147456, 327895)
        and c2_span == (605007, 1653583)
        and sum(c for _, c in c1) == 180439
        and sum(c for _, c in c2) == 1048576)

    checks = {
        "sample_size_exact": len(data) == 3918787,
        "faststart_layout": first == "ftyp" and second == "moov",
        "fragment_count_13_styp_13_moof_12_mdat": (
            counts.get("styp") == 13 and counts.get("moof") == 13
            and counts.get("mdat") == 12),
        "tail_is_orphan_moof_no_mdat": tail_is_orphan_moof,
        "no_sidx_mfra_mfro_index": no_index,
        "variant_b_requests_two_ranges": (
            len(starts) == 2
            and starts[0][1:] == (147456, 180439)
            and starts[1][1:] == (605007, 1048576)),
        "head_including_moov_not_requested": all(
            not (s < gap_head[1] and e > gap_head[0]) for s, e in requested),
        "middle_fragment_not_requested": all(
            not (s < gap_middle[1] and e > gap_middle[0]) for s, e in requested),
        "ondata_matches_request_exactly": data_complete,
        "no_codec_in_variant_b": (
            "Open codec" not in log and ab.get("primary_criterion_met") is False),
        "baseline_repeats_same_offset": any(
            "offset=605007" in s for s in baseline.get("starts", []) if "id=3" in s),
        "onend_only_two_in_variant_b": ends == ["1", "2"],
    }
    failed = [k for k, v in checks.items() if not v]
    result = {
        "method": "12 §61.60 / F4 第二卡点的样本侧结构复核（纯离线、只读）",
        "generated_by": "tools/l3_f4_fmp4_structure_audit.py",
        "sample": str(SAMPLE.relative_to(ROOT)).replace("\\", "/"),
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "evidence": {
            "file_size": len(data),
            "box_counts": dict(sorted(counts.items())),
            "first_boxes": [{k: x[k] for k in ("type", "offset", "size")}
                            for x in boxes[:10]],
            "last_boxes": [{k: x[k] for k in ("type", "offset", "size")}
                           for x in boxes[-4:]],
            "requested_ranges": requested,
            "ondata_id1_span": c1_span,
            "ondata_id2_span": c2_span,
            "unrequested_gaps": [gap_head, gap_middle],
            "onend_ids": ends,
        },
        "conclusion": (
            "f3_m4a_fast.m4a 是人工截取/重排且**不完整的 fMP4**："
            "13 个 moof 但只有 12 个 mdat，文件末尾是**没有配对 mdat 的孤立 moof**"
            "（截断在分片中间），且没有 sidx / mfra / mfro 索引。"
            "变体 B 下 native 只请求 [147456,327895) 与 [605007,1653583)，"
            "**跳过开头 147456 B（含 ftyp+moov）与整个第二分片 [327895,605007)**；"
            "onData 覆盖与请求范围逐字节一致（180439 B + 1048576 B），"
            "说明 Java 侧数据没有丢、不是 vfile 落盘丢数据。"
            "因此 demuxer probe 拿到的字节集合本身不完整，"
            "**第二卡点应优先归因于样本不自洽，而不是 native vfile 落盘缺陷**。"),
        "attribution_correction": (
            "§61.59 把第二卡点写作「fMP4 多 Range × normal mode vfile 落盘 / "
            "demuxer probe 数据不足」；本批把优先级修正为："
            "① 人工重排样本不自洽（缺索引 / 尾部截断 / 分片跳读）为主要嫌疑，"
            "② normal mode vfile 落盘时序为次要候选。"
            "A/B 的实测结果（环节 B 部分成立、主判据未达成）不改写。"),
        "honest_notes": [
            "结构分析是静态的；「样本不自洽导致 probe 失败」仍是高置信推论，未真机验证。",
            "判据需要一个完整、未经重排的真实 .m4a（或原始 mp4 完整片段）重建可判定场景；设备已离线，本批判 PENDING_REAL_SAMPLE。",
            "不判播放器缺陷，也不写「F4 已解决 / m4a 已支持 / 全格式已支持」。",
            "A/B 实测事实不改写；自动进曲 3 首 / seek / ENDED 仍 NOT RUN；A3 仍 G09 PARTIAL、舱不冻结。",
        ],
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.60 结构复核未通过：%s" % failed)
        return 1
    print("[PASS] §61.60 结构复核：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
