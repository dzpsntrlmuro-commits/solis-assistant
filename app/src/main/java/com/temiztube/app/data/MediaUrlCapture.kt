package com.temiztube.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.temiztube.app.model.DownloadAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Loads mobile YouTube briefly to capture progressive googlevideo URLs for download.
 */
object MediaUrlCapture {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capture(
        context: Context,
        videoId: String,
        titleHint: String,
        timeoutMs: Long = 18_000L
    ): DownloadAssets? = withContext(Dispatchers.Main) {
        if (videoId.isBlank()) return@withContext null

        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val appCtx = context.applicationContext
                val videoRef = arrayOfNulls<String>(1)
                val audioRef = arrayOfNulls<String>(1)
                var finished = false
                val web = WebView(appCtx)

                fun finishWith(assets: DownloadAssets?) {
                    if (finished) return
                    finished = true
                    runCatching {
                        web.stopLoading()
                        web.destroy()
                    }
                    if (cont.isActive) cont.resume(assets)
                }

                fun maybeFinish() {
                    if (finished) return
                    val video = videoRef[0]
                    val audio = audioRef[0]
                    if (video.isNullOrBlank() && audio.isNullOrBlank()) return
                    finishWith(
                        DownloadAssets(
                            title = titleHint.ifBlank { "murotube" },
                            videoUrl = video,
                            videoFileName = MediaDownloader.sanitizeFileName(
                                titleHint.ifBlank { "murotube" },
                                "mp4"
                            ),
                            videoMime = "video/mp4",
                            audioUrl = audio,
                            audioFileName = MediaDownloader.sanitizeFileName(
                                titleHint.ifBlank { "murotube" },
                                "m4a"
                            ),
                            audioMime = "audio/mp4"
                        )
                    )
                }

                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.mediaPlaybackRequiresUserGesture = false
                web.settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                web.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY)
                )
                web.layout(0, 0, 400, 300)
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString().orEmpty()
                        classify(url)?.let { (isAudio, mediaUrl) ->
                            if (isAudio) {
                                if (audioRef[0].isNullOrBlank()) audioRef[0] = mediaUrl
                            } else {
                                if (videoRef[0].isNullOrBlank()) videoRef[0] = mediaUrl
                            }
                            view?.post { maybeFinish() }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                cont.invokeOnCancellation {
                    finishWith(null)
                }

                web.loadUrl("https://m.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1")
            }
        }
    }

    fun classify(url: String): Pair<Boolean, String>? {
        if (url.isBlank()) return null
        val lower = url.lowercase()
        val isMediaHost = lower.contains("googlevideo.com") ||
            lower.contains("videoplayback") ||
            lower.contains("odycdn.com")
        if (!isMediaHost) return null
        if (AdBlockFilter.shouldBlock(url)) return null

        val isAudio = lower.contains("mime=audio") ||
            lower.contains("audio%2f") ||
            lower.contains("mime=audio%2f")
        val isVideo = lower.contains("mime=video") ||
            lower.contains("video%2f") ||
            lower.contains("itag=18") ||
            lower.contains("itag=22") ||
            lower.contains("itag=37") ||
            lower.contains("itag=59") ||
            lower.contains("itag=78")

        return when {
            isAudio -> true to url
            isVideo -> false to url
            lower.contains("videoplayback") && !isAudio -> false to url
            else -> null
        }
    }
}
