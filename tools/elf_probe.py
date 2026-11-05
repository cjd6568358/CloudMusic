"""纯 Python 的 ELF64 探测（零第三方依赖）。

用途：C1 一次性 ABI 检查 / C6 指纹。方法同 docs/05-Native库实测分析.md：
解析 ELF64 的 PT_DYNAMIC，取 DT_NEEDED 递归闭包与 .dynstr 中的 Java_* 导出符号。

重要事实（2026-11-05 复核）：apktool 解包产物 analysis/decoded/lib/arm64-v8a/
只剩 1 个文件（libnesec-x86.so，实为 x86_64），169 个 ELF32 库在 armeabi-v7a/；
APK 包内的 lib/arm64-v8a/ 才完整（167 个 / 125.6 MiB）。
因此本模块支持直接从 APK 条目读取 so 字节，不依赖解包是否完整。
"""
from __future__ import annotations

import struct
import zipfile
from pathlib import Path

DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10
PT_LOAD = 1
PT_DYNAMIC = 2

# 03-12 §4 拍板的闭包打包集（按 10 §2 R3 / 11 §8）。
# 2026-11-05 spike #1 实证修正：原 17 个漏了 `libc++_shared.so` —— 它是 5 个入口/依赖库的
# DT_NEEDED 直接依赖，且 NDK 明确「not a system library, must be included in your APK」，
# 竞品 APK 也自带该文件（968.7 KB）。移出 SYSTEM_LIBS 并计入打包集，现为 18 个 / ~18.6 MB。
# 详见 docs/13-引擎舱实证复核.md §2。
PACKED_LIBS: tuple[str, ...] = (
    "libncmplayerengine.so", "libaudioplayer.so", "libnmplaylist.so", "libNMCommonCache.so",
    "libncmaudioplayer.so", "libncmusbaudio.so",
    "libffmpeg.so", "libneaudioeffects.so", "libneaudioeffect_core.so", "libneaudioaiif.so",
    "libMNN.so", "libMNN_Express.so",
    "libsqlite3x.so",
    "libnecmMP3Dec.so", "libnecmAACDec.so", "libnecmFlacDec.so", "libnecmMediaCodecJDec.so",
    "libc++_shared.so",
)

# 4 个带 JNI 导出的入口库（05 §2 / 11 §2.1）
ENTRY_LIBS: tuple[str, ...] = (
    "libncmplayerengine.so", "libaudioplayer.so",
    "libnmplaylist.so", "libNMCommonCache.so",
)

# 系统库：DT_NEEDED 可引用但不打包（03-12 §4 注）。
# ⚠️ 不含 `libc++_shared.so`：NDK 的 C++ 共享运行时必须随 APK 打包（spike #1 实证），
# 已移入 PACKED_LIBS；`libstdc++.so` 才是系统提供的旧版 C++ 运行时。
SYSTEM_LIBS = frozenset({
    "libc.so", "libm.so", "libdl.so", "libz.so", "liblog.so", "libandroid.so",
    "libEGL.so", "libGLESv2.so", "libGLESv3.so", "libOpenSLES.so", "libjnigraphics.so",
    "libmediandk.so", "libaaudio.so", "libamidi.so", "libstdc++.so",
    "libbinder_ndk.so", "libxml2.so", "libcrypto.so", "libssl.so", "libvulkan.so",
    "libnativehelper.so", "libbinder.so", "libutils.so", "libcutils.so",
})


def parse_elf(data: bytes, with_symbols: bool = True) -> dict:
    """返回 {elf_class, machine, needed, jni_symbols, size}；非 ELF64 抛 ValueError。

    with_symbols=False 时跳过 .dynstr 全量切分，供批量扫描 167 个 so 时使用。
    """
    if data[:4] != b"\x7fELF":
        raise ValueError("不是 ELF 文件")
    elf_class = data[4]
    if elf_class != 2:
        raise ValueError(f"仅支持 ELF64（当前 ELF{32 if elf_class == 1 else '?'}）")
    machine = struct.unpack_from("<H", data, 18)[0]
    e_phoff = struct.unpack_from("<Q", data, 32)[0]
    e_phentsize = struct.unpack_from("<H", data, 54)[0]
    e_phnum = struct.unpack_from("<H", data, 56)[0]

    loads: list[tuple[int, int, int]] = []
    dynamic: tuple[int, int] | None = None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        p_offset, p_vaddr = struct.unpack_from("<QQ", data, off + 8)
        p_filesz = struct.unpack_from("<Q", data, off + 32)[0]
        if p_type == PT_LOAD:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == PT_DYNAMIC:
            dynamic = (p_offset, p_filesz)

    needed_offsets: list[int] = []
    strtab_vaddr: int | None = None
    strtab_size = 0
    if dynamic:
        dyn_off, dyn_size = dynamic
        pos = 0
        while pos + 16 <= dyn_size:
            tag = struct.unpack_from("<q", data, dyn_off + pos)[0]
            val = struct.unpack_from("<Q", data, dyn_off + pos + 8)[0]
            if tag == DT_NULL:
                break
            if tag == DT_NEEDED:
                needed_offsets.append(val)
            elif tag == DT_STRTAB:
                strtab_vaddr = val
            elif tag == DT_STRSZ:
                strtab_size = val
            pos += 16

    def vaddr_to_offset(addr: int) -> int | None:
        for vaddr, offset, filesz in loads:
            if vaddr <= addr < vaddr + filesz:
                return offset + (addr - vaddr)
        return None

    strtab = b""
    if strtab_vaddr is not None and strtab_size:
        str_off = vaddr_to_offset(strtab_vaddr)
        if str_off is not None:
            strtab = data[str_off:str_off + strtab_size]

    def cstr(offset: int) -> str:
        if offset >= len(strtab):
            return ""
        end = strtab.find(b"\x00", offset)
        raw = strtab[offset:end if end >= 0 else len(strtab)]
        return raw.decode("utf-8", "replace")

    needed = [cstr(o) for o in needed_offsets if cstr(o)]
    jni_symbols: list[str] = []
    if with_symbols:
        jni_symbols = sorted({
            s.decode("utf-8", "replace")
            for s in strtab.split(b"\x00")
            if s.startswith(b"Java_")
        })
    return {
        "elf_class": 64,
        "machine": machine,  # 183 = EM_AARCH64, 62 = EM_X86_64, 40 = EM_ARM
        "needed": needed,
        "jni_symbols": jni_symbols,
        "size": len(data),
    }


def read_apk_lib(apk: Path, abi: str, name: str) -> bytes | None:
    """从 APK 的 lib/<abi>/<name> 读取原始 so 字节；不存在返回 None。"""
    member = f"lib/{abi}/{name}"
    with zipfile.ZipFile(apk) as z:
        try:
            return z.read(member)
        except KeyError:
            return None


def list_apk_libs(apk: Path, abi: str) -> dict[str, int]:
    """返回 APK 内该 ABI 下 {文件名: 未压缩大小}。"""
    prefix = f"lib/{abi}/"
    with zipfile.ZipFile(apk) as z:
        return {
            info.filename[len(prefix):]: info.file_size
            for info in z.infolist()
            if info.filename.startswith(prefix) and info.filename.endswith(".so")
        }
