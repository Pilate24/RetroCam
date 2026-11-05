# RetroCam v1.6.0

CameraX + Android Graphics Shading Language (AGSL `RuntimeShader`) authentic retro film camera for Android 13+ (minSdk 33).

**[Download the Latest APK (v1.6.0)](https://github.com/Pilate24/RetroCam/releases/download/v1.6.0/RetroCam-v1.6.0.apk)** — Sideload directly to any Android 13+ device.

---

## What's New in v1.6.0

- 🖼️ **Full-Featured Retro Gallery & Album Manager**:
  - **Single & Batch Deletion**: Instant deletion with confirmation dialogs directly from the full-screen photo viewer or in multi-select mode from the gallery grid, cleanly removing files from device storage (`MediaStore`) and updating favorites and trips.
  - **Single & Batch Sharing**: Share single or multiple photos and videos simultaneously with standard Android Sharesheet (`ACTION_SEND` and `ACTION_SEND_MULTIPLE`) with read URI permissions.
  - **Folder & Album Filter Bar**: Tactile retro horizontal chip selector to filter by **All**, **Photos**, **Videos**, **Long Exp**, **Booth**, **RAW**, **Remastered**, and **Favorites**, displaying live item counts per folder and tailored empty states.
  - **Multi-Selection Mode**: Long-press any photo or tap "Select" to enter multi-select mode with dimming overlays, amber checkmark circles, item counter header, "Select All" / "Deselect All", batch share, and batch delete.
  - **Photo Favorites**: Mark and unmark shots as favorites with one tap on the heart icon (`FavoriteStore`); favorite items display a dedicated badge on thumbnails and are grouped in the Favorites folder.
  - **Photo Details Inspector**: One-tap EXIF and storage details sheet showing file name, resolution ($W \times H$), file size (KB/MB), storage directory (`Pictures/RetroCam`), and shot category.
  - **Immersive Full-Screen Viewer**: Tap anywhere on the photo to toggle toolbars on/off for distraction-free viewing, with shortcuts to AI Remaster, Share, Favorite, Details, and Delete.
- 📸 **Pro Camera Mode for Photos (`PRO PHOTO`)**: Full manual camera control for still photography:
  - **Expanded Shutter Speeds**: `1/8000s`, `1/4000s`, `1/2000s`, `1/1000s`, `1/500s`, `1/250s`, `1/125s`, `1/60s`, `1/30s`, `1/15s`, `1/8s`, `1/4s`, `1/2s`, `1s`, `2s`, `4s`, `8s`, `15s`, `30s` + fine continuous slider.
  - **Manual ISO Sensitivity**: `50`, `100`, `200`, `400`, `800`, `1600`, `3200`, `6400` + fine slider.
  - **Manual Focus Rack & Distance Readout**: Smooth rack from `∞ (Infinity)` to `Macro` with real-time subject distance in meters.
  - **Exposure Value (EV)**: Manual exposure compensation from `-3.0 EV` to `+3.0 EV`.
  - **White Balance (AWB & Kelvin)**: `2800K` (Incandescent), `4000K` (Fluorescent), `5500K` (Daylight), `6500K` (Cloudy), `7500K` (Shade), and `AUTO`.
  - **Auto Exposure Lock (AE-L) & Auto Focus Lock (AF-L)**: One-tap tactile locks to hold exposure and focus when recomposing shots.
  - **Focus Peaking**: Real-time high-frequency edge gradient detection highlighting sharp in-focus contours in vibrant neon mint green.
  - **Live RGB & Luminance Histogram**: 256-bin hardware-accelerated real-time histogram overlay with instant highlight clipping and shadow crushing alerts.
  - **RAW (DNG) Digital Negative + JPEG**: Capture uncompressed digital negatives alongside developed retro film photos with rich EXIF metadata.
- 🌌 **Computational & Hardware Long Exposure Mode (`LONG EXP`)**:
  - **Light Trails**: Captures moving traffic streaks, sparklers, and fireworks using maximum-intensity luminance accumulation while preserving dark backgrounds.
  - **Silky Water / Motion Blur**: Simulates optical Neutral Density (ND) filters via temporal moving-average frame blending, rendering waterfalls, ocean waves, and crowd motion as silky ethereal blurs while static structures remain tack-sharp.
  - **Star Trails**: Noise-reduced light accumulation for night skies and astrophotography.
  - **Classic Timed Bulb**: Manual or preset shutter exposures with durations: `1s`, `2s`, `3s`, `5s`, `10s`, `15s`, `30s`, and `BULB` (infinite manual).
  - **Live Viewfinder Exposure Progress**: Watch light streaks and silky motion develop in real-time directly on the viewfinder.
  - **Interactive Long Exposure HUD**: Real-time progress bar, elapsed timer countdown, accumulated frame counter, steady-hold guidance, and an instant "STOP EXPOSURE" button.
- 🎨 **Retro Camera Body Themes (Skeuomorphic Skins)**: Real-time customization of camera body textures, dials, and shutter buttons across 4 iconic retro eras (1970s Classic, 1990s Translucent Neon, Disposable Cardboard Wrap, Golden Brass Rangefinder).
- 🎞️ **4-Shot Photobooth Film Strip Maker**: Automated 4-pose photo booth with timed countdowns, flash support, and 5 vintage border card themes.
- 🎭 **Analog Double Exposure Mode (MX)**: Optical screen exposure composite with live viewfinder ghost overlay and instant retake banner.
- ⚡ **Enhanced Flash Synchronization**: Universal flash trigger synchronization supporting back LED torch/flash and warm front-screen illumination.
- 🚀 **Streamlined Lightweight Gallery**: Removed heavy external media library indexer; restored zero-permission, ultra-fast Scoped Storage photo gallery.
- 🎬 **AI Shot Director & Smart Lens Recommender**: Real-time on-device computer vision analyzing preview framing, scene geometry, and composition:
  - **Scene Classification**: Automatically identifies Portrait, Group Photo, Landscape, Architecture, Food, Sunset/Sunrise, Close-Up/Macro, and Street scenes with genuine confidence scoring.
  - **Hardware Lens & Zoom Detection**: Detects real physical lenses (Ultra-Wide, Main, Telephoto) via 35mm equivalent focal length ($f_{35} = f \times CF$) and optical field-of-view, strictly clamped to verified hardware zoom limits (`minZoomRatio`..`maxZoomRatio`).
  - **Smart Aspect Ratio & Crop Warnings**: Intelligently recommends 4:3, 3:2, 1:1, 4:5 (portrait social), 16:9, or 9:16, actively checking whether crop boundaries clip subjects or heads and displaying immediate safety warnings.
  - **One-Tap "Apply Framing"**: Applies verified zoom and aspect ratio to the camera session with one touch, accompanied by clear physical movement guidance (e.g., *"Step back 1-2 steps"* or *"Hold at eye level"*).
  - **3 Alternative Shot Ideas**: Generates 3 meaningfully distinct ways to shoot the current scene (Wide, Balanced, Detail / Editorial) with one-tap application.
  - **Compare Ratios Mode**: Live simultaneous framing boundaries for 4:3, 4:5, and 9:16 directly on the viewfinder.
  - **Precision Composition Overlays**: Rule-of-thirds grid with golden intersection points, center symmetry crosshairs, motion sensor-driven artificial horizon level gauge ($\pm 1.0^\circ$ precision), portrait framing silhouette, and face brackets.
  - **Privacy & Performance**: 100% on-device processing via Android `FaceDetector` and spatial gradients. Zero network calls, zero cloud APIs, and throttled execution that automatically pauses during video recording to guarantee zero dropped frames.
- ✨ **AI Photo Remaster**: On-device photographic remastering engine with auto-tonal enhancement, shadow lift, portrait enhancement, and super-resolution.
- 🎨 **Retro Camera Body Themes (Skeuomorphic Skins)**: Customize the physical body material, tactile knobs, and shutter release:
  - **1970s Classic**: Matte black vulcanite leatherette with brushed chrome metal top-plate, satin aluminum selector pill, and iconic red enamel soft-release shutter button core.
  - **1990s Translucent Neon**: Transparent colorful plastic casing (like the iMac G3 or Game Boy Color) in atomic teal/cyan polycarbonate with glowing neon rim and radiant hot-magenta dome shutter button.
  - **Disposable Camera Cardboard Wrap**: Graphic printed cardboard box look with classic packaging golden yellow, racing red diagonal band, printed seams, and tactile injection-molded red plastic shutter.
  - **Golden Brass Rangefinder**: Vintage Leica-inspired aged brass body with hand-burnished patina, etched dials, knurled knobs, and antique ivory enamel markings.
  - **Instant Switcher & Persistence**: Tap the Camera Skin button in the top bar or overflow menu to preview and apply any theme with real-time haptic visual update, persisted across sessions via `SharedPreferences`.
- 🗂️ **Photo Gallery & Trips**: Lightweight zero-permission in-app retro photo and video gallery, full-screen viewer with sharing and AI Remaster shortcut, and photo trip memory logs.
- 🎞️ **4-Shot Photobooth Film Strip Maker**: Authentic 90s mall / Korean 4-cut photobooth shooting experience:
  - **Automated 4-Pose Session**: Guided poses with animated countdowns (3… 2… 1… Snap!) and camera shutter clicks.
  - **Full Filter & Flash Consistency**: Every pose is filtered with the active retro film look and supports both back LED flash and front-screen warm flash.
  - **5 Analog Strip Themes**: Real-time styling with Classic White, Matte Black, Vintage Cream, Y2K Pastel Pink, and 90s Lavender card borders.
  - **Analog Details**: Header title, pose indices (`#01` to `#04`), timestamp, film recipe name, and authentic printed barcode.
  - **Save & Share**: One-tap high-resolution JPEG export to `Pictures/RetroCam` and direct social sharing.
- 🎭 **Analog Double Exposure Mode (MX)**: Classic dual-exposure photographic composite on the same virtual frame of film:
  - **Live Ghost Viewfinder**: Once Shot 1 (base silhouette / portrait) is taken, a semi-transparent ghost preview overlays the live camera feed so you can align Shot 2 (textures, city lights, flowers) with pinpoint visual accuracy.
  - **One-Tap Retake Banner**: Easily discard and retake Shot 1 anytime before exposing Shot 2.
  - **Optical Screen Blending**: Mathematically simulates real photographic silver-halide light exposure ($A + B - A \times B$), burning highlights into shadows without harsh clipping.
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
  - `AUTO` (preset default), `3:4` (classic portrait), `4:5` (portrait social), `9:16` (vertical video / stories / reels), `1:1` (square / Polaroid), `16:9` (widescreen), `2:1` (cinemascope), `3:2` (35mm film), and `FULL` (uncropped sensor).
- 💎 **Video Resolution & Frame Rate Selection**:
  - Resolutions: **4K UHD** (40 Mbps), **1080p FHD** (16 Mbps), **720p HD** (8 Mbps), **480p SD** (3.5 Mbps).
  - Frame Rates: **24 fps** (cinematic film), **30 fps** (standard), **60 fps** (high-speed smooth motion).
- 🎨 **Custom Vintage Camera App Icon**: Hand-crafted adaptive and legacy icon suite across all Android display densities (`mdpi` through `xxxhdpi`).
- 🤳 **Front Camera Orientation & Mirroring**: Correct natural front-facing camera preview and capture orientation.

---

## Key Architecture

- `MainActivity.kt` — CameraX binding, mode switcher (`PHOTO`, `VIDEO`, `RAW VIDEO`), AI Shot Director coordinator, aspect ratio popups, resolution/FPS popups, pro manual controls UI, zoom gestures, and shutter flow.
- `director/` — AI Shot Director subsystem:
  - `detector/CameraCapabilityDetector.kt`: Hardware lens enumeration, 35mm equivalent focal length and FOV calculations, hardware zoom clamping.
  - `detector/HorizonSensorDetector.kt`: Real-time device motion/orientation sensor tracking for horizon level gauge.
  - `analyzer/ShotSceneAnalyzer.kt`: On-device preview frame scene categorization, face detection, chromatic histograms, and edge gradients.
  - `engine/ShotDirectorEngine.kt`: Unified coordination of lens, zoom, aspect ratio, camera position, and 3 alternative shot ideas.
  - `engine/AspectRatioEvaluator.kt`: Aspect ratio selection and subject crop clipping detection.
  - `engine/CompositionEvaluator.kt`: Headroom, thirds alignment, and physical movement advice.
  - `ui/ShotDirectorOverlayView.kt`: Custom hardware-accelerated preview overlays (grid, symmetry, horizon, silhouette, scrim, brackets).
  - `ui/ShotDirectorController.kt`: UI controller for expandable recommendation panel and controls.
  - `ui/ShotIdeasBottomSheet.kt`: Bottom sheet modal for the 3 alternative shot ideas.
- `video/FilteredVideoRecorder.kt` — Zero-allocation Android `MediaCodec` H.264 video encoder consuming hardware `Surface` canvases rendered live by AGSL shaders, synchronized with AAC audio encoding and monotonic timestamp multiplexing.
- `video/ProCameraManager.kt` — Camera2Interop controller modulating manual sensor sensitivity (ISO), exposure time, focus distance, exposure compensation, and AWB color balance directly on the camera hardware in real time.
- `video/VideoRecordingManager.kt` — Recording lifecycle state machine, pause/resume duration offsetting, dimension 4-alignment, and Scoped Storage publication to `Movies/RetroCam`.
- `video/VideoSettings.kt` — Quality profiles (4K, 1080p, 720p, 480p) and target frame rate configurations (24, 30, 60 fps).
- `filter/FilterShader.kt` — AGSL runtime shader compiling all tone grades, halation, grain, light leaks, chromatic aberration, scanlines, and sync bars on-device.
- `filter/OverlayCompositor.kt` — Frame geometry compositor managing aspect ratio cropping, Polaroid borders, and burned-in retro date stamps.
- `filter/ShaderRenderer.kt` — Hardware-accelerated canvas rasterizer rendering shaders directly to `SurfaceView` and video encoder surfaces.
- `theme/` — Skeuomorphic retro camera body themes:
  - `CameraSkin.kt`: Enum definitions, tactile attributes, drawables, colors, and asset mappings for all 4 eras (`CLASSIC_1970S`, `TRANSLUCENT_NEON_90S`, `DISPOSABLE_CARDBOARD`, `GOLDEN_BRASS`).
  - `CameraSkinManager.kt`: Persistent storage and retrieval of active skin preferences.
  - `CameraSkinBottomSheet.kt`: Interactive bottom sheet theme selector with tactile swatch cards and active state indicators.
- `remaster/` — AI Photo Remaster processor, face retouching, super-resolution, and before/after comparison.
- `gallery/` — Lightweight zero-permission gallery, full-screen viewer, and share integration.
- `trip/` — Location-based photographic trip journals and memory carousels.

---

## Camera Controls

| Gesture / Control | Action |
|---|---|
| **AI Shot Director Pill** | Tap top pill to expand/collapse AI framing advice, scene confidence, and camera position tips |
| **Apply Framing Button** | One-tap apply recommended verified zoom and aspect ratio to active camera session |
| **3 Shot Ideas Button** | Browse and apply 3 distinct alternative shot compositions for the current scene |
| **Compare Ratios Button** | View simultaneous 4:3, 4:5, and 9:16 framing boundaries on the live viewfinder |
| **Guides Button** | Toggle Rule-of-Thirds, Symmetry, Horizon Level, Portrait Silhouette, Scrim, and Face Brackets |
| **Camera Skin Button** | Tap top-bar palette icon or overflow menu to switch between 4 skeuomorphic retro camera bodies |
| **Mode Switcher** | Switch between `PHOTO`, `PRO PHOTO`, `LONG EXP`, `BOOTH` (4-Cut Film Strip), `DOUBLE` (MX Double Exposure), `VIDEO`, and `RAW VIDEO` |
| **Pro / RAW Control Bar** | Tap `ISO`, `S`, `EV`, `FOCUS`, `WB`, `AE-L`, `AF-L`, `PEAK`, `HISTO`, `RAW`, or `LONG EXP` chips to adjust parameters, toggle locks, or view live histogram |
| **Tap Viewfinder** | Focus and meter on that coordinate |
| **Pinch Viewfinder** | Smooth optical/digital zoom clamped to sensor limits |
| **Double Tap Viewfinder** | Quick toggle between 1× wide and 2× crop |
| **Filter Carousel** | Swipe horizontally to switch between 73 retro film looks |
| **Category Tabs** | Filter presets by category (All, Film, Vintage, 80s/90s, Disposable, B&W, VHS, Effects) |
| **Intensity Slider** | Adjust the effect strength from 0% to 100% |
| **Aspect Button** | Change aspect ratio (`AUTO`, `3:4`, `4:5`, `9:16`, `1:1`, `16:9`, `2:1`, `3:2`, `FULL`) |
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
