"""Scan a PNG for row-level color transitions (pure stdlib)."""
import sys
from pngscale import read_png

def main(path, x, y0, y1):
    w, h, ch, rows = read_png(path)
    print('size', w, h, 'channels', ch)
    prev = None
    for y in range(y0, min(y1, h)):
        r = rows[y]
        i = x * ch
        px = tuple(r[i:i+ch])
        if px != prev:
            print('y=%4d %s' % (y, px))
            prev = px

if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]))
