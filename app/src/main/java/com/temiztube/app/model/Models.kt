package com.temiztube.app.model

data class VideoItem(
    val id: String,
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val viewCount: Long
)

enum class StreamKind {
    HLS,
    DASH,
    PROGRESSIVE,
    MERGED
}

data class PlayableStream(
    val videoUrl: String,
    val audioUrl: String? = null,
    val kind: StreamKind = StreamKind.PROGRESSIVE,
    val title: String,
    val uploader: String,
    val qualityLabel: String
)
