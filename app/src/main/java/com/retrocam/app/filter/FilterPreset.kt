package com.retrocam.app.filter

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * One "look". Adding a filter is adding a JSON file under `assets/presets/` plus a LUT PNG —
 * no code and no new shader.
 *
 * [shader] feeds [FilterShader]'s uniforms directly; [aspect], [frame] and [dateStamp] are applied
 * afterwards by [OverlayCompositor] because they change the frame's shape rather than its pixels.
 */
data class FilterPreset(
    val id: String,
    val name: String,
    val lut: Bitmap,
    val shader: ShaderParams,
    /** Width / height to centre-crop to, or null to keep the sensor's own aspect. */
    val aspect: Float?,
    val frame: Frame?,
    val dateStamp: DateStamp?
) {
    /** True when the compositor has nothing to do and can hand the frame straight back. */
    val hasOverlays: Boolean get() = aspect != null || frame != null || dateStamp != null

    /**
     * Dials the whole look up or down for the intensity slider. Only *strengths* scale — radii,
     * counts, thresholds and widths are the shape of an effect, not its amount, and scaling them
     * would change the look rather than weaken it.
     *
     * Crop, border and date stamp deliberately do not scale: they are the format the look shoots
     * in, not part of its processing, so a Polaroid stays square at any intensity.
     */
    fun scaledBy(amount: Float): FilterPreset =
        if (amount == 1f) this else copy(shader = shader.scaledBy(amount))
}

/**
 * Uniforms for [FilterShader]. Spatial values are relative to the frame, never pixels: `*Radius`
 * and `chromaAmount` are percentages of the short side, `scanlineCount` is lines across the full
 * height. That is what keeps a 640px preview and a 4000px capture looking identical.
 */
data class ShaderParams(
    val lutIntensity: Float = 1f,
    val grainAmount: Float = 0f,
    val vignetteAmount: Float = 0f,
    val chromaAmount: Float = 0f,
    val halationAmount: Float = 0f,
    val halationRadius: Float = 0f,
    val halationThreshold: Float = 1f,
    val scanlineAmount: Float = 0f,
    val scanlineCount: Float = 0f,
    val syncBarAmount: Float = 0f,
    val syncBarWidth: Float = 0.05f,
    val syncBarShift: Float = 0f,
    val leakAmount: Float = 0f,
    val leakX: Float = 1f,
    val leakY: Float = 0f,
    val leakRadius: Float = 0.5f,
    val flashAmount: Float = 0f,
    val flashHardness: Float = 2f
) {
    fun scaledBy(amount: Float) = copy(
        lutIntensity = lutIntensity * amount,
        grainAmount = grainAmount * amount,
        vignetteAmount = vignetteAmount * amount,
        chromaAmount = chromaAmount * amount,
        halationAmount = halationAmount * amount,
        scanlineAmount = scanlineAmount * amount,
        syncBarAmount = syncBarAmount * amount,
        syncBarShift = syncBarShift * amount,
        leakAmount = leakAmount * amount,
        flashAmount = flashAmount * amount
    )
}

/** Border drawn around the image. Fractions are of the *image width*, so they scale with output. */
data class Frame(
    val color: Int,
    val side: Float,
    val top: Float,
    val bottom: Float
)

/** Burned-in date, bottom-right. [size] is a fraction of the image height. */
data class DateStamp(
    val color: Int,
    val size: Float,
    val format: String,
    val glow: Boolean
)

object PresetRepository {

    private const val TAG = "RetroCam"
    private const val INDEX = "presets/index.json"

    /**
     * Reads `presets/index.json` for the carousel order, then each preset beside it. LUT bitmaps
     * are cached by path so presets can share one. A preset that fails to parse is skipped rather
     * than taking the app down with it.
     */
    fun load(assets: AssetManager): List<FilterPreset> {
        val ids = try {
            JSONArray(assets.readText(INDEX))
        } catch (e: Exception) {
            Log.e(TAG, "Could not read $INDEX", e)
            return emptyList()
        }

        val lutCache = mutableMapOf<String, Bitmap>()
        val presets = mutableListOf<FilterPreset>()

        for (i in 0 until ids.length()) {
            val id = ids.getString(i)
            try {
                val json = JSONObject(assets.readText("presets/$id.json"))
                val lutPath = json.getString("lut")

                presets += FilterPreset(
                    id = id,
                    name = json.optString("name", id),
                    lut = lutCache.getOrPut(lutPath) { assets.readBitmap(lutPath) },
                    shader = json.readShaderParams(),
                    aspect = json.optString("aspect").takeIf { it.isNotEmpty() }?.toAspect(),
                    frame = json.optJSONObject("frame")?.toFrame(),
                    dateStamp = json.optJSONObject("dateStamp")?.toDateStamp()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Skipping preset '$id'", e)
            }
        }
        return presets
    }

    private fun JSONObject.readShaderParams() = ShaderParams(
        lutIntensity = optFloat("lutIntensity", 1f),
        grainAmount = optFloat("grainAmount", 0f),
        vignetteAmount = optFloat("vignetteAmount", 0f),
        chromaAmount = optFloat("chromaAmount", 0f),
        halationAmount = optFloat("halationAmount", 0f),
        halationRadius = optFloat("halationRadius", 0f),
        halationThreshold = optFloat("halationThreshold", 1f),
        scanlineAmount = optFloat("scanlineAmount", 0f),
        scanlineCount = optFloat("scanlineCount", 0f),
        syncBarAmount = optFloat("syncBarAmount", 0f),
        syncBarWidth = optFloat("syncBarWidth", 0.05f),
        syncBarShift = optFloat("syncBarShift", 0f),
        leakAmount = optFloat("leakAmount", 0f),
        leakX = optFloat("leakX", 1f),
        leakY = optFloat("leakY", 0f),
        leakRadius = optFloat("leakRadius", 0.5f),
        flashAmount = optFloat("flashAmount", 0f),
        flashHardness = optFloat("flashHardness", 2f)
    )

    private fun JSONObject.toFrame() = Frame(
        color = Color.parseColor(optString("color", "#FFFFFFFF")),
        side = optFloat("side", 0f),
        top = optFloat("top", 0f),
        bottom = optFloat("bottom", 0f)
    )

    private fun JSONObject.toDateStamp() = DateStamp(
        color = Color.parseColor(optString("color", "#FFFF8A2B")),
        size = optFloat("size", 0.035f),
        format = optString("format", "yyyy M d"),
        glow = optBoolean("glow", true)
    )

    /** Accepts "4:3" or a bare number. */
    private fun String.toAspect(): Float {
        val parts = split(":")
        return if (parts.size == 2) parts[0].trim().toFloat() / parts[1].trim().toFloat()
        else trim().toFloat()
    }

    private fun AssetManager.readText(path: String): String =
        open(path).bufferedReader().use { it.readText() }

    /**
     * LUTs must be decoded pixel-exact: no density scaling (assets are exempt anyway) and no
     * premultiplication, or the colour cells shift and the grade goes wrong.
     */
    private fun AssetManager.readBitmap(path: String): Bitmap {
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPremultiplied = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return open(path).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Could not decode LUT $path")
    }

    private fun JSONObject.optFloat(key: String, fallback: Float): Float =
        optDouble(key, fallback.toDouble()).toFloat()
}
