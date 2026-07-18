package com.temiztube.app.data

import com.temiztube.app.model.PlayableStream
import com.temiztube.app.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
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

    /**
     * Fast path only — Piped race, max 5 seconds. No slow NewPipe fallback.
     */
    suspend fun resolvePlayableFast(url: String): PlayableStream {
        val videoId = extractVideoId(url)
            ?: throw IllegalArgumentException("Geçersiz video bağlantısı")
        return withTimeout(5_000) {
            PipedClient.resolveStreamFast(videoId)
        }
    }

    private fun InfoItem.toVideoItem(): VideoItem? {
        if (this !is StreamInfoItem) return null
        val id = extractVideoId(url) ?: url.substringAfterLast('/')
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
        fun extractVideoId(url: String): String? {
            val patterns = listOf(
                Regex("""[?&]v=([a-zA-Z0-9_-]{11})"""),
                Regex("""youtu\.be/([a-zA-Z0-9_-]{11})"""),
                Regex("""shorts/([a-zA-Z0-9_-]{11})"""),
                Regex("""embed/([a-zA-Z0-9_-]{11})""")
            )
            for (p in patterns) {
                val m = p.find(url)
                if (m != null) return m.groupValues[1]
            }
            return if (url.matches(Regex("""^[a-zA-Z0-9_-]{11}$"""))) url else null
        }

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
