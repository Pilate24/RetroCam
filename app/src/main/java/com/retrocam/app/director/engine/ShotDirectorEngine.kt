package com.retrocam.app.director.engine

import com.retrocam.app.director.analyzer.FrameAnalysisResult
import com.retrocam.app.director.model.AspectRatioOption
import com.retrocam.app.director.model.CameraCapabilities
import com.retrocam.app.director.model.PhysicalLensInfo
import com.retrocam.app.director.model.PhysicalLensType
import com.retrocam.app.director.model.SceneCategory
import com.retrocam.app.director.model.ShotIdea
import com.retrocam.app.director.model.ShotRecommendation
import com.retrocam.app.director.model.ShotType
import kotlin.math.min

/**
 * Recommendation engine that coordinates verified device capabilities,
 * scene analysis, composition requirements, and aspect ratio.
 */
object ShotDirectorEngine {

    /**
     * Evaluates live scene and device capabilities to generate a unified ShotRecommendation.
     *
     * @param capabilities Verified device hardware capabilities (never hardcoded).
     * @param analysis Computer vision analysis metrics from preview frame.
     * @param isUprightPortrait True if the preview/device is currently in portrait orientation.
     * @param deviceRollDegrees Live horizon roll angle from hardware sensor (if available).
     * @param isHorizonSensorAvailable Whether real orientation/motion sensors are active.
     */
    fun generateRecommendation(
        capabilities: CameraCapabilities,
        analysis: FrameAnalysisResult,
        isUprightPortrait: Boolean,
        deviceRollDegrees: Float = 0f,
        isHorizonSensorAvailable: Boolean = false
    ): ShotRecommendation {
        // 1. Determine recommended lens (strictly from verified hardware)
        val recommendedLens = determineRecommendedLens(capabilities, analysis.sceneCategory)

        // 2. Determine recommended zoom (validated and clamped to hardware bounds)
        val recommendedZoom = determineRecommendedZoom(capabilities, analysis)

        // 3. Determine recommended aspect ratio
        val recommendedAspect = AspectRatioEvaluator.recommendAspectRatio(analysis, isUprightPortrait)

        // 4. Verify crop safety (warn if crop would clip people/faces)
        val sourceAspect = if (isUprightPortrait) 0.75f else 1.333f
        val cropWarning = AspectRatioEvaluator.checkSubjectClipping(
            targetOption = recommendedAspect,
            faces = analysis.detectedFaces,
            sourceAspect = sourceAspect
        )

        // 5. Evaluate composition, camera positioning, and physical movement
        val comp = CompositionEvaluator.evaluate(
            analysis = analysis,
            deviceRollDegrees = deviceRollDegrees,
            isHorizonSensorAvailable = isHorizonSensorAvailable
        )

        // 6. Categorize shot type
        val shotType = when (analysis.sceneCategory) {
            SceneCategory.PORTRAIT -> ShotType.PORTRAIT
            SceneCategory.GROUP_PHOTO -> ShotType.BALANCED
            SceneCategory.LANDSCAPE -> ShotType.WIDE
            SceneCategory.ARCHITECTURE -> if (analysis.verticalEdgeDensity > 0.35f) ShotType.CINEMATIC else ShotType.SYMMETRIC
            SceneCategory.FOOD, SceneCategory.PRODUCT, SceneCategory.CLOSE_UP -> ShotType.DETAIL
            SceneCategory.SUNSET_SUNRISE -> ShotType.CINEMATIC
            SceneCategory.STREET_TRAVEL, SceneCategory.UNKNOWN_OR_MIXED -> ShotType.BALANCED
        }

        // 7. Generate 3 alternative shot ideas
        val alternativeIdeas = generateThreeShotIdeas(
            capabilities = capabilities,
            analysis = analysis,
            isUprightPortrait = isUprightPortrait
        )

        return ShotRecommendation(
            sceneCategory = analysis.sceneCategory,
            shotType = shotType,
            recommendedLens = recommendedLens,
            recommendedZoom = recommendedZoom,
            recommendedAspect = recommendedAspect,
            cameraPositionAdvice = comp.cameraPositionAdvice,
            compositionTip = comp.compositionTip,
            reason = comp.reason,
            confidence = analysis.confidence,
            alternativeIdeas = alternativeIdeas,
            cropWarning = cropWarning,
            requiresPhysicalMovement = comp.requiresPhysicalMovement,
            physicalMovementAdvice = comp.physicalMovementAdvice
        )
    }

    /**
     * Chooses the optimal lens strictly from verified available lenses on the device.
     */
    private fun determineRecommendedLens(
        capabilities: CameraCapabilities,
        scene: SceneCategory
    ): PhysicalLensInfo {
        val ultraWide = capabilities.verifiedLenses.firstOrNull { it.lensType == PhysicalLensType.ULTRA_WIDE }
        val telephoto = capabilities.verifiedLenses.firstOrNull { it.lensType == PhysicalLensType.TELEPHOTO }
        val main = capabilities.verifiedLenses.firstOrNull { it.lensType == PhysicalLensType.MAIN } ?: capabilities.activeLens

        return when (scene) {
            SceneCategory.LANDSCAPE, SceneCategory.ARCHITECTURE -> {
                // Prefer ultra-wide if actually available on the device; otherwise main camera
                ultraWide ?: main
            }
            SceneCategory.PORTRAIT, SceneCategory.CLOSE_UP -> {
                // Prefer telephoto for natural portrait perspective if verified; otherwise main camera
                telephoto ?: main
            }
            else -> main
        }
    }

    /**
     * Calculates an optimal zoom level, strictly validated against real hardware bounds.
     * Never recommends an out-of-range zoom ratio.
     */
    private fun determineRecommendedZoom(
        capabilities: CameraCapabilities,
        analysis: FrameAnalysisResult
    ): Float? {
        val minZ = capabilities.minZoomRatio
        val maxZ = capabilities.maxZoomRatio

        // If camera only supports 1x (no optical/digital zoom), return null or 1.0f
        if (maxZ <= minZ * 1.05f) {
            return null
        }

        val targetZoom = when (analysis.sceneCategory) {
            SceneCategory.PORTRAIT -> {
                // Recommend 1.5x - 2.0x for flattering portrait focal length without wide distortion
                val desired = 1.5f
                if (desired in minZ..maxZ) desired else minZ
            }
            SceneCategory.GROUP_PHOTO -> {
                // Keep at minimum / wide to fit everyone
                minZ
            }
            SceneCategory.CLOSE_UP, SceneCategory.FOOD -> {
                // Slight 1.3x - 1.5x zoom to isolate subject if supported
                val desired = 1.4f
                if (desired in minZ..maxZ) desired else minZ
            }
            SceneCategory.LANDSCAPE -> {
                minZ
            }
            else -> {
                1.0f.coerceIn(minZ, maxZ)
            }
        }

        return capabilities.clampZoom(targetZoom)
    }

    /**
     * Generates three distinctly different, valid shot ideas for the scene,
     * validated against actual hardware capabilities.
     */
    fun generateThreeShotIdeas(
        capabilities: CameraCapabilities,
        analysis: FrameAnalysisResult,
        isUprightPortrait: Boolean
    ): List<ShotIdea> {
        val minZ = capabilities.minZoomRatio
        val maxZ = capabilities.maxZoomRatio
        val mainLens = capabilities.verifiedLenses.firstOrNull { it.lensType == PhysicalLensType.MAIN } ?: capabilities.activeLens
        val ultraWideLens = capabilities.verifiedLenses.firstOrNull { it.lensType == PhysicalLensType.ULTRA_WIDE } ?: mainLens
        val telephotoLens = capabilities.verifiedLenses.firstOrNull { it.lensType == PhysicalLensType.TELEPHOTO } ?: mainLens

        val zoomTight = if (maxZ >= 1.5f) capabilities.clampZoom(1.5f) else null
        val zoomMedium = if (maxZ >= 1.2f) capabilities.clampZoom(1.2f) else null

        return when (analysis.sceneCategory) {
            SceneCategory.PORTRAIT -> listOf(
                ShotIdea(
                    name = "Tight Facial Portrait",
                    visualEffect = "Isolates facial emotion and expressions with soft background",
                    lens = telephotoLens,
                    zoomRatio = zoomTight,
                    aspectOption = AspectRatioOption.RATIO_4_5,
                    cameraPosition = "Hold at eye level; fill frame with head and shoulders",
                    compositionTip = "Position eyes along the upper horizontal third line.",
                    explanation = "Tighter framing emphasizes facial nuances and minimizes background distraction."
                ),
                ShotIdea(
                    name = "Environmental Portrait",
                    visualEffect = "Shows the subject within their context, location, and surroundings",
                    lens = mainLens,
                    zoomRatio = capabilities.clampZoom(1.0f),
                    aspectOption = AspectRatioOption.RATIO_4_3,
                    cameraPosition = "Step back 2-3 steps; place subject on left or right third",
                    compositionTip = "Use negative space around the subject to tell a environmental story.",
                    explanation = "Capturing surrounding context provides narrative depth to the portrait."
                ),
                ShotIdea(
                    name = "Full-Body Editorial",
                    visualEffect = "Dynamic elongated perspective emphasizing posture and outfit",
                    lens = mainLens,
                    zoomRatio = capabilities.clampZoom(1.0f),
                    aspectOption = AspectRatioOption.RATIO_9_16,
                    cameraPosition = "Lower camera to waist level and tilt slightly upward",
                    compositionTip = "Keep the subject centered with ample breathing room above head.",
                    explanation = "A lower shooting angle enhances stature and creates editorial presence."
                )
            )

            SceneCategory.GROUP_PHOTO -> listOf(
                ShotIdea(
                    name = "Expansive Wide Group",
                    visualEffect = "Ensures every person is comfortably framed with generous margins",
                    lens = ultraWideLens,
                    zoomRatio = capabilities.clampZoom(minZ),
                    aspectOption = if (isUprightPortrait) AspectRatioOption.RATIO_4_3 else AspectRatioOption.RATIO_16_9,
                    cameraPosition = "Step back 2-3 meters; hold camera at eye level of front row",
                    compositionTip = "Keep outer people at least 15% away from edge boundaries to avoid distortion.",
                    explanation = "Wide margins protect subjects from optical corner stretching."
                ),
                ShotIdea(
                    name = "Compact Candid Group",
                    visualEffect = "Warm, intimate group interaction with tighter cohesion",
                    lens = mainLens,
                    zoomRatio = capabilities.clampZoom(1.0f),
                    aspectOption = AspectRatioOption.RATIO_4_3,
                    cameraPosition = "Ask group to gather close and tilt heads slightly inward",
                    compositionTip = "Frame from waist up to emphasize expressions over empty space.",
                    explanation = "Closer physical arrangement translates into stronger emotional warmth."
                ),
                ShotIdea(
                    name = "Elevated Perspective",
                    visualEffect = "Shows multiple rows clearly without heads blocking each other",
                    lens = mainLens,
                    zoomRatio = capabilities.clampZoom(1.0f),
                    aspectOption = AspectRatioOption.RATIO_4_5,
                    cameraPosition = "Hold camera high overhead and angle down ~30 degrees",
                    compositionTip = "Have everyone look up toward the camera lens.",
                    explanation = "Elevated angle eliminates height disparity and flatters facial angles."
                )
            )

            SceneCategory.LANDSCAPE -> listOf(
                ShotIdea(
                    name = "Expansive Horizon Vista",
                    visualEffect = "Maximizes sky or foreground panoramic grandeur",
                    lens = ultraWideLens,
                    zoomRatio = capabilities.clampZoom(minZ),
                    aspectOption = if (isUprightPortrait) AspectRatioOption.RATIO_9_16 else AspectRatioOption.RATIO_16_9,
                    cameraPosition = "Keep camera level; position horizon on the lower third line",
                    compositionTip = "Use a prominent foreground rock or plant to anchor depth.",
                    explanation = "Foreground elements draw the viewer's eye into the distant background."
                ),
                ShotIdea(
                    name = "Balanced Golden Ratio",
                    visualEffect = "Harmonious balance between earth, horizon, and sky",
                    lens = mainLens,
                    zoomRatio = capabilities.clampZoom(1.0f),
                    aspectOption = AspectRatioOption.RATIO_4_3,
                    cameraPosition = "Hold at eye level; align major landmarks along third intersections",
                    compositionTip = "Look for natural diagonal lines (roads, rivers, ridgelines).",
                    explanation = "Diagonal leading lines create visual dynamism through the frame."
                ),
                ShotIdea(
                    name = "Minimalist Detail",
                    visualEffect = "Isolates a single distinctive natural feature or texture",
                    lens = telephotoLens,
                    zoomRatio = zoomTight,
                    aspectOption = AspectRatioOption.RATIO_1_1,
                    cameraPosition = "Focus on a solitary tree, solitary peak, or textured wave",
                    compositionTip = "Center the subject with expansive uncluttered negative space.",
                    explanation = "Simplicity and negative space evoke serenity and artistic focus."
                )
            )

            SceneCategory.ARCHITECTURE -> listOf(
                ShotIdea(
                    name = "Towering Perspective",
                    visualEffect = "Emphasizes height, grandeur, and converging vertical lines",
                    lens = ultraWideLens,
                    zoomRatio = capabilities.clampZoom(minZ),
                    aspectOption = AspectRatioOption.RATIO_9_16,
                    cameraPosition = "Crouch low near base of structure and tilt upward",
                    compositionTip = "Center the peak or corner to create dramatic symmetry.",
                    explanation = "Dramatic low-angle perspective conveys monumentality."
                ),
                ShotIdea(
                    name = "Symmetrical Facade",
                    visualEffect = "Clean, graphic architectural order and geometric perfection",
                    lens = mainLens,
                    zoomRatio = capabilities.clampZoom(1.0f),
                    aspectOption = AspectRatioOption.RATIO_1_1,
                    cameraPosition = "Stand dead center facing facade; ensure camera is perfectly level",
                    compositionTip = "Align architectural axis with center symmetry crosshair.",
                    explanation = "Square symmetrical framing highlights human geometric craft."
                ),
                ShotIdea(
                    name = "Structural Detail & Light",
                    visualEffect = "Focuses on patterns, materials, and interplay of light and shadow",
                    lens = telephotoLens,
                    zoomRatio = zoomTight,
                    aspectOption = AspectRatioOption.RATIO_3_2,
                    cameraPosition = "Look for repetitive windows, arches, or shadow patterns",
                    compositionTip = "Fill the frame so pattern edges bleed off-screen.",
                    explanation = "Abstracting structural rhythm turns architecture into fine art."
                )
            )

            SceneCategory.FOOD, SceneCategory.PRODUCT, SceneCategory.CLOSE_UP -> listOf(
                ShotIdea(
                    name = "Top-Down Flat Lay",
                    visualEffect = "Graphic, clean overhead arrangement of all elements",
                    lens = mainLens,
                    zoomRatio = capabilities.clampZoom(1.0f),
                    aspectOption = AspectRatioOption.RATIO_1_1,
                    cameraPosition = "Hold camera directly overhead (90° bird's eye view)",
                    compositionTip = "Arrange props and cutlery in balanced geometric spacing.",
                    explanation = "Overhead flat lays eliminate background clutter and showcase plating."
                ),
                ShotIdea(
                    name = "45-Degree Diner Angle",
                    visualEffect = "Natural, inviting presentation as seen from the table",
                    lens = mainLens,
                    zoomRatio = zoomMedium,
                    aspectOption = AspectRatioOption.RATIO_4_5,
                    cameraPosition = "Sit at table height and angle down 45 degrees",
                    compositionTip = "Focus on the hero ingredient on the front upper third.",
                    explanation = "Mimics the genuine sensory experience of sitting down to eat."
                ),
                ShotIdea(
                    name = "Macro Texture Shot",
                    visualEffect = "Close-up immersion into sizzle, steam, or intricate surface detail",
                    lens = telephotoLens,
                    zoomRatio = zoomTight,
                    aspectOption = AspectRatioOption.RATIO_4_3,
                    cameraPosition = "Move to closest sharp focus distance",
                    compositionTip = "Angle side-lighting across the surface to accentuate texture relief.",
                    explanation = "Raking light highlights minute textural details and freshness."
                )
            )

            SceneCategory.SUNSET_SUNRISE -> listOf(
                ShotIdea(
                    name = "Silhouette Drama",
                    visualEffect = "High-contrast silhouettes against fiery sky colors",
                    lens = mainLens,
                    zoomRatio = capabilities.clampZoom(1.0f),
                    aspectOption = AspectRatioOption.RATIO_9_16,
                    cameraPosition = "Place person or landmark between camera and sunset",
                    compositionTip = "Expose for sky highlights so subjects become dark silhouettes.",
                    explanation = "Silhouettes create timeless mystery and emphasize sky hues."
                ),
                ShotIdea(
                    name = "Expansive Golden Horizon",
                    visualEffect = "Wide peaceful transition of dusk or dawn light",
                    lens = ultraWideLens,
                    zoomRatio = capabilities.clampZoom(minZ),
                    aspectOption = if (isUprightPortrait) AspectRatioOption.RATIO_4_3 else AspectRatioOption.RATIO_16_9,
                    cameraPosition = "Keep horizon perfectly level on lower third",
                    compositionTip = "Position the sun on an intersection point, not dead center.",
                    explanation = "Rule-of-thirds sun placement balances warm light with cool shadow."
                ),
                ShotIdea(
                    name = "Sun Flare Detail",
                    visualEffect = "Dreamy optical flares and warm atmospheric glow",
                    lens = mainLens,
                    zoomRatio = zoomMedium,
                    aspectOption = AspectRatioOption.RATIO_3_2,
                    cameraPosition = "Partially block the sun behind a tree branch or building edge",
                    compositionTip = "Let subtle rim light outline the subject's edges.",
                    explanation = "Partial occultation produces authentic optical starbursts."
                )
            )

            SceneCategory.STREET_TRAVEL, SceneCategory.UNKNOWN_OR_MIXED -> listOf(
                ShotIdea(
                    name = "Classic Rule of Thirds",
                    visualEffect = "Balanced, aesthetically pleasing composition with natural flow",
                    lens = mainLens,
                    zoomRatio = capabilities.clampZoom(1.0f),
                    aspectOption = AspectRatioOption.RATIO_4_3,
                    cameraPosition = "Hold steady at eye level; wait for decisive moment",
                    compositionTip = "Align focal point with upper-right or upper-left intersection.",
                    explanation = "Thirds alignment allows viewer's eye to explore the entire frame."
                ),
                ShotIdea(
                    name = "Leading Line Depth",
                    visualEffect = "Pulls viewer deep into the scene using paths, curbs, or rails",
                    lens = ultraWideLens,
                    zoomRatio = capabilities.clampZoom(minZ),
                    aspectOption = AspectRatioOption.RATIO_9_16,
                    cameraPosition = "Lower camera to knee level near road or pathway",
                    compositionTip = "Start the leading line from the bottom corner towards center.",
                    explanation = "Low diagonal lines amplify visual perspective and depth."
                ),
                ShotIdea(
                    name = "Graphic Square Frame",
                    visualEffect = "Timeless medium-format film style emphasizing geometry and form",
                    lens = mainLens,
                    zoomRatio = zoomMedium,
                    aspectOption = AspectRatioOption.RATIO_1_1,
                    cameraPosition = "Hold camera at chest level; square up against background",
                    compositionTip = "Frame subject inside an architectural arch, door, or window.",
                    explanation = "Sub-framing focuses attention and adds multidimensional layering."
                )
            )
        }
    }
}
