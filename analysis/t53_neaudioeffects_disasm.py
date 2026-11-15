"""§61.155 T5-3 选项 C 前置取证：libneaudioeffects.so NCAudioEffectsProcessor ABI。

Usage:
  python analysis/t53_neaudioeffects_disasm.py sym <regex>      # 列 dynsym 匹配符号
  python analysis/t53_neaudioeffects_disasm.py dis <sym-regex> [max]  # 反汇编首个匹配符号
  python analysis/t53_neaudioeffects_disasm.py vtable <ctor-regex>    # 追 ctor 内 vtable 构造

证据落点：docs/12 §61.155 / analysis/t5-3-ncabi-forensics-2026-11-16.md。
"""
import struct
import sys
import re

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

PATH = 'engine-legacy/src/main/jniLibs/arm64-v8a/libneaudioeffects.so'
DATA = open(PATH, 'rb').read()


def _sections():
    e_shoff = struct.unpack_from('<Q', DATA, 0x28)[0]
    e_shentsize = struct.unpack_from('<H', DATA, 0x3A)[0]
    e_shnum = struct.unpack_from('<H', DATA, 0x3C)[0]
    e_shstrndx = struct.unpack_from('<H', DATA, 0x3E)[0]
    secs = []
    for i in range(e_shnum):
        (name, typ, _flags, addr, offset, size, link, _info,
         _align, entsize) = struct.unpack_from('<IIQQQQIIQQ', DATA, e_shoff + i * e_shentsize)
        secs.append(dict(name=name, typ=typ, addr=addr, offset=offset, size=size, link=link, entsize=entsize))
    shstr = secs[e_shstrndx]
    for s in secs:
        raw = DATA[shstr['offset'] + s['name']:]
        s['sname'] = raw[:raw.find(b'\0')].decode(errors='replace')
    return secs


SECTIONS = _sections()


def vaddr_to_off(v):
    for s in SECTIONS:
        if s['typ'] != 8 and s['addr'] <= v < s['addr'] + s['size'] and s['typ'] == 1:
            return s['offset'] + (v - s['addr'])
    return None


def _dynamic_symbols():
    # .dynsym 经 .dynsym 的 section link → .dynstr
    dynsym = next(s for s in SECTIONS if s['sname'] == '.dynsym')
    dynstr = next(s for s in SECTIONS if s['sname'] == '.dynstr')
    out = []
    n = dynsym['size'] // dynsym['entsize']
    for i in range(n):
        off = dynsym['offset'] + i * dynsym['entsize']
        (name_off, info, _other, shndx, value, size) = struct.unpack_from('<IBBHQQ', DATA, off)
        raw = DATA[dynstr['offset'] + name_off:]
        name = raw[:raw.find(b'\0')].decode(errors='replace')
        out.append(dict(name=name, value=value, size=size, info=info))
    return out


DYN_SYMBOLS = _dynamic_symbols()
MD = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
MD.detail = False


def demangle_ish(name):
    # 粗可读化：_ZN... 结尾长度前缀 → 简单拆分展示
    return name


def find_symbols(pattern):
    rx = re.compile(pattern)
    return [s for s in DYN_SYMBOLS if rx.search(s['name'])]


def disasm_sym(sym, max_ins=120):
    off = vaddr_to_off(sym['value'])
    if off is None:
        print('no file offset for', hex(sym['value']))
        return
    code = DATA[off:off + sym['size'] or 0x400]
    print('== %s @ 0x%x (size=%d)' % (sym['name'], sym['value'], sym['size']))
    n = 0
    for ins in MD.disasm(code, sym['value']):
        print('  0x%x:\t%s\t%s' % (ins.address, ins.mnemonic, ins.op_str))
        n += 1
        if n >= max_ins:
            break


def disasm_addr(vaddr, max_ins=60):
    off = vaddr_to_off(vaddr)
    if off is None:
        print('no file offset for', hex(vaddr))
        return
    code = DATA[off:off + max_ins * 4]
    print('== addr 0x%x ==' % vaddr)
    n = 0
    for ins in MD.disasm(code, vaddr):
        print('  0x%x:\t%s\t%s' % (ins.address, ins.mnemonic, ins.op_str))
        n += 1
        if n >= max_ins:
            break


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'sym'
    if cmd == 'sym':
        for s in find_symbols(sys.argv[2] if len(sys.argv) > 2 else 'NCAudioEffects'):
            print('0x%08x size=%-6d %s' % (s['value'], s['size'], s['name']))
    elif cmd == 'dis':
        pat = sys.argv[2]
        max_ins = int(sys.argv[3]) if len(sys.argv) > 3 else 120
        matches = find_symbols(pat)
        if not matches:
            print('no match:', pat)
            return
        disasm_sym(matches[0], max_ins)
    elif cmd == 'addr':
        disasm_addr(int(sys.argv[2], 16), int(sys.argv[3]) if len(sys.argv) > 3 else 60)
    elif cmd == 'vtable':
        pat = sys.argv[2]
        sym = find_symbols(pat)[0]
        off = vaddr_to_off(sym['value'])
        code = DATA[off:off + sym['size'] or 0x800]
        # adrp/add 对：定位 vtable 页地址
        vaddrs = []
        for ins in MD.disasm(code, sym['value']):
            if ins.mnemonic == 'adrp':
                vaddrs.append(('adrp', ins.op_str))
            elif ins.mnemonic == 'add' and '#' in ins.op_str:
                vaddrs.append(('add', ins.op_str))
        for t, o in vaddrs[:20]:
            print(t, o)


if __name__ == '__main__':
    main()
