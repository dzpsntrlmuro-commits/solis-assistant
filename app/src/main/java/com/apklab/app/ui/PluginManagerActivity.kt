package com.apklab.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apklab.app.R
import com.apklab.app.databinding.ActivityPluginsBinding
import com.apklab.app.plugin.ApkPlugin
import com.apklab.app.plugin.PluginEngine

class PluginManagerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityPluginsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        PluginEngine.installSampleExternalPlugin(this)
        val plugins = PluginEngine.allPlugins(this)

        binding.rvPlugins.layoutManager = LinearLayoutManager(this)
        binding.rvPlugins.adapter = PluginAdapter(plugins)
    }
}

private class PluginAdapter(
    private val items: List<ApkPlugin>
) : RecyclerView.Adapter<PluginAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_plugin, parent, false)
        return Holder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.id.text = item.id
        holder.desc.text = item.description
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvPluginName)
        val id: TextView = view.findViewById(R.id.tvPluginId)
        val desc: TextView = view.findViewById(R.id.tvPluginDesc)
    }
}
