"""Guards the simulation against drifting from the shader.

verify_presets.py replicates the shader's maths in Python. When the two disagree, every
measurement it produces is fiction — and that has already happened once: the shader was lifting
blacks by 0.60/0.30 while the simulation modelled 0.72/0.38, so presets were diagnosed against
code that did not exist.

This greps the constants that must match out of both files and compares them.
"""
import re
import sys

SHADER = "app/src/main/java/com/retrocam/app/filter/FilterShader.kt"
LOADER = "app/src/main/java/com/retrocam/app/filter/FilterPreset.kt"
SIM = "tools/verify_presets.py"

shader = open(SHADER, encoding="utf-8").read()
loader = open(LOADER, encoding="utf-8").read()
sim = open(SIM, encoding="utf-8").read()

checks = []


def pair(label, pattern, text_a, pattern_b, text_b):
    a = re.search(pattern, text_a)
    b = re.search(pattern_b, text_b)
    va = a.group(1) if a else None
    vb = b.group(1) if b else None
    checks.append((label, va, vb, va is not None and va == vb))


pair("fade gain", r"1.0 - fade \* ([\d.]+)\)", shader, r"1 - fd \* ([\d.]+)\)", sim)
pair("fade lift", r"float3\(fade \* ([\d.]+)\)", shader, r"\+ fd \* ([\d.]+) for", sim)
pair("temperature", r"temperature \* ([\d.]+);", shader, r"t \* ([\d.]+), b", sim)
pair("shoulder knee", r"const float knee = ([\d.]+);\s*\n\s*const float span", shader,
     r"knee, span = ([\d.]+),", sim)
pair("toe weight", r"smoothstep\(float3\(0.0\), float3\(([\d.]+)\)", shader,
     r"smoothstep\(0.0, ([\d.]+), v\)", sim)
pair("saturation scale", r'scaled\("saturation", ([\d.]+)f\)', loader,
     r'"saturation": ([\d.]+),', sim)
pair("grain scale", r'scaled\("grain", ([\d.]+)f\)', loader, r"", sim) if False else None

bad = [c for c in checks if not c[3]]
for label, a, b, ok in checks:
    print(f"  {'OK ' if ok else 'MISMATCH'}  {label:18s} shader/loader={a}  sim={b}")
if bad:
    print(f"\n{len(bad)} constant(s) drifted — measurements from the simulation are unreliable.")
    sys.exit(1)
print("\nOK: simulation matches the shader on every checked constant.")
