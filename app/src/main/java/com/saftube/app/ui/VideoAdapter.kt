package com.saftube.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.saftube.app.R
import com.saftube.app.data.VideoItem
import com.saftube.app.databinding.ItemVideoBinding
import com.saftube.app.util.Formatters

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit
) : ListAdapter<VideoItem, VideoAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: VideoItem) {
            binding.tvTitle.text = item.title
            val views = Formatters.views(item.views)
            binding.tvMeta.text = buildString {
                append(item.uploader.ifBlank { "YouTube" })
                if (item.views > 0) {
                    append("  ·  ")
                    append(binding.root.context.getString(R.string.views_format, views))
                }
                if (!item.uploadedDate.isNullOrBlank()) {
                    append("  ·  ")
                    append(item.uploadedDate)
                }
            }
            val duration = Formatters.duration(item.durationSeconds)
            binding.tvDuration.isVisible = duration.isNotBlank()
            binding.tvDuration.text = duration

            binding.ivThumb.load(item.thumbnailUrl) {
                crossfade(220)
                placeholder(R.drawable.bg_thumb)
                error(R.drawable.bg_thumb)
                transformations(RoundedCornersTransformation(28f))
            }

            binding.root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoItem>() {
            override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem) = oldItem == newItem
        }
    }
}
