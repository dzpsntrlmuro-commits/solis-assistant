package com.solis.assistant.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.solis.assistant.api.GeminiClient
import com.solis.assistant.api.GeminiResult
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

        title = "📊 Gün Sonu Özeti"

        // Bildirimden gelen JSON varsa direkt göster
        val summaryJson = intent.getStringExtra("summary_json")
        if (summaryJson != null) {
            displaySummaryFromJson(summaryJson)
        } else {
            generateSummary()
        }

        binding.btnRefresh.setOnClickListener {
            generateSummary()
        }
    }

    private fun generateSummary() {
        binding.progressBar.visibility = View.VISIBLE
        binding.scrollContent.visibility = View.GONE

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@DailySummaryActivity)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val records = db.audioRecordDao().getByDateSync(today)

            if (records.isEmpty()) {
                binding.progressBar.visibility = View.GONE
                binding.tvNoData.visibility = View.VISIBLE
                return@launch
            }

            val allText = records.joinToString("\n---\n") { record ->
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(record.timestamp))
                "[$time] ${record.transcript}"
            }

            val result = GeminiClient.createDailySummary(allText)

            binding.progressBar.visibility = View.GONE

            when (result) {
                is GeminiResult.Success -> {
                    displaySummaryFromJson(result.text)
                }
                is GeminiResult.Error -> {
                    Toast.makeText(this@DailySummaryActivity, "Hata: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displaySummaryFromJson(json: String) {
        binding.scrollContent.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE

        try {
            val gson = com.google.gson.Gson()
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(json, Map::class.java) as Map<String, Any>

            binding.tvSummaryText.text = map["ozet"]?.toString() ?: "Özet bulunamadı"

            val konusulanlar = (map["konusulanlar"] as? List<*>)?.joinToString("\n• ", prefix = "• ") ?: "-"
            binding.tvPeople.text = konusulanlar

            val konular = (map["konular"] as? List<*>)?.joinToString("\n• ", prefix = "• ") ?: "-"
            binding.tvTopics.text = konular

            val yapilacaklar = (map["yapilacaklar"] as? List<*>)?.joinToString("\n• ", prefix = "• ") ?: "-"
            binding.tvTasks.text = yapilacaklar

            val yarin = (map["yarin_onerileri"] as? List<*>)?.joinToString("\n• ", prefix = "• ") ?: "-"
            binding.tvTomorrow.text = yarin

        } catch (e: Exception) {
            binding.tvSummaryText.text = json
        }
    }
}
