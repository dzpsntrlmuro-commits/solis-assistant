package com.solis.assistant.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.solis.assistant.api.GeminiClient
import com.solis.assistant.data.AppDatabase
import com.solis.assistant.databinding.ActivityDailySummaryBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class DailySummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDailySummaryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDailySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "Günlük Özet"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        loadSummary()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun loadSummary() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            try {
                val records = AppDatabase.getInstance(this@DailySummaryActivity)
                    .audioRecordDao().getByDateSync(today)
                if (records.isEmpty()) {
                    binding.tvSummary.text = "Bugün henüz kayıt yok."
                } else {
                    val texts = records.map { it.transcript }
                    val summary = GeminiClient.generateDailySummary(texts)
                    binding.tvSummary.text = summary
                    binding.tvCount.text = "${records.size} kayıt analiz edildi"
                }
            } catch (e: Exception) {
                Toast.makeText(this@DailySummaryActivity, "Hata: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }
}
