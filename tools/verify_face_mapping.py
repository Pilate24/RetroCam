"""Validates MainActivity.focusAt() by round-tripping it against its inverse.

focusAt turns a screen tap into a sensor coordinate, walking back through letterbox, frame
border, aspect crop and buffer rotation. Getting any step wrong aims focus at the wrong part of
the scene — invisible in code review, obvious in a blurry photo.

sensor_to_view below is the forward mapping. It used to exist in the app too, for drawing face
detection boxes; that feature has been removed, so it lives on here purely as a test fixture,
derived independently so that composing the two is a real check rather than a tautology.

Mirrors the Kotlin exactly; run it after touching focusAt.
"""

import sys


class Layout:
    """Mirror of OverlayCompositor.Layout / layoutFor()."""

    def __init__(self, src_w, src_h, aspect=None, side=0.0, top=0.0, bottom=0.0):
        crop_w, crop_h = src_w, src_h
        if aspect is not None and abs(src_w / src_h - aspect) >= 0.001:
            if src_w / src_h > aspect:
                crop_w = min(round(src_h * aspect), src_w)
            else:
                crop_h = min(round(src_w / aspect), src_h)
        self.crop_left = (src_w - crop_w) // 2
        self.crop_top = (src_h - crop_h) // 2
        self.crop_width = crop_w
        self.crop_height = crop_h
        self.border_left = round(crop_w * side)
        self.border_top = round(crop_w * top)
        self.output_width = crop_w + 2 * self.border_left
        self.output_height = crop_h + self.border_top + round(crop_w * bottom)


class Geometry:
    def __init__(self, layout, frame_w, frame_h, rotation, view_w, view_h):
        self.layout = layout
        self.frame_width = frame_w
        self.frame_height = frame_h
        self.rotation = rotation
        # Mirrors drawToSurface: fit, then centre.
        self.scale = min(view_w / layout.output_width, view_h / layout.output_height)
        self.dest_left = (view_w - layout.output_width * self.scale) / 2
        self.dest_top = (view_h - layout.output_height * self.scale) / 2


def view_to_sensor(vx, vy, g):
    """MainActivity.focusAt"""
    composed_x = (vx - g.dest_left) / g.scale
    composed_y = (vy - g.dest_top) / g.scale
    picture_x = composed_x - g.layout.border_left
    picture_y = composed_y - g.layout.border_top
    if picture_x < 0 or picture_y < 0 or \
       picture_x > g.layout.crop_width or picture_y > g.layout.crop_height:
        return None  # on the border or the letterbox

    u = (picture_x + g.layout.crop_left) / g.frame_width
    v = (picture_y + g.layout.crop_top) / g.frame_height

    r = g.rotation
    if r == 90:
        return v, 1.0 - u
    if r == 180:
        return 1.0 - u, 1.0 - v
    if r == 270:
        return 1.0 - v, u
    return u, v


def sensor_to_view(bu, bv, g):
    """MainActivity.sensorToView"""
    r = g.rotation
    if r == 90:
        u, v = 1.0 - bv, bu
    elif r == 180:
        u, v = 1.0 - bu, 1.0 - bv
    elif r == 270:
        u, v = bv, 1.0 - bu
    else:
        u, v = bu, bv

    cropped_x = u * g.frame_width - g.layout.crop_left
    cropped_y = v * g.frame_height - g.layout.crop_top
    return (
        (cropped_x + g.layout.border_left) * g.scale + g.dest_left,
        (cropped_y + g.layout.border_top) * g.scale + g.dest_top,
    )


CASES = {
    "no crop, no frame": Layout(480, 640),
    "VHS 4:3 crop": Layout(480, 640, aspect=4 / 3),
    "Polaroid 1:1 + border": Layout(480, 640, aspect=1.0, side=0.062, top=0.062, bottom=0.210),
    "square override": Layout(480, 640, aspect=1.0),
}


def main():
    view_w, view_h = 1080, 2340
    worst = 0.0
    worst_at = None
    checked = 0

    for name, layout in CASES.items():
        for rotation in (0, 90, 180, 270):
            g = Geometry(layout, 480, 640, rotation, view_w, view_h)
            for i in range(0, 21):
                for j in range(0, 21):
                    # Walk points across the picture area only; the border is meant to be rejected.
                    px = g.layout.border_left + g.layout.crop_width * i / 20
                    py = g.layout.border_top + g.layout.crop_height * j / 20
                    vx = px * g.scale + g.dest_left
                    vy = py * g.scale + g.dest_top

                    sensor = view_to_sensor(vx, vy, g)
                    if sensor is None:
                        continue
                    back_x, back_y = sensor_to_view(sensor[0], sensor[1], g)
                    err = max(abs(back_x - vx), abs(back_y - vy))
                    checked += 1
                    if err > worst:
                        worst, worst_at = err, (name, rotation, round(vx, 1), round(vy, 1))

    print(f"checked {checked} points across {len(CASES)} layouts x 4 rotations")
    print(f"worst round-trip error: {worst:.6f} px  at {worst_at}")

    # Border rejection: a tap on the Polaroid border must not produce a focus point.
    g = Geometry(CASES["Polaroid 1:1 + border"], 480, 640, 90, view_w, view_h)
    on_border = view_to_sensor(g.dest_left + 2, g.dest_top + 2, g)
    print(f"tap on Polaroid border rejected: {on_border is None}")

    assert worst < 0.01, f"mappings disagree by {worst}px"
    assert on_border is None
    print("\nOK: focusAt round-trips exactly through its inverse.")


if __name__ == "__main__":
    sys.exit(main())
