package com.temiztube.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.temiztube.app.R
import com.temiztube.app.data.YoutubeRepository
import com.temiztube.app.databinding.ItemVideoBinding
import com.temiztube.app.model.VideoItem

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit
) : ListAdapter<VideoItem, VideoAdapter.VideoVH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoVH {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoVH(binding)
    }

    override fun onBindViewHolder(holder: VideoVH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VideoVH(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: VideoItem) {
            binding.title.text = item.title
            val duration = YoutubeRepository.formatDuration(item.durationSeconds)
            binding.duration.text = duration
            binding.duration.visibility =
                if (duration.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

            val views = YoutubeRepository.formatViews(item.viewCount)
            binding.meta.text = buildString {
                append(item.uploader.ifBlank { "YouTube" })
                if (views.isNotBlank()) {
                    append(" · ")
                    append(binding.root.context.getString(R.string.views_format, views))
                }
            }

            val radius = binding.root.resources.displayMetrics.density * 6f
            binding.thumbnail.load(item.thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_thumb)
                error(R.drawable.bg_thumb)
                transformations(RoundedCornersTransformation(radius))
            }

            binding.root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoItem>() {
            override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem) =
                oldItem == newItem
        }
    }
}
