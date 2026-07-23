package com.temiztube.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.temiztube.app.R
import com.temiztube.app.data.DownloadStore
import com.temiztube.app.data.MediaDownloader
import com.temiztube.app.databinding.ActivityDownloadsBinding
import com.temiztube.app.databinding.ItemDownloadBinding
import com.temiztube.app.model.DownloadItem
import com.temiztube.app.model.DownloadKind
import com.temiztube.app.model.DownloadStatus
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadsAdapter
    private val store by lazy { DownloadStore.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = DownloadsAdapter(
            onOpen = { openItem(it) },
            onDelete = { deleteItem(it) }
        )
        binding.downloadsList.layoutManager = LinearLayoutManager(this)
        binding.downloadsList.adapter = adapter
        binding.backButton.setOnClickListener { finish() }

        lifecycleScope.launch {
            store.items.collectLatest { items ->
                adapter.submit(items)
                binding.emptyState.isVisible = items.isEmpty()
                binding.downloadsList.isVisible = items.isNotEmpty()
            }
        }
    }

    private fun openItem(item: DownloadItem) {
        val file = item.localPath?.let { File(it) }?.takeIf { it.exists() }
        val uri = when {
            file != null -> FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            !item.publicUri.isNullOrBlank() -> Uri.parse(item.publicUri)
            else -> null
        }
        if (uri == null) {
            Toast.makeText(this, R.string.download_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val mime = when (item.kind) {
            DownloadKind.VIDEO -> "video/*"
            DownloadKind.AUDIO -> "audio/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.download_open))) }
            .onFailure {
                Toast.makeText(this, R.string.download_open_failed, Toast.LENGTH_SHORT).show()
            }
    }

    private fun deleteItem(item: DownloadItem) {
        item.localPath?.let { path ->
            runCatching { File(path).delete() }
        }
        // Also try remove orphan files with same name in app dir
        runCatching {
            val dir = MediaDownloader.appDownloadDir(this)
            File(dir, item.fileName).takeIf { it.exists() }?.delete()
        }
        store.remove(item.id)
    }
}

private class DownloadsAdapter(
    private val onOpen: (DownloadItem) -> Unit,
    private val onDelete: (DownloadItem) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.Holder>() {

    private val items = mutableListOf<DownloadItem>()
    private val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    fun submit(next: List<DownloadItem>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(
        private val binding: ItemDownloadBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadItem) {
            binding.title.text = item.title
            binding.kindIcon.setImageResource(
                if (item.kind == DownloadKind.AUDIO) R.drawable.ic_music else R.drawable.ic_download
            )

            val statusLabel = when (item.status) {
                DownloadStatus.QUEUED -> binding.root.context.getString(R.string.download_status_queued)
                DownloadStatus.DOWNLOADING -> {
                    if (item.progressPercent in 1..99) {
                        binding.root.context.getString(
                            R.string.download_status_progress,
                            item.progressPercent
                        )
                    } else {
                        binding.root.context.getString(R.string.download_status_downloading)
                    }
                }
                DownloadStatus.COMPLETED -> binding.root.context.getString(R.string.download_status_done)
                DownloadStatus.FAILED -> binding.root.context.getString(R.string.download_status_failed)
            }
            val kindLabel = if (item.kind == DownloadKind.AUDIO) "MP3/Ses" else "Video"
            binding.statusText.text = "$kindLabel · $statusLabel · ${timeFormat.format(Date(item.createdAt))}"

            val showProgress = item.status == DownloadStatus.DOWNLOADING ||
                item.status == DownloadStatus.QUEUED
            binding.progress.isVisible = showProgress
            if (showProgress) {
                binding.progress.isIndeterminate = item.progressPercent <= 0
                if (item.progressPercent > 0) {
                    binding.progress.progress = item.progressPercent
                }
            }

            binding.errorText.isVisible = item.status == DownloadStatus.FAILED &&
                !item.errorMessage.isNullOrBlank()
            binding.errorText.text = item.errorMessage

            binding.openButton.isVisible = item.status == DownloadStatus.COMPLETED
            binding.openButton.setOnClickListener { onOpen(item) }
            binding.deleteButton.setOnClickListener { onDelete(item) }
        }
    }
}
