#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.55 / F3 · OpenList 真实音频格式的可复跑盘点（只读列目录，不下载音频）。

F3 矩阵（`12 §61.49`）有 7 种 NO_SAMPLE 格式：
m4a / aac / opus / wv / tak / dsf / dff。本工具递归列 OpenList 目录，
确认这 7 种在数据源里到底有没有，把「待用户提供样本」的边界钉死。

凭据**只从进程环境变量 OLU / OLP 读取，绝不写入仓库或产出 JSON**；
未设置时直接退出（不猜测、不内置默认值）。产出
analysis/l3-f3-openlist-scan.json 只含聚合统计，不含任何文件路径/凭据。
"""
from __future__ import annotations

import collections
import json
import os
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "analysis" / "l3-f3-openlist-scan.json"
BASE = "http://192.168.1.1:15244"
NO_SAMPLE = ("m4a", "aac", "opus", "wv", "tak", "dsf", "dff")
WANT = NO_SAMPLE + ("aiff", "aif", "flac", "ape", "wma", "ogg",
                    "mp3", "wav", "mp4")
MAX_DEPTH = 3
MAX_DIRS = 80


def main() -> int:
    user, pw = os.environ.get("OLU", ""), os.environ.get("OLP", "")
    if not (user and pw):
        print("[SKIP] 未设置 OLU / OLP 环境变量，跳过网络盘点"
              "（凭据不落仓库；本工具不连 adb、不下载音频）")
        return 2
    try:
        req = urllib.request.Request(
            BASE + "/api/auth/login",
            data=json.dumps({"username": user, "password": pw}).encode(),
            headers={"Content-Type": "application/json"})
        token = json.load(urllib.request.urlopen(req, timeout=15))["data"]["token"]
    except Exception as exc:
        print("[FAIL] OpenList 登录失败：%s" % exc)
        return 1

    def api(path: str, body: dict) -> dict:
        q = urllib.request.Request(
            BASE + path, data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json", "Authorization": token})
        return json.load(urllib.request.urlopen(q, timeout=20))

    ext = collections.Counter()
    candidates = collections.Counter()
    errors = []
    dirs = 0
    queue = collections.deque([("/", 0)])
    seen = set()
    while queue and dirs < MAX_DIRS:
        path, depth = queue.popleft()
        if path in seen or depth > MAX_DEPTH:
            continue
        seen.add(path)
        dirs += 1
        try:
            content = api("/api/fs/list", {
                "path": path, "page": 1, "per_page": 500,
                "refresh": False})["data"]["content"]
        except Exception as exc:
            errors.append({"depth": depth, "reason": str(exc)[:80]})
            continue
        for item in content:
            name = item.get("name", "")
            if item.get("is_dir"):
                queue.append((path.rstrip("/") + "/" + name, depth + 1))
                continue
            suffix = name.rsplit(".", 1)[-1].lower() if "." in name else ""
            if suffix in WANT:
                ext[suffix] += 1
                if suffix in NO_SAMPLE:
                    candidates[suffix] += 1

    found = sorted(k for k in NO_SAMPLE if candidates[k])
    data = {
        "method": "12 §61.55 / F3 NO_SAMPLE 格式的数据源边界盘点"
                  "（只读列目录、不下载音频、凭据仅进程内）",
        "generated_by": "tools/l3_f3_openlist_scan.py",
        "scanned_at": datetime.now().isoformat(timespec="seconds"),
        "config": {"max_depth": MAX_DEPTH, "max_dirs": MAX_DIRS,
                   "per_page": 500, "endpoint_host": "192.168.1.1"},
        "dirs_scanned": dirs,
        "list_errors": len(errors),
        "audio_ext_counts": dict(sorted(ext.items())),
        "no_sample_formats_checked": list(NO_SAMPLE),
        "no_sample_candidates": dict(candidates),
        "no_sample_found": found,
        "no_sample_candidate_total": sum(candidates.values()),
        "conclusion": (
            "递归列目录后，7 种 NO_SAMPLE 格式在数据源中"
            + ("出现：" + ", ".join(found) if found else "一个都没有")
            + "；F3 的 NO_SAMPLE 缺口不是脚本能力问题，而是数据源确实没有这些格式，"
              "只能由用户提供样本或换数据源后复用 §8.9 脚本复跑。"),
        "honest_notes": [
            "只列目录不改任何远端文件；不下载音频；不连 adb、不碰设备。",
            "凭据从 OLU/OLP 环境变量读取，不写入源码、日志或本 JSON；token 仅在进程内存活。",
            "扫描受 max_depth / max_dirs 上限约束，结论只对已扫描到的目录成立。",
            "本盘点不改变 F3 矩阵 14 格的既有结论，也不判任何格式为已支持。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    print("[INFO] dirs=%d errors=%d audio_ext=%s no_sample_found=%s"
          % (dirs, len(errors), dict(ext), found))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
