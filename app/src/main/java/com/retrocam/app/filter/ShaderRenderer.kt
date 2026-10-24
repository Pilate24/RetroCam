package com.retrocam.app.filter

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Rasterizes [FilterShader] to a plain software [Bitmap] at a fixed size.
 *
 * RuntimeShader only runs on a hardware-accelerated canvas, and a Canvas backed directly by a
 * Bitmap is software-only — so off-screen rendering goes through an [ImageReader]'s Surface via
 * [android.view.Surface.lockHardwareCanvas], then gets copied back to a normal Bitmap.
 *
 * The same instance type is used for both the live preview (recreated only when the frame size
 * changes) and the captured photo (one-off), so what you see is what you get.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ShaderRenderer(val width: Int, val height: Int) {

    private val shader = RuntimeShader(FilterShader.SOURCE)

    private val imageReader = ImageReader.newInstance(
        width,
        height,
        PixelFormat.RGBA_8888,
        MAX_IMAGES,
        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
    )

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** LUTs are per-preset and immutable, so their shader is rebuilt only when the look changes. */
    private var cachedLut: Bitmap? = null
    private var cachedLutShader: BitmapShader? = null

    /**
     * unlockCanvasAndPost() only *queues* the frame; the buffer reaches the reader asynchronously,
     * so acquiring straight after the post races the GPU and comes back null. Wait for the
     * reader's callback instead.
     */
    private val frameReady = Semaphore(0)
    private val callbackThread = HandlerThread("ShaderRenderer").apply { start() }

    init {
        imageReader.setOnImageAvailableListener(
            { frameReady.release() },
            Handler(callbackThread.looper)
        )
    }

    /**
     * Renders [preset] over [source] directly using the GPU and provides the zero-copy [Bitmap.Config.HARDWARE]
     * wrapped [Bitmap] to [action] without forcing a CPU readback.
     *
     * [rotationDegrees] and [isFront] are applied directly via [BitmapShader]'s local matrix on the GPU,
     * avoiding redundant intermediate bitmap allocations.
     */
    fun renderHardware(
        source: Bitmap,
        rotationDegrees: Int = 0,
        isFront: Boolean = false,
        seed: Float,
        preset: FilterPreset,
        action: (Bitmap) -> Unit
    ) {
        applyUniforms(source, rotationDegrees, isFront, seed, preset)

        frameReady.drainPermits()

        val surface = imageReader.surface
        val canvas = surface.lockHardwareCanvas()
            ?: error("ShaderRenderer could not lock a hardware canvas")
        try {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }

        if (!frameReady.tryAcquire(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            error("ShaderRenderer timed out waiting for a rendered frame")
        }

        val image = imageReader.acquireNextImage()
            ?: error("ShaderRenderer produced no image")
        try {
            val buffer = image.hardwareBuffer
                ?: error("Failed to acquire HardwareBuffer from image")
            buffer.use {
                val hardwareBitmap = Bitmap.wrapHardwareBuffer(it, null)
                    ?: error("Failed to wrap rendered HardwareBuffer")
                try {
                    action(hardwareBitmap)
                } finally {
                    hardwareBitmap.recycle()
                }
            }
        } finally {
            image.close()
        }
    }

    /**
     * Runs [preset] over [source] and returns a new software Bitmap. Used for captured photos
     * that must be compressed to JPEG and saved to disk.
     */
    fun render(
        source: Bitmap,
        seed: Float,
        preset: FilterPreset,
        rotationDegrees: Int = 0,
        isFront: Boolean = false
    ): Bitmap {
        var result: Bitmap? = null
        renderHardware(source, rotationDegrees, isFront, seed, preset) { hwBitmap ->
            result = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        return result ?: error("ShaderRenderer failed to create software copy")
    }

    private fun applyUniforms(
        source: Bitmap,
        rotationDegrees: Int,
        isFront: Boolean,
        seed: Float,
        preset: FilterPreset
    ) {
        val inputShader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
            setLocalMatrix(buildTransformMatrix(source.width, source.height, rotationDegrees, isFront, width, height))
        }

        val p = preset.shader
        shader.setInputShader("inputShader", inputShader)
        shader.setInputShader("lutShader", lutShaderFor(preset.lut))
        shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
        shader.setFloatUniform("seed", seed)
        shader.setFloatUniform("lutIntensity", p.lutIntensity)

        shader.setFloatUniform("exposure", p.exposure)
        shader.setFloatUniform("brightness", p.brightness)
        shader.setFloatUniform("contrast", p.contrast)
        shader.setFloatUniform("saturation", p.saturation)
        shader.setFloatUniform("temperature", p.temperature)
        shader.setFloatUniform("tint", p.tint)
        shader.setFloatUniform("highlights", p.highlights)
        shader.setFloatUniform("shadows", p.shadows)
        shader.setFloatUniform("whites", p.whites)
        shader.setFloatUniform("blacks", p.blacks)
        shader.setFloatUniform("fade", p.fade)

        shader.setFloatUniform("toneShoulder", p.toneShoulder)
        shader.setFloatUniform("toneToe", p.toneToe)
        shader.setFloatUniform("channelGamma", p.channelGamma[0], p.channelGamma[1], p.channelGamma[2])
        shader.setFloatUniform("shadowTint", p.shadowTint[0], p.shadowTint[1], p.shadowTint[2])
        shader.setFloatUniform("shadowTintAmount", p.shadowTintAmount)
        shader.setFloatUniform("highlightTint", p.highlightTint[0], p.highlightTint[1], p.highlightTint[2])
        shader.setFloatUniform("highlightTintAmount", p.highlightTintAmount)
        shader.setFloatUniform("skinSat", p.skinSat)
        shader.setFloatUniform("skinShift", p.skinShift)
        shader.setFloatUniform("greenSat", p.greenSat)
        shader.setFloatUniform("greenShift", p.greenShift)
        shader.setFloatUniform("blueSat", p.blueSat)
        shader.setFloatUniform("blueShift", p.blueShift)
        shader.setFloatUniform("microContrast", p.microContrast)

        shader.setFloatUniform("blurAmount", p.blurAmount)
        shader.setFloatUniform("motionBlur", p.motionBlur)
        shader.setFloatUniform("motionAngle", p.motionAngle)
        shader.setFloatUniform("sharpness", p.sharpness)
        shader.setFloatUniform("clarity", p.clarity)
        shader.setFloatUniform("chromaAmount", p.chromaAmount)
        shader.setFloatUniform("rgbShift", p.rgbShift)
        shader.setFloatUniform("anaglyph", p.anaglyph)
        shader.setFloatUniform("colorBleed", p.colorBleed)

        shader.setFloatUniform("halationAmount", p.halationAmount)
        shader.setFloatUniform("halationRadius", p.halationRadius)
        shader.setFloatUniform("halationThreshold", p.halationThreshold)
        shader.setFloatUniform("glowAmount", p.glowAmount)
        shader.setFloatUniform("glowThreshold", p.glowThreshold)
        shader.setFloatUniform("leakAmount", p.leakAmount)
        shader.setFloatUniform("leakColor", p.leakColor[0], p.leakColor[1], p.leakColor[2])
        shader.setFloatUniform("leakOrigin", p.leakX, p.leakY)
        shader.setFloatUniform("leakRadius", p.leakRadius)
        shader.setFloatUniform("burnAmount", p.burnAmount)
        shader.setFloatUniform("flashAmount", p.flashAmount)
        shader.setFloatUniform("flashHardness", p.flashHardness)

        shader.setFloatUniform("scanlineAmount", p.scanlineAmount)
        shader.setFloatUniform("scanlineCount", p.scanlineCount)
        shader.setFloatUniform("syncBarAmount", p.syncBarAmount)
        shader.setFloatUniform("syncBarWidth", p.syncBarWidth)
        shader.setFloatUniform("syncBarShift", p.syncBarShift)
        shader.setFloatUniform("trackingAmount", p.trackingAmount)
        shader.setFloatUniform("noiseAmount", p.noiseAmount)

        shader.setFloatUniform("grainAmount", p.grainAmount)
        shader.setFloatUniform("grainSize", p.grainSize)
        shader.setFloatUniform("dustAmount", p.dustAmount)
        shader.setFloatUniform("scratchAmount", p.scratchAmount)
        shader.setFloatUniform("vignetteAmount", p.vignetteAmount)
        paint.shader = shader
    }

    private fun buildTransformMatrix(
        srcW: Int,
        srcH: Int,
        rotationDegrees: Int,
        isFront: Boolean,
        dstW: Int,
        dstH: Int
    ): Matrix {
        val matrix = Matrix()
        matrix.postTranslate(-srcW / 2f, -srcH / 2f)
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }
        if (isFront) {
            matrix.postScale(-1f, 1f)
        }
        val rotatedW = if (rotationDegrees == 90 || rotationDegrees == 270) srcH else srcW
        val rotatedH = if (rotationDegrees == 90 || rotationDegrees == 270) srcW else srcH
        matrix.postScale(dstW.toFloat() / rotatedW, dstH.toFloat() / rotatedH)
        matrix.postTranslate(dstW / 2f, dstH / 2f)
        return matrix
    }

    /**
     * LINEAR filtering is what smooths the 64 colour levels into a continuous grade; with NEAREST
     * the LUT bands visibly. The shader samples cell centres, so neighbouring cells never bleed.
     */
    private fun lutShaderFor(lut: Bitmap): BitmapShader {
        cachedLutShader?.let { if (cachedLut === lut) return it }
        val created = BitmapShader(lut, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        cachedLut = lut
        cachedLutShader = created
        return created
    }

    fun close() {
        imageReader.close()
        callbackThread.quitSafely()
    }

    private companion object {
        const val MAX_IMAGES = 2
        const val FRAME_TIMEOUT_MS = 1000L
    }
}
