"""Diagnoses *quality* problems in presets, not just difference from the original.

verify_camera_presets.py answers "is this filter distinguishable" — which a washed-out or garish
filter passes easily. This answers the questions someone actually judges a photo by:

  midtone    does 50% grey stay near 50%, or does the look go muddy / blown?
  contrast   the gap between 20% and 80% grey. Below the original's 0.6 the image reads flat.
  saturation mean chroma across real-world patches. Far above the original reads garish.

Reuses the shader replication in verify_camera_presets.py so both tools describe the same maths.
"""

import json
import sys

from verify_camera_presets import REFERENCE, LUMA, apply

ORIGINAL_CONTRAST = 0.60   # neutral 80% minus neutral 20%
ORIGINAL_SATURATION = 0.324


def luma(rgb):
    return sum(c * w for c, w in zip(rgb, LUMA))


def saturation(rgb):
    return max(rgb) - min(rgb)


def measure(preset):
    out = {name: apply(preset, rgb) for name, rgb in REFERENCE.items()}
    contrast = luma(out["neutral 80%"]) - luma(out["neutral 20%"])
    chroma = [saturation(out[n]) for n in ("skin mid", "foliage", "sky", "red")]
    return {
        "midtone": luma(out["neutral 50%"]),
        "contrast": contrast,
        "saturation": sum(chroma) / len(chroma),
        "highlight": luma(out["highlight"]),
        "shadow": luma(out["neutral 20%"]),
    }


def main():
    presets = [p for p in json.load(open(sys.argv[1], encoding="utf-8"))
               if p.get("category") == "Camera"]
    rows = [(p["name"], measure(p)) for p in presets]

    flat = sorted(rows, key=lambda r: r[1]["contrast"])[:8]
    print(f"FLATTEST — contrast vs the original's {ORIGINAL_CONTRAST:.2f}")
    for n, m in flat:
        drop = (1 - m["contrast"] / ORIGINAL_CONTRAST) * 100
        print(f"  {n:26s} contrast {m['contrast']:.2f}  ({drop:+.0f}% vs original)"
              f"   midtone {m['midtone']:.2f}  highlight {m['highlight']:.2f}")

    print(f"\nMOST SATURATED — vs the original's {ORIGINAL_SATURATION:.2f}")
    for n, m in sorted(rows, key=lambda r: -r[1]["saturation"])[:6]:
        gain = (m["saturation"] / ORIGINAL_SATURATION - 1) * 100
        print(f"  {n:26s} saturation {m['saturation']:.2f}  ({gain:+.0f}%)")

    print("\nMIDTONE DRIFT — 50% grey should land near 0.50")
    for n, m in sorted(rows, key=lambda r: -abs(r[1]["midtone"] - 0.5))[:6]:
        print(f"  {n:26s} midtone {m['midtone']:.2f}  ({(m['midtone'] - 0.5) * 100:+.0f} pts)")

    washed = [n for n, m in rows if m["contrast"] < ORIGINAL_CONTRAST * 0.75]
    garish = [n for n, m in rows if m["saturation"] > ORIGINAL_SATURATION * 1.75]
    print(f"\n{len(washed)}/{len(rows)} presets lose >25% contrast (read as flat/washed)")
    print(f"{len(garish)}/{len(rows)} presets exceed +75% saturation (read as garish)")


if __name__ == "__main__":
    main()
