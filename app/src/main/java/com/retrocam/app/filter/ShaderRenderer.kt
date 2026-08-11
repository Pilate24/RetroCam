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
     * Runs [preset] over [source] and returns a new software Bitmap. [source] is left untouched.
     * [seed] drives the grain pattern — pass elapsed time to animate it in the preview, or a
     * per-shot random value so two captures don't share identical grain.
     *
     * Throws [IllegalStateException] if the GPU produced no frame; callers driving the live
     * preview should drop the frame rather than treat that as fatal.
     */
    fun render(source: Bitmap, seed: Float, preset: FilterPreset): Bitmap {
        // Scale the source into the renderer's own dimensions so the shader's fragCoord space and
        // the sampled image always line up, whatever size the caller hands us.
        val inputShader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
            setLocalMatrix(Matrix().apply {
                setScale(width.toFloat() / source.width, height.toFloat() / source.height)
            })
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
        shader.setFloatUniform("fade", p.fade)

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
                    return hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    hardwareBitmap.recycle()
                }
            }
        } finally {
            image.close()
        }
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
