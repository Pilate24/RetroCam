package com.retrocam.app.library.model

enum class DuplicateType {
    EXACT_BYTE,
    BURST_SHOT,
    VISUALLY_SIMILAR
}

data class DuplicateGroup(
    val groupId: String,
    val type: DuplicateType,
    val reason: String,
    val items: List<MediaItem>,
    val reclaimableBytes: Long
)
