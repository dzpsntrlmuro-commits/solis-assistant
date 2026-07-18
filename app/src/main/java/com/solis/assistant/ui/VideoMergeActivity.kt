package com.solis.assistant.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.solis.assistant.R
import com.solis.assistant.databinding.ActivityVideoMergeBinding
import com.solis.assistant.utils.VideoMerger
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoMergeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoMergeBinding
    private val selectedVideos = mutableListOf<Uri>()
    private lateinit var adapter: VideoItemAdapter
    private var outputFile: File? = null

    private val pickVideos = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Geçici okuma izni yeterli olabilir
            }
            if (!selectedVideos.contains(uri)) selectedVideos.add(uri)
        }
        refreshList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoMergeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = VideoItemAdapter { index ->
            selectedVideos.removeAt(index)
            refreshList()
        }
        binding.rvVideos.layoutManager = LinearLayoutManager(this)
        binding.rvVideos.adapter = adapter

        binding.btnPickVideos.setOnClickListener {
            pickVideos.launch(arrayOf("video/*"))
        }

        binding.btnClear.setOnClickListener {
            selectedVideos.clear()
            outputFile = null
            binding.btnShare.visibility = View.GONE
            binding.progressBar.progress = 0
            binding.progressBar.visibility = View.GONE
            refreshList()
        }

        binding.btnMerge.setOnClickListener { startMerge() }
        binding.btnShare.setOnClickListener { shareOutput() }
        refreshList()
    }

    private fun refreshList() {
        adapter.submit(selectedVideos)
        binding.btnMerge.isEnabled = selectedVideos.size >= 2
        binding.tvStatus.text = when {
            selectedVideos.isEmpty() -> getString(R.string.video_merge_status_empty)
            selectedVideos.size == 1 -> getString(R.string.video_merge_status_one)
            else -> getString(R.string.video_merge_status_ready, selectedVideos.size)
        }
    }

    private fun startMerge() {
        if (selectedVideos.size < 2) {
            Toast.makeText(this, R.string.video_merge_need_two, Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnMerge.isEnabled = false
        binding.btnPickVideos.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvStatus.text = getString(R.string.video_merge_working)
        binding.btnShare.visibility = View.GONE

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outDir = File(cacheDir, "merged").apply { mkdirs() }
        val out = File(outDir, "solis_birlesik_$stamp.mp4")

        lifecycleScope.launch {
            try {
                val result = VideoMerger.merge(
                    context = this@VideoMergeActivity,
                    uris = selectedVideos.toList(),
                    outputFile = out
                ) { progress ->
                    runOnUiThread {
                        binding.progressBar.progress = (progress * 100).toInt()
                    }
                }
                outputFile = result.outputFile
                binding.progressBar.progress = 100
                binding.tvStatus.text = getString(R.string.video_merge_done, result.outputFile.name)
                binding.btnShare.visibility = View.VISIBLE
                Toast.makeText(this@VideoMergeActivity, R.string.video_merge_success, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                binding.tvStatus.text = getString(R.string.video_merge_error, e.message ?: "bilinmeyen hata")
                Toast.makeText(
                    this@VideoMergeActivity,
                    getString(R.string.video_merge_error, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.btnMerge.isEnabled = selectedVideos.size >= 2
                binding.btnPickVideos.isEnabled = true
            }
        }
    }

    private fun shareOutput() {
        val file = outputFile ?: return
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.video_merge_share)))
    }
}
