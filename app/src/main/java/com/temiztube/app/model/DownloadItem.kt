package com.temiztube.app.model

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED
}

enum class DownloadKind {
    VIDEO,
    AUDIO
}

data class DownloadItem(
    val id: String,
    val videoId: String,
    val title: String,
    val kind: DownloadKind,
    val status: DownloadStatus,
    val progressPercent: Int = 0,
    val fileName: String = "",
    val localPath: String? = null,
    val publicUri: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L
)
