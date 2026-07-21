package com.hellokittiy.launcher.ui

import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hellokittiy.launcher.databinding.ItemAppBinding
import com.hellokittiy.launcher.databinding.ItemNotificationBinding
import com.hellokittiy.launcher.model.AppItem
import com.hellokittiy.launcher.model.NotifItem

class AppAdapter(
    private var items: List<AppItem>,
    private val onClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvAppName.text = item.label
        holder.binding.imgAppIcon.setImageDrawable(item.icon)
        holder.binding.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun submit(list: List<AppItem>) {
        items = list
        notifyDataSetChanged()
    }
}

class NotifAdapter(
    private var items: List<NotifItem>
) : RecyclerView.Adapter<NotifAdapter.VH>() {

    class VH(val binding: ItemNotificationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemNotificationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvNotifTitle.text = item.title
        holder.binding.tvNotifBody.text = item.body
    }

    override fun getItemCount(): Int = items.size

    fun submit(list: List<NotifItem>) {
        items = list
        notifyDataSetChanged()
    }
}

fun ResolveInfo.toAppItem(pm: PackageManager): AppItem {
    val label = loadLabel(pm)?.toString().orEmpty()
    val icon = loadIcon(pm)
    return AppItem(
        label = label,
        packageName = activityInfo.packageName,
        activityName = activityInfo.name,
        icon = icon
    )
}
