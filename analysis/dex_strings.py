"""Extract the MUTF-8 string table from every classes*.dex in an APK.

Only reads string_ids / string_data, no full dex disassembly. Fast enough for
large multidex APKs and gives us class descriptors, URLs, SDK names, feature
flags etc. for architecture reconnaissance.
"""
import struct
import sys
import zipfile
from pathlib import Path


def uleb128(buf, pos):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, pos


def dex_strings(data):
    if data[:4] != b"dex\n":
        return []
    # dex header: string_ids_size @56, string_ids_off @60
    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 56)
    out = []
    for i in range(string_ids_size):
        (off,) = struct.unpack_from("<I", data, string_ids_off + i * 4)
        try:
            _, p = uleb128(data, off)
            end = data.index(b"\x00", p)
            out.append(data[p:end].decode("utf-8", errors="replace"))
        except (IndexError, ValueError):
            continue
    return out


def main():
    apk = Path(sys.argv[1])
    outdir = Path(sys.argv[2])
    outdir.mkdir(parents=True, exist_ok=True)
    total = 0
    with zipfile.ZipFile(apk) as z:
        dexes = sorted(n for n in z.namelist() if n.endswith(".dex"))
        for name in dexes:
            strings = dex_strings(z.read(name))
            total += len(strings)
            stem = Path(name).stem
            (outdir / f"{stem}.strings.txt").write_text(
                "\n".join(strings), encoding="utf-8", errors="replace"
            )
            print(f"{name}: {len(strings)} strings")
    print(f"TOTAL: {total}")


if __name__ == "__main__":
    main()
