package com.retrocam.app.pro

import android.graphics.Bitmap

/**
 * Result data from real-time preview frame histogram analysis.
 */
data class HistogramData(
    val redBins: FloatArray,   // 256 normalized bins (0.0 to 1.0)
    val greenBins: FloatArray, // 256 normalized bins
    val blueBins: FloatArray,  // 256 normalized bins
    val lumaBins: FloatArray,  // 256 normalized bins
    val maxBinValue: Int,
    val hasHighlightClipping: Boolean,
    val hasShadowClipping: Boolean,
    val averageLuminance: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HistogramData
        return maxBinValue == other.maxBinValue &&
                hasHighlightClipping == other.hasHighlightClipping &&
                hasShadowClipping == other.hasShadowClipping &&
                averageLuminance == other.averageLuminance &&
                redBins.contentEquals(other.redBins) &&
                greenBins.contentEquals(other.greenBins) &&
                blueBins.contentEquals(other.blueBins) &&
                lumaBins.contentEquals(other.lumaBins)
    }

    override fun hashCode(): Int {
        var result = maxBinValue
        result = 31 * result + hasHighlightClipping.hashCode()
        result = 31 * result + hasShadowClipping.hashCode()
        result = 31 * result + averageLuminance.hashCode()
        result = 31 * result + redBins.contentHashCode()
        result = 31 * result + greenBins.contentHashCode()
        result = 31 * result + blueBins.contentHashCode()
        result = 31 * result + lumaBins.contentHashCode()
        return result
    }
}

/**
 * Fast histogram analyzer for real-time live exposure feedback.
 */
object HistogramAnalyzer {

    private const val BINS_COUNT = 256
    private const val SAMPLE_STEP = 2 // Subsample every 2nd pixel for ultra-fast performance

    fun analyze(bitmap: Bitmap): HistogramData {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return analyze(pixels, width, height)
    }

    fun analyze(pixels: IntArray, width: Int, height: Int): HistogramData {
        val redCounts = IntArray(BINS_COUNT)
        val greenCounts = IntArray(BINS_COUNT)
        val blueCounts = IntArray(BINS_COUNT)
        val lumaCounts = IntArray(BINS_COUNT)

        var totalSamples = 0
        var lumaSum = 0L
        var clippedHighlights = 0
        var clippedShadows = 0

        // Subsampled pass for ultra-fast calculation (<1ms)
        for (y in 0 until height step SAMPLE_STEP) {
            val rowOffset = y * width
            for (x in 0 until width step SAMPLE_STEP) {
                val pixel = pixels[rowOffset + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = (r * 299 + g * 587 + b * 114) / 1000

                redCounts[r]++
                greenCounts[g]++
                blueCounts[b]++
                lumaCounts[luma]++

                lumaSum += luma
                totalSamples++

                if (luma >= 250) clippedHighlights++
                if (luma <= 5) clippedShadows++
            }
        }

        if (totalSamples == 0) totalSamples = 1

        // Find max count for normalization
        var maxCount = 1
        for (i in 0 until BINS_COUNT) {
            if (redCounts[i] > maxCount) maxCount = redCounts[i]
            if (greenCounts[i] > maxCount) maxCount = greenCounts[i]
            if (blueCounts[i] > maxCount) maxCount = blueCounts[i]
            if (lumaCounts[i] > maxCount) maxCount = lumaCounts[i]
        }

        val normR = FloatArray(BINS_COUNT)
        val normG = FloatArray(BINS_COUNT)
        val normB = FloatArray(BINS_COUNT)
        val normLuma = FloatArray(BINS_COUNT)
        val maxF = maxCount.toFloat()

        for (i in 0 until BINS_COUNT) {
            normR[i] = (redCounts[i] / maxF).coerceIn(0f, 1f)
            normG[i] = (greenCounts[i] / maxF).coerceIn(0f, 1f)
            normB[i] = (blueCounts[i] / maxF).coerceIn(0f, 1f)
            normLuma[i] = (lumaCounts[i] / maxF).coerceIn(0f, 1f)
        }

        val highlightClipRatio = clippedHighlights.toFloat() / totalSamples
        val shadowClipRatio = clippedShadows.toFloat() / totalSamples

        return HistogramData(
            redBins = normR,
            greenBins = normG,
            blueBins = normB,
            lumaBins = normLuma,
            maxBinValue = maxCount,
            hasHighlightClipping = highlightClipRatio > 0.015f, // > 1.5% clipped
            hasShadowClipping = shadowClipRatio > 0.015f,
            averageLuminance = lumaSum.toFloat() / totalSamples
        )
    }
}
