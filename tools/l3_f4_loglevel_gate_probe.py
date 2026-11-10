#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
F4 · level-5 日志门控「零侵入开启路径」探查（纯离线、只读 so 二进制）。

承接 `12 §61.68` 剩余断点①：确认是否存在「不改 Java 源码 / 不重编译 /
不重装 APK」即可在真机开启 native level-5 分支日志的路径。

复跑（需 capstone + pyelftools，用项目既有 venv）：
  /c/Users/cjd65/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe \
      tools/l3_f4_loglevel_gate_probe.py

二进制结论（本工具逐项复验）：
- 全局 log level 存于 `.bss` 的 `0x4c388`（SHT_NOBITS → 进程启动时恒为 0）；
- 全 `.text` 只有 2 条指令访问它：getter `0x37e24 (ldr)` / setter
  `0x37e38 (str)`；
- setter 所在函数 `0x37e34` 全 `.text` **只有一个调用点** `0x32d88`，
  即 `SetLoggerCallback(INMLogger,int)` JNI 实现的 tail call；
- `SetLoggerCallback` JNI @`0x32d64` 仅 40 字节：`w3`=level → `0x37e34`；
- `DT_NEEDED` 无 `libnblog.so`；`libnblog.so` / `nblog_write` 只是 `.rodata`
  字符串（经 `libdl.so` 动态探测），无 prop / 配置文件 / 跨进程入口；
- `SetConfig` C++ @`0xe3e4` 函数体 ADRP+ADD 引用到的 `.rodata` 串只有
  6 条 `Config Error...` / `Finish` 日志格式串；全 so 二进制大小写不敏感
  扫描 `level` / `debug` / `verbose` / `log_output` **命中 0** → native 侧
  根本不存在「日志级别配置」概念（无 JSON key / 无 prop / 无配置文件）。

=> 零侵入路径不存在；唯一开启方式是在 **app 进程内**调用
   `NMVirtualFileManager.SetLoggerCallback(logger, level)`，而生产 app
   全库无该调用点（native 全局 level 是进程内 `.bss`，跨进程注入无效）
   → 必须改 Java 源码 + 重编译 + 重装 APK（动 P0 舱体），需用户拍板。

两个改 P0 前必须先确认的实操风险（本轮二进制实证）：
- JNI `0x32d70 mov x0,x2` 把 logger 对象传给唯一 callee `0x36198`
  （`0x361c8 mov x20,x0`），传 **null** 的安全性未实证 → 应传一个
  `INMLogger` 实例而非 null；
- getter `0x37e20` 实为 `cmp w8,w0; cset w0,gt`（全局 level **严格大于**
  传入 level 才为真），故 `level=5` 是否足以解锁 level-5 分支须结合
  调用点实参确认，不是「填 5 就一定开」。

F4 仍 BLOCKED。本探查不改任何生产源码、不连 adb、不改 so。
**性质**：`§61.68` 剩余断点的探查证据，不构成新交付批次；
待用户拍板后并入下一批统一固化文档 / 守护。
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SO = ROOT / "engine-legacy" / "src" / "main" / "jniLibs" / "arm64-v8a" / "libNMCommonCache.so"
OUT = ROOT / "analysis" / "l3-f4-loglevel-gate-probe.json"

LEVEL_GLOBAL = 0x4C388
SETTER = 0x37E34
GETTER = 0x37E20
JNI_SETLOGGER = 0x32D64
SETCONFIG_CPP = 0xE3E4
TAILCALL_SITE = 0x32D88


def main() -> int:
    try:
        from elftools.elf.elffile import ELFFile
        from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
    except ImportError as exc:
        print("[FAIL] 需要 capstone + pyelftools（用项目 venv 跑）：%s" % exc)
        return 2
    if not SO.is_file():
        print("[FAIL] so 缺失：%s" % SO)
        return 1

    raw = SO.read_bytes()
    with SO.open("rb") as fp:
        elf = ELFFile(fp)
        needed = []
        for sec in elf.iter_sections():
            if sec.header.sh_type == "SHT_DYNAMIC":
                for tag in sec.iter_tags():
                    if tag.entry.d_tag == "DT_NEEDED":
                        needed.append(tag.needed)

        dyn = elf.get_section_by_name(".dynsym")
        symbols = {}
        for s in dyn.iter_symbols():
            if s.name:
                symbols[s.name] = {
                    "value": s.entry.st_value,
                    "size": s.entry.st_size,
                    "shndx": str(s.entry.st_shndx),
                }

        in_bss = False
        bss_name = ""
        for sec in elf.iter_sections():
            addr, size = sec["sh_addr"], sec["sh_size"]
            if addr <= LEVEL_GLOBAL < addr + size:
                bss_name = sec.name
                in_bss = sec["sh_type"] == "SHT_NOBITS"

        text = elf.get_section_by_name(".text")
        code, base = text.data(), text["sh_addr"]
        md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
        md.detail = True
        ins = list(md.disasm(code, base))

        by_addr = {i.address: i for i in ins}
        setter_calls = [i.address for i in ins
                        if i.mnemonic in ("bl", "b")
                        and i.op_str.strip() == "#0x%x" % SETTER]

        # 所有访问 0x4c388 的指令（adrp x?,#0x4c000 后 8 条内命中 #0x388）
        global_access = []
        for idx, i in enumerate(ins):
            if i.mnemonic == "adrp" and "#0x4c000" in i.op_str:
                reg = i.op_str.split(",")[0].strip()
                for j in ins[idx + 1: idx + 8]:
                    if "0x388" in j.op_str and ("[%s" % reg) in j.op_str:
                        global_access.append({"adrp": i.address,
                                              "access": j.address,
                                              "insn": "%s %s" % (j.mnemonic, j.op_str)})
                        break

        # SetConfig @0xe3e4 函数体：还原 ADRP+ADD 实际引用的 .rodata 字符串。
        # 这是「SetConfig 无 level 类 key」的直接证据，比在字符串表里猜 key 集合
        # 更严谨（0x3d286 附近另有一张 NUL 分隔的 JSON key 表，并非本函数引用）。
        ro = elf.get_section_by_name(".rodata")
        rodata, roaddr = ro.data(), ro["sh_addr"]
        cfg_body = [i for i in ins
                    if SETCONFIG_CPP <= i.address < SETCONFIG_CPP + 1144]
        cfg_refs: list[int] = []
        for idx, i in enumerate(cfg_body):
            if i.mnemonic != "adrp":
                continue
            m = re.match(r"(\w+), #(0x[0-9a-f]+)", i.op_str.strip())
            if not m:
                continue
            reg, page = m.group(1), int(m.group(2), 16)
            for j in cfg_body[idx + 1: idx + 8]:
                if j.mnemonic == "adrp":
                    break
                m2 = re.match(r"(\w+), " + re.escape(reg) + r", #(0x[0-9a-f]+)",
                              j.op_str.strip())
                if m2:
                    cfg_refs.append(page + int(m2.group(2), 16))
                    break

        def rodata_cstr(addr: int) -> str | None:
            off = addr - roaddr
            if not (0 <= off < len(rodata)):
                return None
            end = rodata.find(b"\x00", off)
            s = rodata[off:end if end >= 0 else off + 80]
            if not s or any(c < 32 or c > 126 for c in s):
                return None
            return s.decode("ascii")

        cfg_strings = []
        for a in sorted(set(cfg_refs)):
            s = rodata_cstr(a)
            if s:
                cfg_strings.append({"addr": hex(a), "text": s})
        cfg_level_refs = [x["text"] for x in cfg_strings
                          if any(w in x["text"].lower()
                                 for w in ("level", "debug", "verbose", "log_output"))]
        level_keyword_hits = [m.group().decode("ascii", "replace")
                              for m in re.finditer(
                                  rb"(?i)(?:log)?level|debug|verbose|log_output", raw)]
        setlogger_callee_calls = [i.address for i in ins
                                  if i.mnemonic == "bl"
                                  and i.op_str.strip() == "#0x36198"]

        anchors = {
            "jni_setlogger_prologue": [hex(a) for a in
                                       (0x32D64, 0x32D70, 0x32D74, 0x32D78, 0x32D80, 0x32D88)
                                       if a in by_addr],
            "setter_body": [hex(a) for a in (SETTER, 0x37E38, 0x37E3C) if a in by_addr],
            "getter_body": [hex(a) for a in (GETTER, 0x37E24) if a in by_addr],
            "level_global": hex(LEVEL_GLOBAL),
            "level_global_section": bss_name,
            "setter_callsites": [hex(a) for a in setter_calls],
            "global_access_count": len(global_access),
            "global_access": global_access,
            "needed": needed,
            "setconfig_body_insns": len(cfg_body),
            "setlogger_callee_callsites": [hex(a) for a in setlogger_callee_calls],
            "getter_compare_is_strict_gt": (
                by_addr.get(0x37E28) is not None
                and by_addr[0x37E28].mnemonic == "cmp"
                and "w8, w0" in by_addr[0x37E28].op_str
                and by_addr.get(0x37E2C) is not None
                and by_addr[0x37E2C].mnemonic == "cset"
                and "gt" in by_addr[0x37E2C].op_str),
        }

        # .rodata 字符串：只用于 nblog 动态探测线索（dlopen 探测目标）。
        # 注：不再用「字符串表里凑 config key 集合」证明无 level key —— 其中
        # `dir`(3 字符) 会被 `{4,}` 正则漏掉，且 0x3d286 附近那张 NUL 分隔
        # JSON key 表并非 SetConfig 引用，证据链不严谨，已改由函数体反汇编直证。
        all_strings = [m.group().decode("ascii", "replace")
                       for m in re.finditer(rb"[ -~]{4,}", raw)]
        nblog_strings = [s for s in all_strings
                         if "libnblog" in s or "nblog_write" in s]

        sym = symbols.get(
            "Java_com_netease_cloudmusic_sdk_NMCommonCache_NMVirtualFileManager_SetLoggerCallback", {})
        sym_cfg = symbols.get(
            "_ZN20NMVirtualFileManager9SetConfigERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE", {})

        checks = {
            "so_binary_intact": len(raw) == 309632,
            "needed_excludes_libnblog": "libnblog.so" not in needed,
            "needed_has_libdl_for_dlopen": "libdl.so" in needed,
            "android_log_print_is_undef_import": "__android_log_print" in symbols
                and symbols["__android_log_print"]["shndx"] == "SHN_UNDEF",
            "setlogger_jni_addr_and_size": sym.get("value") == JNI_SETLOGGER
                and sym.get("size") == 40,
            "setconfig_cpp_addr": sym_cfg.get("value") == SETCONFIG_CPP,
            "level_global_in_bss_zero_init": in_bss and bss_name == ".bss",
            "setter_stores_w0_to_global": 0x37E38 in by_addr
                and by_addr[0x37E38].mnemonic == "str"
                and "0x388" in by_addr[0x37E38].op_str
                and 0x37E34 in by_addr
                and by_addr[0x37E34].mnemonic == "adrp",
            "getter_loads_global": 0x37E24 in by_addr
                and by_addr[0x37E24].mnemonic == "ldr"
                and "0x388" in by_addr[0x37E24].op_str,
            "setter_has_single_callsite": setter_calls == [TAILCALL_SITE],
            "jni_tailcalls_setter": TAILCALL_SITE in by_addr
                and by_addr[TAILCALL_SITE].mnemonic == "b"
                and by_addr[TAILCALL_SITE].op_str.strip() == "#0x%x" % SETTER,
            "jni_passes_w3_as_level": 0x32D74 in by_addr
                and by_addr[0x32D74].mnemonic == "mov"
                and "w19, w3" in by_addr[0x32D74].op_str,
            "global_access_only_getter_and_setter": len(global_access) == 2,
            "nblog_is_rodata_dlopen_probe_only": bool(nblog_strings)
                and "libnblog.so" not in needed,
            "setconfig_cpp_refs_no_level_key": len(cfg_strings) >= 6
                and not cfg_level_refs,
            "so_has_no_level_keyword_at_all": not level_keyword_hits,
            "jni_passes_x2_as_logger": 0x32D70 in by_addr
                and "x0, x2" in by_addr[0x32D70].op_str,
            "setlogger_callee_has_single_callsite":
                setlogger_callee_calls == [0x32D78],
            "getter_is_strict_gt_compare": anchors["getter_compare_is_strict_gt"],
        }

        result = {
            "method": "12 §61.68 剩余断点① · level-5 零侵入路径探查（ELF 符号 + capstone 全 .text 反汇编，纯离线只读）",
            "generated_by": "tools/l3_f4_loglevel_gate_probe.py",
            "so": str(SO.relative_to(ROOT)).replace("\\", "/"),
            "so_size": len(raw),
            "anchors": anchors,
            "nblog_rodata_strings": nblog_strings,
            "setconfig_rodata_refs": cfg_strings,
            "setconfig_level_like_refs": cfg_level_refs,
            "so_level_keyword_hits": level_keyword_hits,
            "checks": checks,
            "all_pass": all(checks.values()),
            "failed_checks": [k for k, v in checks.items() if not v],
            "finding": {
                "level_global": "0x4c388 位于 .bss（SHT_NOBITS）→ 进程启动恒为 0",
                "sole_writer": "0x37e34 setter 全 .text 唯一调用点 0x32d88 = SetLoggerCallback JNI tail call",
                "zero_intrusive_verdict": "不存在：SetConfig 函数体引用串与全 so 二进制均无 level/debug/verbose 关键字（大小写不敏感 0 命中）、无 prop / 配置文件 / 跨进程入口；libnblog.so 未链接（仅 dlopen 探测字符串）",
                "only_path": "在 app 进程内调用 NMVirtualFileManager.SetLoggerCallback(INMLogger 实例, level)；生产 app 全库无调用点 → 须改 Java 源码 + 重编译 + 重装 APK（动 P0 舱体）",
                "null_logger_risk": "JNI 0x32d70 mov x0,x2 把 logger 传给唯一 callee 0x36198（0x361c8 mov x20,x0）；传 null 的安全性未实证 → 改 P0 时应传 INMLogger 实例而非 null",
                "level_compare_semantics": "getter 0x37e20 实为 cmp w8,w0; cset w0,gt（全局 level 严格大于传入 level 才为真）；level=5 是否足以解锁 level-5 分支须结合调用点实参确认，不是填 5 就一定开",
                "f4_status": "BLOCKED（本探查只收窄断点，不修复、不验证）",
            },
            "honest_notes": [
                "本探查是纯离线二进制取证，未连 adb、未改任何生产源码、so 二进制原样。",
                "§61.68 两轮真机观测已实证 setprop log.tag.* VERBOSE 不解锁 level-5；本探查给出其二进制层原因。",
                "0x10798 整包丢弃分支与 NMSlicesFile::Write 的 file_len 门控仍无直接分支日志；level-5 未开启。",
                "改 P0 方案的两个前置风险已实证：SetLoggerCallback 传 null logger 的安全性未实证（应传 INMLogger 实例）；getter 是严格大于比较，level 取值须结合调用点实参确认。二者均未做取舍、未改代码。",
                "§61.68 后修正：原 `setconfig_json_keys_have_no_level` 用字符串表凑 6 个 key，`dir` 仅 3 字符被 `{4,}` 正则漏掉而误报 FAIL；本轮不放宽门槛凑绿，改由 SetConfig 函数体 ADRP+ADD 反汇编直证 + 全 so 大小写不敏感关键字扫描。",
                "F4 仍 BLOCKED：-103 未通过、无 Write start play / doPlayCompleted、自动进曲 3 首 / seek / ENDED NOT RUN、A3 仍 G09 PARTIAL、舱不冻结。",
                "本文件为探查证据，不构成新交付批次；待用户拍板后并入下一批统一固化文档 / 守护。",
            ],
        }
        OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                       encoding="utf-8")
        print("[INFO] 输出 %s" % OUT.relative_to(ROOT))
        failed = result["failed_checks"]
        if failed:
            print("[FAIL] %s" % failed)
            return 1
        print("[PASS] level-5 零侵入路径探查：%d 项 checks 全 PASS" % len(checks))
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
