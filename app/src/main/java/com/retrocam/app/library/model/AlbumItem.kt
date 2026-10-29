package com.retrocam.app.library.model

import android.net.Uri

data class AlbumItem(
    val id: String,
    val name: String,
    val coverUri: Uri? = null,
    val mediaCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isSystemGenerated: Boolean = false
)
