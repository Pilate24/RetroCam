package com.retrocam.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
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
import com.retrocam.app.ui.CompareActivity
import com.retrocam.app.ui.CompareFrame
import com.retrocam.app.ui.FilterCarouselAdapter
import com.retrocam.app.ui.ThumbnailCache
import com.retrocam.app.video.VideoRecordingManager
import com.retrocam.app.video.VideoReviewActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    enum class CameraMode { PHOTO, VIDEO }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var videoRecordingManager: VideoRecordingManager

    private var cameraMode = CameraMode.PHOTO
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraProvider: ProcessCameraProvider? = null

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
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
    private var lastCompareFrameNanos = 0L

    private var flash = FlashChoice.OFF
    private var timerSeconds = 0
    private var aspect = AspectChoice.AUTO
    private var countingDown = false
    private var surpriseMode = false
    private var lastSurpriseId: String? = null
    private var isPulsing = false

    /**
     * The selected preset with the intensity already applied. Written on the main thread whenever
     * the selection or the slider changes, read on the camera thread by every frame — so it is
     * scaled once here rather than per frame.
     */
    @Volatile
    private var activePreset: FilterPreset? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                binding.permissionDeniedContainer.visibility = View.GONE
                startCamera()
            } else {
                showPermissionDenied()
            }
        }

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast(getString(R.string.mic_permission_denied_notice))
            }
            startVideoRecording()
        }

    private val videoReviewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val retake = result.data?.getBooleanExtra(VideoReviewActivity.EXTRA_RETAKE, false) ?: false
                if (retake) {
                    switchToMode(CameraMode.VIDEO)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        videoRecordingManager = VideoRecordingManager(this) { state ->
            onRecordingStateChanged(state)
        }

        setUpShutterAndControls()
        setUpTopBar()
        setUpModeSelector()
        setUpFlipCamera()
        setUpPermissionUI()

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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (videoRecordingManager.isRecording) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Recording in Progress")
                        .setMessage("Do you want to stop recording and exit?")
                        .setPositiveButton("Stop & Exit") { _, _ ->
                            videoRecordingManager.stopRecording()
                            finish()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // --- Mode Selection & Camera Switching ---------------------------------

    private fun setUpModeSelector() {
        binding.modePhoto.setOnClickListener {
            if (cameraMode != CameraMode.PHOTO) {
                switchToMode(CameraMode.PHOTO)
            }
        }

        binding.modeVideo.setOnClickListener {
            if (cameraMode != CameraMode.VIDEO) {
                switchToMode(CameraMode.VIDEO)
            }
        }
    }

    private fun switchToMode(mode: CameraMode) {
        if (videoRecordingManager.isRecording) return
        cameraMode = mode

        if (mode == CameraMode.PHOTO) {
            binding.modePhoto.setBackgroundResource(R.drawable.mode_pill_selected)
            binding.modePhoto.setTextColor(Color.BLACK)
            binding.modePhoto.setTypeface(null, android.graphics.Typeface.BOLD)

            binding.modeVideo.background = null
            binding.modeVideo.setTextColor(ContextCompat.getColor(this, R.color.readout_dim))
            binding.modeVideo.setTypeface(null, android.graphics.Typeface.NORMAL)

            binding.shutterButton.setBackgroundResource(R.drawable.shutter_button)
            binding.shutterButton.contentDescription = getString(R.string.shutter)

            binding.videoPreviewView.visibility = View.GONE
            binding.viewFinder.visibility = View.VISIBLE
            binding.filterCarousel.visibility = View.VISIBLE
            binding.categoryTabs.visibility = View.VISIBLE
            binding.readoutRow.visibility = View.VISIBLE
            binding.recordingIndicatorBar.visibility = View.GONE
            binding.pauseResumeButton.visibility = View.GONE
            binding.galleryButton.visibility = View.VISIBLE

            binding.topBar.visibility = View.VISIBLE
        } else {
            binding.modeVideo.setBackgroundResource(R.drawable.mode_pill_selected)
            binding.modeVideo.setTextColor(Color.BLACK)
            binding.modeVideo.setTypeface(null, android.graphics.Typeface.BOLD)

            binding.modePhoto.background = null
            binding.modePhoto.setTextColor(ContextCompat.getColor(this, R.color.readout_dim))
            binding.modePhoto.setTypeface(null, android.graphics.Typeface.NORMAL)

            binding.shutterButton.setBackgroundResource(R.drawable.shutter_video_record)
            binding.shutterButton.contentDescription = getString(R.string.record_video)

            binding.videoPreviewView.visibility = View.GONE
            binding.viewFinder.visibility = View.VISIBLE
            binding.filterCarousel.visibility = View.VISIBLE
            binding.categoryTabs.visibility = View.VISIBLE
            binding.readoutRow.visibility = View.VISIBLE
            binding.recordingIndicatorBar.visibility = View.GONE
            binding.pauseResumeButton.visibility = View.GONE
            binding.galleryButton.visibility = View.VISIBLE
            binding.topBar.visibility = View.VISIBLE

            checkAudioPermissionInfo()
        }

        startCamera()
    }

    private fun checkAudioPermissionInfo() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.mic_permission_rationale))
        }
    }

    private fun setUpFlipCamera() {
        binding.flipCameraButton.setOnClickListener {
            if (videoRecordingManager.isRecording) return@setOnClickListener
            val provider = cameraProvider ?: return@setOnClickListener
            val nextSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            if (provider.hasCamera(nextSelector)) {
                cameraSelector = nextSelector
                startCamera()
            } else {
                toast(getString(R.string.no_camera_available))
            }
        }
    }

    private fun setUpShutterAndControls() {
        binding.shutterButton.setOnClickListener {
            if (cameraMode == CameraMode.PHOTO) {
                takePhoto()
            } else {
                handleVideoRecordClick()
            }
        }

        binding.pauseResumeButton.setOnClickListener {
            if (videoRecordingManager.isRecording) {
                if (videoRecordingManager.isPaused) {
                    videoRecordingManager.resumeRecording()
                } else {
                    videoRecordingManager.pauseRecording()
                }
            }
        }
    }

    private var latestComposedWidth = 720
    private var latestComposedHeight = 1280

    private fun startVideoRecording() {
        val width = latestComposedWidth.takeIf { it > 0 } ?: 720
        val height = latestComposedHeight.takeIf { it > 0 } ?: 1280
        videoRecordingManager.startRecording(width, height)
    }

    private fun handleVideoRecordClick() {
        if (videoRecordingManager.isRecording) {
            videoRecordingManager.stopRecording()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startVideoRecording()
            } else {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun onRecordingStateChanged(state: VideoRecordingManager.RecordingState) {
        when (state) {
            is VideoRecordingManager.RecordingState.Idle -> {
                binding.recordingIndicatorBar.visibility = View.GONE
                binding.pauseResumeButton.visibility = View.GONE
                binding.galleryButton.visibility = View.VISIBLE
                binding.modeSelector.isEnabled = true
                binding.modeSelector.alpha = 1f
                binding.flipCameraButton.isEnabled = true
                binding.flipCameraButton.alpha = 1f
                binding.aspectButton.isEnabled = true
                binding.aspectButton.alpha = 1f
                binding.filterCarousel.isEnabled = true
                binding.filterCarousel.alpha = 1f
                binding.categoryTabs.isEnabled = true
                binding.categoryTabs.alpha = 1f
                binding.shutterButton.isEnabled = true
                binding.shutterButton.setBackgroundResource(R.drawable.shutter_video_record)
                binding.shutterButton.contentDescription = getString(R.string.record_video)
                setStatus(R.string.status_ready)
                stopPulsingRecordDot()
            }

            is VideoRecordingManager.RecordingState.Starting -> {
                binding.recordingIndicatorBar.visibility = View.VISIBLE
                binding.recordTimerText.text = "00:00"
                binding.recordStateText.text = getString(R.string.status_recording)
                binding.micStatusBadge.visibility = if (!state.audioEnabled) View.VISIBLE else View.GONE
                binding.pauseResumeButton.visibility = View.VISIBLE
                binding.pauseResumeButton.setImageResource(R.drawable.ic_pause)
                binding.pauseResumeButton.contentDescription = getString(R.string.pause_recording)
                binding.galleryButton.visibility = View.GONE
                binding.modeSelector.isEnabled = false
                binding.modeSelector.alpha = 0.4f
                binding.flipCameraButton.isEnabled = false
                binding.flipCameraButton.alpha = 0.4f
                binding.aspectButton.isEnabled = false
                binding.aspectButton.alpha = 0.4f
                binding.filterCarousel.isEnabled = false
                binding.filterCarousel.alpha = 0.4f
                binding.categoryTabs.isEnabled = false
                binding.categoryTabs.alpha = 0.4f
                binding.intensitySlider.visibility = View.GONE
                binding.shutterButton.setBackgroundResource(R.drawable.shutter_video_stop)
                binding.shutterButton.contentDescription = getString(R.string.stop_recording)
                pulseRecordDot()
            }

            is VideoRecordingManager.RecordingState.Recording -> {
                binding.recordingIndicatorBar.visibility = View.VISIBLE
                binding.recordTimerText.text = formatRecordTime(state.durationSeconds)
                binding.recordStateText.text = getString(R.string.status_recording)
                binding.recordStateText.setTextColor(ContextCompat.getColor(this, R.color.record_red))
                binding.micStatusBadge.visibility = if (!state.audioEnabled) View.VISIBLE else View.GONE
                binding.pauseResumeButton.visibility = View.VISIBLE
                binding.pauseResumeButton.setImageResource(R.drawable.ic_pause)
                binding.pauseResumeButton.contentDescription = getString(R.string.pause_recording)
                binding.galleryButton.visibility = View.GONE
                binding.modeSelector.isEnabled = false
                binding.modeSelector.alpha = 0.4f
                binding.flipCameraButton.isEnabled = false
                binding.flipCameraButton.alpha = 0.4f
                binding.aspectButton.isEnabled = false
                binding.aspectButton.alpha = 0.4f
                binding.filterCarousel.isEnabled = false
                binding.filterCarousel.alpha = 0.4f
                binding.categoryTabs.isEnabled = false
                binding.categoryTabs.alpha = 0.4f
                binding.intensitySlider.visibility = View.GONE
                binding.shutterButton.setBackgroundResource(R.drawable.shutter_video_stop)
                binding.shutterButton.contentDescription = getString(R.string.stop_recording)
                pulseRecordDot()
            }

            is VideoRecordingManager.RecordingState.Paused -> {
                binding.recordTimerText.text = formatRecordTime(state.durationSeconds)
                binding.recordStateText.text = getString(R.string.status_paused)
                binding.recordStateText.setTextColor(ContextCompat.getColor(this, R.color.amber))
                binding.pauseResumeButton.setImageResource(R.drawable.ic_play)
                binding.pauseResumeButton.contentDescription = getString(R.string.resume_recording)
                stopPulsingRecordDot()
            }

            is VideoRecordingManager.RecordingState.Finalizing -> {
                binding.shutterButton.isEnabled = false
                setStatus(R.string.status_finalizing)
            }

            is VideoRecordingManager.RecordingState.Success -> {
                binding.shutterButton.isEnabled = true
                onRecordingStateChanged(VideoRecordingManager.RecordingState.Idle)
                toast(getString(R.string.video_saved_toast))
                val intent = Intent(this, VideoReviewActivity::class.java).apply {
                    putExtra(VideoReviewActivity.EXTRA_URI, state.mediaStoreUri)
                    putExtra(VideoReviewActivity.EXTRA_NAME, state.displayName)
                    putExtra(VideoReviewActivity.EXTRA_DURATION_MS, state.durationMs)
                }
                videoReviewLauncher.launch(intent)
            }

            is VideoRecordingManager.RecordingState.Error -> {
                binding.shutterButton.isEnabled = true
                onRecordingStateChanged(VideoRecordingManager.RecordingState.Idle)
                toast(state.message)
            }
        }
    }

    private fun formatRecordTime(seconds: Long): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        val hours = minutes / 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes % 60, remainingSeconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, remainingSeconds)
        }
    }

    private fun pulseRecordDot() {
        if (isPulsing) return
        isPulsing = true
        binding.recordDot.animate()
            .alpha(0.2f)
            .setDuration(500)
            .withEndAction {
                if (isPulsing) {
                    binding.recordDot.animate()
                        .alpha(1f)
                        .setDuration(500)
                        .withEndAction {
                            isPulsing = false
                            if (videoRecordingManager.isRecording && !videoRecordingManager.isPaused) {
                                pulseRecordDot()
                            }
                        }.start()
                }
            }.start()
    }

    private fun stopPulsingRecordDot() {
        isPulsing = false
        binding.recordDot.animate().cancel()
        binding.recordDot.alpha = 1f
    }

    // --- filter carousel ---------------------------------------------------

    private fun setUpCategories() {
        val categories = PresetRepository.categoriesOf(allPresets)
        binding.categoryTabs.layoutManager =
            LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.categoryTabs.adapter = CategoryAdapter(categories) { category -> showCategory(category) }
    }

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

        binding.filterCarousel.doOnLayout { view ->
            val itemWidth = resources.getDimensionPixelSize(R.dimen.filter_item_width)
            val padding = ((view.width - itemWidth) / 2).coerceAtLeast(0)
            view.setPadding(padding, 0, padding, 0)
        }
    }

    private fun centerOn(position: Int) {
        val carousel = binding.filterCarousel
        val layoutManager = carousel.layoutManager as? LinearLayoutManager ?: return
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
            revealIntensity()
        }
        binding.readoutRow.setOnClickListener {
            if (binding.intensitySlider.isVisible) hideIntensity() else revealIntensity()
        }
    }

    private fun revealIntensity() {
        binding.intensitySlider.isVisible = true
        binding.readoutRow.removeCallbacks(hideIntensityRunnable)
        binding.readoutRow.postDelayed(hideIntensityRunnable, INTENSITY_VISIBLE_MS)
    }

    private fun hideIntensity() {
        binding.readoutRow.removeCallbacks(hideIntensityRunnable)
        binding.intensitySlider.isVisible = false
    }

    private val hideIntensityRunnable = Runnable { binding.intensitySlider.isVisible = false }

    private fun selectPreset(position: Int) {
        if (position !in visiblePresets.indices) return
        selectedPresetId = visiblePresets[position].id
        carouselAdapter?.selectedPosition = position
        refreshActivePreset()
    }

    private fun refreshActivePreset() {
        val base = allPresets.firstOrNull { it.id == selectedPresetId } ?: return
        val scaled = base.scaledBy(intensity)
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
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                // Verify the chosen camera is supported, or fall back to default
                if (!provider.hasCamera(cameraSelector)) {
                    val fallback = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    if (provider.hasCamera(fallback)) {
                        cameraSelector = fallback
                    } else {
                        showCameraError(getString(R.string.no_camera_available))
                        return@addListener
                    }
                }

                provider.unbindAll()
                hideCameraError()

                if (cameraMode == CameraMode.PHOTO) {
                    bindPhotoUseCases(provider)
                } else {
                    bindVideoUseCases(provider)
                }

                observeZoom()
                setStatus(R.string.status_ready)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                showCameraError(getString(R.string.camera_init_error))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPhotoUseCases(provider: ProcessCameraProvider) {
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

        camera = provider.bindToLifecycle(
            this, cameraSelector, analysis, capture
        )
    }

    private fun bindVideoUseCases(provider: ProcessCameraProvider) {
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

        val analysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(resolutionSelector)
            .build()
            .also { it.setAnalyzer(cameraExecutor, ::processFrame) }

        camera = provider.bindToLifecycle(
            this, cameraSelector, analysis
        )
    }

    private fun showCameraError(message: String) {
        binding.cameraErrorContainer.visibility = View.VISIBLE
        binding.cameraErrorMessage.text = message
        binding.retryCameraButton.setOnClickListener {
            binding.cameraErrorContainer.visibility = View.GONE
            startCamera()
        }
    }

    private fun hideCameraError() {
        binding.cameraErrorContainer.visibility = View.GONE
    }

    private fun setUpPermissionUI() {
        binding.grantPermissionButton.setOnClickListener {
            binding.permissionDeniedContainer.visibility = View.GONE
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        binding.openSettingsButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    private fun showPermissionDenied() {
        binding.permissionDeniedContainer.visibility = View.VISIBLE
        val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.CAMERA
        )
        binding.openSettingsButton.visibility = if (permanentlyDenied) View.VISIBLE else View.GONE
    }

    // --- status chip -------------------------------------------------------

    private fun setStatus(resId: Int) {
        binding.statusChip.setText(resId)
        binding.statusChip.animate().alpha(1f).setDuration(120).start()
    }

    // --- top bar -----------------------------------------------------------

    private enum class AspectChoice(val label: String, val ratio: Float?) {
        AUTO("AUTO", null),
        PORTRAIT_3_4("3:4", 0.75f),
        PORTRAIT_9_16("9:16", 9f / 16f),
        SQUARE("1:1", 1f),
        WIDESCREEN("16:9", 16f / 9f),
        CINEMASCOPE("2:1", 2f),
        THREETWO("3:2", 3f / 2f),
        FULL("FULL", null)
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
            if (cameraMode == CameraMode.VIDEO) {
                // Torch mode for video
                camera?.cameraControl?.enableTorch(flash == FlashChoice.ON)
            }
            binding.flashButton.setImageResource(flash.icon)
            binding.flashButton.alpha = if (flash == FlashChoice.OFF) 0.65f else 1f
            toast(getString(R.string.flash_toast, flash.label))
        }
        binding.flashButton.alpha = 0.65f

        binding.timerButton.setOnClickListener {
            if (cameraMode == CameraMode.VIDEO) {
                toast("Self-timer is for photo mode")
                return@setOnClickListener
            }
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

        binding.aspectButton.setOnClickListener { showAspectMenu(it) }
        binding.aspectButton.text = aspect.label

        binding.overflowButton.setOnClickListener { showOverflow(it) }
    }

    private fun showAspectMenu(anchor: View) {
        if (videoRecordingManager.isRecording) {
            toast("Cannot change aspect ratio while recording")
            return
        }
        val popup = PopupMenu(this, anchor)
        AspectChoice.entries.forEachIndexed { index, choice ->
            val title = if (choice == aspect) "✓ ${choice.label}" else "    ${choice.label}"
            popup.menu.add(0, index, index, title)
        }
        popup.setOnMenuItemClickListener { item ->
            val selected = AspectChoice.entries.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            aspect = selected
            binding.aspectButton.text = aspect.label
            refreshActivePreset()
            toast(getString(R.string.aspect_toast, aspect.label))
            true
        }
        popup.show()
    }

    private fun showOverflow(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.camera_overflow, menu)
            menu.findItem(R.id.menuGrid).isChecked =
                binding.gridOverlay.visibility == View.VISIBLE
            menu.findItem(R.id.menuSurprise).isChecked = surpriseMode

            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menuGrid -> {
                        val showing = binding.gridOverlay.visibility == View.VISIBLE
                        binding.gridOverlay.visibility = if (showing) View.GONE else View.VISIBLE
                        true
                    }
                    R.id.menuSurprise -> {
                        if (cameraMode == CameraMode.VIDEO) {
                            toast("Surprise mode is for photo mode")
                            return@setOnMenuItemClickListener true
                        }
                        surpriseMode = !surpriseMode
                        setStatus(
                            if (surpriseMode) R.string.surprise_status else R.string.status_ready
                        )
                        toast(
                            getString(
                                if (surpriseMode) R.string.surprise_on else R.string.surprise_off
                            )
                        )
                        true
                    }
                    R.id.menuCompare -> {
                        if (cameraMode == CameraMode.VIDEO) {
                            toast("Camera compare is for photo mode")
                            return@setOnMenuItemClickListener true
                        }
                        openCompare()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun openCompare() {
        val frame = latestFrame
        if (frame == null) {
            toast(getString(R.string.compare_waiting))
            return
        }
        CompareFrame.set(frame)
        latestFrame = null
        compareLauncher.launch(Intent(this, CompareActivity::class.java))
    }

    @Volatile
    private var latestFrame: Bitmap? = null

    private val compareLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val id = result.data?.getStringExtra(CompareActivity.EXTRA_PRESET_ID)
            if (result.resultCode == Activity.RESULT_OK && id != null) applyPresetById(id)
            CompareFrame.clear()
        }

    private fun applyPresetById(id: String) {
        if (allPresets.none { it.id == id }) return
        selectedPresetId = id
        showCategory(PresetRepository.CATEGORY_ALL)
        (binding.categoryTabs.adapter as? CategoryAdapter)?.selectedPosition = 0
        refreshActivePreset()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun rollRandomPreset(): FilterPreset? {
        if (allPresets.isEmpty()) return null
        val pool = allPresets.filter { it.id != lastSurpriseId }.ifEmpty { allPresets }
        val picked = pool[Random.nextInt(pool.size)]
        lastSurpriseId = picked.id
        return picked.scaledBy(intensity).let { scaled ->
            when (aspect) {
                AspectChoice.AUTO -> scaled
                AspectChoice.FULL -> scaled.copy(aspect = null)
                else -> scaled.copy(aspect = aspect.ratio)
            }
        }
    }

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
                    val zoomedIn = state.zoomRatio > state.minZoomRatio * 1.1f
                    applyZoom(
                        if (zoomedIn) state.minZoomRatio
                        else minOf(DOUBLE_TAP_ZOOM, state.maxZoomRatio)
                    )
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    focusAt(e.x, e.y)
                    return true
                }
            }
        )

        val touchListener = View.OnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }

        binding.viewFinder.setOnTouchListener(touchListener)
        binding.videoPreviewView.setOnTouchListener(touchListener)
    }

    // --- tap to focus ------------------------------------------------------

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

    private fun focusAt(viewX: Float, viewY: Float) {
        val camera = this.camera ?: return
        val geometry = previewGeometry ?: return
        val layout = geometry.layout

        val composedX = (viewX - geometry.destLeft) / geometry.scale
        val composedY = (viewY - geometry.destTop) / geometry.scale

        val pictureX = composedX - layout.borderLeft
        val pictureY = composedY - layout.borderTop
        if (pictureX < 0f || pictureY < 0f ||
            pictureX > layout.cropWidth || pictureY > layout.cropHeight
        ) return

        val rawU = (pictureX + layout.cropLeft) / geometry.frameWidth
        val u = if (isFrontCamera()) 1f - rawU else rawU
        val v = (pictureY + layout.cropTop) / geometry.frameHeight

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
        setStatus(R.string.status_focusing)
        focusing.addListener(
            { setStatus(R.string.status_ready) },
            ContextCompat.getMainExecutor(this)
        )
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
        val rotated = rotateBitmap(imageProxy.toBitmap(), imageRotation, isFront = isFrontCamera())
        imageProxy.close()

        val renderer = previewRenderer.let { current ->
            if (current == null || current.width != rotated.width || current.height != rotated.height) {
                current?.close()
                ShaderRenderer(rotated.width, rotated.height).also { previewRenderer = it }
            } else {
                current
            }
        }

        val now = System.nanoTime()
        if (now - lastThumbnailSourceNanos > THUMBNAIL_REFRESH_NANOS) {
            lastThumbnailSourceNanos = now
            thumbnails?.setSource(rotated)
            runOnUiThread { carouselAdapter?.refreshThumbnails() }
        }

        if (now - lastCompareFrameNanos > COMPARE_FRAME_NANOS) {
            lastCompareFrameNanos = now
            val previous = latestFrame
            latestFrame = Bitmap.createScaledBitmap(
                rotated, COMPARE_FRAME_WIDTH,
                (COMPARE_FRAME_WIDTH * rotated.height / rotated.width.toFloat()).toInt()
                    .coerceAtLeast(1),
                true
            )
            previous?.recycle()
        }

        val elapsedSeconds = (now - startTimeNanos) / 1_000_000_000f
        val filtered = try {
            renderer.render(rotated, elapsedSeconds, preset)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Dropped preview frame", e)
            return
        }

        previewDate.time = System.currentTimeMillis()
        val composed = OverlayCompositor.compose(filtered, preset, previewDate)
        latestComposedWidth = composed.width
        latestComposedHeight = composed.height

        drawToSurface(
            composed,
            OverlayCompositor.layoutFor(rotated.width, rotated.height, preset),
            rotated.width,
            rotated.height,
            imageRotation
        )

        if (cameraMode == CameraMode.VIDEO && videoRecordingManager.isRecording) {
            videoRecordingManager.onNewFrame(composed)
        }

        if (composed !== filtered) filtered.recycle()
        composed.recycle()

        reportFrameRate(now, rotated.width, rotated.height)
    }

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

            val scale = minOf(viewWidth / bitmap.width, viewHeight / bitmap.height)
            val dx = (viewWidth - bitmap.width * scale) / 2f
            val dy = (viewHeight - bitmap.height * scale) / 2f
            previewMatrix.setScale(scale, scale)
            previewMatrix.postTranslate(dx, dy)
            canvas.drawBitmap(bitmap, previewMatrix, null)

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

    private fun isFrontCamera(): Boolean =
        cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA

    private fun rotateBitmap(source: Bitmap, degrees: Int, isFront: Boolean = false): Bitmap {
        val matrix = Matrix()
        if (degrees != 0) {
            matrix.postRotate(degrees.toFloat())
        }
        if (isFront) {
            // Mirror horizontally so the front camera behaves like a natural selfie mirror
            matrix.postScale(-1f, 1f)
        }
        if (matrix.isIdentity) return source
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
        val preset = (if (surpriseMode) rollRandomPreset() else activePreset) ?: return
        setStatus(R.string.status_saving)

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotated = rotateBitmap(image.toBitmap(), image.imageInfo.rotationDegrees, isFront = isFrontCamera())
                image.close()

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
                saveToGallery(composed, preset, revealName = surpriseMode)
                if (composed !== filtered) filtered.recycle()
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed", exception)
                runOnUiThread { setStatus(R.string.status_ready) }
            }
        })
    }

    private fun saveToGallery(bitmap: Bitmap, preset: FilterPreset, revealName: Boolean = false) {
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
            if (revealName) toast(getString(R.string.surprise_result, preset.name))
            else Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()
            setStatus(if (surpriseMode) R.string.surprise_status else R.string.status_ready)
        }
    }

    override fun onStop() {
        super.onStop()
        if (::videoRecordingManager.isInitialized && videoRecordingManager.isRecording) {
            videoRecordingManager.stopRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::videoRecordingManager.isInitialized) {
            videoRecordingManager.release()
        }
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
        private const val COMPARE_FRAME_NANOS = 1_000_000_000L
        private const val COMPARE_FRAME_WIDTH = 480
        private const val INTENSITY_VISIBLE_MS = 3500L
    }
}
