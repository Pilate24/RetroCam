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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
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
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
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
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis, capture)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val preset = activePreset
        if (preset == null) {
            imageProxy.close()
            return
        }

        val rotated = rotateBitmap(imageProxy.toBitmap(), imageProxy.imageInfo.rotationDegrees)
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
        drawToSurface(composed)
        if (composed !== filtered) filtered.recycle()
        composed.recycle()
    }

    private fun drawToSurface(bitmap: Bitmap) {
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
    }
}
