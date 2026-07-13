package com.solis.assistant.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.solis.assistant.data.AudioRecord
import com.solis.assistant.databinding.ItemRecordBinding
import java.text.SimpleDateFormat
import java.util.*

class RecordAdapter : RecyclerView.Adapter<RecordAdapter.ViewHolder>() {

    private val items = mutableListOf<AudioRecord>()
    private val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submitList(list: List<AudioRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class ViewHolder(private val b: ItemRecordBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(record: AudioRecord) {
            b.tvTime.text = sdf.format(Date(record.timestamp))
            b.tvAnalysis.text = record.analysis.ifEmpty { record.transcript }
            b.tvSuggestions.text = record.suggestions
        }
    }
}
