package com.temiztube.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.temiztube.app.R
import com.temiztube.app.data.AdBlockFilter
import com.temiztube.app.data.YoutubeRepository
import com.temiztube.app.databinding.ActivityPlayerBinding
import com.temiztube.app.model.PlayableStream
import com.temiztube.app.model.StreamKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/**
 * Instant playback strategy:
 * 1) Open ad-blocked WebView immediately (no waiting)
 * 2) In parallel race Piped for a clean stream (≤5s)
 * 3) If clean stream arrives, switch to ExoPlayer (fully ad-free)
 */
@SuppressLint("SetJavaScriptEnabled", "UnsafeOptInUsageError")
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var webView: WebView? = null
    private val repository = YoutubeRepository()
    private var videoUrl: String = ""
    private var videoId: String = ""
    private var resolveJob: Job? = null
    private var usingExo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val uploader = intent.getStringExtra(EXTRA_UPLOADER).orEmpty()
        videoId = YoutubeRepository.extractVideoId(videoUrl).orEmpty()

        binding.playerTitle.text = title
        binding.playerMeta.text = uploader.ifBlank { "Murovideo" }
        binding.backButton.setOnClickListener { finish() }
        binding.retryButton.setOnClickListener { startFastPlayback() }
        binding.webFallbackButton.isVisible = false

        if (videoUrl.isBlank() || videoId.isBlank()) {
            showError(getString(R.string.error_stream))
            return
        }

        startFastPlayback()
    }

    private fun startFastPlayback() {
        usingExo = false
        resolveJob?.cancel()
        binding.playerError.isVisible = false
        binding.retryButton.isVisible = false
        binding.streamLoading.isVisible = true

        // 1) Instant: ad-blocked embedded player (no network race wait)
        openAdBlockedWebPlayer()

        // 2) Parallel: try clean Piped stream quickly, then switch
        resolveJob = lifecycleScope.launch {
            runCatching { repository.resolvePlayableFast(videoUrl) }
                .onSuccess { stream ->
                    if (isDestroyed || isFinishing) return@onSuccess
                    binding.playerTitle.text = stream.title.ifBlank { binding.playerTitle.text }
                    binding.playerMeta.text = buildString {
                        append(stream.uploader.ifBlank { "Murovideo" })
                        if (stream.qualityLabel.isNotBlank()) {
                            append(" · ")
                            append(stream.qualityLabel)
                        }
                    }
                    startExoPlayer(stream)
                }
                .onFailure {
                    // Keep WebView — already playing with ad blocking
                    binding.streamLoading.isVisible = false
                    binding.playerMeta.text = getString(R.string.fast_adblock_meta)
                }
        }
    }

    private fun openAdBlockedWebPlayer() {
        releasePlayer()
        binding.playerView.isVisible = false

        val wv = webView ?: createWebView().also { created ->
            binding.playerContainer.addView(
                created,
                0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            webView = created
        }

        // Custom HTML shell loads embed and aggressively skips ads
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
              <style>
                html,body{margin:0;background:#000;height:100%;overflow:hidden}
                iframe{position:fixed;inset:0;width:100%;height:100%;border:0}
              </style>
            </head>
            <body>
              <iframe
                id="player"
                src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&modestbranding=1&controls=1"
                allow="autoplay; encrypted-media; picture-in-picture"
                allowfullscreen></iframe>
              <script>
                // Keep trying to skip ads / hide ad UI inside this document shell.
                // Cross-origin iframe limits deep DOM access; network ads are blocked in shouldInterceptRequest.
                setInterval(function() {
                  try {
                    document.querySelectorAll(
                      '.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,' +
                      '.ytp-ad-overlay-close-button,.videoAdUiSkipButton'
                    ).forEach(function(b){ b.click(); });
                  } catch(e) {}
                }, 400);
              </script>
            </body>
            </html>
        """.trimIndent()

        wv.loadDataWithBaseURL(
            "https://www.youtube-nocookie.com",
            html,
            "text/html",
            "utf-8",
            null
        )
        binding.playerMeta.text = getString(R.string.fast_adblock_meta)
        binding.streamLoading.isVisible = false
    }

    private fun createWebView(): WebView {
        val wv = WebView(this)
        wv.setBackgroundColor(Color.BLACK)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        wv.settings.userAgentString = USER_AGENT
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun noop() = Unit
        }, "Murotube")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                if (url.isNotBlank() && AdBlockFilter.shouldBlock(url)) {
                    return EMPTY_RESPONSE
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.streamLoading.isVisible = false
                // Inject skipper into top-level page
                view?.evaluateJavascript(AD_SKIP_JS, null)
            }
        }
        return wv
    }

    private fun startExoPlayer(stream: PlayableStream) {
        usingExo = true
        removeWebView()
        binding.playerView.isVisible = true
        binding.streamLoading.isVisible = true
        binding.playerError.isVisible = false

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(12_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.youtube.com",
                    "Origin" to "https://www.youtube.com",
                    "Accept" to "*/*"
                )
            )

        val mediaSource = buildMediaSource(stream, httpFactory)
        val exo = ExoPlayer.Builder(this).build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    binding.streamLoading.isVisible = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.streamLoading.isVisible = false
                // Fall back to web player instantly
                usingExo = false
                openAdBlockedWebPlayer()
            }

            override fun onRenderedFirstFrame() {
                binding.streamLoading.isVisible = false
            }
        })

        binding.playerView.player = exo
        binding.playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = true
        player = exo
    }

    private fun buildMediaSource(
        stream: PlayableStream,
        httpFactory: DefaultHttpDataSource.Factory
    ): MediaSource {
        val item = MediaItem.fromUri(stream.videoUrl)
        return when (stream.kind) {
            StreamKind.HLS -> HlsMediaSource.Factory(httpFactory).createMediaSource(item)
            StreamKind.DASH -> DashMediaSource.Factory(httpFactory).createMediaSource(item)
            StreamKind.PROGRESSIVE -> ProgressiveMediaSource.Factory(httpFactory).createMediaSource(item)
            StreamKind.MERGED -> {
                val progressive = ProgressiveMediaSource.Factory(httpFactory)
                val videoSource = progressive.createMediaSource(item)
                val audioUrl = stream.audioUrl
                if (audioUrl.isNullOrBlank()) {
                    videoSource
                } else {
                    MergingMediaSource(
                        videoSource,
                        progressive.createMediaSource(MediaItem.fromUri(audioUrl))
                    )
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.playerError.text = message
        binding.playerError.isVisible = true
        binding.retryButton.isVisible = true
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        binding.playerView.player = null
    }

    private fun removeWebView() {
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        webView = null
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        resolveJob?.cancel()
        releasePlayer()
        removeWebView()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_UPLOADER = "extra_uploader"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private val EMPTY_RESPONSE = WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )

        private const val AD_SKIP_JS = """
            (function(){
              try {
                document.querySelectorAll(
                  '.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,' +
                  '.ytp-ad-overlay-close-button,.videoAdUiSkipButton'
                ).forEach(function(b){ b.click(); });
              } catch(e) {}
            })();
        """
    }
}
