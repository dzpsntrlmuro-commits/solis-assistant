package com.apkatolye.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apkatolye.app.R
import com.apkatolye.app.apk.DexClassReader
import com.apkatolye.app.apk.WorkspacePaths
import com.apkatolye.app.databinding.ActivityClassesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClassesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClassesBinding
    private var allClasses: List<String> = emptyList()
    private val adapter = ClassAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClassesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.count.text = "DEX okunuyor…"

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        lifecycleScope.launch {
            try {
                allClasses = withContext(Dispatchers.IO) {
                    DexClassReader.listClasses(WorkspacePaths.selectedApk(this@ClassesActivity))
                }
                applyFilter(binding.search.text?.toString().orEmpty())
            } catch (e: Exception) {
                binding.count.text = "Hata: ${e.message}"
            }
        }
    }

    private fun applyFilter(query: String) {
        val filtered = if (query.isBlank()) allClasses
        else allClasses.filter { it.contains(query, ignoreCase = true) }
        adapter.submit(filtered)
        binding.count.text = "${filtered.size} / ${allClasses.size} sınıf"
    }

    private class ClassAdapter : RecyclerView.Adapter<ClassAdapter.VH>() {
        private var items: List<String> = emptyList()

        fun submit(data: List<String>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_class, parent, false) as TextView
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.text.text = items[position]
        }

        override fun getItemCount(): Int = items.size

        class VH(val text: TextView) : RecyclerView.ViewHolder(text)
    }
}
