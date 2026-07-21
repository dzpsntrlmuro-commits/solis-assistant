package com.hellokittiy.launcher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hellokittiy.launcher.databinding.ItemNotificationBinding
import com.hellokittiy.launcher.model.NotifItem

class NotificationAdapter : RecyclerView.Adapter<NotificationAdapter.VH>() {

    private var items: List<NotifItem> = emptyList()

    fun submit(list: List<NotifItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemNotificationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    class VH(private val binding: ItemNotificationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: NotifItem) {
            binding.tvNotifTitle.text = item.title
            binding.tvNotifText.text = item.text.ifBlank { item.packageName }
        }
    }
}
