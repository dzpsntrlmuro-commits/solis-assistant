package com.solis.assistant.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.solis.assistant.data.AppDatabase
import com.solis.assistant.data.AudioRecord
import com.solis.assistant.databinding.ActivityMainBinding
import com.solis.assistant.service.ListenerService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val PERMISSIONS_REQUEST_CODE = 100
    private var isListenerRunning = false

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkAndRequestPermissions()
        loadTodayRecords()
    }

    private fun setupUI() {
        // Başlat/Durdur butonu
        binding.btnToggle.setOnClickListener {
            if (isListenerRunning) {
                stopListener()
            } else {
                startListener()
            }
        }

        // Gün özeti butonu
        binding.btnSummary.setOnClickListener {
            startActivity(Intent(this, DailySummaryActivity::class.java))
        }

        // Pil optimizasyonunu devre dışı bırak
        binding.btnBatteryOptimize.setOnClickListener {
            disableBatteryOptimization()
        }

        updateUI()
    }

    private fun startListener() {
        if (!hasPermissions()) {
            checkAndRequestPermissions()
            return
        }

        ListenerService.start(this)
        isListenerRunning = true

        val prefs = getSharedPreferences("solis_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("listener_enabled", true).apply()

        updateUI()
        Toast.makeText(this, "✅ Solis dinlemeye başladı!", Toast.LENGTH_SHORT).show()
    }

    private fun stopListener() {
        ListenerService.stop(this)
        isListenerRunning = false

        val prefs = getSharedPreferences("solis_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("listener_enabled", false).apply()

        updateUI()
        Toast.makeText(this, "⏹️ Solis durduruldu", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        if (isListenerRunning) {
            binding.btnToggle.text = "⏹️ Durdur"
            binding.btnToggle.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.tvStatus.text = "🟢 Aktif - Dinleniyor"
        } else {
            binding.btnToggle.text = "▶️ Başlat"
            binding.btnToggle.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.tvStatus.text = "🔴 Pasif"
        }
    }

    private fun loadTodayRecords() {
        val db = AppDatabase.getInstance(this)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        lifecycleScope.launch {
            db.audioRecordDao().getByDate(today).collectLatest { records ->
                updateRecordsList(records)
            }
        }
    }

    private fun updateRecordsList(records: List<AudioRecord>) {
        binding.tvRecordCount.text = "Bugün ${records.size} kayıt"

        if (records.isEmpty()) {
            binding.tvNoRecords.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.tvNoRecords.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            binding.recyclerView.layoutManager = LinearLayoutManager(this)
            binding.recyclerView.adapter = RecordAdapter(records)
        }
    }

    private fun disableBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Pil Optimizasyonu")
                .setMessage("Solis'in arka planda düzgün çalışması için pil optimizasyonunu devre dışı bırakın.")
                .setPositiveButton("Ayarlara Git") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("İptal", null)
                .show()
        } else {
            Toast.makeText(this, "✅ Pil optimizasyonu zaten devre dışı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "İzinler verildi ✅", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Mikrofon izni gerekli!", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Servis hala çalışıyor mu kontrol et
        val prefs = getSharedPreferences("solis_prefs", MODE_PRIVATE)
        isListenerRunning = prefs.getBoolean("listener_enabled", false)
        updateUI()
    }
}
