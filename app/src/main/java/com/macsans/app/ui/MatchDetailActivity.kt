package com.macsans.app.ui

import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macsans.app.R
import com.macsans.app.adapter.PlayerAdapter
import com.macsans.app.engine.PredictionEngine
import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus

class MatchDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MATCH_ID = "match_id"
        var cache: Match? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_match_detail)

        val match = cache
        if (match == null) {
            finish()
            return
        }

        findViewById<TextView>(R.id.txtDetailTitle).text =
            "${match.homeTeam} vs ${match.awayTeam}"
        findViewById<TextView>(R.id.txtDetailLeague).text =
            "${match.country} · ${match.league} · ${match.venue}"

        val statusText = when (match.status) {
            MatchStatus.LIVE -> "CANLI ${match.minute}'  |  ${match.homeScore}-${match.awayScore}"
            MatchStatus.UPCOMING -> "Başlama: bugün ${match.kickoffLabel}"
            MatchStatus.FINISHED -> "Sonuç: ${match.homeScore}-${match.awayScore}"
        }
        findViewById<TextView>(R.id.txtDetailStatus).text = statusText

        val analysis = match.analysis ?: PredictionEngine.analyze(match, match.weather)
        findViewById<TextView>(R.id.txtHomePct).text = "%${analysis.homeWinPercent}"
        findViewById<TextView>(R.id.txtDrawPct).text = "%${analysis.drawPercent}"
        findViewById<TextView>(R.id.txtAwayPct).text = "%${analysis.awayWinPercent}"
        findViewById<TextView>(R.id.txtHomeLabel).text = match.homeTeam
        findViewById<TextView>(R.id.txtAwayLabel).text = match.awayTeam
        findViewById<ProgressBar>(R.id.detailBarHome).progress = analysis.homeWinPercent
        findViewById<ProgressBar>(R.id.detailBarDraw).progress = analysis.drawPercent
        findViewById<ProgressBar>(R.id.detailBarAway).progress = analysis.awayWinPercent

        findViewById<TextView>(R.id.txtSummaryDetail).text = analysis.summary
        findViewById<TextView>(R.id.txtWeatherFactor).text = analysis.weatherFactor
        findViewById<TextView>(R.id.txtInjuryFactor).text = analysis.injuryFactor
        findViewById<TextView>(R.id.txtEmotionFactor).text = analysis.emotionFactor
        findViewById<TextView>(R.id.txtFormFactor).text = analysis.formFactor
        findViewById<TextView>(R.id.txtLiveFactor).text = analysis.liveFactor

        val weather = match.weather
        findViewById<TextView>(R.id.txtWeatherCard).text = if (weather != null) {
            "${weather.city}: ${weather.condition}\n" +
                "${weather.temperatureC}°C · nem %${weather.humidity} · rüzgar ${weather.windKmh} km/s · yağış ${weather.precipitationMm} mm"
        } else {
            "Hava bilgisi yok"
        }

        val recycler = findViewById<RecyclerView>(R.id.recyclerPlayers)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = PlayerAdapter(match.homePlayers + match.awayPlayers)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
    }
}
