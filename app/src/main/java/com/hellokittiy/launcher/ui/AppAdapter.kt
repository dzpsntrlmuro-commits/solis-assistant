package com.hellokittiy.launcher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hellokittiy.launcher.databinding.ItemAppBinding
import com.hellokittiy.launcher.model.AppItem

class AppAdapter(
    private val onClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppItem> = emptyList()

    fun submit(list: List<AppItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AppItem) {
            binding.tvLabel.text = item.label
            binding.imgIcon.setImageDrawable(item.icon)
            binding.root.setOnClickListener { onClick(item) }
            binding.root.animate().cancel()
            binding.root.alpha = 0f
            binding.root.scaleX = 0.85f
            binding.root.scaleY = 0.85f
            binding.root.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setStartDelay((bindingAdapterPosition % 8) * 30L)
                .start()
        }
    }
}
