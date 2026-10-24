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
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import com.retrocam.app.video.VideoFps
import com.retrocam.app.video.VideoQuality
import com.retrocam.app.video.ProCameraManager
import android.widget.LinearLayout
import android.widget.TextView
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

    enum class CameraMode { PHOTO, VIDEO, RAW_VIDEO }

    enum class ProParam { ISO, SHUTTER, EV, FOCUS, WB }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var videoRecordingManager: VideoRecordingManager
    private val proCameraManager = ProCameraManager()
    private var activeProParam: ProParam? = null

    private var cameraMode = CameraMode.PHOTO
    private val isVideoMode: Boolean
        get() = cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.RAW_VIDEO
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
    private var videoQuality = VideoQuality.FHD_1080P
    private var videoFps = VideoFps.FPS_30
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
        setUpProControls()
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

        binding.modeRawVideo.setOnClickListener {
            if (cameraMode != CameraMode.RAW_VIDEO) {
                switchToMode(CameraMode.RAW_VIDEO)
            }
        }
    }

    private fun updateModePills(mode: CameraMode) {
        val selectedPill = when (mode) {
            CameraMode.PHOTO -> binding.modePhoto
            CameraMode.VIDEO -> binding.modeVideo
            CameraMode.RAW_VIDEO -> binding.modeRawVideo
        }

        val allPills = listOf(binding.modePhoto, binding.modeVideo, binding.modeRawVideo)
        for (pill in allPills) {
            if (pill == selectedPill) {
                pill.setBackgroundResource(R.drawable.mode_pill_selected)
                pill.setTextColor(Color.BLACK)
                pill.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                pill.background = null
                pill.setTextColor(ContextCompat.getColor(this, R.color.readout_dim))
                pill.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun switchToMode(mode: CameraMode) {
        if (videoRecordingManager.isRecording) return
        val prevMode = cameraMode
        cameraMode = mode
        updateModePills(mode)

        if (mode == CameraMode.PHOTO) {
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
            binding.timerButton.visibility = View.VISIBLE
            binding.videoQualityButton.visibility = View.GONE
            binding.rawControlBar.visibility = View.GONE
            binding.rawAdjustPanel.visibility = View.GONE

            camera?.let { proCameraManager.resetToAuto(it) }
            startCamera()
        } else if (mode == CameraMode.VIDEO) {
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

            binding.timerButton.visibility = View.GONE
            binding.videoQualityButton.visibility = View.VISIBLE
            binding.rawControlBar.visibility = View.GONE
            binding.rawAdjustPanel.visibility = View.GONE
            updateVideoQualityButton()

            checkAudioPermissionInfo()
            camera?.let { proCameraManager.resetToAuto(it) }
            if (prevMode == CameraMode.PHOTO) {
                startCamera()
            }
        } else { // CameraMode.RAW_VIDEO
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

            binding.timerButton.visibility = View.GONE
            binding.videoQualityButton.visibility = View.VISIBLE
            binding.rawControlBar.visibility = View.VISIBLE
            updateVideoQualityButton()
            updateProChips()

            checkAudioPermissionInfo()
            if (prevMode == CameraMode.PHOTO) {
                startCamera()
            } else {
                camera?.let { proCameraManager.apply(it) }
            }
        }
    }

    private fun setUpProControls() {
        binding.chipIso.setOnClickListener { selectProParam(ProParam.ISO) }
        binding.chipShutter.setOnClickListener { selectProParam(ProParam.SHUTTER) }
        binding.chipEv.setOnClickListener { selectProParam(ProParam.EV) }
        binding.chipFocus.setOnClickListener { selectProParam(ProParam.FOCUS) }
        binding.chipWb.setOnClickListener { selectProParam(ProParam.WB) }

        binding.chipAperture.setOnClickListener {
            val apertures = proCameraManager.availableApertures
            if (apertures.size > 1) {
                val current = proCameraManager.selectedAperture ?: apertures.first()
                val nextIdx = (apertures.indexOf(current) + 1) % apertures.size
                proCameraManager.setAperture(apertures[nextIdx], camera)
                updateProChips()
                toast("Aperture: ${proCameraManager.getApertureSummary()}")
            } else {
                toast("Fixed lens: ${proCameraManager.getApertureSummary()}. Adjust EV for exposure.")
            }
        }

        binding.buttonRawReset.setOnClickListener {
            camera?.let { cam ->
                proCameraManager.resetToAuto(cam)
            }
            activeProParam = null
            binding.rawAdjustPanel.visibility = View.GONE
            highlightActiveProChip(null)
            updateProChips()
            toast("All camera controls reset to AUTO")
        }

        binding.rawCloseButton.setOnClickListener {
            activeProParam = null
            binding.rawAdjustPanel.visibility = View.GONE
            highlightActiveProChip(null)
        }

        binding.rawAutoButton.setOnClickListener {
            val param = activeProParam ?: return@setOnClickListener
            when (param) {
                ProParam.ISO -> proCameraManager.setIso(null, camera)
                ProParam.SHUTTER -> proCameraManager.setShutterNanos(null, camera)
                ProParam.EV -> proCameraManager.setEvIndex(0, camera)
                ProParam.FOCUS -> proCameraManager.setFocusDistance(null, camera)
                ProParam.WB -> proCameraManager.setWbMode(null, camera)
            }
            updateProChips()
            configureAdjustPanelFor(param)
        }
    }

    private fun selectProParam(param: ProParam) {
        if (activeProParam == param && binding.rawAdjustPanel.isVisible) {
            activeProParam = null
            binding.rawAdjustPanel.visibility = View.GONE
            highlightActiveProChip(null)
            return
        }

        if (param == ProParam.FOCUS && !proCameraManager.isManualFocusSupported) {
            toast("Manual focus not supported by this lens")
            return
        }

        activeProParam = param
        highlightActiveProChip(param)
        binding.rawAdjustPanel.visibility = View.VISIBLE
        configureAdjustPanelFor(param)
    }

    private fun highlightActiveProChip(param: ProParam?) {
        val chips = listOf(
            ProParam.ISO to binding.chipIso,
            ProParam.SHUTTER to binding.chipShutter,
            ProParam.EV to binding.chipEv,
            ProParam.FOCUS to binding.chipFocus,
            ProParam.WB to binding.chipWb
        )
        for ((p, chip) in chips) {
            if (p == param) {
                chip.setBackgroundResource(R.drawable.pro_chip_selected_bg)
            } else {
                chip.setBackgroundResource(R.drawable.pro_chip_bg)
            }
        }
    }

    private fun updateProChips() {
        binding.chipIso.text = "ISO ${proCameraManager.getIsoSummary()}"
        binding.chipShutter.text = "S ${proCameraManager.getShutterSummary()}"
        binding.chipEv.text = String.format("EV %+.1f", proCameraManager.evIndex * 0.5f)
        binding.chipFocus.text = "FOCUS ${proCameraManager.getFocusSummary()}"
        binding.chipWb.text = "WB ${proCameraManager.getWbSummary()}"
        binding.chipAperture.text = proCameraManager.getApertureSummary()
    }

    private fun configureAdjustPanelFor(param: ProParam) {
        binding.rawPresetsContainer.removeAllViews()
        binding.rawSlider.clearOnChangeListeners()

        when (param) {
            ProParam.ISO -> {
                val isManual = proCameraManager.isManualIso
                binding.rawAdjustTitle.text = "ISO: ${proCameraManager.getIsoSummary()}"
                updateAutoButtonState(!isManual)

                val minIso = proCameraManager.isoRange.lower.toFloat()
                val maxIso = proCameraManager.isoRange.upper.toFloat()
                binding.rawSlider.valueFrom = minIso
                binding.rawSlider.valueTo = maxIso
                binding.rawSlider.stepSize = 50f
                val currentVal = (if (isManual) proCameraManager.manualIso.toFloat() else 100f).coerceIn(minIso, maxIso)
                binding.rawSlider.value = currentVal

                binding.rawSlider.addOnChangeListener { _, value, fromUser ->
                    if (fromUser) {
                        proCameraManager.setIso(value.roundToInt(), camera)
                        binding.rawAdjustTitle.text = "ISO: ${proCameraManager.manualIso}"
                        binding.chipIso.text = "ISO ${proCameraManager.manualIso}"
                        updateAutoButtonState(false)
                    }
                }

                for (iso in ProCameraManager.COMMON_ISOS) {
                    if (iso in proCameraManager.isoRange.lower..proCameraManager.isoRange.upper) {
                        val isSelected = isManual && proCameraManager.manualIso == iso
                        addPresetButton("$iso", isSelected) {
                            proCameraManager.setIso(iso, camera)
                            configureAdjustPanelFor(ProParam.ISO)
                            updateProChips()
                        }
                    }
                }
            }

            ProParam.SHUTTER -> {
                val isManual = proCameraManager.isManualShutter
                binding.rawAdjustTitle.text = "SHUTTER: ${proCameraManager.getShutterSummary()}"
                updateAutoButtonState(!isManual)

                val presets = ProCameraManager.COMMON_SHUTTERS
                binding.rawSlider.valueFrom = 0f
                binding.rawSlider.valueTo = (presets.size - 1).toFloat()
                binding.rawSlider.stepSize = 1f

                val currentIdx = presets.indexOfFirst { it.nanos == proCameraManager.manualShutterNanos }.takeIf { it >= 0 } ?: 4
                binding.rawSlider.value = currentIdx.toFloat()

                binding.rawSlider.addOnChangeListener { _, value, fromUser ->
                    if (fromUser) {
                        val preset = presets[value.toInt()]
                        proCameraManager.setShutterNanos(preset.nanos, camera)
                        binding.rawAdjustTitle.text = "SHUTTER: ${preset.label}s"
                        binding.chipShutter.text = "S ${preset.label}s"
                        updateAutoButtonState(false)
                    }
                }

                for (preset in presets) {
                    val isSelected = isManual && proCameraManager.manualShutterNanos == preset.nanos
                    addPresetButton(preset.label, isSelected) {
                        proCameraManager.setShutterNanos(preset.nanos, camera)
                        configureAdjustPanelFor(ProParam.SHUTTER)
                        updateProChips()
                    }
                }
            }

            ProParam.EV -> {
                val currentEv = proCameraManager.evIndex * 0.5f
                binding.rawAdjustTitle.text = String.format("EXPOSURE: %+.1f EV", currentEv)
                updateAutoButtonState(proCameraManager.evIndex == 0)

                binding.rawSlider.valueFrom = -6f
                binding.rawSlider.valueTo = 6f
                binding.rawSlider.stepSize = 1f
                binding.rawSlider.value = proCameraManager.evIndex.toFloat().coerceIn(-6f, 6f)

                binding.rawSlider.addOnChangeListener { _, value, fromUser ->
                    if (fromUser) {
                        val idx = value.toInt()
                        proCameraManager.setEvIndex(idx, camera)
                        val ev = idx * 0.5f
                        binding.rawAdjustTitle.text = String.format("EXPOSURE: %+.1f EV", ev)
                        binding.chipEv.text = String.format("EV %+.1f", ev)
                        updateAutoButtonState(idx == 0)
                    }
                }

                val evPresets = listOf(-4 to "-2.0", -2 to "-1.0", -1 to "-0.5", 0 to "0.0", 1 to "+0.5", 2 to "+1.0", 4 to "+2.0")
                for ((idx, label) in evPresets) {
                    val isSelected = proCameraManager.evIndex == idx
                    addPresetButton(label, isSelected) {
                        proCameraManager.setEvIndex(idx, camera)
                        configureAdjustPanelFor(ProParam.EV)
                        updateProChips()
                    }
                }
            }

            ProParam.FOCUS -> {
                val isManual = proCameraManager.isManualFocus
                binding.rawAdjustTitle.text = "FOCUS: ${proCameraManager.getFocusSummary()}"
                updateAutoButtonState(!isManual)

                val maxDist = proCameraManager.minFocusDistance.takeIf { it > 0f } ?: 10f
                binding.rawSlider.valueFrom = 0f
                binding.rawSlider.valueTo = maxDist
                binding.rawSlider.stepSize = maxDist / 50f
                val currentDist = (if (isManual) proCameraManager.manualFocusDistance else 0f).coerceIn(0f, maxDist)
                binding.rawSlider.value = currentDist

                binding.rawSlider.addOnChangeListener { _, value, fromUser ->
                    if (fromUser) {
                        proCameraManager.setFocusDistance(value, camera)
                        binding.rawAdjustTitle.text = "FOCUS: ${proCameraManager.getFocusSummary()}"
                        binding.chipFocus.text = "FOCUS ${proCameraManager.getFocusSummary()}"
                        updateAutoButtonState(false)
                    }
                }

                val focusPresets = listOf(
                    "∞" to 0f,
                    "2.0m" to (maxDist * 0.2f),
                    "1.0m" to (maxDist * 0.4f),
                    "0.5m" to (maxDist * 0.7f),
                    "Macro" to maxDist
                )
                for ((label, dist) in focusPresets) {
                    val isSelected = isManual && Math.abs(proCameraManager.manualFocusDistance - dist) < 0.2f
                    addPresetButton(label, isSelected) {
                        proCameraManager.setFocusDistance(dist, camera)
                        configureAdjustPanelFor(ProParam.FOCUS)
                        updateProChips()
                    }
                }
            }

            ProParam.WB -> {
                val isManual = proCameraManager.isManualWb
                binding.rawAdjustTitle.text = "WHITE BALANCE: ${proCameraManager.getWbSummary()}"
                updateAutoButtonState(!isManual)

                val presets = ProCameraManager.COMMON_WB_MODES
                binding.rawSlider.valueFrom = 0f
                binding.rawSlider.valueTo = (presets.size - 1).toFloat()
                binding.rawSlider.stepSize = 1f
                val currentIdx = presets.indexOfFirst { it.mode == proCameraManager.wbMode }.takeIf { it >= 0 } ?: 2
                binding.rawSlider.value = currentIdx.toFloat()

                binding.rawSlider.addOnChangeListener { _, value, fromUser ->
                    if (fromUser) {
                        val preset = presets[value.toInt()]
                        proCameraManager.setWbMode(preset.mode, camera)
                        binding.rawAdjustTitle.text = "WHITE BALANCE: ${preset.kelvin} (${preset.label})"
                        binding.chipWb.text = "WB ${preset.kelvin}"
                        updateAutoButtonState(false)
                    }
                }

                for (preset in presets) {
                    val isSelected = isManual && proCameraManager.wbMode == preset.mode
                    addPresetButton("${preset.kelvin} ${preset.label}", isSelected) {
                        proCameraManager.setWbMode(preset.mode, camera)
                        configureAdjustPanelFor(ProParam.WB)
                        updateProChips()
                    }
                }
            }
        }
    }

    private fun updateAutoButtonState(isAuto: Boolean) {
        if (isAuto) {
            binding.rawAutoButton.setBackgroundResource(R.drawable.mode_pill_selected)
            binding.rawAutoButton.setTextColor(Color.BLACK)
        } else {
            binding.rawAutoButton.setBackgroundResource(R.drawable.zoom_pill)
            binding.rawAutoButton.setTextColor(Color.WHITE)
        }
    }

    private fun addPresetButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
        val button = TextView(this).apply {
            this.text = text
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            val padH = dpToPx(8)
            val padV = dpToPx(3)
            setPadding(padH, padV, padH, padV)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpToPx(6) }
            layoutParams = lp
            if (isSelected) {
                setBackgroundResource(R.drawable.mode_pill_selected)
                setTextColor(Color.BLACK)
            } else {
                setBackgroundResource(R.drawable.pro_chip_bg)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.readout))
            }
            setOnClickListener { onClick() }
        }
        binding.rawPresetsContainer.addView(button)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

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
        videoRecordingManager.startRecording(width, height, fps = videoFps, quality = videoQuality)
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
                binding.videoQualityButton.isEnabled = true
                binding.videoQualityButton.alpha = 1f
                binding.filterCarousel.isEnabled = true
                binding.filterCarousel.alpha = 1f
                binding.categoryTabs.isEnabled = true
                binding.categoryTabs.alpha = 1f
                binding.rawControlBar.isEnabled = true
                binding.rawControlBar.alpha = 1f
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
                binding.videoQualityButton.isEnabled = false
                binding.videoQualityButton.alpha = 0.4f
                binding.filterCarousel.isEnabled = false
                binding.filterCarousel.alpha = 0.4f
                binding.categoryTabs.isEnabled = false
                binding.categoryTabs.alpha = 0.4f
                binding.rawControlBar.isEnabled = false
                binding.rawControlBar.alpha = 0.4f
                binding.rawAdjustPanel.visibility = View.GONE
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
                binding.videoQualityButton.isEnabled = false
                binding.videoQualityButton.alpha = 0.4f
                binding.filterCarousel.isEnabled = false
                binding.filterCarousel.alpha = 0.4f
                binding.categoryTabs.isEnabled = false
                binding.categoryTabs.alpha = 0.4f
                binding.rawControlBar.isEnabled = false
                binding.rawControlBar.alpha = 0.4f
                binding.rawAdjustPanel.visibility = View.GONE
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

                camera?.let { cam ->
                    proCameraManager.updateCapabilities(cam)
                    if (cameraMode == CameraMode.RAW_VIDEO) {
                        proCameraManager.apply(cam)
                        updateProChips()
                    }
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

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bindVideoUseCases(provider: ProcessCameraProvider) {
        val targetSize = videoQuality.targetSize()
        val resolutionStrategy = ResolutionStrategy(
            targetSize,
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
        )
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(resolutionStrategy)
            .build()

        val analysisBuilder = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(resolutionSelector)

        try {
            val extender = Camera2Interop.Extender(analysisBuilder)
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(videoFps.fps, videoFps.fps)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting FPS range on camera", e)
        }

        val analysis = analysisBuilder.build()
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
            if (isVideoMode) {
                // Torch mode for video
                camera?.cameraControl?.enableTorch(flash == FlashChoice.ON)
            }
            binding.flashButton.setImageResource(flash.icon)
            binding.flashButton.alpha = if (flash == FlashChoice.OFF) 0.65f else 1f
            toast(getString(R.string.flash_toast, flash.label))
        }
        binding.flashButton.alpha = 0.65f

        binding.timerButton.setOnClickListener {
            if (isVideoMode) {
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

        binding.videoQualityButton.setOnClickListener { showQualityFpsMenu(it) }
        updateVideoQualityButton()

        binding.overflowButton.setOnClickListener { showOverflow(it) }
    }

    private fun updateVideoQualityButton() {
        binding.videoQualityButton.text = "${videoQuality.label} · ${videoFps.fps}fps"
    }

    private fun showQualityFpsMenu(anchor: View) {
        if (videoRecordingManager.isRecording) {
            toast("Cannot change video quality while recording")
            return
        }
        val popup = PopupMenu(this, anchor)
        // Resolutions
        VideoQuality.entries.forEachIndexed { index, q ->
            val title = if (q == videoQuality) "✓ ${q.label}" else "    ${q.label}"
            popup.menu.add(1, index, index, title)
        }
        // Frame rates (offset by 100)
        VideoFps.entries.forEachIndexed { index, f ->
            val title = if (f == videoFps) "✓ ${f.label}" else "    ${f.label}"
            popup.menu.add(2, 100 + index, 100 + index, title)
        }
        popup.setOnMenuItemClickListener { item ->
            val id = item.itemId
            if (id < 100) {
                val q = VideoQuality.entries.getOrNull(id) ?: return@setOnMenuItemClickListener false
                videoQuality = q
                toast("Video Quality: ${q.label}")
            } else {
                val f = VideoFps.entries.getOrNull(id - 100) ?: return@setOnMenuItemClickListener false
                videoFps = f
                toast("Frame Rate: ${f.label}")
            }
            updateVideoQualityButton()
            if (isVideoMode) {
                startCamera()
            }
            true
        }
        popup.show()
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
                        if (isVideoMode) {
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
                        if (isVideoMode) {
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

        if (isVideoMode && videoRecordingManager.isRecording) {
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
