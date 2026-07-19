package com.apklab.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apklab.app.R
import com.apklab.app.core.ApkExtractor
import com.apklab.app.core.ApkRepacker
import com.apklab.app.core.DexInspector
import com.apklab.app.core.ManifestDecoder
import com.apklab.app.databinding.ActivityWorkspaceBinding
import com.apklab.app.plugin.PluginEngine
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WorkspaceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWorkspaceBinding
    private lateinit var extractDir: File
    private lateinit var label: String
    private lateinit var packageName: String

    private val lineAdapter = LineAdapter { line ->
        if (line.startsWith("FILE:")) {
            val path = line.removePrefix("FILE:").substringBefore(" (").trim()
            val file = File(extractDir, path)
            if (file.exists() && file.isFile && file.length() < 2_000_000) {
                startActivity(
                    Intent(this, CodeViewerActivity::class.java).apply {
                        putExtra(CodeViewerActivity.EXTRA_PATH, file.absolutePath)
                        putExtra(CodeViewerActivity.EXTRA_TITLE, path)
                    }
                )
            }
        } else if (line.startsWith("REPORT:")) {
            val path = line.removePrefix("REPORT:").trim()
            startActivity(
                Intent(this, CodeViewerActivity::class.java).apply {
                    putExtra(CodeViewerActivity.EXTRA_PATH, path)
                    putExtra(CodeViewerActivity.EXTRA_TITLE, File(path).name)
                }
            )
        }
    }

    private var classes: List<String> = emptyList()
    private var strings: List<String> = emptyList()
    private var files: List<String> = emptyList()
    private var overview: List<String> = emptyList()
    private var pluginLines: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkspaceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        label = intent.getStringExtra(EXTRA_LABEL) ?: "APK"
        packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: "?"
        extractDir = File(intent.getStringExtra(EXTRA_DIR) ?: error("extract dir missing"))

        binding.tvTitle.text = label
        binding.tvSubtitle.text = packageName
        binding.rvContent.layoutManager = LinearLayoutManager(this)
        binding.rvContent.adapter = lineAdapter

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_overview))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_classes))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_strings))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_files))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_plugins))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.btnRunPlugins.setOnClickListener { runPlugins() }
        binding.btnRebuild.setOnClickListener { rebuild() }

        analyze()
    }

    private fun analyze() {
        lifecycleScope.launch {
            binding.progress.isVisible = true
            binding.tvWorkspaceStatus.text = getString(R.string.status_analyzing)
            val result = withContext(Dispatchers.IO) {
                val manifest = ManifestDecoder.decode(extractDir)
                val permissions = ManifestDecoder.extractPermissions(extractDir)
                val (cls, str) = DexInspector.inspect(extractDir)
                val fileEntries = ApkExtractor.listFiles(extractDir)
                AnalyzeBundle(
                    overview = buildList {
                        add("Paket: $packageName")
                        add("Etiket: $label")
                        add("Sınıf sayısı: ${cls.size}")
                        add("Metin sayısı: ${str.size}")
                        add("Dosya sayısı: ${fileEntries.count { !it.isDirectory }}")
                        add("İzin sayısı: ${permissions.size}")
                        add("")
                        add("--- İzinler ---")
                        addAll(permissions.take(40))
                        add("")
                        add("--- Manifest (özet) ---")
                        addAll(manifest.lines().take(80))
                    },
                    classes = cls.map { it.name },
                    strings = str.map { it.value }.take(1500),
                    files = fileEntries.filter { !it.isDirectory }.map {
                        "FILE:${it.relativePath} (${it.sizeBytes} B)"
                    }
                )
            }
            overview = result.overview
            classes = result.classes
            strings = result.strings
            files = result.files
            binding.progress.isVisible = false
            binding.tvWorkspaceStatus.text =
                "${classes.size} sınıf · ${strings.size} metin · ${files.size} dosya"
            showTab(0)
        }
    }

    private fun showTab(index: Int) {
        val data = when (index) {
            1 -> classes
            2 -> strings
            3 -> files
            4 -> pluginLines.ifEmpty { listOf("Henüz eklenti çalıştırılmadı") }
            else -> overview
        }
        lineAdapter.submit(data)
    }

    private fun runPlugins() {
        lifecycleScope.launch {
            binding.progress.isVisible = true
            binding.tvWorkspaceStatus.text = "Eklentiler çalışıyor…"
            val results = withContext(Dispatchers.IO) {
                PluginEngine.runAll(
                    this@WorkspaceActivity,
                    extractDir,
                    packageName,
                    label
                )
            }
            pluginLines = results.flatMap { r ->
                listOf(
                    "▶ ${r.pluginName}: ${r.summary}",
                    *(r.details.take(12).toTypedArray()),
                    r.outputFile?.let { "REPORT:$it" } ?: "",
                    ""
                )
            }
            binding.progress.isVisible = false
            binding.tvWorkspaceStatus.text = "${results.size} eklenti tamamlandı"
            binding.tabLayout.getTabAt(4)?.select()
            showTab(4)
            Toast.makeText(this@WorkspaceActivity, "Raporlar _apklab_reports/ altında", Toast.LENGTH_SHORT).show()
        }
    }

    private fun rebuild() {
        lifecycleScope.launch {
            binding.progress.isVisible = true
            binding.btnRebuild.isEnabled = false
            binding.tvWorkspaceStatus.text = getString(R.string.status_rebuilding)
            try {
                val result = withContext(Dispatchers.IO) {
                    ApkRepacker.rebuild(
                        this@WorkspaceActivity,
                        extractDir,
                        outName = packageName.ifBlank { label }
                    )
                }
                binding.tvWorkspaceStatus.text = result.message
                Toast.makeText(this@WorkspaceActivity, result.message, Toast.LENGTH_LONG).show()
                shareApk(File(result.signedApk))
            } catch (e: Exception) {
                binding.tvWorkspaceStatus.text = "Paketleme hatası: ${e.message}"
                Toast.makeText(this@WorkspaceActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                binding.progress.isVisible = false
                binding.btnRebuild.isEnabled = true
            }
        }
    }

    private fun shareApk(file: File) {
        val shareUri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Yeniden paketlenmiş APK"))
    }

    private data class AnalyzeBundle(
        val overview: List<String>,
        val classes: List<String>,
        val strings: List<String>,
        val files: List<String>
    )

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_DIR = "dir"
        const val EXTRA_SOURCE = "source"
    }
}

private class LineAdapter(
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<LineAdapter.Holder>() {
    private val items = mutableListOf<String>()

    fun submit(data: List<String>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_line, parent, false)
        return Holder(view.findViewById(R.id.tvLine))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val line = items[position]
        holder.tv.text = line
        holder.tv.setOnClickListener { onClick(line) }
    }

    class Holder(val tv: TextView) : RecyclerView.ViewHolder(tv)
}
