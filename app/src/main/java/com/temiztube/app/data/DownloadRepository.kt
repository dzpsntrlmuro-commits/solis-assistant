package com.temiztube.app.data

import android.content.Context
import com.temiztube.app.model.DownloadAssets
import com.temiztube.app.model.DownloadKind
import com.temiztube.app.model.DownloadStatus
import com.temiztube.app.model.PlayableStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Queues downloads, tracks progress in [DownloadStore], and persists files locally.
 */
class DownloadRepository(context: Context) {

    private val appContext = context.applicationContext
    private val store = DownloadStore.get(appContext)
    private val youtubeRepository = YoutubeRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun enqueue(
        videoUrl: String,
        videoId: String,
        title: String,
        wantVideo: Boolean,
        cachedAssets: DownloadAssets?,
        cachedPlayable: PlayableStream?,
        capturedVideoUrl: String?,
        capturedAudioUrl: String?
    ): String {
        val kind = if (wantVideo) DownloadKind.VIDEO else DownloadKind.AUDIO
        val provisionalName = MediaDownloader.sanitizeFileName(
            title.ifBlank { "murotube" },
            if (wantVideo) "mp4" else "m4a"
        )
        val item = store.create(
            videoId = videoId,
            title = title.ifBlank { "Murotube" },
            kind = kind,
            fileName = provisionalName
        )

        scope.launch {
            mutex.withLock {
                runDownload(
                    itemId = item.id,
                    videoUrl = videoUrl,
                    wantVideo = wantVideo,
                    cachedAssets = cachedAssets,
                    cachedPlayable = cachedPlayable,
                    capturedVideoUrl = capturedVideoUrl,
                    capturedAudioUrl = capturedAudioUrl
                )
            }
        }
        return item.id
    }

    private suspend fun runDownload(
        itemId: String,
        videoUrl: String,
        wantVideo: Boolean,
        cachedAssets: DownloadAssets?,
        cachedPlayable: PlayableStream?,
        capturedVideoUrl: String?,
        capturedAudioUrl: String?
    ) {
        store.update(itemId) {
            it.copy(status = DownloadStatus.DOWNLOADING, progressPercent = 0, errorMessage = null)
        }

        try {
            val assets = resolveAssets(
                videoUrl = videoUrl,
                wantVideo = wantVideo,
                cachedAssets = cachedAssets,
                cachedPlayable = cachedPlayable,
                capturedVideoUrl = capturedVideoUrl,
                capturedAudioUrl = capturedAudioUrl
            )

            val urlsToTry = linkedSetOf<String>()
            val fileName: String
            val mime: String
            if (wantVideo) {
                assets.videoUrl?.takeIf { it.isNotBlank() }?.let { urlsToTry.add(it) }
                capturedVideoUrl?.takeIf { it.isNotBlank() }?.let { urlsToTry.add(it) }
                cachedPlayable?.videoUrl?.takeIf { it.isNotBlank() }?.let { urlsToTry.add(it) }
                cachedAssets?.videoUrl?.takeIf { !it.isNullOrBlank() }?.let { urlsToTry.add(it) }
                fileName = assets.videoFileName
                mime = assets.videoMime
                if (urlsToTry.isEmpty()) throw IllegalStateException("Video dosyası bulunamadı")
            } else {
                assets.audioUrl?.takeIf { it.isNotBlank() }?.let { urlsToTry.add(it) }
                capturedAudioUrl?.takeIf { it.isNotBlank() }?.let { urlsToTry.add(it) }
                cachedPlayable?.audioUrl?.takeIf { !it.isNullOrBlank() }?.let { urlsToTry.add(it) }
                cachedAssets?.audioUrl?.takeIf { !it.isNullOrBlank() }?.let { urlsToTry.add(it) }
                fileName = assets.audioFileName
                mime = assets.audioMime
                if (urlsToTry.isEmpty()) throw IllegalStateException("Ses dosyası bulunamadı")
            }

            store.update(itemId) {
                it.copy(title = assets.title.ifBlank { it.title }, fileName = fileName)
            }

            val ordered = urlsToTry.sortedByDescending { MediaDownloader.isReachable(it) }

            var lastError: Exception? = null
            for (url in ordered) {
                try {
                    val result = MediaDownloader.download(
                        context = appContext,
                        url = url,
                        fileName = fileName,
                        mimeType = mime
                    ) { downloaded, total ->
                        val percent = if (total > 0) {
                            ((downloaded * 100) / total).toInt().coerceIn(0, 99)
                        } else {
                            -1
                        }
                        store.update(itemId) {
                            it.copy(
                                status = DownloadStatus.DOWNLOADING,
                                progressPercent = if (percent >= 0) percent else it.progressPercent,
                                bytesDownloaded = downloaded,
                                totalBytes = total
                            )
                        }
                    }
                    store.update(itemId) {
                        it.copy(
                            status = DownloadStatus.COMPLETED,
                            progressPercent = 100,
                            localPath = result.localFile.absolutePath,
                            publicUri = result.publicUri?.toString(),
                            fileName = result.localFile.name,
                            errorMessage = null,
                            bytesDownloaded = result.localFile.length(),
                            totalBytes = result.localFile.length()
                        )
                    }
                    return
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("İndirme başarısız")
        } catch (e: Exception) {
            store.update(itemId) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message?.take(160) ?: "İndirme başarısız"
                )
            }
        }
    }

    private suspend fun resolveAssets(
        videoUrl: String,
        wantVideo: Boolean,
        cachedAssets: DownloadAssets?,
        cachedPlayable: PlayableStream?,
        capturedVideoUrl: String?,
        capturedAudioUrl: String?
    ): DownloadAssets {
        val title = cachedAssets?.title
            ?: cachedPlayable?.title
            ?: "murotube"

        fun usable(assets: DownloadAssets?): Boolean {
            if (assets == null) return false
            return if (wantVideo) !assets.videoUrl.isNullOrBlank() else !assets.audioUrl.isNullOrBlank()
        }

        fun reachable(assets: DownloadAssets): Boolean {
            val url = if (wantVideo) assets.videoUrl else assets.audioUrl
            return !url.isNullOrBlank() && MediaDownloader.isReachable(url)
        }

        val fromCapture = if (!capturedVideoUrl.isNullOrBlank() || !capturedAudioUrl.isNullOrBlank()) {
            DownloadAssets(
                title = title,
                videoUrl = capturedVideoUrl ?: cachedAssets?.videoUrl,
                videoFileName = MediaDownloader.sanitizeFileName(title, "mp4"),
                videoMime = "video/mp4",
                audioUrl = capturedAudioUrl ?: cachedAssets?.audioUrl,
                audioFileName = MediaDownloader.sanitizeFileName(title, "m4a"),
                audioMime = "audio/mp4"
            )
        } else null

        val fromPlayable = cachedPlayable?.let { youtubeRepository.downloadAssetsFromPlayable(it) }

        listOf(fromCapture, cachedAssets, fromPlayable)
            .firstOrNull { usable(it) && reachable(it!!) }
            ?.let { return it }

        val resolved = runCatching { youtubeRepository.resolveDownloadAssets(videoUrl) }.getOrNull()
        if (usable(resolved) && reachable(resolved!!)) return resolved
        if (usable(resolved)) return resolved!!

        listOf(fromCapture, fromPlayable, cachedAssets, resolved)
            .firstOrNull { usable(it) }
            ?.let { return it!! }

        throw IllegalStateException("İndirme bağlantısı alınamadı")
    }

    companion object {
        @Volatile
        private var instance: DownloadRepository? = null

        fun get(context: Context): DownloadRepository {
            return instance ?: synchronized(this) {
                instance ?: DownloadRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
