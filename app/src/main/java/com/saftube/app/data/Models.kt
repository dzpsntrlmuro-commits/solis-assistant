package com.saftube.app.data

data class VideoItem(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val uploader: String,
    val views: Long,
    val durationSeconds: Long,
    val uploadedDate: String? = null,
    val shortDescription: String? = null
)

data class VideoStream(
    val videoId: String,
    val title: String,
    val description: String,
    val uploader: String,
    val views: Long,
    val hlsUrl: String?,
    val dashUrl: String?,
    val progressiveUrl: String?,
    val related: List<VideoItem>,
    val blockedAdHosts: Int
)

data class SearchResult(
    val items: List<VideoItem>,
    val blockedAdHosts: Int
)
