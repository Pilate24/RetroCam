# RetroCam — Phases 1–5

CameraX + AGSL (`RuntimeShader`) retro camera. minSdk 33 (Android 13+).

**[Download the APK](https://github.com/Pilate24/RetroCam/releases/latest)** — debug build, sideload only. Read the "Verified" section below first: Phases 4 and 5 have not been run on a device.

## What's here

- `MainActivity.kt` — permission flow, CameraX binding, live filtered preview, filter carousel, shutter -> save to `Pictures/RetroCam`.
- `filter/FilterShader.kt` — the one AGSL program every look runs through.
- `filter/OverlayCompositor.kt` — the overlays that change the frame's *shape*: aspect crop, the Polaroid border, the burned-in date stamp.
- `filter/FilterPreset.kt` — the preset data class and the loader that reads `assets/presets/`.
- `filter/ShaderRenderer.kt` — rasterizes the shader to a `Bitmap` by drawing onto an `ImageReader` surface via `lockHardwareCanvas()`. This is the one code path used for **both** preview frames and the captured photo, so what you see is what you get.
- `ui/FilterCarouselAdapter.kt` — the swipeable strip of look names.
- `gallery/` — `PhotoStore` (MediaStore query scoped to our own folder), `GalleryActivity` (thumbnail grid), `ViewerActivity` (full size + share sheet).

`RuntimeShader` only runs on a hardware-accelerated canvas, and a Canvas backed directly by a Bitmap is software-only — hence the `ImageReader` round-trip. Note that `unlockCanvasAndPost()` only *queues* the frame; the buffer reaches the reader asynchronously, so `ShaderRenderer` waits on `setOnImageAvailableListener` before acquiring. Acquiring immediately after the post races the GPU and returns null on every frame.

## Adding a filter

No code, no new shader:

1. Drop a LUT PNG in `assets/luts/`.
2. Add `assets/presets/<id>.json` with the knobs below.
3. Add `"<id>"` to `assets/presets/index.json` — that file is also the carousel order.

```json
{
  "name": "VHS",
  "lut": "luts/vhs.png",
  "aspect": "4:3",
  "grainAmount": 0.09,
  "vignetteAmount": 0.45,
  "chromaAmount": 0.35,
  "scanlineAmount": 0.22,
  "scanlineCount": 320.0,
  "syncBarAmount": 0.10,
  "syncBarWidth": 0.035,
  "syncBarShift": 0.012,
  "dateStamp": { "color": "#FFEFEFEF", "size": 0.040, "format": "MMM dd yyyy", "glow": false }
}
```

Every field is optional and defaults to "off", so a minimal preset is just a name and a LUT. `frame` takes `color`/`side`/`top`/`bottom`, the last three as fractions of image width (that's how Polaroid gets its fat bottom border).

### Intensity

The slider calls `FilterPreset.scaledBy(t)`, which scales effect *strengths* — `lutIntensity`, grain, vignette, chroma, halation, scanlines, sync bar, leak, flash. It deliberately does **not** scale radii, counts, thresholds or widths: those are the shape of an effect, not its amount, and scaling them changes the look rather than weakening it.

Crop, border and date stamp don't scale either — they're the format a look shoots in, not part of its processing, so a Polaroid stays square at 0%. Worth knowing, since "0% still looks framed" is otherwise surprising.

### The two overlay layers

**Procedural, in the shader** — scanlines, the rolling sync bar, light leaks, the flash hotspot. Per-pixel, so they belong with the colour pipeline.

**Geometric, in `OverlayCompositor`** — aspect crop, borders, the date stamp. These change the output's *dimensions*, which a shader writing a fixed-size buffer can't do. Runs after the shader, on both preview and capture.

### The shader stages

In order: **sync-bar displacement -> chromatic aberration -> LUT colour grade -> halation -> light leak -> flash hotspot -> scanlines -> sync-bar glow -> grain -> vignette.**

Everything spatial (`chromaAmount`, `halationRadius`, `scanlineCount`, grain size, every compositor fraction) is expressed relative to the frame rather than in pixels. That is what keeps the low-res preview and the full-resolution capture looking the same; using pixel units here silently breaks WYSIWYG, because grain sized for a 640px preview frame vanishes in a 4000px photo.

The same reasoning forced two other fixes: the preview **letterboxes** rather than centre-crops (filling would hide the Polaroid border that the saved file keeps), and `ImageAnalysis` and `ImageCapture` are pinned to the same 4:3 aspect strategy so the preview isn't composing a different crop than the photo.

### LUT format

512×512 PNG: an 8×8 grid of 64×64 cells. Cell index is the blue level, x within a cell is red, y is green. `ShaderRenderer` samples it with `FILTER_MODE_LINEAR`, which is what smooths the 64 discrete levels into a continuous grade — with `NEAREST` the LUT bands visibly.

`tools/gen_luts.py` builds the bundled LUTs from plain colour maths (stdlib only, no PIL/numpy). `tools/verify_lut.py` simulates the shader's sampling in Python and asserts the identity LUT round-trips; run it after changing the layout or the sampling formula. Current identity error is 0.33/255, i.e. below one quantisation step.

```bash
py tools/gen_luts.py app/src/main/assets/luts && py tools/verify_lut.py app/src/main/assets/luts
```

## How preview filtering works

CameraX's low-level `SurfaceProcessor`/`CameraEffect` API expects you to write raw OpenGL against a `SurfaceTexture`, which doesn't mix with AGSL. Instead this app uses `ImageAnalysis` in `OUTPUT_IMAGE_FORMAT_RGBA_8888` mode to get each frame as a `Bitmap`, runs it through `ShaderRenderer`, and draws the result onto a `SurfaceView` via `lockHardwareCanvas()`. Simple and correct, but it re-encodes each frame to a `Bitmap` and reruns the GPU rasterization pass — not yet tuned for battery/frame-rate.

## Build & run

1. Open this folder in Android Studio — the **project root**, the one with `settings.gradle.kts`, not `app/`.
2. Let Gradle sync (needs JDK 17, which Studio bundles).
3. Run on a physical device with **API 33+**. The emulator's synthetic camera feed makes the grades hard to judge.
4. Grant the camera permission prompt.

Saved files are named `RetroCam_<preset>_<timestamp>.jpg`, so the filename tells you which look produced it.

## Verified

- Phases 1–2 on a physical device (Samsung, Android 14): live filtered preview, shutter, and save, across a background/resume cycle.
- Phase 3: builds clean; the extended shader compiles on-device at runtime; all five presets load and the carousel centres and highlights correctly.
- Phase 4: builds clean; presets parse and every referenced LUT resolves.
- Phase 5: builds clean; the APK carries all five LUTs and preset files, and declares the gallery and viewer activities.
- **Not yet verified on device:** everything added in Phases 4 and 5 — the shader compiling at runtime, the compositor's crop/border/stamp output, the intensity slider, the gallery, the share sheet, and how any of the looks actually appear. Nothing since Phase 3 has been run.

### Gallery and permissions

The gallery asks for **no storage permission**. An app can always read the media it created, and the query is scoped to `Pictures/RetroCam`. The trade: photos taken before a full uninstall won't appear, because MediaStore drops the owner record — preferable to requesting access to every image on the device. `adb install -r` updates keep ownership; only a real uninstall loses it.

Sharing goes through the system chooser, so the user picks the destination and the read grant travels with the URI.

## Known rough edges

- The compositor still allocates a new bitmap per preview frame (two, for cropped looks). Paint, Matrix and SimpleDateFormat are now hoisted out of the loop, but bitmap pooling is the remaining win.
- `OverlayCompositor` holds mutable shared state (Paint, SimpleDateFormat) and is safe only because preview and capture both run on MainActivity's single-threaded `cameraExecutor`. Splitting those onto separate executors needs a lock or a ThreadLocal first.
- The deeper perf issue is unchanged: every frame does a full GPU round-trip and blocks on the ImageReader callback.
- Light leaks are a single procedural blob, not a scanned overlay texture; they read as a soft glow rather than real film edge-fog.
- The gallery has no delete — share only. Deleting MediaStore entries needs a system consent prompt that isn't wired up.
- Intensity isn't persisted; it resets to 100% on relaunch, as does the selected filter.
- Each `render()` does a full GPU round-trip *and blocks on the reader callback* per frame — the biggest perf lever before Phase 5.
- Halation costs 8 extra texture samples per pixel and is branch-guarded, so presets with `halationAmount: 0.0` skip it.
- A preview frame that fails to render is logged and dropped rather than crashing; watch for `Dropped preview frame` in logcat if the preview stutters.
- Captured photos aren't downscaled before filtering, so very high-res sensors will be slower to save.
- The grades were written by eye against colour theory, not matched to real film stocks — expect to tune `tools/gen_luts.py` once you see them on real photos.

## Next steps

1. Run it on the phone. Two phases of work are unverified, and the shader in particular gained ten uniforms since it last ran.
2. Judge all five looks on real shots and tune: LUT colour in `tools/gen_luts.py`, everything else in the preset JSON.
3. Then, in rough priority order: bitmap pooling on the preview path, persisting the selected filter and intensity, and real scanned light-leak textures.
