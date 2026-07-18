package com.temiztube.app.data

import com.temiztube.app.model.PlayableStream
import com.temiztube.app.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale

class YoutubeRepository {

    private val youtube = ServiceList.YouTube

    suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val extractor = youtube.getSearchExtractor(query)
        extractor.fetchPage()
        extractor.initialPage.items.mapNotNull { it.toVideoItem() }
    }

    suspend fun trending(): List<VideoItem> = withContext(Dispatchers.IO) {
        val extractor = youtube.kioskList.defaultKioskExtractor
        extractor.fetchPage()
        extractor.initialPage.items.mapNotNull { it.toVideoItem() }
    }

    suspend fun resolvePlayable(url: String): PlayableStream = withContext(Dispatchers.IO) {
        val extractor = youtube.getStreamExtractor(url)
        extractor.fetchPage()

        val title = extractor.name.orEmpty()
        val uploader = extractor.uploaderName.orEmpty()

        // Prefer progressive muxed streams (audio+video in one URL) — no ads in stream.
        val muxed = extractor.videoStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { !it.content.isNullOrBlank() }
            .sortedWith(compareByDescending<VideoStream> { it.height }.thenByDescending { it.bitrate })

        muxed.firstOrNull()?.let { chosen ->
            return@withContext PlayableStream(
                videoUrl = chosen.content,
                audioUrl = null,
                title = title,
                uploader = uploader,
                qualityLabel = chosen.getResolution().ifBlank { "${chosen.height}p" }
            )
        }

        // Fallback: separate video + audio (common on YouTube)
        val videoOnly = extractor.videoOnlyStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { !it.content.isNullOrBlank() }
            .sortedWith(compareByDescending<VideoStream> { it.height }.thenByDescending { it.bitrate })

        val audio = extractor.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { !it.content.isNullOrBlank() }
            .maxByOrNull { it.averageBitrate }

        val video = videoOnly.firstOrNull()
            ?: extractor.videoStreams.firstOrNull { !it.content.isNullOrBlank() }
            ?: throw IllegalStateException("Uygun video akışı bulunamadı")

        PlayableStream(
            videoUrl = video.content,
            audioUrl = audio?.content,
            title = title,
            uploader = uploader,
            qualityLabel = video.getResolution().ifBlank { "${video.height}p" }
        )
    }

    private fun InfoItem.toVideoItem(): VideoItem? {
        if (this !is StreamInfoItem) return null
        val id = url.substringAfter("v=", url.substringAfterLast('/'))
        return VideoItem(
            id = id,
            url = url,
            title = name.orEmpty(),
            uploader = uploaderName.orEmpty(),
            thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
            durationSeconds = duration,
            viewCount = viewCount
        )
    }

    companion object {
        fun formatDuration(seconds: Long): String {
            if (seconds <= 0L) return ""
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%d:%02d", m, s)
            }
        }

        fun formatViews(count: Long): String {
            if (count <= 0L) return ""
            return when {
                count >= 1_000_000_000 -> String.format(Locale("tr"), "%.1f Mr", count / 1_000_000_000.0)
                count >= 1_000_000 -> String.format(Locale("tr"), "%.1f Mn", count / 1_000_000.0)
                count >= 1_000 -> String.format(Locale("tr"), "%.1f B", count / 1_000.0)
                else -> count.toString()
            }
        }
    }
}
