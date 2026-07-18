package com.temiztube.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled", "UnsafeOptInUsageError")
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var webView: WebView? = null
    private val repository = YoutubeRepository()
    private var videoUrl: String = ""
    private var videoId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val uploader = intent.getStringExtra(EXTRA_UPLOADER).orEmpty()
        videoId = YoutubeRepository.extractVideoId(videoUrl).orEmpty()

        binding.playerTitle.text = title
        binding.playerMeta.text = uploader.ifBlank { "YouTube" }
        binding.backButton.setOnClickListener { finish() }
        binding.retryButton.setOnClickListener {
            if (videoUrl.isNotBlank()) loadStream(videoUrl)
        }
        binding.webFallbackButton.setOnClickListener { openWebFallback() }

        if (videoUrl.isBlank()) {
            showError(getString(R.string.error_stream))
            return
        }

        loadStream(videoUrl)
    }

    private fun loadStream(url: String) {
        binding.streamLoading.isVisible = true
        binding.playerError.isVisible = false
        binding.retryButton.isVisible = false
        binding.webFallbackButton.isVisible = false
        binding.playerView.isVisible = true
        removeWebView()

        lifecycleScope.launch {
            runCatching { repository.resolvePlayable(url) }
                .onSuccess { stream ->
                    binding.playerTitle.text = stream.title.ifBlank { binding.playerTitle.text }
                    binding.playerMeta.text = buildString {
                        append(stream.uploader.ifBlank { "YouTube" })
                        if (stream.qualityLabel.isNotBlank()) {
                            append(" · ")
                            append(stream.qualityLabel)
                        }
                    }
                    startPlayer(stream)
                }
                .onFailure { error ->
                    binding.streamLoading.isVisible = false
                    showError(
                        error.message?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.error_stream)
                    )
                    // Auto-fallback to embedded player so user is not stuck on black screen
                    openWebFallback()
                }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startPlayer(stream: PlayableStream) {
        releasePlayer()
        binding.streamLoading.isVisible = true
        binding.playerError.isVisible = false
        binding.retryButton.isVisible = false
        binding.webFallbackButton.isVisible = false

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
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
                when (playbackState) {
                    Player.STATE_BUFFERING -> binding.streamLoading.isVisible = true
                    Player.STATE_READY, Player.STATE_ENDED -> binding.streamLoading.isVisible = false
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.streamLoading.isVisible = false
                val detail = error.cause?.message ?: error.message
                showError(detail ?: getString(R.string.error_stream))
                openWebFallback()
            }

            override fun onRenderedFirstFrame() {
                binding.streamLoading.isVisible = false
            }
        })

        binding.playerView.player = exo
        binding.playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = true
        player = exo
    }

    @SuppressLint("UnsafeOptInUsageError")
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
                    val audioSource = progressive.createMediaSource(MediaItem.fromUri(audioUrl))
                    MergingMediaSource(videoSource, audioSource)
                }
            }
        }
    }

    private fun openWebFallback() {
        if (videoId.isBlank()) return
        releasePlayer()
        binding.playerView.isVisible = false
        binding.streamLoading.isVisible = false
        binding.playerError.isVisible = false
        binding.retryButton.isVisible = true
        binding.webFallbackButton.isVisible = false

        if (webView == null) {
            val wv = WebView(this)
            wv.setBackgroundColor(Color.BLACK)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.mediaPlaybackRequiresUserGesture = false
            wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            wv.settings.userAgentString = USER_AGENT
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString().orEmpty()
                    if (url.isNotBlank() && AdBlockFilter.shouldBlock(url)) {
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            val container = binding.playerContainer
            container.addView(
                wv,
                0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            webView = wv
        }

        // Embed player — ad network requests are blocked via AdBlockFilter
        val embed =
            "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&modestbranding=1"
        webView?.loadUrl(embed)
        binding.playerMeta.text = getString(R.string.web_fallback_meta)
    }

    private fun showError(message: String) {
        binding.playerError.text = message
        binding.playerError.isVisible = true
        binding.retryButton.isVisible = true
        binding.webFallbackButton.isVisible = videoId.isNotBlank()
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

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
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
    }
}
