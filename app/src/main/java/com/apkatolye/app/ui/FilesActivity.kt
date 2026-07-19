package com.apkatolye.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apkatolye.app.R
import com.apkatolye.app.apk.ApkAnalyzer
import com.apkatolye.app.apk.WorkspacePaths
import com.apkatolye.app.databinding.ActivityFilesBinding
import java.io.File

class FilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilesBinding
    private val adapter = FileAdapter(
        onClick = { item -> onItemClick(item) },
        onLongClick = { item -> onItemLongClick(item) }
    )

    private var currentDir: File? = null
    private var currentTreeUri: Uri? = null
    private var currentDocs: List<DocumentFile> = emptyList()
    private var mode: Mode = Mode.LOCAL
    private var allLocal: List<FileItem> = emptyList()

    private enum class Mode { LOCAL, TREE }

    private val openTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        currentTreeUri = uri
        mode = Mode.TREE
        openTreeRoot(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnExtracted.setOnClickListener {
            mode = Mode.LOCAL
            openLocal(WorkspacePaths.extractDir(this))
        }
        binding.btnWorkspace.setOnClickListener {
            mode = Mode.LOCAL
            openLocal(WorkspacePaths.root(this))
        }
        binding.btnPickFolder.setOnClickListener { openTree.launch(null) }
        binding.btnUp.setOnClickListener { goUp() }

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
        })

        val start = intent.getStringExtra(EXTRA_START_DIR)
        val startDir = if (start != null) File(start) else WorkspacePaths.extractDir(this)
        openLocal(startDir)
    }

    private fun openLocal(dir: File) {
        currentTreeUri = null
        mode = Mode.LOCAL
        if (!dir.exists()) dir.mkdirs()
        currentDir = dir
        binding.pathLabel.text = dir.absolutePath
        val children = dir.listFiles()?.toList().orEmpty()
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .map {
                FileItem(
                    name = it.name,
                    meta = if (it.isDirectory) "klasör" else ApkAnalyzer.formatSize(it.length()),
                    isDirectory = it.isDirectory,
                    localFile = it,
                    document = null
                )
            }
        allLocal = children
        binding.emptyHint.visibility = if (children.isEmpty()) View.VISIBLE else View.GONE
        applyFilter(binding.search.text?.toString().orEmpty())
    }

    private fun openTreeRoot(uri: Uri) {
        val root = DocumentFile.fromTreeUri(this, uri) ?: return
        openTreeDir(root)
    }

    private fun openTreeDir(dir: DocumentFile) {
        mode = Mode.TREE
        currentDocs = dir.listFiles().toList()
            .sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name.orEmpty().lowercase() })
        binding.pathLabel.text = dir.uri.toString()
        val items = currentDocs.map {
            FileItem(
                name = it.name ?: "?",
                meta = if (it.isDirectory) "klasör" else ApkAnalyzer.formatSize(it.length()),
                isDirectory = it.isDirectory,
                localFile = null,
                document = it
            )
        }
        allLocal = items
        binding.emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        applyFilter(binding.search.text?.toString().orEmpty())
    }

    private fun applyFilter(query: String) {
        val filtered = if (query.isBlank()) allLocal
        else allLocal.filter { it.name.contains(query, ignoreCase = true) }
        adapter.submit(filtered)
    }

    private fun goUp() {
        if (mode == Mode.LOCAL) {
            val parent = currentDir?.parentFile ?: return
            val root = WorkspacePaths.root(this)
            if (parent.absolutePath.startsWith(root.absolutePath) || parent.canRead()) {
                openLocal(parent)
            }
        } else {
            val uri = currentTreeUri ?: return
            // DocumentFile has no reliable parent; reopen tree root
            openTreeRoot(uri)
            Toast.makeText(this, "Klasör köküne dönüldü", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onItemClick(item: FileItem) {
        if (item.isDirectory) {
            if (item.localFile != null) openLocal(item.localFile)
            else if (item.document != null) openTreeDir(item.document)
            return
        }

        if (isEditable(item.name)) {
            if (item.localFile != null) {
                startActivity(
                    Intent(this, FileEditorActivity::class.java)
                        .putExtra(FileEditorActivity.EXTRA_PATH, item.localFile.absolutePath)
                )
            } else if (item.document != null) {
                startActivity(
                    Intent(this, FileEditorActivity::class.java)
                        .putExtra(FileEditorActivity.EXTRA_URI, item.document.uri.toString())
                        .putExtra(FileEditorActivity.EXTRA_NAME, item.name)
                )
            }
        } else {
            Toast.makeText(
                this,
                "Bu dosya türü düzenlenemez. Uzun basarak sil / yeniden adlandırabilirsiniz.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun onItemLongClick(item: FileItem): Boolean {
        val options = mutableListOf("Yeniden adlandır", "Sil")
        if (!item.isDirectory && item.name.endsWith(".apk", true)) {
            options.add(0, "APK olarak seç")
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "APK olarak seç" -> selectAsApk(item)
                    "Yeniden adlandır" -> promptRename(item)
                    "Sil" -> deleteItem(item)
                }
            }
            .show()
        return true
    }

    private fun selectAsApk(item: FileItem) {
        val dest = WorkspacePaths.selectedApk(this)
        try {
            if (item.localFile != null) {
                item.localFile.copyTo(dest, overwrite = true)
            } else if (item.document != null) {
                contentResolver.openInputStream(item.document.uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Okunamadı")
            }
            WorkspacePaths.metaFile(this).writeText(item.name)
            Toast.makeText(this, "APK seçildi: ${item.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun promptRename(item: FileItem) {
        val input = android.widget.EditText(this).apply {
            setText(item.name)
            setSelection(item.name.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Yeniden adlandır")
            .setView(input)
            .setPositiveButton("Tamam") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                try {
                    if (item.localFile != null) {
                        val target = File(item.localFile.parentFile, newName)
                        if (!item.localFile.renameTo(target)) error("Yeniden adlandırılamadı")
                        openLocal(currentDir ?: return@setPositiveButton)
                    } else if (item.document != null) {
                        item.document.renameTo(newName)
                        currentTreeUri?.let { openTreeRoot(it) }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun deleteItem(item: FileItem) {
        AlertDialog.Builder(this)
            .setTitle("Silinsin mi?")
            .setMessage(item.name)
            .setPositiveButton("Sil") { _, _ ->
                try {
                    if (item.localFile != null) {
                        if (item.localFile.isDirectory) item.localFile.deleteRecursively()
                        else item.localFile.delete()
                        openLocal(currentDir ?: return@setPositiveButton)
                    } else if (item.document != null) {
                        item.document.delete()
                        currentTreeUri?.let { openTreeRoot(it) }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun isEditable(name: String): Boolean {
        val lower = name.lowercase()
        val editable = listOf(
            ".xml", ".json", ".txt", ".properties", ".smali", ".js", ".css", ".html",
            ".htm", ".csv", ".md", ".yml", ".yaml", ".cfg", ".ini", ".log", ".kt", ".java"
        )
        return editable.any { lower.endsWith(it) }
    }

    data class FileItem(
        val name: String,
        val meta: String,
        val isDirectory: Boolean,
        val localFile: File?,
        val document: DocumentFile?
    )

    private class FileAdapter(
        private val onClick: (FileItem) -> Unit,
        private val onLongClick: (FileItem) -> Boolean
    ) : RecyclerView.Adapter<FileAdapter.VH>() {
        private var items: List<FileItem> = emptyList()

        fun submit(data: List<FileItem>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.icon.text = if (item.isDirectory) "DIR" else when {
                item.name.endsWith(".apk", true) -> "APK"
                item.name.endsWith(".dex", true) -> "DEX"
                item.name.endsWith(".so", true) -> "SO"
                else -> "FILE"
            }
            holder.name.text = item.name
            holder.meta.text = item.meta
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener { onLongClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: TextView = view.findViewById(R.id.icon)
            val name: TextView = view.findViewById(R.id.name)
            val meta: TextView = view.findViewById(R.id.meta)
        }
    }

    companion object {
        const val EXTRA_START_DIR = "start_dir"
    }
}
