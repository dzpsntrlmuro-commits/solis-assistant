package com.temiztube.app.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.temiztube.app.R
import com.temiztube.app.data.YoutubeRepository
import com.temiztube.app.databinding.ActivityPlayerBinding
import com.temiztube.app.model.PlayableStream
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val repository = YoutubeRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val uploader = intent.getStringExtra(EXTRA_UPLOADER).orEmpty()

        binding.playerTitle.text = title
        binding.playerMeta.text = uploader.ifBlank { "YouTube" }
        binding.backButton.setOnClickListener { finish() }

        if (url.isBlank()) {
            showError(getString(R.string.error_stream))
            return
        }

        loadStream(url)
    }

    private fun loadStream(url: String) {
        binding.streamLoading.isVisible = true
        binding.playerError.isVisible = false

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
                }
        }
    }

    private fun startPlayer(stream: PlayableStream) {
        releasePlayer()

        val httpFactory = OkHttpDataSource.Factory(
            OkHttpClient.Builder().build()
        ).setUserAgent(
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        )

        val mediaFactory = ProgressiveMediaSource.Factory(httpFactory)
        val videoSource = mediaFactory.createMediaSource(MediaItem.fromUri(stream.videoUrl))
        val mediaSource = if (!stream.audioUrl.isNullOrBlank()) {
            val audioSource = mediaFactory.createMediaSource(MediaItem.fromUri(stream.audioUrl))
            MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }

        val exo = ExoPlayer.Builder(this).build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    binding.streamLoading.isVisible = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.streamLoading.isVisible = false
                showError(error.message ?: getString(R.string.error_stream))
            }
        })

        binding.playerView.player = exo
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = true
        player = exo
        binding.streamLoading.isVisible = false
    }

    private fun showError(message: String) {
        binding.playerError.text = message
        binding.playerError.isVisible = true
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        binding.playerView.player = null
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_UPLOADER = "extra_uploader"
    }
}
