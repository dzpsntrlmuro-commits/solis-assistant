package com.saftube.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.saftube.app.R
import com.saftube.app.data.PipedRepository
import com.saftube.app.data.VideoStream
import com.saftube.app.databinding.ActivityPlayerBinding
import com.saftube.app.util.Formatters
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val repo = PipedRepository()
    private var player: ExoPlayer? = null
    private lateinit var relatedAdapter: VideoAdapter
    private var currentVideoId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentVideoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        val titleHint = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.tvPlayerTitle.text = titleHint.ifBlank { getString(R.string.player_loading) }

        relatedAdapter = VideoAdapter { item ->
            loadVideo(item.id)
        }
        binding.rvRelated.layoutManager = LinearLayoutManager(this)
        binding.rvRelated.adapter = relatedAdapter

        binding.btnBack.setOnClickListener { finish() }

        if (currentVideoId.isBlank()) {
            Toast.makeText(this, R.string.error_play, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        loadVideo(currentVideoId)
    }

    private fun loadVideo(videoId: String) {
        currentVideoId = videoId
        binding.playerProgress.isVisible = true
        lifecycleScope.launch {
            try {
                val stream = repo.streams(videoId)
                bindMeta(stream)
                playStream(stream)
            } catch (e: Exception) {
                binding.playerProgress.isVisible = false
                Toast.makeText(
                    this@PlayerActivity,
                    e.message ?: getString(R.string.error_play),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun bindMeta(stream: VideoStream) {
        binding.tvPlayerTitle.text = stream.title
        binding.tvPlayerMeta.text = buildString {
            append(stream.uploader.ifBlank { "YouTube" })
            if (stream.views > 0) {
                append("  ·  ")
                append(getString(R.string.views_format, Formatters.views(stream.views)))
            }
            if (stream.blockedAdHosts > 0) {
                append("  ·  ")
                append(getString(R.string.blocked_count, stream.blockedAdHosts))
            }
        }
        binding.tvPlayerDesc.text = stream.description.ifBlank { getString(R.string.ad_blocked_badge) }
        relatedAdapter.submitList(stream.related)
        binding.tvRelatedHeader.isVisible = stream.related.isNotEmpty()
    }

    private fun playStream(stream: VideoStream) {
        val url = stream.hlsUrl
            ?: stream.dashUrl
            ?: stream.progressiveUrl

        if (url.isNullOrBlank()) {
            binding.playerProgress.isVisible = false
            Toast.makeText(this, R.string.error_play, Toast.LENGTH_LONG).show()
            return
        }

        releasePlayer()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this))
            .build()
        player = exo
        binding.playerView.player = exo

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                binding.playerProgress.isVisible = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                // Try progressive fallback if HLS/DASH failed
                val fallback = stream.progressiveUrl
                if (!fallback.isNullOrBlank() && url != fallback) {
                    exo.setMediaItem(MediaItem.fromUri(fallback))
                    exo.prepare()
                    exo.playWhenReady = true
                } else {
                    Toast.makeText(this@PlayerActivity, R.string.error_play, Toast.LENGTH_LONG).show()
                    binding.playerProgress.isVisible = false
                }
            }
        })

        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        binding.playerView.player = null
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        player?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_TITLE = "title"
    }
}
