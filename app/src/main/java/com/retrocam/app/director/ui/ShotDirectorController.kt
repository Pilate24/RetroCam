package com.retrocam.app.director.ui

import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.retrocam.app.R
import com.retrocam.app.databinding.LayoutShotDirectorPanelBinding
import com.retrocam.app.director.analyzer.FrameAnalysisResult
import com.retrocam.app.director.model.AspectRatioOption
import com.retrocam.app.director.model.CameraCapabilities
import com.retrocam.app.director.model.CompositionGuideConfig
import com.retrocam.app.director.model.ShotIdea
import com.retrocam.app.director.model.ShotRecommendation

/**
 * Controller linking the Shot Director UI panel, live preview overlays,
 * recommendation engine, and camera interactions.
 */
class ShotDirectorController(
    private val activity: AppCompatActivity,
    private val panelBinding: LayoutShotDirectorPanelBinding,
    private val overlayView: ShotDirectorOverlayView
) {
    var isEnabled: Boolean = true
        set(value) {
            field = value
            updateVisibility()
        }

    var isExpanded: Boolean = false
        set(value) {
            field = value
            panelBinding.directorExpandedCard.visibility = if (value && isEnabled) View.VISIBLE else View.GONE
            panelBinding.directorExpandIcon.rotation = if (value) 180f else 0f
        }

    var currentCapabilities: CameraCapabilities = CameraCapabilities.DEFAULT_FALLBACK
        set(value) {
            field = value
            overlayView.isHorizonSensorAvailable = isHorizonSensorAvailable
        }

    var isHorizonSensorAvailable: Boolean = false
        set(value) {
            field = value
            overlayView.isHorizonSensorAvailable = value
        }

    var currentRecommendation: ShotRecommendation? = null
        private set

    var onApplyZoomRequested: ((Float) -> Boolean)? = null
    var onApplyAspectRequested: ((AspectRatioOption) -> Unit)? = null

    init {
        setupListeners()
        updateVisibility()
    }

    private fun setupListeners() {
        // Tap pill to toggle expanded card
        panelBinding.directorHeaderPill.setOnClickListener {
            isExpanded = !isExpanded
        }

        // Close/minimize button inside expanded card
        panelBinding.directorCloseCardButton.setOnClickListener {
            isExpanded = false
        }

        // Apply Framing Button
        panelBinding.directorApplyFramingButton.setOnClickListener {
            val rec = currentRecommendation ?: run {
                toast("No active recommendation to apply")
                return@setOnClickListener
            }
            applyRecommendation(rec)
        }

        // 3 Shot Ideas Button
        panelBinding.directorThreeIdeasButton.setOnClickListener {
            val rec = currentRecommendation ?: return@setOnClickListener
            if (rec.alternativeIdeas.isEmpty()) {
                toast("No alternative shot ideas available for this scene")
                return@setOnClickListener
            }

            val sheet = ShotIdeasBottomSheet.newInstance(rec.alternativeIdeas)
            sheet.onIdeaSelected = { idea ->
                applyShotIdea(idea)
            }
            sheet.show(activity.supportFragmentManager, ShotIdeasBottomSheet.TAG)
        }

        // Compare Ratios Button
        panelBinding.directorCompareRatiosButton.setOnClickListener {
            val current = overlayView.config.isCompareRatiosActive
            val newActive = !current
            overlayView.config = overlayView.config.copy(isCompareRatiosActive = newActive)
            panelBinding.directorCompareRatiosButton.text = if (newActive) "EXIT COMPARE" else "COMPARE"
            toast(if (newActive) "Comparing 4:3, 4:5, and 9:16 framing boundaries" else "Exited ratio comparison")
        }

        // Guides & Overlays Menu Button
        panelBinding.directorGuidesMenuButton.setOnClickListener { anchor ->
            showGuidesPopupMenu(anchor)
        }
    }

    private fun showGuidesPopupMenu(anchor: View) {
        val popup = PopupMenu(activity, anchor)
        val config = overlayView.config

        popup.menu.add(0, 1, 1, "Rule of Thirds Grid").apply {
            isCheckable = true
            isChecked = config.showRuleOfThirds
        }
        popup.menu.add(0, 2, 2, "Center Symmetry Guide").apply {
            isCheckable = true
            isChecked = config.showCenterSymmetry
        }
        popup.menu.add(0, 3, 3, "Horizon Level Gauge" + if (!isHorizonSensorAvailable) " (Sensor Unavailable)" else "").apply {
            isCheckable = true
            isChecked = config.showHorizonGuide
            isEnabled = isHorizonSensorAvailable
        }
        popup.menu.add(0, 4, 4, "Portrait Silhouette Guide").apply {
            isCheckable = true
            isChecked = config.showPortraitGuide
        }
        popup.menu.add(0, 5, 5, "Crop Boundaries Scrim").apply {
            isCheckable = true
            isChecked = config.showCropBoundaries
        }
        popup.menu.add(0, 6, 6, "Subject / Face Boxes").apply {
            isCheckable = true
            isChecked = config.showSubjectBoxes
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> overlayView.config = overlayView.config.copy(showRuleOfThirds = !item.isChecked)
                2 -> overlayView.config = overlayView.config.copy(showCenterSymmetry = !item.isChecked)
                3 -> {
                    if (isHorizonSensorAvailable) {
                        overlayView.config = overlayView.config.copy(showHorizonGuide = !item.isChecked)
                    } else {
                        toast("Motion / orientation sensors are not available on this device")
                    }
                }
                4 -> overlayView.config = overlayView.config.copy(showPortraitGuide = !item.isChecked)
                5 -> overlayView.config = overlayView.config.copy(showCropBoundaries = !item.isChecked)
                6 -> overlayView.config = overlayView.config.copy(showSubjectBoxes = !item.isChecked)
            }
            true
        }
        popup.show()
    }

    /**
     * Updates the recommendation UI with a freshly calculated recommendation.
     */
    fun updateRecommendation(
        recommendation: ShotRecommendation,
        analysis: FrameAnalysisResult
    ) {
        if (!isEnabled) return
        currentRecommendation = recommendation

        // Update Pill
        panelBinding.directorPillIcon.text = recommendation.sceneCategory.icon
        panelBinding.directorPillSummary.text = "· ${recommendation.shotType.displayName} · ${recommendation.recommendedAspect.displayLabel}"

        // Update Card
        panelBinding.directorSceneBadge.text = "${recommendation.sceneCategory.icon} ${recommendation.sceneCategory.title.uppercase()}"
        panelBinding.directorConfidenceText.text = "${recommendation.formattedConfidence()} CONFIDENCE"
        panelBinding.directorShotTitle.text = "Recommended: ${recommendation.shotType.displayName}"

        panelBinding.directorLensChip.text = "LENS: ${recommendation.recommendedLens.getLabel()}"
        panelBinding.directorZoomChip.text = "ZOOM: ${recommendation.recommendedZoom?.let { String.format("%.1f×", it) } ?: "1.0×"}"
        panelBinding.directorAspectChip.text = "ASPECT: ${recommendation.recommendedAspect.displayLabel}"

        panelBinding.directorPositionAdvice.text = "📍 ${recommendation.cameraPositionAdvice}"
        panelBinding.directorCompositionTip.text = "💡 ${recommendation.compositionTip}"
        panelBinding.directorReason.text = "🔍 ${recommendation.reason}"

        if (recommendation.cropWarning != null) {
            panelBinding.directorCropWarning.visibility = View.VISIBLE
            panelBinding.directorCropWarning.text = "⚠️ ${recommendation.cropWarning}"
        } else {
            panelBinding.directorCropWarning.visibility = View.GONE
        }

        // Update overlay view with active aspect ratio and detected faces
        overlayView.activeAspectOption = recommendation.recommendedAspect
        overlayView.detectedFaces = analysis.detectedFaces
    }

    /**
     * Applies the recommendation's verified zoom and aspect ratio to the camera session.
     */
    fun applyRecommendation(rec: ShotRecommendation) {
        // 1. Apply zoom if recommended and supported
        val zoom = rec.recommendedZoom
        var zoomApplied = false
        if (zoom != null) {
            val clamped = currentCapabilities.clampZoom(zoom)
            zoomApplied = onApplyZoomRequested?.invoke(clamped) ?: false
        }

        // 2. Apply aspect ratio
        onApplyAspectRequested?.invoke(rec.recommendedAspect)
        overlayView.activeAspectOption = rec.recommendedAspect

        // 3. User feedback
        val zoomMsg = if (zoomApplied && zoom != null) "${String.format("%.1f×", zoom)} zoom" else "framing"
        val feedback = StringBuilder("Applied $zoomMsg with ${rec.recommendedAspect.displayLabel} aspect ratio.")

        if (rec.requiresPhysicalMovement && rec.physicalMovementAdvice != null) {
            feedback.append("\n👉 Note: ${rec.physicalMovementAdvice}")
        }

        toast(feedback.toString())
    }

    /**
     * Applies a specific alternative shot idea selected by the user.
     */
    fun applyShotIdea(idea: ShotIdea) {
        val zoom = idea.zoomRatio
        if (zoom != null) {
            val clamped = currentCapabilities.clampZoom(zoom)
            onApplyZoomRequested?.invoke(clamped)
        }

        onApplyAspectRequested?.invoke(idea.aspectOption)
        overlayView.activeAspectOption = idea.aspectOption

        toast("Applied \"${idea.name}\" framing (${idea.aspectOption.displayLabel})")
    }

    private fun updateVisibility() {
        panelBinding.directorPanelRoot.visibility = if (isEnabled) View.VISIBLE else View.GONE
        overlayView.visibility = if (isEnabled) View.VISIBLE else View.GONE
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
