"""Pure-standard-library PNG downscaler for device screenshots (no PIL available)."""
import struct, zlib, sys

def read_png(path):
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', 'not PNG'
    pos = 8
    idat = []
    w = h = bd = ct = None
    while pos < len(data):
        ln = struct.unpack('>I', data[pos:pos+4])[0]
        typ = data[pos+4:pos+8]
        chunk = data[pos+8:pos+8+ln]
        pos += 12 + ln
        if typ == b'IHDR':
            w, h, bd, ct, comp, filt, inter = struct.unpack('>IIBBBBB', chunk)
            assert bd == 8 and inter == 0, (bd, inter)
        elif typ == b'IDAT':
            idat.append(chunk)
        elif typ == b'IEND':
            break
    channels = {0: 1, 2: 3, 4: 2, 6: 4}[ct]
    raw = zlib.decompress(b''.join(idat))
    stride = w * channels
    rows = []
    prev = bytearray(stride)
    off = 0
    for _ in range(h):
        f = raw[off]
        off += 1
        line = bytearray(raw[off:off+stride])
        off += stride
        if f == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i-channels]) & 0xff
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xff
        elif f == 3:
            for i in range(stride):
                a = line[i-channels] if i >= channels else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xff
        elif f == 4:
            for i in range(stride):
                a = line[i-channels] if i >= channels else 0
                b = prev[i]
                c = prev[i-channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p-a), abs(p-b), abs(p-c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xff
        elif f != 0:
            raise ValueError('bad filter %d' % f)
        rows.append(bytes(line))
        prev = line
    return w, h, channels, rows

def write_png(path, w, h, channels, rows):
    ct = {1: 0, 2: 4, 3: 2, 4: 6}[channels]
    def chunk(typ, payload):
        return struct.pack('>I', len(payload)) + typ + payload + struct.pack('>I', zlib.crc32(typ + payload) & 0xffffffff)
    raw = b''.join(b'\x00' + r for r in rows)
    out = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, ct, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b'')
    open(path, 'wb').write(out)

def scale(src, dst, factor=2):
    w, h, ch, rows = read_png(src)
    nw, nh = w // factor, h // factor
    outrows = []
    for y in range(nh):
        sy = y * factor
        row = rows[sy]
        out = bytearray()
        for x in range(nw):
            i = x * factor * ch
            out += row[i:i+ch]
        outrows.append(bytes(out))
    write_png(dst, nw, nh, ch, outrows)
    print('%s -> %s : %dx%d -> %dx%d' % (src, dst, w, h, nw, nh))

if __name__ == '__main__':
    scale(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 2)
