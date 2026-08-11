package com.retrocam.app.ui

import android.graphics.Bitmap

/**
 * The single frame the compare screen grades every camera against.
 *
 * Handed over through a holder rather than an Intent extra: a bitmap large enough to judge a
 * colour rendering by is far past the Binder transaction limit, and writing it to disk to pass a
 * path would be slower and leave a file to clean up.
 */
object CompareFrame {

    @Volatile
    private var frame: Bitmap? = null

    fun set(bitmap: Bitmap) {
        val previous = frame
        frame = bitmap
        if (previous !== bitmap) previous?.recycle()
    }

    fun get(): Bitmap? = frame?.takeIf { !it.isRecycled }

    fun clear() {
        frame?.recycle()
        frame = null
    }
}
