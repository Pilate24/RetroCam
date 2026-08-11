package com.retrocam.app.filter

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * One "look". Adding a filter is adding a JSON entry — no code and no new shader.
 *
 * [shader] feeds [FilterShader]'s uniforms directly; [aspect], [frame] and [dateStamp] are applied
 * afterwards by [OverlayCompositor] because they change the frame's shape rather than its pixels.
 */
data class FilterPreset(
    val id: String,
    val name: String,
    val category: String,
    /** Optional colour lookup table. Presets that grade parametrically get the identity LUT. */
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
     * counts, thresholds, sizes and origins are the shape of an effect, not its amount, and
     * scaling them would change the look rather than weaken it.
     *
     * Every tone and colour value is a signed delta around neutral, so scaling to 0 lands exactly
     * on the untouched image. That's what makes intensity an interpolation of the parameters
     * rather than a cross-fade against an overlay.
     *
     * Crop, border and date stamp deliberately do not scale: they are the format the look shoots
     * in, not part of its processing, so a Polaroid stays square at any intensity.
     */
    fun scaledBy(amount: Float): FilterPreset =
        if (amount == 1f) this else copy(shader = shader.scaledBy(amount))
}

/**
 * Uniforms for [FilterShader], already normalised into shader units. Spatial values are relative
 * to the frame, never pixels: `*Radius` and the separation amounts are percentages of the short
 * side, `scanlineCount` is lines across the full height. That is what keeps a 640px preview and a
 * 4000px capture looking identical.
 *
 * Presets are authored on a friendlier -100..100 scale; [PresetRepository] does the conversion.
 */
data class ShaderParams(
    val lutIntensity: Float = 0f,

    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val fade: Float = 0f,

    val toneShoulder: Float = 0f,
    val toneToe: Float = 0f,
    val channelGamma: FloatArray = floatArrayOf(1f, 1f, 1f),
    val shadowTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    val shadowTintAmount: Float = 0f,
    val highlightTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    val highlightTintAmount: Float = 0f,
    val skinSat: Float = 0f,
    val skinShift: Float = 0f,
    val greenSat: Float = 0f,
    val greenShift: Float = 0f,
    val blueSat: Float = 0f,
    val blueShift: Float = 0f,
    val microContrast: Float = 0f,

    val blurAmount: Float = 0f,
    val motionBlur: Float = 0f,
    val motionAngle: Float = 0f,
    val sharpness: Float = 0f,
    val clarity: Float = 0f,
    val chromaAmount: Float = 0f,
    val rgbShift: Float = 0f,
    val anaglyph: Float = 0f,
    val colorBleed: Float = 0f,

    val halationAmount: Float = 0f,
    val halationRadius: Float = 0.8f,
    val halationThreshold: Float = 0.72f,
    val glowAmount: Float = 0f,
    val glowThreshold: Float = 0.65f,
    val leakAmount: Float = 0f,
    val leakColor: FloatArray = floatArrayOf(1f, 0.52f, 0.22f),
    val leakX: Float = 1.05f,
    val leakY: Float = 0.2f,
    val leakRadius: Float = 0.55f,
    val burnAmount: Float = 0f,
    val flashAmount: Float = 0f,
    val flashHardness: Float = 2f,

    val scanlineAmount: Float = 0f,
    val scanlineCount: Float = 320f,
    val syncBarAmount: Float = 0f,
    val syncBarWidth: Float = 0.035f,
    val syncBarShift: Float = 0.012f,
    val trackingAmount: Float = 0f,
    val noiseAmount: Float = 0f,

    val grainAmount: Float = 0f,
    val grainSize: Float = 1f,
    val dustAmount: Float = 0f,
    val scratchAmount: Float = 0f,
    val vignetteAmount: Float = 0f
) {
    fun scaledBy(a: Float) = copy(
        lutIntensity = lutIntensity * a,
        exposure = exposure * a,
        brightness = brightness * a,
        contrast = contrast * a,
        saturation = saturation * a,
        temperature = temperature * a,
        tint = tint * a,
        highlights = highlights * a,
        shadows = shadows * a,
        whites = whites * a,
        blacks = blacks * a,
        fade = fade * a,
        toneShoulder = toneShoulder * a,
        toneToe = toneToe * a,
        // Gamma is multiplicative around 1.0, so it interpolates toward 1.0 rather than toward 0.
        channelGamma = FloatArray(3) { 1f + (channelGamma[it] - 1f) * a },
        shadowTintAmount = shadowTintAmount * a,
        highlightTintAmount = highlightTintAmount * a,
        skinSat = skinSat * a,
        skinShift = skinShift * a,
        greenSat = greenSat * a,
        greenShift = greenShift * a,
        blueSat = blueSat * a,
        blueShift = blueShift * a,
        microContrast = microContrast * a,
        blurAmount = blurAmount * a,
        motionBlur = motionBlur * a,
        sharpness = sharpness * a,
        clarity = clarity * a,
        chromaAmount = chromaAmount * a,
        rgbShift = rgbShift * a,
        anaglyph = anaglyph * a,
        colorBleed = colorBleed * a,
        halationAmount = halationAmount * a,
        glowAmount = glowAmount * a,
        leakAmount = leakAmount * a,
        burnAmount = burnAmount * a,
        flashAmount = flashAmount * a,
        scanlineAmount = scanlineAmount * a,
        syncBarAmount = syncBarAmount * a,
        syncBarShift = syncBarShift * a,
        trackingAmount = trackingAmount * a,
        noiseAmount = noiseAmount * a,
        grainAmount = grainAmount * a,
        dustAmount = dustAmount * a,
        scratchAmount = scratchAmount * a,
        vignetteAmount = vignetteAmount * a
    )

    // FloatArray in a data class breaks structural equality; these keep it sane.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShaderParams) return false
        return toString() == other.toString() && leakColor.contentEquals(other.leakColor)
    }

    override fun hashCode(): Int = 31 * toString().hashCode() + leakColor.contentHashCode()
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
    private const val IDENTITY_LUT = "luts/identity.png"

    const val CATEGORY_ALL = "All"

    /**
     * Reads `presets/index.json`, which holds the carousel order as either a bare array of ids
     * (one file each, the original layout) or an object that can also name *packs* — single files
     * holding many presets, so a themed set ships as one asset instead of seventy.
     *
     * LUT bitmaps are cached by path, so the many parametric presets all share one identity LUT
     * rather than decoding a megabyte each. A preset that fails to parse is skipped rather than
     * taking the app down with it.
     */
    fun load(assets: AssetManager): List<FilterPreset> {
        val raw = try {
            assets.readText(INDEX)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read $INDEX", e)
            return emptyList()
        }

        val singles = mutableListOf<String>()
        val packs = mutableListOf<String>()
        try {
            if (raw.trimStart().startsWith("[")) {
                JSONArray(raw).forEachString { singles += it }
            } else {
                val index = JSONObject(raw)
                index.optJSONArray("presets")?.forEachString { singles += it }
                index.optJSONArray("packs")?.forEachString { packs += it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Malformed $INDEX", e)
            return emptyList()
        }

        val lutCache = mutableMapOf<String, Bitmap>()
        val presets = mutableListOf<FilterPreset>()

        for (id in singles) {
            try {
                presets += JSONObject(assets.readText("presets/$id.json")).toPreset(id, assets, lutCache)
            } catch (e: Exception) {
                Log.e(TAG, "Skipping preset '$id'", e)
            }
        }

        for (path in packs) {
            try {
                val array = JSONArray(assets.readText(path))
                for (i in 0 until array.length()) {
                    val json = array.getJSONObject(i)
                    val id = json.optString("id", "${path.substringAfterLast('/')}_$i")
                    try {
                        presets += json.toPreset(id, assets, lutCache)
                    } catch (e: Exception) {
                        Log.e(TAG, "Skipping preset '$id' from $path", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Skipping pack '$path'", e)
            }
        }
        return presets
    }

    /** Category tabs, in display order, derived from what actually loaded. */
    fun categoriesOf(presets: List<FilterPreset>): List<String> {
        val order =
            listOf("Camera", "Film", "Vintage", "80s/90s", "Disposable", "B&W", "VHS", "Effects")
        val present = presets.map { it.category }.toSet()
        return listOf(CATEGORY_ALL) + order.filter { it in present } +
            present.filter { it !in order }.sorted()
    }

    private fun JSONObject.toPreset(
        id: String,
        assets: AssetManager,
        lutCache: MutableMap<String, Bitmap>
    ): FilterPreset {
        val lutPath = optString("lut").takeIf { it.isNotEmpty() } ?: IDENTITY_LUT
        return FilterPreset(
            id = id,
            name = optString("name", id),
            category = optString("category", "Effects"),
            lut = lutCache.getOrPut(lutPath) { assets.readBitmap(lutPath) },
            shader = readShaderParams(hasLut = has("lut")),
            aspect = optString("aspect").takeIf { it.isNotEmpty() }?.toAspect(),
            frame = optJSONObject("frame")?.toFrame(),
            dateStamp = optJSONObject("dateStamp")?.toDateStamp()
        )
    }

    /**
     * Presets are authored on a -100..100 scale, which is what a person can reason about; the
     * shader wants normalised units. The factor beside each field is the full-scale value at 100.
     *
     * Presets without a LUT get lutIntensity 0 so the identity lookup is skipped entirely — two
     * texture samples saved per pixel on the majority of filters.
     */
    private fun JSONObject.readShaderParams(hasLut: Boolean) = ShaderParams(
        lutIntensity = if (hasLut) optFloat("lutIntensity", 1f) else 0f,

        exposure = scaled("exposure", 2f),
        brightness = scaled("brightness", 0.45f),
        contrast = scaled("contrast", 1.6f),
        saturation = scaled("saturation", 2.1f),
        temperature = scaled("temperature", 1f),
        tint = scaled("tint", 1f),
        highlights = scaled("highlights", 0.7f),
        shadows = scaled("shadows", 0.7f),
        whites = scaled("whites", 0.8f),
        blacks = scaled("blacks", 0.65f),
        fade = scaled("fade", 1f),

        toneShoulder = scaled("highlightRolloff", 1f),
        toneToe = scaled("shadowResponse", 0.6f),
        // Authored as -100..100 around neutral; 100 maps to gamma 0.6 (lifts that channel), -100
        // to 1.6 (deepens it). Inverted because lower gamma means a brighter channel.
        channelGamma = floatArrayOf(
            gammaFor("redResponse"), gammaFor("greenResponse"), gammaFor("blueResponse")
        ),
        shadowTint = optString("shadowTint").takeIf { it.isNotEmpty() }?.toRgb()
            ?: floatArrayOf(0f, 0f, 0f),
        shadowTintAmount = scaled("shadowTintAmount", 0.8f),
        highlightTint = optString("highlightTint").takeIf { it.isNotEmpty() }?.toRgb()
            ?: floatArrayOf(0f, 0f, 0f),
        highlightTintAmount = scaled("highlightTintAmount", 0.8f),
        skinSat = scaled("skinSaturation", 2.1f),
        skinShift = scaled("skinHue", 0.13f),
        greenSat = scaled("greenSaturation", 2.1f),
        greenShift = scaled("greenHue", 0.13f),
        blueSat = scaled("blueSaturation", 2.1f),
        blueShift = scaled("blueHue", 0.13f),
        microContrast = scaled("microContrast", 1.8f),

        blurAmount = scaled("blur", 2f),
        motionBlur = scaled("motionBlur", 2.5f),
        motionAngle = Math.toRadians(optFloat("motionAngle", 0f).toDouble()).toFloat(),
        sharpness = scaled("sharpness", 2f),
        clarity = scaled("clarity", 1.5f),
        chromaAmount = scaled("chromaticAberration", 3.2f),
        rgbShift = scaled("rgbShift", 3.2f),
        anaglyph = scaled("anaglyph", 3.2f),
        colorBleed = scaled("colorBleed", 2f),

        halationAmount = scaled("halation", 2.6f),
        halationRadius = optFloat("halationRadius", 0.8f),
        halationThreshold = optFloat("halationThreshold", 0.72f),
        glowAmount = scaled("glow", 2.6f),
        glowThreshold = optFloat("glowThreshold", 0.65f),
        leakAmount = scaled("lightLeak", 2.6f),
        leakColor = optString("leakColor").takeIf { it.isNotEmpty() }?.toRgb()
            ?: floatArrayOf(1f, 0.52f, 0.22f),
        leakX = optFloat("leakX", 1.05f),
        leakY = optFloat("leakY", 0.2f),
        leakRadius = optFloat("leakRadius", 0.55f),
        burnAmount = scaled("filmBurn", 1.2f),
        flashAmount = scaled("flash", 0.75f),
        flashHardness = optFloat("flashHardness", 2f),

        scanlineAmount = scaled("scanlines", 1f),
        scanlineCount = optFloat("scanlineCount", 320f),
        syncBarAmount = scaled("syncBar", 0.6f),
        syncBarWidth = optFloat("syncBarWidth", 0.035f),
        syncBarShift = optFloat("syncBarShift", 0.012f),
        trackingAmount = scaled("tracking", 1.5f),
        noiseAmount = scaled("noise", 0.5f),

        grainAmount = scaled("grain", 0.42f),
        // 0..100 maps to a half-size..double-size grain cell, 50 being the reference.
        grainSize = 0.5f + optFloat("grainSize", 50f) / 100f * 1.5f,
        dustAmount = scaled("dust", 2.2f),
        scratchAmount = scaled("scratches", 2.2f),
        vignetteAmount = scaled("vignette", 3.4f)
    )

    private fun JSONObject.scaled(key: String, fullScale: Float): Float =
        optFloat(key, 0f) / 100f * fullScale

    private fun JSONObject.gammaFor(key: String): Float =
        1f - optFloat(key, 0f) / 100f * 0.72f

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

    private fun String.toRgb(): FloatArray {
        val c = Color.parseColor(this)
        return floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
    }

    /** Accepts "4:3" or a bare number. */
    private fun String.toAspect(): Float {
        val parts = split(":")
        return if (parts.size == 2) parts[0].trim().toFloat() / parts[1].trim().toFloat()
        else trim().toFloat()
    }

    private inline fun JSONArray.forEachString(action: (String) -> Unit) {
        for (i in 0 until length()) action(getString(i))
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
