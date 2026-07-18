package com.matchpredictor.app.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.matchpredictor.app.R
import com.matchpredictor.app.data.MatchRepository
import com.matchpredictor.app.data.models.PredictionFactor
import kotlinx.coroutines.launch

class MatchDetailActivity : AppCompatActivity() {

    private val repository = MatchRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_match_detail)

        val matchId = intent.getStringExtra(EXTRA_MATCH_ID) ?: return finish()
        val home = intent.getStringExtra(EXTRA_HOME) ?: ""
        val away = intent.getStringExtra(EXTRA_AWAY) ?: ""

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "$home vs $away"

        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val content = findViewById<LinearLayout>(R.id.contentLayout)

        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val match = repository.getMatchDetail(matchId)
            progressBar.visibility = View.GONE

            if (match == null) {
                findViewById<TextView>(R.id.tvError).apply {
                    visibility = View.VISIBLE
                    text = "Maç detayı yüklenemedi"
                }
                return@launch
            }

            content.visibility = View.VISIBLE

            findViewById<TextView>(R.id.tvLeague).text = match.league
            findViewById<TextView>(R.id.tvVenue).text = "${match.venue}, ${match.country}"
            findViewById<TextView>(R.id.tvDateTime).text = "${match.date} ${match.time}"

            match.prediction?.let { pred ->
                findViewById<TextView>(R.id.tvHomeWin).text = "%${pred.homeWinPercent}"
                findViewById<TextView>(R.id.tvDraw).text = "%${pred.drawPercent}"
                findViewById<TextView>(R.id.tvAwayWin).text = "%${pred.awayWinPercent}"
                findViewById<TextView>(R.id.tvConfidence).text = "Güven: ${pred.confidence}"

                val factorsLayout = findViewById<LinearLayout>(R.id.factorsLayout)
                factorsLayout.removeAllViews()
                pred.factors.forEach { factor ->
                    factorsLayout.addView(createFactorView(factor))
                }
            }

            match.weather?.let { w ->
                findViewById<TextView>(R.id.tvWeatherDetail).text =
                    "${w.description} | ${w.temperature.toInt()}°C | Rüzgar: ${w.windSpeed.toInt()} km/s | Yağış: ${w.precipitation}mm\n${w.impactExplanation}"
            }

            match.homeForm?.let { f ->
                findViewById<TextView>(R.id.tvHomeForm).text =
                    "${match.homeTeam}: ${f.moraleExplanation}\nSon maçlar: ${f.recentResults.joinToString(", ")}"
            }

            match.awayForm?.let { f ->
                findViewById<TextView>(R.id.tvAwayForm).text =
                    "${match.awayTeam}: ${f.moraleExplanation}\nSon maçlar: ${f.recentResults.joinToString(", ")}"
            }
        }
    }

    private fun createFactorView(factor: PredictionFactor): View {
        val tv = TextView(this)
        tv.text = "• ${factor.name} (${factor.impact}): ${factor.detail}"
        tv.setPadding(0, 8, 0, 8)
        tv.textSize = 14f
        return tv
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_MATCH_ID = "match_id"
        const val EXTRA_HOME = "home_team"
        const val EXTRA_AWAY = "away_team"
    }
}
