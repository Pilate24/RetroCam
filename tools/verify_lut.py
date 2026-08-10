"""Simulates the AGSL LUT sampling against the generated PNGs.

Validates the cell layout + blue interpolation formula before it goes into the
shader: an identity LUT must round-trip any colour back to (approximately) itself.
"""

import struct
import sys
import zlib

GRID, LEVELS = 8, 64
SIZE = GRID * LEVELS


def read_png(path):
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    pos, idat, w, h = 8, b'', 0, 0
    while pos < len(data):
        ln = struct.unpack('>I', data[pos:pos + 4])[0]
        tag = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + ln]
        if tag == b'IHDR':
            w, h, depth, ctype = struct.unpack('>IIBB', payload[:10])
            assert (depth, ctype) == (8, 2), (depth, ctype)
        elif tag == b'IDAT':
            idat += payload
        pos += 12 + ln

    raw = zlib.decompress(idat)
    stride = w * 3
    out = bytearray(w * h * 3)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        ft = raw[p]; p += 1
        line = bytearray(raw[p:p + stride]); p += stride
        if ft == 1:
            for i in range(3, stride):
                line[i] = (line[i] + line[i - 3]) & 0xFF
        elif ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ft == 3:
            for i in range(stride):
                left = line[i - 3] if i >= 3 else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ft == 4:
            for i in range(stride):
                a = line[i - 3] if i >= 3 else 0
                b = prev[i]
                c = prev[i - 3] if i >= 3 else 0
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        elif ft != 0:
            raise ValueError(f'filter {ft}')
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, out


def texel(img, x, y):
    o = (int(y) * SIZE + int(x)) * 3
    return img[o] / 255.0, img[o + 1] / 255.0, img[o + 2] / 255.0


def bilinear(img, px, py):
    """Matches BitmapShader FilterMode.LINEAR: sample at pixel centres."""
    fx, fy = px - 0.5, py - 0.5
    x0, y0 = int(fx // 1), int(fy // 1)
    tx, ty = fx - x0, fy - y0
    x0 = max(0, min(SIZE - 1, x0)); y0 = max(0, min(SIZE - 1, y0))
    x1 = max(0, min(SIZE - 1, x0 + 1)); y1 = max(0, min(SIZE - 1, y0 + 1))
    c00, c10 = texel(img, x0, y0), texel(img, x1, y0)
    c01, c11 = texel(img, x0, y1), texel(img, x1, y1)
    return tuple(
        (c00[i] * (1 - tx) + c10[i] * tx) * (1 - ty) +
        (c01[i] * (1 - tx) + c11[i] * tx) * ty
        for i in range(3)
    )


def sample_lut(img, r, g, b):
    """The exact formula used in FilterShader.kt's sampleLut()."""
    blue = min(max(b, 0.0), 1.0) * (LEVELS - 1)
    b0 = float(int(blue))
    b1 = min(b0 + 1.0, float(LEVELS - 1))
    f = blue - b0

    inner_x = 0.5 + min(max(r, 0.0), 1.0) * (LEVELS - 1)
    inner_y = 0.5 + min(max(g, 0.0), 1.0) * (LEVELS - 1)

    def cell(bi):
        cx = bi % GRID
        cy = bi // GRID
        return cx * LEVELS + inner_x, cy * LEVELS + inner_y

    p0, p1 = cell(int(b0)), cell(int(b1))
    s0, s1 = bilinear(img, *p0), bilinear(img, *p1)
    return tuple(s0[i] * (1 - f) + s1[i] * f for i in range(3))


def main():
    lut_dir = sys.argv[1]
    _, _, ident = read_png(f'{lut_dir}/identity.png')

    worst, worst_at = 0.0, None
    steps = 18
    for i in range(steps + 1):
        for j in range(steps + 1):
            for k in range(steps + 1):
                r, g, b = i / steps, j / steps, k / steps
                out = sample_lut(ident, r, g, b)
                err = max(abs(out[0] - r), abs(out[1] - g), abs(out[2] - b))
                if err > worst:
                    worst, worst_at = err, (r, g, b, out)
    print(f'identity round-trip worst error: {worst:.5f}  ({worst * 255:.2f}/255)')
    print(f'  at rgb={worst_at[:3]} -> {tuple(round(v, 4) for v in worst_at[3])}')

    # Spot-check that a graded LUT actually moves colour in the expected direction.
    for name, (probe, expect) in {
        'kodak': ((0.5, 0.5, 0.5), 'warmer: r > b'),
        'vhs': ((0.5, 0.5, 0.5), 'cooler: b > r'),
        'polaroid': ((0.0, 0.0, 0.0), 'lifted black: all > 0.05'),
        'disposable': ((0.5, 0.5, 0.5), 'green-yellow: g > b'),
    }.items():
        _, _, img = read_png(f'{lut_dir}/{name}.png')
        out = sample_lut(img, *probe)
        print(f'{name:11s} {probe} -> {tuple(round(v, 4) for v in out)}   [{expect}]')

    assert worst < 0.01, f'identity LUT round-trip error too high: {worst}'
    print('\nOK: sampling formula validated.')


if __name__ == '__main__':
    main()
