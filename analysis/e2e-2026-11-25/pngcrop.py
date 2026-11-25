"""Crop a region of a PNG (pure stdlib, reuses pngscale decoder)."""
import sys
from pngscale import read_png, write_png

def crop(src, dst, x0, y0, x1, y1):
    w, h, ch, rows = read_png(src)
    x0 = max(0, x0); y0 = max(0, y0); x1 = min(w, x1); y1 = min(h, y1)
    out = []
    for y in range(y0, y1):
        out.append(rows[y][x0 * ch:x1 * ch])
    write_png(dst, x1 - x0, y1 - y0, ch, out)
    print('%s -> %s : (%d,%d)-(%d,%d) %dx%d' % (src, dst, x0, y0, x1, y1, x1 - x0, y1 - y0))

if __name__ == '__main__':
    a = sys.argv[1:]
    crop(a[0], a[1], int(a[2]), int(a[3]), int(a[4]), int(a[5]))
