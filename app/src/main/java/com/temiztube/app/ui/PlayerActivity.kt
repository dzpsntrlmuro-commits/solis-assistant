package com.temiztube.app.ui

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.temiztube.app.data.MediaDownloader
import com.temiztube.app.data.YoutubeRepository
import com.temiztube.app.databinding.ActivityPlayerBinding
import com.temiztube.app.model.DownloadAssets
import com.temiztube.app.model.PlayableStream
import com.temiztube.app.model.StreamKind
import kotlinx.coroutines.Job
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
    private var resolveJob: Job? = null
    private var usingExo = false
    private var isFullscreen = false
    private var webCustomView: View? = null
    private var webCustomViewCallback: WebChromeClient.CustomViewCallback? = null
    private var downloadAssets: DownloadAssets? = null
    private var downloadJob: Job? = null
    private var currentPlayable: PlayableStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val uploader = intent.getStringExtra(EXTRA_UPLOADER).orEmpty()
        videoId = YoutubeRepository.extractVideoId(videoUrl).orEmpty()

        binding.playerTitle.text = title
        binding.playerMeta.text = uploader.ifBlank { "Murovideo" }
        binding.backButton.setOnClickListener {
            if (isFullscreen) exitFullscreen() else finish()
        }
        binding.fullscreenButton.setOnClickListener { toggleFullscreen() }
        binding.retryButton.setOnClickListener { startFastPlayback() }
        binding.webFallbackButton.isVisible = false
        binding.downloadVideoButton.setOnClickListener { startDownload(video = true) }
        binding.downloadMp3Button.setOnClickListener { startDownload(video = false) }

        binding.playerView.setFullscreenButtonClickListener { enter ->
            if (enter) enterFullscreen() else exitFullscreen()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        webCustomView != null -> hideWebCustomView()
                        isFullscreen -> exitFullscreen()
                        else -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        )

        if (videoUrl.isBlank() || videoId.isBlank()) {
            showError(getString(R.string.error_stream))
            return
        }

        startFastPlayback()
    }

    private fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        if (isFullscreen) return
        isFullscreen = true

        binding.infoPanel.isVisible = false
        binding.backButton.isVisible = false
        binding.fullscreenButton.setImageResource(R.drawable.ic_fullscreen_exit)
        binding.fullscreenButton.contentDescription = getString(R.string.fullscreen_exit)

        val set = ConstraintSet()
        set.clone(binding.playerRoot)
        set.clear(R.id.playerContainer, ConstraintSet.BOTTOM)
        set.connect(
            R.id.playerContainer,
            ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID,
            ConstraintSet.BOTTOM
        )
        set.applyTo(binding.playerRoot)

        binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun exitFullscreen() {
        if (!isFullscreen && webCustomView == null) return
        hideWebCustomView()
        if (!isFullscreen) return
        isFullscreen = false

        binding.infoPanel.isVisible = true
        binding.backButton.isVisible = true
        binding.fullscreenButton.setImageResource(R.drawable.ic_fullscreen)
        binding.fullscreenButton.contentDescription = getString(R.string.fullscreen)

        val set = ConstraintSet()
        set.clone(binding.playerRoot)
        set.clear(R.id.playerContainer, ConstraintSet.BOTTOM)
        set.connect(
            R.id.playerContainer,
            ConstraintSet.BOTTOM,
            R.id.infoPanel,
            ConstraintSet.TOP
        )
        set.applyTo(binding.playerRoot)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun startFastPlayback() {
        usingExo = false
        resolveJob?.cancel()
        binding.playerError.isVisible = false
        binding.retryButton.isVisible = false
        binding.streamLoading.isVisible = true

        openAdBlockedWebPlayer()

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
                src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=0&fs=1&rel=0&modestbranding=1&controls=1"
                allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
                allowfullscreen
                webkitallowfullscreen
                mozallowfullscreen></iframe>
              <script>
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
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                hideWebCustomView()
                webCustomView = view
                webCustomViewCallback = callback
                binding.webFullscreenContainer.removeAllViews()
                binding.webFullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                binding.webFullscreenContainer.isVisible = true
                enterFullscreen()
            }

            override fun onHideCustomView() {
                hideWebCustomView()
                exitFullscreen()
            }

            override fun getDefaultVideoPoster(): Bitmap? {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
        }
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
                view?.evaluateJavascript(AD_SKIP_JS, null)
            }
        }
        return wv
    }

    private fun hideWebCustomView() {
        webCustomViewCallback?.onCustomViewHidden()
        webCustomViewCallback = null
        webCustomView = null
        binding.webFullscreenContainer.removeAllViews()
        binding.webFullscreenContainer.isVisible = false
    }

    private fun startExoPlayer(stream: PlayableStream) {
        usingExo = true
        currentPlayable = stream
        removeWebView()
        binding.playerView.isVisible = true
        binding.streamLoading.isVisible = true
        binding.playerError.isVisible = false

        repository.downloadAssetsFromPlayable(stream)?.let { fromPlayable ->
            val existing = downloadAssets
            downloadAssets = if (existing == null) {
                fromPlayable
            } else {
                existing.copy(
                    videoUrl = existing.videoUrl ?: fromPlayable.videoUrl,
                    videoFileName = if (existing.videoUrl.isNullOrBlank()) {
                        fromPlayable.videoFileName
                    } else {
                        existing.videoFileName
                    },
                    audioUrl = existing.audioUrl ?: fromPlayable.audioUrl,
                    audioFileName = if (existing.audioUrl.isNullOrBlank()) {
                        fromPlayable.audioFileName
                    } else {
                        existing.audioFileName
                    }
                )
            }
        }
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

    private fun startDownload(video: Boolean) {
        if (videoUrl.isBlank()) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            return
        }
        downloadJob?.cancel()
        Toast.makeText(this, R.string.download_preparing, Toast.LENGTH_SHORT).show()
        binding.downloadVideoButton.isEnabled = false
        binding.downloadMp3Button.isEnabled = false

        downloadJob = lifecycleScope.launch {
            var assets = downloadAssets
                ?: runCatching { repository.resolveDownloadAssets(videoUrl) }.getOrNull()
                ?: currentPlayable?.let { repository.downloadAssetsFromPlayable(it) }

            if (assets != null) {
                downloadAssets = assets
            }

            if (assets == null) {
                binding.downloadVideoButton.isEnabled = true
                binding.downloadMp3Button.isEnabled = true
                Toast.makeText(this@PlayerActivity, R.string.download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }

            try {
                val url: String?
                val fileName: String
                val mime: String
                if (video) {
                    url = assets.videoUrl
                    fileName = assets.videoFileName
                    mime = assets.videoMime
                    if (url.isNullOrBlank()) {
                        Toast.makeText(this@PlayerActivity, R.string.download_no_video, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                } else {
                    url = assets.audioUrl
                    fileName = assets.audioFileName
                    mime = assets.audioMime
                    if (url.isNullOrBlank()) {
                        Toast.makeText(this@PlayerActivity, R.string.download_no_audio, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }

                Toast.makeText(this@PlayerActivity, R.string.download_started, Toast.LENGTH_SHORT).show()
                try {
                    MediaDownloader.download(
                        context = this@PlayerActivity,
                        url = url,
                        fileName = fileName,
                        mimeType = mime
                    )
                } catch (first: Exception) {
                    // Stale/proxy URL — force a fresh resolve and retry once
                    downloadAssets = null
                    assets = runCatching { repository.resolveDownloadAssets(videoUrl) }.getOrNull()
                        ?: throw first
                    downloadAssets = assets
                    val retryUrl = if (video) assets.videoUrl else assets.audioUrl
                    val retryName = if (video) assets.videoFileName else assets.audioFileName
                    val retryMime = if (video) assets.videoMime else assets.audioMime
                    if (retryUrl.isNullOrBlank() || retryUrl == url) throw first
                    MediaDownloader.download(
                        context = this@PlayerActivity,
                        url = retryUrl,
                        fileName = retryName,
                        mimeType = retryMime
                    )
                }
                Toast.makeText(this@PlayerActivity, R.string.download_complete, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@PlayerActivity,
                    e.message?.take(120) ?: getString(R.string.download_failed),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.downloadVideoButton.isEnabled = true
                binding.downloadMp3Button.isEnabled = true
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
        hideWebCustomView()
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
        downloadJob?.cancel()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
