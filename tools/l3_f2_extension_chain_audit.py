#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.46 / F2 前置实证 · EngineAdapter 的 ResourceInfo.extension 推断链盘点
（只读、纯标准库、可复跑）。

产出 analysis/l3-f2-extension-chain.json：
  - extension 表达式的分支与 LOCAL 源落点（是否把 MIME 字符串当扩展名）；
  - UrlDataProvider 的 createDataRequestHandler / getLocalPath / openLocalFd /
    getUrl 状态；
  - 「待真机验证的假设」自述是否在位、F2 是否已改 P0 引擎舱代码；
  - 真机证据缺口与 F2 剩余 todo（如实登记，不预先判 PASS）。

与 §61.44 / §61.45 证据的关系：本工具只盘点源码现状，不采集设备状态、不调用
adb、不覆盖任何历史证据。设备侧实证（adb 端点 / logcat 命中数）由 12 §61.46
断点正文登记，工具重跑不会重新采集。

不修改任何生产文件、不猜测 native 行为、不伪造真机验证结论。
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/EngineAdapter.kt"
OUT = ROOT / "analysis" / "l3-f2-extension-chain.json"


def between(text: str, start: str, end: str) -> str:
    i = text.index(start)
    j = text.index(end, i)
    return text[i:j]


def audit(t: str) -> dict:
    chain = between(t, "val extension = source.url", "val info = ResourceInfo(")
    url_steps = {
        "substringAfterLast_dot": "substringAfterLast('.', \"\")" in chain,
        "substringBefore_query": "substringBefore('?')" in chain,
        "takeIf_len_le_5": "length <= 5" in chain,
        "elvis_to_mimeHint": "?: source.mimeHint.orEmpty()" in chain,
    }
    local_is_mime = url_steps["elvis_to_mimeHint"] and "source.url" in chain
    assumption = "待真机验证的假设" in t and "不是已证实的修复" in t

    provider = {
        "create_data_request_handler_url_only": "val url = source.url ?: return null" in t,
        "local_returns_null": "val url = source.url ?: return null" in t,
        "get_local_path_returns_real_path": "val path = source.localPath.orEmpty()" in t,
        "open_local_fd_returns": -1 if "return -1" in between(
            t, "override fun openLocalFd", "override fun closeLocalFd"
        ) else None,
        "url_result_format_arg": "source.mimeHint" in between(
            t, "override fun getUrl()", "override fun getLocalPath()"
        ),
    }

    return {
        "method": "12 §61.46 / F2 前置实证：EngineAdapter.ResourceInfo.extension 推断链静态盘点（只读）",
        "generated_by": "tools/l3_f2_extension_chain_audit.py",
        "source": str(SOURCE.relative_to(ROOT)).replace("\\", "/"),
        "extension_chain": {
            "line_range": "405-417",
            "url_branch_steps": url_steps,
            "local_fallback": "source.mimeHint.orEmpty()",
            "local_fallback_is_mime": local_is_mime,
            "resource_info_ctor": "ResourceInfo(extension, length, source.trackId, \"\")",
            "diag_log": "resourceInfo extension=$extension fileSize=$length",
            "self_declared_assumption": assumption,
            "f2_code_unchanged": assumption,
        },
        "provider": provider,
        "findings": [
            "LOCAL 源 source.url == null 时，整条 Elvis 链落到 source.mimeHint.orEmpty()，"
            "把 audio/mpeg 这类 MIME 字符串当作 extension 传进 ResourceInfo 第一个参数。",
            "REMOTE 源若 URL 无点、或最后一个点之后超过 5 字符（如回环直链 "
            "http://10.0.0.2:port/t/tok），takeIf 失败后同样落到 mimeHint。",
            "UrlDataProvider.getUrl() 构造 UrlResult 时 format 参数也传 source.mimeHint，"
            "与 extension 同源的 MIME/扩展名混用语义。",
            "createDataRequestHandler() 仅 URL 源创建 UrlDataRequest，LOCAL 源返回 null；"
            "getLocalPath() 返回真实路径，但 openLocalFd() 返回 -1（注释 V0.1 未实现），"
            "LOCAL 真实取流能力仍待真机确认，不预断为阻塞。",
            "代码自述「待真机验证的假设，不是已证实的修复」；本批未改该链、未改 P0 引擎舱。",
        ],
        "blockers": [
            "本批 adb 实跑：历史 logcat 缓冲区 622 行内无 EngineDiag / resourceInfo / "
            "Open codec / CloudMusic 命中 → 无法从历史日志证实或证伪假设。",
            "MIUI 无线调试限制 input tap / input keyevent（INJECT_EVENTS 被拒），"
            "无法自动切到 LOCAL 源并触发播放。",
            "需用户手动在自研 app 播放一个本地文件（或授权预置夹具 + am start），"
            "由本助手 adb logcat 抓 resourceInfo extension=... 与 Open codec 对照。",
        ],
        "todo": [
            "F2-a 真机取证：REMOTE 与 LOCAL 各播放一次，逐路径记录 extension 实际值与解码结果。",
            "F2-b 按证据修正 extension 推断：LOCAL 优先取 source.localPath 的真实扩展名，"
            "不再拿 MIME 字符串当扩展名；REMOTE 保留 URL 后缀但处理无扩展名直链。",
            "F2-c 复验并更新 §61.46 守护，随后进入 F3 双路径矩阵。",
        ],
        "honest_notes": [
            "本工具只做静态源码盘点，不调用 adb、不改生产代码、不声称 LOCAL 或全格式可播。",
            "设备侧实证（端点 10.0.0.2:37827 / 23013RK75C / Android 13 / 历史 logcat 命中 0）"
            "由 12 §61.46 断点正文登记，工具重跑不重新采集设备状态。",
            "P0 引擎舱（20 so / EngineAdapter / EngineModule）本批零改动。",
        ],
    }


def main() -> int:
    if not SOURCE.is_file():
        print("[FAIL] F2 证据源码缺失：%s" % SOURCE)
        return 1
    text = SOURCE.read_text(encoding="utf-8")
    # 12 §61.48 语义演进：F2-b 已按 §61.47 真机结论修正 extension 推断链，
    # 旧 Elvis 链不再存在。本工具是 §61.46 的**历史静态快照**，若让它继续跑会
    # 在 between() 处抛异常，或用当前源码覆盖历史证据（伪造「F2 未改代码」）。
    # 与 §61.44 的 l3_audio_format_audit.py 同纪律：F2-b 后拒绝重跑，返回 2。
    if "val extension = source.url" not in text:
        print("[GUARD] 12 §61.46 历史证据受保护：F2-b（12 §61.48）已修正 "
              "EngineAdapter.ResourceInfo.extension 推断链，本工具不再重跑、"
              "不覆盖 analysis/l3-f2-extension-chain.json。"
              "当前源码由 tools/l3_f2_ext_fix_audit.py 与 "
              "check_source_contracts.py §61.48 守护约束。")
        return 2
    data = audit(text)
    ec = data["extension_chain"]
    ok = (
        all(ec["url_branch_steps"].values())
        and ec["local_fallback_is_mime"] is True
        and ec["self_declared_assumption"] is True
        and ec["f2_code_unchanged"] is True
        and data["provider"]["create_data_request_handler_url_only"] is True
        and data["provider"]["get_local_path_returns_real_path"] is True
        and data["provider"]["open_local_fd_returns"] == -1
    )
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("[INFO] 源码 %s" % data["source"])
    print("[INFO] extension 链 4 分支：%s / LOCAL 落 mimeHint=%s / 自述假设=%s" %
          ("全在位" if all(ec["url_branch_steps"].values()) else "有缺",
           ec["local_fallback_is_mime"], ec["self_declared_assumption"]))
    print("[INFO] createDataRequestHandler 仅 URL=%s / getLocalPath 真路径=%s / openLocalFd=%s" %
          (data["provider"]["create_data_request_handler_url_only"],
           data["provider"]["get_local_path_returns_real_path"],
           data["provider"]["open_local_fd_returns"]))
    print("[INFO] 证据缺口 %d 条 / F2 todo %d 条；输出 %s" %
          (len(data["blockers"]), len(data["todo"]), OUT.relative_to(ROOT)))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
