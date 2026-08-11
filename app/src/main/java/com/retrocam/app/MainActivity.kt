package com.retrocam.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import com.retrocam.app.ui.FilterCarouselAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var previewRenderer: ShaderRenderer? = null
    private val startTimeNanos = System.nanoTime()

    /** Reused per frame; only ever touched on the camera thread inside drawToSurface. */
    private val previewMatrix = Matrix()
    private val previewDate = Date()

    private var presets: List<FilterPreset> = emptyList()
    private var carouselAdapter: FilterCarouselAdapter? = null
    private val snapHelper = LinearSnapHelper()

    private var selectedIndex = 0
    private var intensity = 1f

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
        binding.galleryButton.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        presets = PresetRepository.load(assets)
        if (presets.isEmpty()) {
            Toast.makeText(this, R.string.no_presets, Toast.LENGTH_LONG).show()
            finish()
            return
        }
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

    private fun setUpCarousel() {
        val layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        val adapter = FilterCarouselAdapter(presets) { position -> centerOn(position) }
        carouselAdapter = adapter

        binding.filterCarousel.layoutManager = layoutManager
        binding.filterCarousel.adapter = adapter
        snapHelper.attachToRecyclerView(binding.filterCarousel)

        // Whatever ends up centred after a swipe becomes the active look.
        binding.filterCarousel.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
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
        val child = layoutManager.findViewByPosition(position)
        if (child == null) {
            layoutManager.scrollToPositionWithOffset(position, carousel.paddingStart)
            selectPreset(position)
            return
        }
        val delta = (child.left + child.right) / 2 - carousel.width / 2
        if (delta == 0) selectPreset(position) else carousel.smoothScrollBy(delta, 0)
    }

    private fun setUpIntensitySlider() {
        binding.intensitySlider.addOnChangeListener { _, value, _ ->
            intensity = value
            refreshActivePreset()
        }
    }

    private fun selectPreset(position: Int) {
        if (position !in presets.indices) return
        selectedIndex = position
        carouselAdapter?.selectedPosition = position
        refreshActivePreset()
    }

    private fun refreshActivePreset() {
        val base = presets.getOrNull(selectedIndex) ?: return
        activePreset = base.scaledBy(intensity)
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

            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolutionSelector)
                .build()
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
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
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

        try {
            camera.cameraControl.startFocusAndMetering(action)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Focus point rejected", e)
            return
        }
        showFocusRing(viewX, viewY)
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

        val elapsedSeconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000f
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
        val capture = imageCapture ?: return
        val preset = activePreset ?: return

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

        runOnUiThread { Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        previewRenderer?.close()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "RetroCam"
        private const val DOUBLE_TAP_ZOOM = 2f
        private const val ZOOM_LABEL_MS = 1400L
        private const val FOCUS_RING_MS = 1200L
        private const val FOCUS_HOLD_SECONDS = 4L
    }
}
