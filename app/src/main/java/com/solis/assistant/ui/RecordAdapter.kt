package com.solis.assistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.solis.assistant.data.AudioRecord
import java.text.SimpleDateFormat
import java.util.*

class RecordAdapter(private val records: List<AudioRecord>) :
    RecyclerView.Adapter<RecordAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(android.R.id.text1)
        val tvContent: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(record.timestamp))
        holder.tvTime.text = "🕐 $time"
        holder.tvContent.text = if (record.analysis.isNotEmpty()) record.analysis else record.transcript.take(100)
    }

    override fun getItemCount() = records.size
}
