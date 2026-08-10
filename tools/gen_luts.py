"""Generates 512x512 colour lookup tables for RetroCam.

Layout is the conventional 8x8 grid of 64x64 cells: cell index is the blue level,
x within a cell is red, y within a cell is green. The shader in FilterShader.kt
samples this layout and interpolates between the two nearest blue cells.

Pure stdlib (no PIL/numpy) — PNG is encoded by hand.
"""

import os
import struct
import sys
import zlib

GRID = 8          # cells per row/column
LEVELS = 64       # colour levels per axis (== cell size in px)
SIZE = GRID * LEVELS  # 512


def write_png(path, width, height, rgb):
    """rgb: bytearray of width*height*3 bytes."""
    stride = width * 3
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0 (None)
        raw += rgb[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data +
                struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


# --- colour helpers -------------------------------------------------------

def clamp01(x):
    return 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)


def s_curve(x, strength):
    """Contrast S-curve around 0.5. strength 0 = linear."""
    return clamp01(x + strength * (x - 0.5) * (1.0 - abs(x - 0.5) * 2.0) * 2.0)


def contrast(x, amount):
    """amount < 1 flattens, > 1 steepens."""
    return clamp01(0.5 + (x - 0.5) * amount)


def luma(r, g, b):
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def saturate(r, g, b, amount):
    l = luma(r, g, b)
    return (clamp01(l + (r - l) * amount),
            clamp01(l + (g - l) * amount),
            clamp01(l + (b - l) * amount))


def lift_gamma_gain(x, lift, gain):
    return clamp01(lift + x * (gain - lift))


# --- the looks ------------------------------------------------------------

def identity(r, g, b):
    return r, g, b


def kodak(r, g, b):
    """Portra-ish: warm, gentle S-curve, slightly lifted warm shadows."""
    r, g, b = s_curve(r, 0.14), s_curve(g, 0.14), s_curve(b, 0.14)
    r = clamp01(r * 1.05 + 0.018)
    g = clamp01(g * 1.005 + 0.010)
    b = clamp01(b * 0.945 + 0.022)
    return saturate(r, g, b, 1.08)


def vhs(r, g, b):
    """Desaturated, blue-shifted, flat contrast, lifted blacks."""
    r, g, b = saturate(r, g, b, 0.72)
    r, g, b = contrast(r, 0.82), contrast(g, 0.82), contrast(b, 0.82)
    r = clamp01(r * 0.965 + 0.045)
    g = clamp01(g * 0.995 + 0.050)
    b = clamp01(b * 1.055 + 0.065)
    return r, g, b


def polaroid(r, g, b):
    """Lifted blacks, cyan shadows, low contrast, faded highlights."""
    r, g, b = contrast(r, 0.78), contrast(g, 0.78), contrast(b, 0.78)
    r = lift_gamma_gain(r, 0.085, 0.955)
    g = lift_gamma_gain(g, 0.105, 0.965)
    b = lift_gamma_gain(b, 0.120, 0.950)
    # push shadows further toward cyan, leave highlights alone
    shadow = clamp01(1.0 - luma(r, g, b) * 2.0)
    g = clamp01(g + 0.030 * shadow)
    b = clamp01(b + 0.055 * shadow)
    return saturate(r, g, b, 0.92)


def disposable(r, g, b):
    """Green-yellow cast, punchy contrast — cheap flash-lit film."""
    r, g, b = s_curve(r, 0.30), s_curve(g, 0.30), s_curve(b, 0.30)
    r = clamp01(r * 1.040 + 0.005)
    g = clamp01(g * 1.075 + 0.012)
    b = clamp01(b * 0.895)
    return saturate(r, g, b, 1.15)


LOOKS = {
    'identity': identity,
    'kodak': kodak,
    'vhs': vhs,
    'polaroid': polaroid,
    'disposable': disposable,
}


def build_lut(fn):
    rgb = bytearray(SIZE * SIZE * 3)
    for bi in range(LEVELS):
        cell_x = (bi % GRID) * LEVELS
        cell_y = (bi // GRID) * LEVELS
        b = bi / (LEVELS - 1)
        for gi in range(LEVELS):
            g = gi / (LEVELS - 1)
            row = (cell_y + gi) * SIZE
            for ri in range(LEVELS):
                r = ri / (LEVELS - 1)
                nr, ng, nb = fn(r, g, b)
                o = (row + cell_x + ri) * 3
                rgb[o] = int(nr * 255.0 + 0.5)
                rgb[o + 1] = int(ng * 255.0 + 0.5)
                rgb[o + 2] = int(nb * 255.0 + 0.5)
    return rgb


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    for name, fn in LOOKS.items():
        path = os.path.join(out_dir, name + '.png')
        write_png(path, SIZE, SIZE, build_lut(fn))
        print(f'{name}.png  {os.path.getsize(path):,} bytes')


if __name__ == '__main__':
    main()
