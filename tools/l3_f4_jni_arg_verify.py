#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
12 §61.56 / F4 · Java→native 参数槽透传的一手二进制复核（离线、只读）。

§61.52 的 kind 槽假设其实包含两个独立环节：
  A. `UrlDataRequest.kt:96` 传的 `requestId` 是否原样落到 native
     `OnNetSourceDataDone(int kind, ...)` 的 `w1`（kind 槽）；
  B. 这个错误的「预加载完成」通知是否就是 fMP4 REMOTE 卡点的原因。

§61.54 已证实 native 侧 `cmp w1,#2` 的分支语义，§61.55 已证实两个 task
的行为差异；本工具专攻**环节 A**：反汇编 JNI 导出
`Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_OnNativeDataRequest{End,Data}`
与 trampoline `0x36c70`，并与 §59.3 已真机校准的数据包路径做同构交叉验证。

只读；复用 tools/l3_f4_disasm_verify.py 的 ELF/capstone 封装。
产出 analysis/l3-f4-jni-arg.json。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import l3_f4_disasm_verify as dv  # noqa: E402

OUT = ROOT / "analysis" / "l3-f4-jni-arg.json"
JNI_END = "Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_OnNativeDataRequestEnd"
JNI_DATA = "Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_OnNativeDataRequestData"
SRC_JAVA = ROOT / ("engine-legacy/src/main/java/com/netease/cloudmusic/sdk/"
                   "NMCommonCache/SourceDataRequest.java")
URL_REQ = ROOT / ("engine-legacy/src/main/java/com/example/cloudmusic/"
                  "enginelegacy/UrlDataRequest.kt")
DELIVERY12 = ROOT / "docs/12-编码阶段第一批交付.md"

END_ADDR, END_SIZE = 0x3495C, 360
DATA_ADDR, DATA_SIZE = 0x347F4, 360
TRAMPOLINE = 0x36C70
DATA_TRAMPOLINE = 0x36F68
JNI_PREFIX = "Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_"


def lines(addr: int, size: int) -> list[str]:
    return dv.disassemble(dv.SO, addr, size)


def find(lines_: list[str], pattern: str) -> str | None:
    return next((l for l in lines_ if re.search(pattern, l)), None)


def main() -> int:
    if not dv.SO.is_file():
        print("[FAIL] 缺少 %s" % dv.SO)
        return 1
    info = dv.elf_info(dv.SO)
    syms = info["symbols"]
    # 第三个参数是「字节数」不是指令条数：trampoline 的 vtable+0x20 加载
    # 位于 0x36cac（偏移 0x3c），必须覆盖到 0x36cc4 之后。
    end, data, tramp = lines(END_ADDR, END_SIZE), lines(DATA_ADDR, DATA_SIZE), \
        lines(TRAMPOLINE, 0x70)
    done = lines(dv.FUNC_ADDR, dv.FUNC_SIZE)
    java = SRC_JAVA.read_text(encoding="utf-8", errors="replace") \
        if SRC_JAVA.is_file() else ""
    url = URL_REQ.read_text(encoding="utf-8", errors="replace") \
        if URL_REQ.is_file() else ""
    d12 = DELIVERY12.read_text(encoding="utf-8", errors="replace")

    # JNI 调用约定（ARM64）：x0=JNIEnv*, x1=jobject, x2=native_ptr(long j),
    # x3=j2(long, Java 侧的 j1 请求标识), x4+=其余参数。
    checks = {
        "jni_end_export_found": (
            syms.get(JNI_END) == END_ADDR and len(end) >= 80),
        "jni_data_export_found": (
            syms.get(JNI_DATA) == DATA_ADDR and len(data) >= 80),
        "end_saves_j2_into_x21": bool(find(end, r"\bmov\s+x21, x3")),
        "end_saves_native_ptr_into_x23": bool(find(end, r"\bmov\s+x23, x2")),
        "end_sets_x0_to_native_ptr": bool(find(end, r"^0x34a54\s+mov\s+x0, x23")),
        "end_sets_x1_to_j2": bool(find(end, r"^0x34a58\s+mov\s+x1, x21")),
        "end_calls_trampoline_0x36c70": bool(find(end, r"\bbl\s+#0x36c70")),
        "data_saves_j2_into_x21": bool(find(data, r"\bmov\s+x21, x3")),
        "data_sets_x0_to_native_ptr": bool(find(data, r"^0x34900\s+mov\s+x0, x22")),
        "data_sets_x1_to_j2": bool(find(data, r"^0x34904\s+mov\s+x1, x21")),
        "data_calls_parallel_trampoline_0x36f68": bool(
            find(data, r"\bbl\s+#0x36f68")),
        "trampoline_forwards_x1_unchanged": (
            bool(find(tramp, r"\bmov\s+x21, x1"))
            and bool(find(tramp, r"\bmov\s+x1, x21"))),
        "trampoline_dispatches_via_vtable_0x20": (
            bool(find(tramp, r"\bldr\s+x8, \[x8, #0x20\]"))
            and bool(find(tramp, r"\bblr\s+x8"))),
        "done_compares_w1_with_2": bool(find(done, r"\bcmp\s+w1, #2")),
        "java_kdoc_documents_j1_as_kind_slot": (
            "j1==2 → `PreloadDoneTaskOnWriteThread`" in java
            and "原样带回三个回调首参" in java),
        "url_request_passes_requestId": (
            "OnDataRequestEnd(requestId, error ?: \"\")" in url),
        "docs59_3_calibrated_data_kind_1L": (
            "NATIVE_DATA_KIND_NORMAL = 1L" in d12
            and "Send Packet Invalid data error" in d12),
    }
    failed = [k for k, v in checks.items() if not v]

    def key(ls: list[str], pat: str) -> list[str]:
        return [l for l in ls if re.search(pat, l)]

    data = {
        "method": "12 §61.56 / F4 Java→native 参数槽透传的一手二进制复核（离线、只读）",
        "generated_by": "tools/l3_f4_jni_arg_verify.py",
        "so": str(dv.SO.relative_to(ROOT)).replace("\\", "/"),
        "jni_calling_convention": (
            "ARM64 JNI: x0=JNIEnv*, x1=jobject, x2=long j(native_ptr), "
            "x3=long j2(Java 侧 j1 请求标识), x4+=其余参数"),
        "checks": checks,
        "all_pass": not failed,
        "failed_checks": failed,
        "evidence": {
            "jni_end": {
                "symbol": JNI_END, "addr": hex(END_ADDR), "size": END_SIZE,
                "arg_moves": key(end, r"\bmov\s+x(2[0-5]|0|1), x[0-9]+"),
                "call": find(end, r"\bbl\s+#0x36c70"),
            },
            "jni_data": {
                "symbol": JNI_DATA, "addr": hex(DATA_ADDR), "size": DATA_SIZE,
                "arg_moves": key(data, r"^0x3490[0-9a-f]\s+mov"),
                "call": find(data, r"\bbl\s+#0x36f68"),
            },
            "trampoline_0x36c70": key(
                tramp, r"\b(mov\s+x(21|1|0)|ldr\s+x8|blr\s+x8)"),
            "native_done": key(done, r"\bcmp\s+w1, #2|\bb\.ne"),
        },
        "conclusion": (
            "JNI 导出 OnNativeDataRequestEnd 把 Java 的 j2（= UrlDataRequest 传入的 "
            "requestId）原样经 x21 放进 x1，调 trampoline 0x36c70；后者再次 "
            "mov x21,x1 → mov x1,x21 后经 vtable+0x20 虚调用，参数槽未被改写或重映射。"
            "与已被 §59.3 真机校准为 kind 的数据包路径（OnNativeDataRequestData，"
            "同样 x0=native_ptr / x1=j2 / 调 0x36f68）完全同构，而 native 侧 "
            "OnNetSourceDataDone 正是用 w1 与 #2 比较。因此假设的环节 A —— "
            "「requestId 会原样落到 kind 槽，id==2 即 kind==2」—— 由二进制证据 "
            "与历史真机校准双重确认。环节 B（错误的完成通知是否就是 fMP4 REMOTE "
            "卡点的原因）仍无真机证据，F4 仍 BLOCKED。"),
        "honest_notes": [
            "虚表槽 vtable+0x20 的具体目标类型由运行时决定；静态反汇编证明的是「参数槽透传无变换」，"
            "结合 §59.3 数据包路径真机校准与 KDoc 记录构成高置信证据，但不是 100% 的类型证明。",
            "§59.2 exp6 反例、竞品同样传 requestId 却可用、ape×remote 与 aiff 仍不解释。",
            "本工具只读 so / java / docs，不连 adb、不改任何生产代码、零新增依赖。",
            "A/B 尚未执行；自动进曲 3 首 / seek / ENDED 如实 NOT RUN；A3 仍 G09 PARTIAL、舱不冻结。",
        ],
    }
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
    if failed:
        print("[FAIL] §61.56 复核未通过：%s" % failed)
        return 1
    print("[PASS] §61.56 复核：%d 项 checks 全 PASS" % len(checks))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
