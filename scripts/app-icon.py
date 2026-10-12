#!/usr/bin/env python3
"""
Renders the app icon from art/navi-eye.png, Copland OS's eye (white on transparent, dithered):
  res/mipmap-*/ic_launcher_foreground.png   the eye in a blue gradient, its dither kept as the glow
  res/mipmap-*/ic_launcher_monochrome.png   its solid strokes, for Android's themed icons
  res/drawable-*/ic_notification.png        the same strokes, for the status bar
The background layer is res/drawable/ic_launcher_background.xml. Pure Python, no Pillow:
  python3 scripts/app-icon.py
"""
import math
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SOURCE = os.path.join(ROOT, "art", "navi-eye.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

# The eye's colors, from its core out: Copland's accent, then its blue.
CORE = (0x3F, 0xB8, 0xE0)
EDGE = (0x2F, 0x6F, 0xD6)

# An adaptive icon is 108 dp; launchers show the middle 72, and only a 66 dp circle is sure to show.
# The eye's farthest point sits this far from the middle.
REACH_DP = 32
# Dither dots are a few pixels across in the art; the strokes, dozens. Wider than any dot, narrower than any stroke.
OPEN_PX = 6
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def read_png(path):
    """(width, height, rows of alpha 0..255) of an 8-bit PNG; only alpha matters for the art."""
    data = open(path, "rb").read()
    pos, idat, palette, trns = 8, b"", None, None
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
        elif kind == b"PLTE":
            palette = body
        elif kind == b"tRNS":
            trns = body
        elif kind == b"IDAT":
            idat += body
    assert depth == 8, "8-bit PNGs only"
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    raw, stride, bpp = zlib.decompress(idat), w * channels, channels
    prev, rows, i = bytearray(stride), [], 0
    for _ in range(h):
        f, line = raw[i], bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b, c = prev[x], (prev[x - bpp] if x >= bpp else 0)
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        if ctype == 3:
            rows.append([trns[v] if trns and v < len(trns) else 255 for v in line])
        elif ctype in (4, 6):
            rows.append(list(line[channels - 1::channels]))
        else:
            rows.append([255] * w)
    return w, h, rows


def write_png(path, size, pixels):
    """Rows of (r, g, b, a) as an RGBA PNG."""
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for px in row:
            raw += bytes(px)

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def integral(w, h, mask):
    """Summed-area table: s[y][x] counts the set pixels above and left of (x, y)."""
    s = [[0] * (w + 1) for _ in range(h + 1)]
    for y in range(h):
        run, row, above = 0, s[y + 1], s[y]
        for x in range(w):
            run += mask[y][x]
            row[x + 1] = above[x + 1] + run
    return s


def window(s, w, h, x, y, r):
    """How many set pixels the (2r+1)-square around (x, y) holds, clipped to the image."""
    x0, y0, x1, y1 = max(0, x - r), max(0, y - r), min(w, x + r + 1), min(h, y + r + 1)
    return s[y1][x1] - s[y0][x1] - s[y1][x0] + s[y0][x0], (x1 - x0) * (y1 - y0)


def solid(w, h, ink, r=None):
    """
    The strokes without the dither (a morphological opening): ink where a whole square of side 2r+1 is ink,
    grown back by r within the ink. Dither dots, even dense ones, never fill such a square.
    """
    r = OPEN_PX if r is None else r
    s = integral(w, h, ink)
    core = [[window(s, w, h, x, y, r)[0] == (2 * r + 1) ** 2 for x in range(w)] for y in range(h)]
    c = integral(w, h, core)
    return [[ink[y][x] and window(c, w, h, x, y, r)[0] > 0 for x in range(w)] for y in range(h)]


def render(mask, w, h, size, reach_px, center, color):
    """[mask] scaled so its farthest point is [reach_px] from the middle of a [size] square; color(x, y) per pixel."""
    cx, cy, far = center
    scale = reach_px / far
    n = max(4, math.ceil(1 / scale) + 1)
    rows = []
    for oy in range(size):
        row = []
        for ox in range(size):
            hits = 0
            for sy in range(n):
                y = int(cy + ((oy + (sy + 0.5) / n) - size / 2) / scale)
                if not 0 <= y < h:
                    continue
                line = mask[y]
                for sx in range(n):
                    x = int(cx + ((ox + (sx + 0.5) / n) - size / 2) / scale)
                    if 0 <= x < w and line[x]:
                        hits += 1
            a = round(255 * hits / (n * n))
            row.append(color(ox, oy, size) + (a,) if a else (0, 0, 0, 0))
        rows.append(row)
    return rows


def main():
    w, h, alpha = read_png(SOURCE)
    ink = [[a > 127 for a in row] for row in alpha]
    xs = [x for y in range(h) for x in range(w) if ink[y][x]]
    ys = [y for y in range(h) for x in range(w) if ink[y][x]]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    far = max(math.hypot(x - cx, y - cy) for y in range(h) for x in range(w) if ink[y][x])
    strokes = solid(w, h, ink)
    # The gradient centers on the eye's core: the middle of the solid ink in the upper half.
    core = [(x, y) for y in range(int(cy)) for x in range(w) if strokes[y][x] and abs(x - cx) < w * 0.2]
    gx = sum(p[0] for p in core) / len(core)
    gy = sum(p[1] for p in core) / len(core)

    def blue(ox, oy, size):
        scale = (REACH_DP * size / 108) / far
        sx, sy = cx + (ox - size / 2) / scale, cy + (oy - size / 2) / scale
        t = min(1.0, math.hypot(sx - gx, sy - gy) / far) ** 0.85
        return tuple(round(CORE[i] + (EDGE[i] - CORE[i]) * t) for i in range(3))

    def white(ox, oy, size):
        return (255, 255, 255)

    for name, d in DENSITIES.items():
        size = round(108 * d)
        reach = REACH_DP * size / 108
        write_png(os.path.join(RES, f"mipmap-{name}", "ic_launcher_foreground.png"), size, render(ink, w, h, size, reach, (cx, cy, far), blue))
        write_png(os.path.join(RES, f"mipmap-{name}", "ic_launcher_monochrome.png"), size, render(strokes, w, h, size, reach, (cx, cy, far), white))
        # Status bar icons are 24 dp with a 1 dp margin.
        small = round(24 * d)
        write_png(os.path.join(RES, f"drawable-{name}", "ic_notification.png"), small, render(strokes, w, h, small, 11 * d, (cx, cy, far), white))
        print(f"{name}: {size} px, notification {small} px")


if __name__ == "__main__":
    main()
