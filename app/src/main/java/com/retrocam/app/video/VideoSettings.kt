package com.retrocam.app.video

import android.util.Size

/**
 * Supported video recording resolutions.
 */
enum class VideoQuality(
    val label: String,
    val targetShortDim: Int,
    val bitrate30: Int,
    val bitrate60: Int
) {
    SD_480P("480p", 480, 2_500_000, 3_500_000),
    HD_720P("720p", 720, 5_000_000, 7_500_000),
    FHD_1080P("1080p", 1080, 10_000_000, 15_000_000),
    UHD_4K("4K", 2160, 25_000_000, 40_000_000);

    /**
     * Target resolution size for CameraX sensor input (4:3 native ratio).
     */
    fun targetSize(): Size {
        val longDim = ((targetShortDim * 4) / 3 / 4) * 4
        return Size(longDim, targetShortDim)
    }

    fun bitrateFor(fps: VideoFps): Int {
        return when (fps) {
            VideoFps.FPS_24 -> (bitrate30 * 0.85).toInt()
            VideoFps.FPS_30 -> bitrate30
            VideoFps.FPS_60 -> bitrate60
        }
    }
}

/**
 * Supported video recording frame rates.
 */
enum class VideoFps(val label: String, val fps: Int) {
    FPS_24("24 fps", 24),
    FPS_30("30 fps", 30),
    FPS_60("60 fps", 60)
}
