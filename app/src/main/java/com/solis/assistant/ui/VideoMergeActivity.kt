package com.solis.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.solis.assistant.databinding.ActivityVideoMergeBinding
import com.solis.assistant.utils.VideoMerger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoMergeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoMergeBinding
    private val adapter = VideoItemAdapter()
    private val selectedVideos = mutableListOf<VideoItem>()
    private var isMerging = false

    private val pickVideos = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        selectedVideos.clear()
        uris.forEach { uri ->
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            selectedVideos.add(VideoItem(uri, getDisplayName(uri)))
        }
        updateUi()
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openVideoPicker() else showToast("Video erişim izni gerekli")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoMergeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvVideos.layoutManager = LinearLayoutManager(this)
        binding.rvVideos.adapter = adapter

        binding.btnSelectVideos.setOnClickListener { checkPermissionAndPick() }
        binding.btnMerge.setOnClickListener { mergeVideos() }
        updateUi()
    }

    private fun checkPermissionAndPick() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            openVideoPicker()
        } else {
            requestPermission.launch(permission)
        }
    }

    private fun openVideoPicker() {
        pickVideos.launch(arrayOf("video/*"))
    }

    private fun mergeVideos() {
        if (selectedVideos.size < 2) {
            showToast("En az 2 video seçin")
            return
        }
        if (isMerging) return

        isMerging = true
        binding.progressBar.visibility = View.VISIBLE
        binding.btnMerge.isEnabled = false
        binding.btnSelectVideos.isEnabled = false
        binding.tvStatus.text = "Birleştiriliyor..."

        val uris = selectedVideos.map { it.uri }
        lifecycleScope.launch {
            val outputFile = File(cacheDir, "merged_${System.currentTimeMillis()}.mp4")
            val success = withContext(Dispatchers.IO) {
                VideoMerger.merge(this@VideoMergeActivity, uris, outputFile)
            }

            if (success) {
                val name = "birlesik_${timestamp()}.mp4"
                val savedUri = withContext(Dispatchers.IO) {
                    VideoMerger.saveToGallery(this@VideoMergeActivity, outputFile, name)
                }
                outputFile.delete()
                if (savedUri != null) {
                    binding.tvStatus.text = "Tamamlandı! Galeri → Filmler/SolisAsistan klasörüne kaydedildi."
                    showToast("Video birleştirildi ve kaydedildi")
                } else {
                    binding.tvStatus.text = "Birleştirme başarılı ama galeriye kaydedilemedi."
                    showToast("Kayıt başarısız")
                }
            } else {
                binding.tvStatus.text = "Birleştirme başarısız. Videoların aynı çözünürlük ve formatta olduğundan emin olun."
                showToast("Birleştirme başarısız")
            }

            isMerging = false
            binding.progressBar.visibility = View.GONE
            binding.btnSelectVideos.isEnabled = true
            binding.btnMerge.isEnabled = selectedVideos.size >= 2
        }
    }

    private fun updateUi() {
        adapter.submitList(selectedVideos.toList())
        binding.btnMerge.isEnabled = selectedVideos.size >= 2 && !isMerging
        binding.tvStatus.text = when {
            selectedVideos.isEmpty() -> "Henüz video seçilmedi"
            selectedVideos.size == 1 -> "1 video seçildi — en az 1 tane daha ekleyin"
            else -> "${selectedVideos.size} video seçildi — sırayla birleştirilecek"
        }
    }

    private fun getDisplayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "video"
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
