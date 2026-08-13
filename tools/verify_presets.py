"""Measures whether the presets actually render differently.

The requirement is not "distinct parameters" — it is "the same photo should look noticeably
different depending on the selected filter". Two presets can differ on paper and still land on
near-identical pixels once the curve maths is applied, so this replicates the shader's tone and
colour pipeline in Python, pushes a fixed set of reference colours through every preset, and
reports the pairwise distances.

Mirrors FilterShader.main() stages 4-8 and PresetRepository.readShaderParams().

Scope limit worth knowing: only the tone and colour stages are modelled. Grain, dust, scratches,
vignette, halation, glow, blur and the video artefacts are not, so a preset whose entire effect is
textural measures as identical to the original here. That is the model's blind spot, not a defect
in the preset, and such presets are excluded from the "too weak" check rather than failing it.
"""

import colorsys
import json
import math
import sys
from itertools import combinations

LUMA = (0.2126, 0.7152, 0.0722)

# Colours a camera profile is actually judged on.
REFERENCE = {
    "skin light": (0.85, 0.68, 0.58),
    "skin mid": (0.66, 0.47, 0.38),
    "skin deep": (0.38, 0.25, 0.19),
    "foliage": (0.24, 0.42, 0.18),
    "sky": (0.36, 0.55, 0.82),
    "red": (0.62, 0.14, 0.12),
    "neutral 20%": (0.20, 0.20, 0.20),
    "neutral 50%": (0.50, 0.50, 0.50),
    "neutral 80%": (0.80, 0.80, 0.80),
    "highlight": (0.95, 0.93, 0.90),
}

# Must track PresetRepository.readShaderParams(). If these drift, the measurements are fiction.
SCALES = {
    "exposure": 2.0, "brightness": 0.45, "contrast": 1.6, "saturation": 1.15,
    "temperature": 1.0, "tint": 1.0, "highlights": 0.7, "shadows": 0.7,
    "whites": 0.8, "blacks": 0.65, "fade": 1.0,
    "highlightRolloff": 1.0, "shadowResponse": 0.6,
    "shadowTintAmount": 0.8, "highlightTintAmount": 0.8,
    "skinSaturation": 1.2, "skinHue": 0.13, "greenSaturation": 1.2, "greenHue": 0.13,
    "blueSaturation": 1.2, "blueHue": 0.13,
}


def norm(preset, key):
    return preset.get(key, 0) / 100.0 * SCALES[key]


def gamma(preset, key):
    return 1.0 - preset.get(key, 0) / 100.0 * 0.72


def hex_rgb(s):
    s = s.lstrip("#")
    if len(s) == 8:
        s = s[2:]
    return tuple(int(s[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


def clamp01(x):
    return 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)


def smoothstep(e0, e1, x):
    t = clamp01((x - e0) / (e1 - e0)) if e1 != e0 else 0.0
    return t * t * (3 - 2 * t)


def hue_band(h, centre, width):
    d = abs(((h - centre + 0.5) % 1.0) - 0.5)
    return 1.0 - smoothstep(0.0, width, d)


def apply(preset, rgb):
    """FilterShader stages 4-8, in order."""
    r, g, b = rgb

    # 4. exposure and white balance
    e = norm(preset, "exposure")
    if e:
        m = 2.0 ** e
        r, g, b = r * m, g * m, b * m
    br = norm(preset, "brightness")
    r, g, b = r + br, g + br, b + br
    t = norm(preset, "temperature")
    r, b = r + t * 0.45, b - t * 0.45
    ti = norm(preset, "tint")
    g, r, b = g + ti * 0.36, r - ti * 0.13, b - ti * 0.13

    # 5. tone curve
    raw_c = preset.get("contrast", 0)
    c = raw_c / 100.0 * (1.6 if raw_c >= 0 else 0.55)
    if c:
        r, g, b = [(v - 0.5) * (1 + c) + 0.5 for v in (r, g, b)]
    toe = norm(preset, "shadowResponse")
    if toe:
        shaped = []
        for v in (r, g, b):
            w = 1.0 - smoothstep(0.0, 0.62, v)
            shaped.append(v + (max(v, 0.0) ** (1 + toe) - v) * w)
        r, g, b = shaped
    sh = norm(preset, "highlightRolloff")
    if sh:
        knee, span = 0.75, 0.25
        rolled = []
        for v in (r, g, b):
            over = max(v - knee, 0.0)
            y = knee + span * (over / (over + span)) if over else v
            rolled.append(v + (y - v) * sh)
        r, g, b = rolled
    w, bl = norm(preset, "whites"), norm(preset, "blacks")
    if w or bl:
        l = sum(x * y for x, y in zip((clamp01(r), clamp01(g), clamp01(b)), LUMA))
        f = 1 + w * smoothstep(0.65, 1.0, l)
        lift = bl * (1 - smoothstep(0.0, 0.28, l))
        r, g, b = r * f + lift, g * f + lift, b * f + lift

    # 6. colour rendering
    s = norm(preset, "saturation")
    if s:
        l = sum(x * y for x, y in zip((r, g, b), LUMA))
        r, g, b = [l + (v - l) * max(0.0, 1 + s) for v in (r, g, b)]

    # 7. channel response, HSL, split toning
    gr, gg, gb = gamma(preset, "redResponse"), gamma(preset, "greenResponse"), gamma(preset, "blueResponse")
    if (gr, gg, gb) != (1.0, 1.0, 1.0):
        r, g, b = clamp01(r) ** gr, clamp01(g) ** gg, clamp01(b) ** gb

    hsl_keys = ("skinSaturation", "greenSaturation", "blueSaturation", "skinHue", "greenHue", "blueHue")
    if any(preset.get(k) for k in hsl_keys):
        h, v, sat = colorsys.rgb_to_hsv(clamp01(r), clamp01(g), clamp01(b))[0], None, None
        h, sat, v = colorsys.rgb_to_hsv(clamp01(r), clamp01(g), clamp01(b))
        skin, green, blue = hue_band(h, 0.06, 0.10), hue_band(h, 0.33, 0.14), hue_band(h, 0.60, 0.13)
        sat = clamp01(sat * (1 + norm(preset, "skinSaturation") * skin
                            + norm(preset, "greenSaturation") * green
                            + norm(preset, "blueSaturation") * blue))
        h = (h + norm(preset, "skinHue") * skin
             + norm(preset, "greenHue") * green
             + norm(preset, "blueHue") * blue) % 1.0
        r, g, b = colorsys.hsv_to_rgb(h, sat, v)

    sta, hta = norm(preset, "shadowTintAmount"), norm(preset, "highlightTintAmount")
    if sta or hta:
        l = sum(x * y for x, y in zip((clamp01(r), clamp01(g), clamp01(b)), LUMA))
        st = hex_rgb(preset.get("shadowTint", "#FF000000"))
        ht = hex_rgb(preset.get("highlightTint", "#FF000000"))
        ws, wh = (1 - smoothstep(0.0, 0.55, l)) * sta, smoothstep(0.45, 1.0, l) * hta
        r, g, b = (r + st[0] * ws + ht[0] * wh,
                   g + st[1] * ws + ht[1] * wh,
                   b + st[2] * ws + ht[2] * wh)

    # 8. highlight / shadow recovery, fade
    hi, sd = norm(preset, "highlights"), norm(preset, "shadows")
    if hi or sd:
        l = sum(x * y for x, y in zip((clamp01(r), clamp01(g), clamp01(b)), LUMA))
        d = hi * smoothstep(0.5, 1.0, l) + sd * (1 - smoothstep(0.0, 0.5, l))
        r, g, b = r + d, g + d, b + d
    fd = norm(preset, "fade")
    if fd:
        r, g, b = [v * (1 - fd * 0.42) + fd * 0.20 for v in (r, g, b)]

    return tuple(clamp01(v) for v in soft_limit((r, g, b)))


def soft_limit(c):
    """Mirror of FilterShader.softLimit(): shoulder above the knee, toe below."""
    knee, rng, toe = 0.9, 0.1, 0.06
    out = []
    for v in c:
        over = max(v - knee, 0.0)
        if over:
            v = v - over + rng * (over / (over + rng))
        under = max(toe - v, 0.0)
        if under:
            v = v + under - toe * (under / (under + toe))
        out.append(v)
    return out


def clipping(preset):
    """
    Fraction of channels pushed into the extreme 2% of the range, where no usable gradation is
    left. Deliberately not a test for exactly 0.0 or 1.0: the shader's toe and shoulder are
    asymptotic, so nothing ever lands exactly on the rails and such a test always reports zero
    however blown out the picture actually is.
    """
    out = [apply(preset, c) for c in REFERENCE.values()]
    flat = [v for px in out for v in px]
    return sum(1 for v in flat if v < 0.02 or v > 0.98) / len(flat)


def signature(preset):
    return [apply(preset, c) for c in REFERENCE.values()]


def distance(a, b):
    """Mean per-channel difference in 0-255 units across the reference colours."""
    total = sum(abs(x - y) for pa, pb in zip(a, b) for x, y in zip(pa, pb))
    return total / (len(a) * 3) * 255


# Keys the simulation actually models. A preset touching none of them is invisible to this tool.
MODELLED = set(SCALES) | {"redResponse", "greenResponse", "blueResponse",
                          "shadowTint", "highlightTint"}


TEXTURAL = {"grain", "dust", "scratches", "vignette", "halation", "glow", "lightLeak", "blur",
            "motionBlur", "sharpness", "clarity", "microContrast", "chromaticAberration",
            "rgbShift", "anaglyph", "colorBleed", "scanlines", "syncBar", "tracking", "noise",
            "filmBurn", "flash"}


def weights(preset):
    """Effect authored as colour grading versus as texture, on the -100..100 scale."""
    graded = sum(abs(v) for k, v in preset.items()
                 if k in MODELLED and isinstance(v, (int, float)))
    textural = sum(abs(v) for k, v in preset.items()
                   if k in TEXTURAL and isinstance(v, (int, float)))
    return graded, textural


def is_gradeable(preset):
    """
    True when a preset is mostly a colour grade, so this tool can fairly judge it.

    A presence test is not enough: "Heavy Grain" carries contrast 4 and fade 10 next to grain 82,
    so it looks modelled while nearly all of its effect is texture the simulation ignores. Calling
    that "too weak" would be the tool reporting its own blind spot as a defect in the preset.
    """
    graded, textural = weights(preset)
    return graded >= textural


def main():
    presets = json.load(open(sys.argv[1], encoding="utf-8"))
    cams = presets
    if not cams:
        sys.exit(f"no presets in {sys.argv[1]}")
    sigs = {p["name"]: signature(p) for p in cams}
    identity = [(*c,) for c in REFERENCE.values()]

    print(f"{len(cams)} presets\n")
    print("distance from the untouched image (mean channel delta, 0-255):")
    from_original = sorted(((distance(s, identity), n) for n, s in sigs.items()))
    for d, n in from_original[:3]:
        print(f"  weakest: {n:26s} {d:5.1f}")
    for d, n in from_original[-3:]:
        print(f"  strongest: {n:24s} {d:5.1f}")

    gradeable = {p["name"] for p in cams if is_gradeable(p)}
    textural = [n for _, n in from_original if n not in gradeable]
    if textural:
        shown = ", ".join(textural[:4]) + ("..." if len(textural) > 4 else "")
        print(f"\n  ({len(textural)} preset(s) work mainly through grain, dust, vignette or blur,"
              f" which this tool does not model, so they are excluded from the checks: {shown})")

    weakest, weakest_name = next((d, n) for d, n in from_original if n in gradeable)

    clipped = sorted(((clipping(p), p["name"]) for p in cams), reverse=True)
    print("\nmost clipped (fraction of channels at pure black or white):")
    for c, n in clipped[:3]:
        print(f"  {n:26s} {c * 100:4.0f}%")

    # Only compare pairs the tool can see; two texture presets differing only in grain size would
    # otherwise look identical here and fail for a reason that isn't real.
    pairs = sorted((distance(sigs[a], sigs[b]), a, b) for a, b in combinations(gradeable, 2))
    print("\nclosest pairs:")
    for d, a, b in pairs[:5]:
        print(f"  {d:5.1f}   {a}  vs  {b}")

    # A preset nobody can tell from the original is pointless, and two presets nobody can tell apart
    # make the picker a lie. 1.5/255 is around the threshold of visibility on a gradient.
    print(f"\nmin distance from original: {weakest:.1f} ({weakest_name})")
    print(f"min distance between any two presets: {pairs[0][0]:.1f}")
    assert weakest > 3.0, f"{weakest_name} is nearly identical to the original"
    worst_clip, worst_clip_name = clipped[0]
    assert worst_clip < 0.20, f"{worst_clip_name} clips {worst_clip:.0%} of channels"
    assert pairs[0][0] > 1.5, f"{pairs[0][1]} and {pairs[0][2]} are visually indistinguishable"
    print("\nOK: every graded preset is distinguishable from the original and from each other.")


if __name__ == "__main__":
    main()
