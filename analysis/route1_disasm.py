"""route1: ELF64 symtab + capstone disasm for libNMCommonCache.so (49th batch, 12 §59).

Usage:
  python analysis/route1_disasm.py sym <regex>            # list symbols matching regex
  python analysis/route1_disasm.py dis <sym-regex> [max]  # disasm first matching symbol
  python analysis/route1_disasm.py addr <vaddr> [max]     # disasm at raw vaddr
Addresses/offsets are file vaddrs as used in docs/12 §58 (e.g. ctor 0x9164).
"""
import struct
import sys
import re

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

PATH = 'engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so'
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
        secs.append(dict(name=name, typ=typ, addr=addr, offset=offset,
                         size=size, link=link, entsize=entsize))
    shstr = secs[e_shstrndx]
    for s in secs:
        raw = DATA[shstr['offset'] + s['name']:]
        s['sname'] = raw[:raw.find(b'\0')].decode(errors='replace')
    return secs


SECTIONS = _sections()


def vaddr_to_off(v):
    for s in SECTIONS:
        if s['addr'] and s['addr'] <= v < s['addr'] + s['size'] and s['typ'] != 8:
            return s['offset'] + (v - s['addr'])
    return None


def symbols():
    symtab = next(s for s in SECTIONS if s['sname'] in ('.symtab', '.dynsym'))
    strtab = SECTIONS[symtab['link']]
    out = []
    for i in range(symtab['size'] // 24):
        st_name, _info, _other, _shndx, st_value, st_size = struct.unpack_from(
            '<IBBHQQ', DATA, symtab['offset'] + i * 24)
        raw = DATA[strtab['offset'] + st_name:]
        nm = raw[:raw.find(b'\0')].decode(errors='replace')
        if nm:
            out.append((st_value, st_size, nm))
    out.sort()
    return out


def disasm(vaddr, size):
    off = vaddr_to_off(vaddr)
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    for ins in md.disasm(DATA[off:off + size], vaddr):
        print(f'{ins.address:#06x}  {ins.mnemonic:<10} {ins.op_str}')


def main():
    mode = sys.argv[1]
    if mode == 'sym':
        pat = re.compile(sys.argv[2])
        for v, s, nm in symbols():
            if pat.search(nm):
                print(f'{v:#x} size={s:<6} {nm}')
    elif mode == 'dis':
        pat = re.compile(sys.argv[2])
        max_ins = int(sys.argv[3]) if len(sys.argv) > 3 else 400
        for v, s, nm in symbols():
            if pat.search(nm) and s > 0:
                print(f'== {nm} @ {v:#x} size={s}')
                count = 0
                off = vaddr_to_off(v)
                md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
                for ins in md.disasm(DATA[off:off + s], v):
                    print(f'{ins.address:#06x}  {ins.mnemonic:<10} {ins.op_str}')
                    count += 1
                    if count >= max_ins:
                        print(f'... (truncated at {max_ins})')
                        break
                break
    elif mode == 'addr':
        vaddr = int(sys.argv[2], 16)
        max_ins = int(sys.argv[3]) if len(sys.argv) > 3 else 200
        off = vaddr_to_off(vaddr)
        md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
        for ins in md.disasm(DATA[off:off + max_ins * 4], vaddr):
            print(f'{ins.address:#06x}  {ins.mnemonic:<10} {ins.op_str}')


if __name__ == '__main__':
    main()
