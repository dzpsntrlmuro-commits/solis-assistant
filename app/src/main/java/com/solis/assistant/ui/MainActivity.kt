package com.solis.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.solis.assistant.data.AppDatabase
import com.solis.assistant.databinding.ActivityMainBinding
import com.solis.assistant.service.ListenerService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val PERMISSION_CODE = 100
    private var isRunning = false
    private val adapter = RecordAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvRecords.layoutManager = LinearLayoutManager(this)
        binding.rvRecords.adapter = adapter

        binding.btnToggle.setOnClickListener {
            if (isRunning) stopListener() else startListener()
        }

        binding.btnSummary.setOnClickListener {
            startActivity(Intent(this, DailySummaryActivity::class.java))
        }

        binding.btnVideoMerge.setOnClickListener {
            startActivity(Intent(this, VideoMergeActivity::class.java))
        }

        binding.btnBatteryOptimize.setOnClickListener {
            requestBatteryOptimizationExclusion()
        }

        checkPermissions()
        loadRecords()
    }

    private fun startListener() {
        if (!hasPermissions()) { requestPermissions(); return }
        ListenerService.start(this)
        isRunning = true
        binding.btnToggle.text = "Durdur"
        binding.tvStatus.text = "Dinleniyor..."
        Toast.makeText(this, "Solis başlatıldı", Toast.LENGTH_SHORT).show()
    }

    private fun stopListener() {
        ListenerService.stop(this)
        isRunning = false
        binding.btnToggle.text = "Başlat"
        binding.tvStatus.text = "Durdu"
    }

    private fun loadRecords() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        lifecycleScope.launch {
            AppDatabase.getInstance(this@MainActivity)
                .audioRecordDao().getByDate(today)
                .collectLatest { records ->
                    adapter.submitList(records)
                    binding.tvStatus.text = if (isRunning) "Dinleniyor... (${records.size} kayıt)" else "Hazır (${records.size} kayıt)"
                }
        }
    }

    private fun hasPermissions(): Boolean {
        val perms = arrayOf(Manifest.permission.RECORD_AUDIO)
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun checkPermissions() {
        if (!hasPermissions()) requestPermissions()
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
            PERMISSION_CODE
        )
    }

    private fun requestBatteryOptimizationExclusion() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } else {
                Toast.makeText(this, "Pil optimizasyonu zaten devre dışı", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ayarlar açılamadı", Toast.LENGTH_SHORT).show()
        }
    }
}
