package com.macsans.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macsans.app.R
import com.macsans.app.adapter.PlayerAdapter
import com.macsans.app.data.ApiKeyStore
import com.macsans.app.data.CouponStore
import com.macsans.app.data.MatchOddsCache
import com.macsans.app.data.MatchRepository
import com.macsans.app.engine.PredictionEngine
import com.macsans.app.model.CouponLeg
import com.macsans.app.model.CouponPickType
import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus
import com.macsans.app.model.WinBreakdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MatchDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MATCH_ID = "match_id"
        var cache: Match? = null
    }

    private var current: Match? = null
    private var listOdds: WinBreakdown? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_match_detail)

        val match = cache
        if (match == null) {
            finish()
            return
        }
        current = match
        listOdds = match.analysis

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        bind(match, refined = MatchOddsCache.get(match.id) != null)

        findViewById<Button>(R.id.btnPickHome).setOnClickListener {
            addPick(CouponPickType.HOME)
        }
        findViewById<Button>(R.id.btnPickDraw).setOnClickListener {
            addPick(CouponPickType.DRAW)
        }
        findViewById<Button>(R.id.btnPickAway).setOnClickListener {
            addPick(CouponPickType.AWAY)
        }
        refreshSlipStatus()

        val alreadyDetailed = MatchOddsCache.get(match.id)?.homeHistory != null
        if (ApiKeyStore.hasKey(this) && !alreadyDetailed) {
            findViewById<TextView>(R.id.txtOddsNote).text =
                "Detaylı geçmiş analizi yükleniyor (oranlar bir kez netleşecek, sonra sabit kalır)…"
            lifecycleScope.launch {
                val detailed = withContext(Dispatchers.IO) {
                    MatchRepository.loadMatchDetail(this@MatchDetailActivity, match)
                }
                MatchOddsCache.put(detailed)
                cache = detailed
                current = detailed
                bind(detailed, refined = true)
            }
        } else if (alreadyDetailed) {
            val detailed = MatchOddsCache.get(match.id)!!
            current = detailed
            cache = detailed
            bind(detailed, refined = true)
        }
    }

    private fun addPick(pick: CouponPickType) {
        val match = current ?: return
        val analysis = match.analysis ?: PredictionEngine.analyze(match, match.weather)
        val percent = when (pick) {
            CouponPickType.HOME -> analysis.homeWinPercent
            CouponPickType.DRAW -> analysis.drawPercent
            CouponPickType.AWAY -> analysis.awayWinPercent
        }
        val leg = CouponLeg(
            matchId = match.id,
            homeTeam = match.homeTeam,
            awayTeam = match.awayTeam,
            league = "${match.country} · ${match.league}",
            kickoffLabel = match.kickoffLabel,
            pick = pick,
            predictedPercent = percent,
            confidenceLabel = CouponStore.confidenceLabel(percent),
            homeWinPercent = analysis.homeWinPercent,
            drawPercent = analysis.drawPercent,
            awayWinPercent = analysis.awayWinPercent
        )
        CouponStore.addOrReplaceSlipLeg(this, leg)
        val pickLabel = when (pick) {
            CouponPickType.HOME -> "1 Ev"
            CouponPickType.DRAW -> "X"
            CouponPickType.AWAY -> "2 Dep"
        }
        Toast.makeText(
            this,
            "Kupona eklendi: $pickLabel · %$percent · ${leg.confidenceLabel}",
            Toast.LENGTH_SHORT
        ).show()
        refreshSlipStatus()
    }

    private fun refreshSlipStatus() {
        val slip = CouponStore.loadSlip(this)
        val matchId = current?.id
        val mine = slip.firstOrNull { it.matchId == matchId }
        findViewById<TextView>(R.id.txtSlipStatus).text = when {
            mine != null -> {
                val p = when (mine.pick) {
                    CouponPickType.HOME -> "1"
                    CouponPickType.DRAW -> "X"
                    CouponPickType.AWAY -> "2"
                }
                "Kuponunda: $p · %${mine.predictedPercent} · ${mine.confidenceLabel} · toplam ${slip.size} maç"
            }
            slip.isEmpty() -> "Kupon boş. 1 / X / 2 seç."
            else -> "Kuponunda ${slip.size} maç var. Ana ekrandan kaydedebilirsin."
        }
    }

    private fun bind(match: Match, refined: Boolean) {
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

        val lo = listOdds
        findViewById<TextView>(R.id.txtOddsNote).text = when {
            refined && lo != null &&
                (lo.homeWinPercent != analysis.homeWinPercent ||
                    lo.drawPercent != analysis.drawPercent ||
                    lo.awayWinPercent != analysis.awayWinPercent) ->
                "Liste hızlı oranı: %${lo.homeWinPercent}/%${lo.drawPercent}/%${lo.awayWinPercent}. " +
                    "Detay oranı geçmiş maç + arşiv hava + sakatlık + çöküş ile güncellendi ve sabitlendi."
            refined ->
                "Detaylı Muro oranı (geçmiş dahil) — listeyle aynı kalması için önbelleğe alındı."
            else ->
                "Bu hızlı oran. Detaylı geçmiş analizi yüklenince bir kez netleşir, sonra değişmez."
        }

        findViewById<TextView>(R.id.txtSummaryDetail).text = analysis.summary
        findViewById<TextView>(R.id.txtHistoryFactor).text =
            analysis.historyFactor.ifBlank { "Geçmiş analiz yükleniyor…" }
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

        findViewById<Button>(R.id.btnPickHome).text = "1 Ev %${analysis.homeWinPercent}"
        findViewById<Button>(R.id.btnPickDraw).text = "X %${analysis.drawPercent}"
        findViewById<Button>(R.id.btnPickAway).text = "2 %${analysis.awayWinPercent}"
    }
}
