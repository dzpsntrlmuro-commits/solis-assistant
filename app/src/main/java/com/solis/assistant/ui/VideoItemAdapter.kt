package com.solis.assistant.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.solis.assistant.databinding.ItemVideoBinding

class VideoItemAdapter(
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<VideoItemAdapter.VH>() {

    private val items = mutableListOf<Uri>()

    fun submit(list: List<Uri>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val uri = items[position]
        holder.binding.tvIndex.text = (position + 1).toString()
        holder.bindName(uri)
        holder.binding.btnRemove.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onRemove(pos)
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bindName(uri: Uri) {
            val name = binding.root.context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
            binding.tvName.text = name ?: uri.lastPathSegment ?: uri.toString()
        }
    }
}
