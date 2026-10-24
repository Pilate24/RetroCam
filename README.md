# RetroCam v1.3.0

CameraX + Android Graphics Shading Language (AGSL `RuntimeShader`) authentic retro film camera for Android 13+ (minSdk 33).

**[Download the Latest APK (v1.3.0)](https://github.com/Pilate24/RetroCam/releases/download/v1.3.0/RetroCam-v1.3.0.apk)** — Sideload directly to any Android 13+ device.

---

## What's New in v1.3.0

- 🎥 **In-App Filtered Video Recording**: Record video with any of the 73 retro filters applied in real-time at full frame rate. Includes hardware-accelerated H.264 video encoding (`MediaCodec`), 44.1 kHz AAC audio recording, pause/resume, and an integrated in-app video review gallery.
- 🎛️ **RAW Video Mode & Pro Camera Controls**: Dedicated pro cinema layout giving full manual control over:
  - **ISO**: `50`, `100`, `200`, `400`, `800`, `1600`, `3200`, `6400` + fine slider.
  - **Shutter Speed**: `1/1000s` down to `1s` + fine step slider.
  - **Focus**: Continuous manual focus rack from `∞ (Infinity)` to `Macro` (with distance readout in meters).
  - **Exposure (EV)**: Exposure value compensation from `-3.0 EV` to `+3.0 EV`.
  - **White Balance**: `2800K` (Incandescent), `4000K` (Fluorescent), `5500K` (Daylight), `6500K` (Cloudy), `7500K` (Shade).
  - **Aperture**: Hardware lens aperture detection.
  - **AUTO ALL**: Instant one-tap reset back to full automatic exposure and focus.
- 📐 **Interactive Aspect Ratio Selector**: Live viewfinder framing and capture for both photos and videos:
  - `AUTO` (preset default), `3:4` (classic portrait), `9:16` (vertical video / stories / reels), `1:1` (square / Polaroid), `16:9` (widescreen), `2:1` (cinemascope), `3:2` (35mm film), and `FULL` (uncropped sensor).
- 💎 **Video Resolution & Frame Rate Selection**:
  - Resolutions: **4K UHD** (40 Mbps), **1080p FHD** (16 Mbps), **720p HD** (8 Mbps), **480p SD** (3.5 Mbps).
  - Frame Rates: **24 fps** (cinematic film), **30 fps** (standard), **60 fps** (high-speed smooth motion).
- 🎨 **Custom Vintage Camera App Icon**: Hand-crafted adaptive and legacy icon suite across all Android display densities (`mdpi` through `xxxhdpi`).
- 🤳 **Front Camera Orientation & Mirroring**: Correct natural front-facing camera preview and capture orientation.

---

## Key Architecture

- `MainActivity.kt` — CameraX binding, mode switcher (`PHOTO`, `VIDEO`, `RAW VIDEO`), aspect ratio popups, resolution/FPS popups, pro manual controls UI, zoom gestures, and shutter flow.
- `video/FilteredVideoRecorder.kt` — Zero-allocation Android `MediaCodec` H.264 video encoder consuming hardware `Surface` canvases rendered live by AGSL shaders, synchronized with AAC audio encoding and monotonic timestamp multiplexing.
- `video/ProCameraManager.kt` — Camera2Interop controller modulating manual sensor sensitivity (ISO), exposure time, focus distance, exposure compensation, and AWB color balance directly on the camera hardware in real time.
- `video/VideoRecordingManager.kt` — Recording lifecycle state machine, pause/resume duration offsetting, dimension 4-alignment, and Scoped Storage publication to `Movies/RetroCam`.
- `video/VideoSettings.kt` — Quality profiles (4K, 1080p, 720p, 480p) and target frame rate configurations (24, 30, 60 fps).
- `filter/FilterShader.kt` — AGSL runtime shader compiling all tone grades, halation, grain, light leaks, chromatic aberration, scanlines, and sync bars on-device.
- `filter/OverlayCompositor.kt` — Frame geometry compositor managing aspect ratio cropping, Polaroid borders, and burned-in retro date stamps.
- `filter/ShaderRenderer.kt` — Hardware-accelerated canvas rasterizer rendering shaders directly to `SurfaceView` and video encoder surfaces.
- `gallery/` — MediaStore query engine and gallery viewers for both `Pictures/RetroCam` and `Movies/RetroCam`.

---

## Camera Controls

| Gesture / Control | Action |
|---|---|
| **Mode Switcher** | Switch between `PHOTO`, `VIDEO`, and `RAW VIDEO` |
| **RAW Control Bar** | Tap `ISO`, `S`, `EV`, `FOCUS`, or `WB` chips to open the fine-tuning slider and quick presets |
| **Tap Viewfinder** | Focus and meter on that coordinate |
| **Pinch Viewfinder** | Smooth optical/digital zoom clamped to sensor limits |
| **Double Tap Viewfinder** | Quick toggle between 1× wide and 2× crop |
| **Filter Carousel** | Swipe horizontally to switch between 73 retro film looks |
| **Category Tabs** | Filter presets by category (All, Film, Vintage, 80s/90s, Disposable, B&W, VHS, Effects) |
| **Intensity Slider** | Adjust the effect strength from 0% to 100% |
| **Aspect Button** | Change aspect ratio (`AUTO`, `3:4`, `9:16`, `1:1`, `16:9`, `2:1`, `3:2`, `FULL`) |
| **Quality/FPS Button** | Switch video resolution (4K, 1080p, 720p, 480p) and frame rate (24, 30, 60 fps) |

---

## The 73 AGSL Film Looks

Every look runs through a single GPU program in AGSL:
**sync-bar displacement -> chromatic aberration -> 3D LUT colour grade -> halation -> light leak -> flash hotspot -> scanlines -> sync-bar glow -> film grain -> vignette -> aspect crop / film frame -> date stamp**.

Everything spatial is expressed relative to frame geometry rather than pixel units, maintaining identical visual appearance between the live viewfinder and final 4K/Full HD capture.

---

## Build & Run

1. Open this project in **Android Studio** (Koala / Ladybug or newer with JDK 17).
2. Let Gradle sync.
3. Build or deploy directly:
   ```powershell
   .\gradlew.bat assembleDebug
   adb install -r -d app\build\outputs\apk\debug\app-debug.apk
   ```
4. Requires a physical device running **Android 13+ (API 33+)**.

---

## Privacy & Scoped Storage

RetroCam requires **zero storage permissions**. Media is saved using Android's native MediaStore Scoped Storage APIs:
- Photos: `Pictures/RetroCam`
- Videos: `Movies/RetroCam`

Videos and photos remain accessible by any gallery app on your device while keeping your personal files private.
