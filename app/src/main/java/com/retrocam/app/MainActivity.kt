package com.retrocam.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.retrocam.app.databinding.ActivityMainBinding
import com.retrocam.app.filter.FilterPreset
import com.retrocam.app.filter.OverlayCompositor
import com.retrocam.app.filter.PresetRepository
import com.retrocam.app.filter.ShaderRenderer
import com.retrocam.app.gallery.GalleryActivity
import com.retrocam.app.ui.CategoryAdapter
import com.retrocam.app.ui.FilterCarouselAdapter
import com.retrocam.app.ui.ThumbnailCache
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.random.Random

@OptIn(markerClass = [ExperimentalCamera2Interop::class])
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    /** Fallback coordinate space for face rectangles when no crop region is reported. */
    private var sensorActiveArray: Rect? = null
    private var previewRenderer: ShaderRenderer? = null
    private val startTimeNanos = System.nanoTime()

    /** Reused per frame; only ever touched on the camera thread inside drawToSurface. */
    private val previewMatrix = Matrix()
    private val previewDate = Date()

    /** Everything that loaded, and the subset the chosen category is showing. */
    private var allPresets: List<FilterPreset> = emptyList()
    private var visiblePresets: List<FilterPreset> = emptyList()

    private var carouselAdapter: FilterCarouselAdapter? = null
    private var thumbnails: ThumbnailCache? = null
    private val snapHelper = LinearSnapHelper()

    /**
     * Tracked by id rather than index: filtering by category renumbers the carousel, and an index
     * would silently point at a different look.
     */
    private var selectedPresetId: String = ""
    private var intensity = 1f

    /** Thumbnails only need to look like the scene, so the source frame is refreshed rarely. */
    private var lastThumbnailSourceNanos = 0L

    /** Distinguishes a user swipe from a layout settle; see the carousel's scroll listener. */
    private var userIsScrolling = false

    private var framesSinceReport = 0
    private var fpsWindowStartNanos = 0L

    private var flash = FlashChoice.OFF
    private var timerSeconds = 0
    private var aspect = AspectChoice.AUTO
    private var countingDown = false

    /**
     * The selected preset with the intensity already applied. Written on the main thread whenever
     * the selection or the slider changes, read on the camera thread by every frame — so it is
     * scaled once here rather than per frame.
     */
    @Volatile
    private var activePreset: FilterPreset? = null

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        binding.shutterButton.setOnClickListener { takePhoto() }
        setUpTopBar()
        binding.galleryButton.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        allPresets = PresetRepository.load(assets)
        if (allPresets.isEmpty()) {
            Toast.makeText(this, R.string.no_presets, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        visiblePresets = allPresets
        selectedPresetId = allPresets.first().id
        thumbnails = ThumbnailCache()

        setUpCategories()
        setUpCarousel()
        setUpIntensitySlider()
        setUpZoomGestures()
        refreshActivePreset()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // --- filter carousel ---------------------------------------------------

    private fun setUpCategories() {
        val categories = PresetRepository.categoriesOf(allPresets)
        binding.categoryTabs.layoutManager =
            LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.categoryTabs.adapter = CategoryAdapter(categories) { category -> showCategory(category) }
    }

    /**
     * Narrows the carousel. The active look is deliberately left alone — changing category is a
     * browsing action, and having it silently re-grade the viewfinder would be hostile.
     */
    private fun showCategory(category: String) {
        visiblePresets = if (category == PresetRepository.CATEGORY_ALL) allPresets
        else allPresets.filter { it.category == category }
        if (visiblePresets.isEmpty()) return

        val selected = visiblePresets.indexOfFirst { it.id == selectedPresetId }.coerceAtLeast(0)
        carouselAdapter?.submit(visiblePresets, selected)
        binding.filterCarousel.post {
            (binding.filterCarousel.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(selected, binding.filterCarousel.paddingStart)
        }
    }

    private fun setUpCarousel() {
        val layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        val adapter = FilterCarouselAdapter(visiblePresets, thumbnails) { position ->
            centerOn(position)
        }
        carouselAdapter = adapter

        binding.filterCarousel.layoutManager = layoutManager
        binding.filterCarousel.adapter = adapter
        snapHelper.attachToRecyclerView(binding.filterCarousel)

        // Whatever the user swipes to the centre becomes the active look.
        //
        // Only a real drag counts. Laying out the carousel — applying the centring padding below,
        // or refilling it for a new category — also ends in SCROLL_STATE_IDLE, and treating that
        // as a selection would silently activate whichever filter happened to land at the centre.
        // A user scroll always passes through DRAGGING first; a layout settle never does.
        binding.filterCarousel.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userIsScrolling = true
                    return
                }
                if (newState != RecyclerView.SCROLL_STATE_IDLE || !userIsScrolling) return
                userIsScrolling = false
                val snapped = snapHelper.findSnapView(layoutManager) ?: return
                selectPreset(layoutManager.getPosition(snapped))
            }
        })

        // Half-a-viewport of padding at both ends lets the first and last items reach the centre.
        binding.filterCarousel.doOnLayout { view ->
            val itemWidth = resources.getDimensionPixelSize(R.dimen.filter_item_width)
            val padding = ((view.width - itemWidth) / 2).coerceAtLeast(0)
            view.setPadding(padding, 0, padding, 0)
        }
    }

    private fun centerOn(position: Int) {
        val carousel = binding.filterCarousel
        val layoutManager = carousel.layoutManager as? LinearLayoutManager ?: return
        // Apply on tap rather than when the scroll animation ends: the filter should change the
        // instant it's tapped, and the listener now ignores non-drag settles anyway.
        selectPreset(position)

        val child = layoutManager.findViewByPosition(position)
        if (child == null) {
            layoutManager.scrollToPositionWithOffset(position, carousel.paddingStart)
            return
        }
        val delta = (child.left + child.right) / 2 - carousel.width / 2
        if (delta != 0) carousel.smoothScrollBy(delta, 0)
    }

    private fun setUpIntensitySlider() {
        binding.intensitySlider.addOnChangeListener { _, value, _ ->
            intensity = value
            refreshActivePreset()
        }
    }

    private fun selectPreset(position: Int) {
        if (position !in visiblePresets.indices) return
        selectedPresetId = visiblePresets[position].id
        carouselAdapter?.selectedPosition = position
        refreshActivePreset()
    }

    private fun refreshActivePreset() {
        val base = allPresets.firstOrNull { it.id == selectedPresetId } ?: return
        val scaled = base.scaledBy(intensity)
        // AUTO leaves the filter's own format alone; anything else is an explicit override.
        activePreset = when (aspect) {
            AspectChoice.AUTO -> scaled
            AspectChoice.FULL -> scaled.copy(aspect = null)
            else -> scaled.copy(aspect = aspect.ratio)
        }
        binding.intensityLabel.text =
            getString(R.string.intensity_label, base.name, (intensity * 100).roundToInt())
    }

    // --- camera ------------------------------------------------------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            // Both use cases must agree on aspect ratio, or the preview would compose a different
            // crop than the photo it claims to be previewing.
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val analysisBuilder = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolutionSelector)

            // Ask the HAL for face rectangles and listen for the results it already produces.
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
                )
                .setSessionCaptureCallback(faceCaptureCallback)

            val analysis = analysisBuilder.build()
                .also { it.setAnalyzer(cameraExecutor, ::processFrame) }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(resolutionSelector)
                .build()
            imageCapture = capture

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, analysis, capture
                )
                observeZoom()
                readCameraCharacteristics()
                setStatus(R.string.status_ready)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun readCameraCharacteristics() {
        val info = camera?.cameraInfo ?: return
        try {
            val camera2Info = Camera2CameraInfo.from(info)
            sensorActiveArray =
                camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val modes = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES
            )
            val maxFaces = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT
            ) ?: 0
            val supported = maxFaces > 0 &&
                modes?.any { it != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF } == true
            Log.i(TAG, "face detection supported=$supported maxFaces=$maxFaces")
        } catch (e: Exception) {
            Log.w(TAG, "Could not read camera characteristics", e)
        }
    }

    // --- status chip -------------------------------------------------------

    private fun setStatus(resId: Int) {
        binding.statusChip.setText(resId)
        binding.statusChip.animate().alpha(1f).setDuration(120).start()
    }

    // --- top bar -----------------------------------------------------------

    /**
     * AUTO defers to the filter, which is what keeps a Polaroid square and a VHS 4:3 by default.
     * The other choices override it, so an explicit pick always wins over the look's own format.
     */
    private enum class AspectChoice(val label: String, val ratio: Float?) {
        AUTO("AUTO", null),
        FULL("FULL", null),
        PORTRAIT_3_4("3:4", 0.75f),
        SQUARE("1:1", 1f)
    }

    private enum class FlashChoice(val label: String, val mode: Int, val icon: Int) {
        OFF("off", ImageCapture.FLASH_MODE_OFF, R.drawable.ic_flash_off),
        AUTO("auto", ImageCapture.FLASH_MODE_AUTO, R.drawable.ic_flash_on),
        ON("on", ImageCapture.FLASH_MODE_ON, R.drawable.ic_flash_on)
    }

    private fun setUpTopBar() {
        binding.flashButton.setOnClickListener {
            flash = FlashChoice.entries[(flash.ordinal + 1) % FlashChoice.entries.size]
            imageCapture?.flashMode = flash.mode
            binding.flashButton.setImageResource(flash.icon)
            binding.flashButton.alpha = if (flash == FlashChoice.OFF) 0.65f else 1f
            toast(getString(R.string.flash_toast, flash.label))
        }
        binding.flashButton.alpha = 0.65f

        binding.timerButton.setOnClickListener {
            timerSeconds = when (timerSeconds) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            binding.timerButton.alpha = if (timerSeconds == 0) 0.65f else 1f
            val label = if (timerSeconds == 0) getString(R.string.timer_off) else "${timerSeconds}s"
            toast(getString(R.string.timer_toast, label))
        }
        binding.timerButton.alpha = 0.65f

        binding.aspectButton.setOnClickListener {
            aspect = AspectChoice.entries[(aspect.ordinal + 1) % AspectChoice.entries.size]
            binding.aspectButton.text = aspect.label
            refreshActivePreset()
            toast(getString(R.string.aspect_toast, aspect.label))
        }
        binding.aspectButton.text = aspect.label

        binding.gridButton.setOnClickListener {
            val showing = binding.gridOverlay.visibility == View.VISIBLE
            binding.gridOverlay.visibility = if (showing) View.GONE else View.VISIBLE
            binding.gridButton.alpha = if (showing) 0.65f else 1f
        }
        binding.gridButton.alpha = 0.65f
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** Counts down on screen, then fires the shutter. */
    private fun startCountdown(remaining: Int) {
        if (remaining <= 0) {
            binding.countdownLabel.animate().alpha(0f).setDuration(150).start()
            capturePhoto()
            return
        }
        binding.countdownLabel.text = remaining.toString()
        binding.countdownLabel.alpha = 1f
        binding.countdownLabel.scaleX = 1.4f
        binding.countdownLabel.scaleY = 1.4f
        binding.countdownLabel.animate().scaleX(1f).scaleY(1f).setDuration(320).start()
        binding.countdownLabel.postDelayed({ startCountdown(remaining - 1) }, 1000)
    }

    // --- zoom --------------------------------------------------------------

    /**
     * Zoom is a camera-level control — CameraX applies it as a sensor crop before either use case
     * sees a frame — so the preview and the capture zoom together and stay WYSIWYG for free.
     */
    private fun setUpZoomGestures() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val current = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: return true
                    applyZoom(current * detector.scaleFactor)
                    return true
                }
            }
        )

        val tapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val state = camera?.cameraInfo?.zoomState?.value ?: return false
                    // Snap back to wide if we're zoomed at all, otherwise jump to 2x.
                    val zoomedIn = state.zoomRatio > state.minZoomRatio * 1.1f
                    applyZoom(
                        if (zoomedIn) state.minZoomRatio
                        else minOf(DOUBLE_TAP_ZOOM, state.maxZoomRatio)
                    )
                    return true
                }

                // Confirmed, not onSingleTapUp: otherwise the first tap of a double-tap zoom
                // would also fire a focus request at the same point.
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    focusAt(e.x, e.y)
                    return true
                }
            }
        )

        binding.viewFinder.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }
    }

    // --- tap to focus ------------------------------------------------------

    /**
     * Everything needed to turn a screen tap back into a sensor coordinate. Recorded on the camera
     * thread as each frame is drawn, read on the main thread when a tap lands.
     */
    private class PreviewGeometry(
        val destLeft: Float,
        val destTop: Float,
        val scale: Float,
        val layout: OverlayCompositor.Layout,
        val frameWidth: Int,
        val frameHeight: Int,
        val rotationDegrees: Int
    )

    @Volatile
    private var previewGeometry: PreviewGeometry? = null

    /**
     * A tap has to be walked back through every transform between the sensor and the screen:
     * letterbox -> frame border -> aspect crop -> the rotation applied before filtering. Skipping
     * any step focuses on the wrong part of the scene, and worst at the edges where people tap.
     */
    private fun focusAt(viewX: Float, viewY: Float) {
        val camera = this.camera ?: return
        val geometry = previewGeometry ?: return
        val layout = geometry.layout

        // Undo the letterbox: view pixels -> composed image pixels.
        val composedX = (viewX - geometry.destLeft) / geometry.scale
        val composedY = (viewY - geometry.destTop) / geometry.scale

        // Undo the border. Taps on the border or the letterbox aren't on the picture at all.
        val pictureX = composedX - layout.borderLeft
        val pictureY = composedY - layout.borderTop
        if (pictureX < 0f || pictureY < 0f ||
            pictureX > layout.cropWidth || pictureY > layout.cropHeight
        ) return

        // Undo the crop, then normalise against the upright frame.
        val u = (pictureX + layout.cropLeft) / geometry.frameWidth
        val v = (pictureY + layout.cropTop) / geometry.frameHeight

        // Undo the rotation that made the buffer upright, back into sensor buffer space.
        val bufferU: Float
        val bufferV: Float
        when (geometry.rotationDegrees) {
            90 -> { bufferU = v; bufferV = 1f - u }
            180 -> { bufferU = 1f - u; bufferV = 1f - v }
            270 -> { bufferU = 1f - v; bufferV = u }
            else -> { bufferU = u; bufferV = v }
        }

        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(bufferU, bufferV)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(FOCUS_HOLD_SECONDS, TimeUnit.SECONDS).build()

        val focusing = try {
            camera.cameraControl.startFocusAndMetering(action)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Focus point rejected", e)
            return
        }
        // Driven by the actual focus result rather than a fixed delay, so the chip doesn't claim
        // "Ready" while the lens is still hunting.
        setStatus(R.string.status_focusing)
        focusing.addListener(
            { setStatus(R.string.status_ready) },
            ContextCompat.getMainExecutor(this)
        )
        showFocusRing(viewX, viewY)
    }

    // --- face detection ----------------------------------------------------

    /**
     * The camera's own face detector, read out of the capture metadata. The ISP already computes
     * this for autofocus, so taking the result costs nothing per frame — running a detection model
     * over every preview frame instead would be the single most expensive thing in the app.
     */
    private val faceCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val geometry = previewGeometry ?: return
            val faces = result.get(CaptureResult.STATISTICS_FACES)
            if (faces == null || faces.isEmpty()) {
                binding.faceOverlay.post { binding.faceOverlay.setFaces(emptyList()) }
                return
            }

            // Face bounds are in active-array coordinates; the crop region is what the preview is
            // actually showing, so normalising against it is what keeps boxes aligned while zoomed.
            val region = result.get(CaptureResult.SCALER_CROP_REGION) ?: sensorActiveArray ?: return
            if (region.width() <= 0 || region.height() <= 0) return

            val boxes = faces.mapNotNull { face ->
                val bounds = face.bounds
                val a = sensorToView(
                    (bounds.left - region.left) / region.width().toFloat(),
                    (bounds.top - region.top) / region.height().toFloat(),
                    geometry
                )
                val b = sensorToView(
                    (bounds.right - region.left) / region.width().toFloat(),
                    (bounds.bottom - region.top) / region.height().toFloat(),
                    geometry
                )
                // Rotation can swap which corner is which, so normalise rather than assume.
                RectF(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
                    .takeIf { it.width() > 1f && it.height() > 1f }
            }
            // Logged only when the count changes: enough to confirm detection and box placement
            // without a per-frame flood.
            if (boxes.size != lastFaceCount) {
                lastFaceCount = boxes.size
                Log.i(TAG, "faces=${boxes.size} ${boxes.firstOrNull()?.let {
                    "first=[%.0f,%.0f,%.0f,%.0f] view=%dx%d".format(
                        it.left, it.top, it.right, it.bottom,
                        binding.faceOverlay.width, binding.faceOverlay.height
                    )
                } ?: ""}")
            }
            binding.faceOverlay.post { binding.faceOverlay.setFaces(boxes) }
        }
    }

    private var lastFaceCount = -1

    /**
     * Sensor-normalised point to a view coordinate: the exact inverse of what [focusAt] does,
     * walking the transform chain forwards instead of backwards.
     */
    private fun sensorToView(bu: Float, bv: Float, g: PreviewGeometry): PointF {
        val u: Float
        val v: Float
        when (g.rotationDegrees) {
            90 -> { u = 1f - bv; v = bu }
            180 -> { u = 1f - bu; v = 1f - bv }
            270 -> { u = bv; v = 1f - bu }
            else -> { u = bu; v = bv }
        }
        val croppedX = u * g.frameWidth - g.layout.cropLeft
        val croppedY = v * g.frameHeight - g.layout.cropTop
        return PointF(
            (croppedX + g.layout.borderLeft) * g.scale + g.destLeft,
            (croppedY + g.layout.borderTop) * g.scale + g.destTop
        )
    }

    private fun showFocusRing(viewX: Float, viewY: Float) {
        val ring = binding.focusRing
        ring.removeCallbacks(hideFocusRing)
        ring.x = viewX - ring.width / 2f
        ring.y = viewY - ring.height / 2f
        ring.alpha = 1f
        ring.scaleX = 1.35f
        ring.scaleY = 1.35f
        ring.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        ring.postDelayed(hideFocusRing, FOCUS_RING_MS)
    }

    private val hideFocusRing = Runnable {
        binding.focusRing.animate().alpha(0f).setDuration(260).start()
    }

    private fun applyZoom(ratio: Float) {
        val camera = this.camera ?: return
        val state = camera.cameraInfo.zoomState.value ?: return
        camera.cameraControl.setZoomRatio(
            ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        )
    }

    private fun observeZoom() {
        camera?.cameraInfo?.zoomState?.observe(this) { state ->
            binding.zoomLabel.text = getString(R.string.zoom_label, state.zoomRatio)
            flashZoomLabel()
        }
    }

    /** Show the ratio while the user is zooming, then get out of the way. */
    private fun flashZoomLabel() {
        val label = binding.zoomLabel
        label.removeCallbacks(hideZoomLabel)
        label.animate().alpha(1f).setDuration(90).start()
        label.postDelayed(hideZoomLabel, ZOOM_LABEL_MS)
    }

    private val hideZoomLabel = Runnable {
        binding.zoomLabel.animate().alpha(0f).setDuration(260).start()
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val preset = activePreset
        if (preset == null) {
            imageProxy.close()
            return
        }

        val imageRotation = imageProxy.imageInfo.rotationDegrees
        val rotated = rotateBitmap(imageProxy.toBitmap(), imageRotation)
        imageProxy.close()

        val renderer = previewRenderer.let { current ->
            if (current == null || current.width != rotated.width || current.height != rotated.height) {
                current?.close()
                ShaderRenderer(rotated.width, rotated.height).also { previewRenderer = it }
            } else {
                current
            }
        }

        // Refresh the thumbnails' source scene occasionally. Rebuilding them is the expensive part
        // (one render per preset), so this is minutes-scale, not per-frame.
        val now = System.nanoTime()
        if (now - lastThumbnailSourceNanos > THUMBNAIL_REFRESH_NANOS) {
            lastThumbnailSourceNanos = now
            thumbnails?.setSource(rotated)
            runOnUiThread { carouselAdapter?.refreshThumbnails() }
        }

        val elapsedSeconds = (now - startTimeNanos) / 1_000_000_000f
        // A frame that fails to render is dropped: the preview stutters, but the app stays alive.
        val filtered = try {
            renderer.render(rotated, elapsedSeconds, preset)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Dropped preview frame", e)
            return
        }

        // Same compositor the capture uses, so the crop, border and stamp you see are the real ones.
        previewDate.time = System.currentTimeMillis()
        val composed = OverlayCompositor.compose(filtered, preset, previewDate)
        drawToSurface(
            composed,
            OverlayCompositor.layoutFor(rotated.width, rotated.height, preset),
            rotated.width,
            rotated.height,
            imageRotation
        )
        if (composed !== filtered) filtered.recycle()
        composed.recycle()

        reportFrameRate(now, rotated.width, rotated.height)
    }

    /**
     * Periodic throughput log. The filter pipeline is the easiest thing in this app to make slow
     * without noticing, so it reports what it is actually achieving rather than leaving it to be
     * guessed at from how the preview feels.
     */
    private fun reportFrameRate(nowNanos: Long, frameWidth: Int, frameHeight: Int) {
        framesSinceReport++
        if (fpsWindowStartNanos == 0L) {
            fpsWindowStartNanos = nowNanos
            return
        }
        val elapsed = nowNanos - fpsWindowStartNanos
        if (elapsed < FPS_REPORT_NANOS) return

        val fps = framesSinceReport * 1_000_000_000.0 / elapsed
        Log.i(TAG, "preview %.1f fps at %dx%d (%s)".format(
            fps, frameWidth, frameHeight, activePreset?.id ?: "-"
        ))
        framesSinceReport = 0
        fpsWindowStartNanos = nowNanos
    }

    private fun drawToSurface(
        bitmap: Bitmap,
        layout: OverlayCompositor.Layout,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int
    ) {
        val holder = binding.viewFinder.holder
        // Frames arrive on the camera thread, so the surface can be torn down mid-draw when the
        // activity goes away. Check first, and still guard the lock against losing that race.
        if (!holder.surface.isValid) return
        val canvas = try {
            holder.lockHardwareCanvas()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Preview surface went away mid-frame", e)
            return
        } ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val viewWidth = binding.viewFinder.width.toFloat()
            val viewHeight = binding.viewFinder.height.toFloat()
            if (viewWidth == 0f || viewHeight == 0f) return

            // Fit, not fill: letterboxing shows the whole composed frame. Filling would crop the
            // Polaroid border and the frame edges off the preview while the saved photo keeps them.
            val scale = minOf(viewWidth / bitmap.width, viewHeight / bitmap.height)
            val dx = (viewWidth - bitmap.width * scale) / 2f
            val dy = (viewHeight - bitmap.height * scale) / 2f
            previewMatrix.setScale(scale, scale)
            previewMatrix.postTranslate(dx, dy)
            canvas.drawBitmap(bitmap, previewMatrix, null)

            // Recorded here rather than computed on tap: this is the transform actually used to
            // put pixels on screen, so focus can invert exactly what the user is looking at.
            previewGeometry = PreviewGeometry(
                destLeft = dx,
                destTop = dy,
                scale = scale,
                layout = layout,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                rotationDegrees = rotationDegrees
            )
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Preview surface went away before post", e)
            }
        }
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    // --- capture -----------------------------------------------------------

    private fun takePhoto() {
        if (countingDown) return
        if (timerSeconds > 0) {
            countingDown = true
            binding.shutterButton.isEnabled = false
            startCountdown(timerSeconds)
            return
        }
        capturePhoto()
    }

    private fun capturePhoto() {
        countingDown = false
        binding.shutterButton.isEnabled = true

        val capture = imageCapture ?: return
        val preset = activePreset ?: return
        setStatus(R.string.status_saving)

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotated = rotateBitmap(image.toBitmap(), image.imageInfo.rotationDegrees)
                image.close()

                // A fresh seed per shot so two photos never share an identical grain pattern.
                val renderer = ShaderRenderer(rotated.width, rotated.height)
                val filtered = try {
                    renderer.render(rotated, Random.nextFloat() * 100f, preset)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Could not filter the captured photo", e)
                    return
                } finally {
                    renderer.close()
                }

                val composed = OverlayCompositor.compose(filtered, preset, Date())
                saveToGallery(composed, preset)
                if (composed !== filtered) filtered.recycle()
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed", exception)
                runOnUiThread { setStatus(R.string.status_ready) }
            }
        })
    }

    private fun saveToGallery(bitmap: Bitmap, preset: FilterPreset) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "RetroCam_${preset.id}_$stamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RetroCam")
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri == null) {
            Log.e(TAG, "Failed to create MediaStore entry")
            return
        }

        contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        runOnUiThread {
            Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
            setStatus(R.string.status_ready)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        previewRenderer?.close()
        thumbnails?.close()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "RetroCam"
        private const val DOUBLE_TAP_ZOOM = 2f
        private const val ZOOM_LABEL_MS = 1400L
        private const val FOCUS_RING_MS = 1200L
        private const val FOCUS_HOLD_SECONDS = 4L
        private const val THUMBNAIL_REFRESH_NANOS = 20_000_000_000L
        private const val FPS_REPORT_NANOS = 3_000_000_000L
    }
}
