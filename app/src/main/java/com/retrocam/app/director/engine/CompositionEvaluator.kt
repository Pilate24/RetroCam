package com.retrocam.app.director.engine

import com.retrocam.app.director.analyzer.FrameAnalysisResult
import com.retrocam.app.director.model.SceneCategory
import kotlin.math.abs

/**
 * Evaluates photographic composition metrics:
 * rule-of-thirds alignment, headroom balance, horizon tilt, and physical positioning.
 */
object CompositionEvaluator {

    data class CompositionAssessment(
        val cameraPositionAdvice: String,
        val compositionTip: String,
        val reason: String,
        val requiresPhysicalMovement: Boolean,
        val physicalMovementAdvice: String?
    )

    fun evaluate(
        analysis: FrameAnalysisResult,
        deviceRollDegrees: Float,
        isHorizonSensorAvailable: Boolean
    ): CompositionAssessment {
        // 1. Horizon Tilt Assessment (only if hardware sensor is available!)
        val isTilted = isHorizonSensorAvailable && abs(deviceRollDegrees) > 2.0f
        val tiltDirection = if (deviceRollDegrees > 0) "left" else "right"

        when (analysis.sceneCategory) {
            SceneCategory.PORTRAIT -> {
                val faces = analysis.detectedFaces
                val primaryFace = faces.firstOrNull()

                val headroom = analysis.headroomFraction
                val isHeadroomTooLarge = headroom > 0.35f
                val isHeadroomTooTight = headroom < 0.10f

                val (positionAdvice, movement, movementAdvice) = when {
                    isHeadroomTooTight -> Triple(
                        "Tilt up slightly or step back ~0.5m",
                        true,
                        "Step back half a step to preserve headroom above hair"
                    )
                    isHeadroomTooLarge -> Triple(
                        "Lower camera or step closer",
                        true,
                        "Move closer or zoom slightly to fill upper frame"
                    )
                    primaryFace != null && abs(primaryFace.centerX - 0.5f) < 0.1f -> Triple(
                        "Hold camera at subject's eye level",
                        false,
                        null
                    )
                    else -> Triple(
                        "Align subject's eyes along the upper third guideline",
                        false,
                        null
                    )
                }

                val tip = when {
                    isHeadroomTooTight -> "Leave breathing space between the head and top edge."
                    isHeadroomTooLarge -> "Position the eyes near the upper third line for natural balance."
                    else -> "Place the subject along a vertical rule-of-thirds line for balanced depth."
                }

                val reason = "Eye-level perspective creates an authentic connection while avoiding distortion."

                return CompositionAssessment(
                    cameraPositionAdvice = if (isTilted) "Level camera ($tiltDirection tilt) · $positionAdvice" else positionAdvice,
                    compositionTip = tip,
                    reason = reason,
                    requiresPhysicalMovement = movement,
                    physicalMovementAdvice = movementAdvice
                )
            }

            SceneCategory.GROUP_PHOTO -> {
                val spread = analysis.horizontalSpreadFraction
                val faces = analysis.detectedFaces
                val outerLeft = faces.minOfOrNull { it.left } ?: 0.2f
                val outerRight = faces.maxOfOrNull { it.right } ?: 0.8f

                val isCrowded = outerLeft < 0.10f || outerRight > 0.90f
                val (pos, movement, mAdv) = if (isCrowded) {
                    Triple(
                        "Step back 1-2 steps to give the group breathing room",
                        true,
                        "Step back to prevent cutting off outer people"
                    )
                } else {
                    Triple(
                        "Hold camera at chest level to keep front and back rows in focus",
                        false,
                        null
                    )
                }

                return CompositionAssessment(
                    cameraPositionAdvice = if (isTilted) "Level camera ($tiltDirection tilt) · $pos" else pos,
                    compositionTip = "Ensure all group members have comfortable margins from the edges.",
                    reason = "Wide margins prevent wide-angle optical edge distortion on group members.",
                    requiresPhysicalMovement = movement,
                    physicalMovementAdvice = mAdv
                )
            }

            SceneCategory.LANDSCAPE -> {
                val hasHorizon = analysis.hasStrongHorizon
                val horizonY = analysis.horizonPositionFraction ?: 0.5f

                val advice = when {
                    isTilted -> "Align device with horizon line (currently ${String.format("%.1f", abs(deviceRollDegrees))}° off)"
                    hasHorizon && horizonY in 0.25f..0.45f -> "Excellent horizon placement on upper third (highlights foreground)"
                    hasHorizon && horizonY in 0.55f..0.75f -> "Excellent horizon placement on lower third (highlights sky)"
                    else -> "Position the horizon along the lower third to emphasize the sky vista"
                }

                return CompositionAssessment(
                    cameraPositionAdvice = advice,
                    compositionTip = "Follow the rule of thirds: place the horizon line on the upper or lower third, never dead center.",
                    reason = "Offsetting the horizon gives the scene depth and a clear visual priority.",
                    requiresPhysicalMovement = false,
                    physicalMovementAdvice = null
                )
            }

            SceneCategory.ARCHITECTURE -> {
                val advice = if (analysis.verticalEdgeDensity > 0.35f) {
                    "Step back and tilt up for towering perspective, or keep sensor perpendicular to reduce converging keystones"
                } else {
                    "Align architectural facade with center symmetry guide"
                }

                return CompositionAssessment(
                    cameraPositionAdvice = if (isTilted) "Level camera to square vertical lines · $advice" else advice,
                    compositionTip = "Keep camera level to keep building vertical walls straight without converging keystones.",
                    reason = "Eliminating vertical convergence produces clean, professional architectural lines.",
                    requiresPhysicalMovement = false,
                    physicalMovementAdvice = null
                )
            }

            SceneCategory.FOOD, SceneCategory.PRODUCT -> {
                return CompositionAssessment(
                    cameraPositionAdvice = "Hold camera directly overhead (90° flat lay) or at 45° angle",
                    compositionTip = "Center the main dish with subtle diagonal leading utensils.",
                    reason = "A 45-degree angle mimics how a diner experiences a meal.",
                    requiresPhysicalMovement = true,
                    physicalMovementAdvice = "Move camera 30-40cm away from the subject for optimal macro focus"
                )
            }

            SceneCategory.SUNSET_SUNRISE -> {
                return CompositionAssessment(
                    cameraPositionAdvice = if (isTilted) "Level camera ($tiltDirection tilt) with horizon" else "Hold camera steady; position sun along intersection point",
                    compositionTip = "Expose for bright highlights near the sun to preserve vibrant color saturation.",
                    reason = "Prevents blown-out sky highlights and captures rich twilight gradients.",
                    requiresPhysicalMovement = false,
                    physicalMovementAdvice = null
                )
            }

            SceneCategory.CLOSE_UP -> {
                return CompositionAssessment(
                    cameraPositionAdvice = "Move close to fill the frame while maintaining minimum focus distance",
                    compositionTip = "Lock focus on the highest contrast texture or leading edge.",
                    reason = "Macro framing emphasizes fine textures and organic detail.",
                    requiresPhysicalMovement = true,
                    physicalMovementAdvice = "Physically adjust distance until the subject is crisp and sharp"
                )
            }

            SceneCategory.STREET_TRAVEL, SceneCategory.UNKNOWN_OR_MIXED -> {
                return CompositionAssessment(
                    cameraPositionAdvice = if (isTilted) "Level camera with horizon line" else "Hold camera steady at eye level",
                    compositionTip = "Use rule of thirds grid: place points of interest on line intersections.",
                    reason = "Classic third-grid alignment creates natural visual movement across the photograph.",
                    requiresPhysicalMovement = false,
                    physicalMovementAdvice = null
                )
            }
        }
    }
}
