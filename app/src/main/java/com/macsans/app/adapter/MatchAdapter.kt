package com.macsans.app.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.macsans.app.R
import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus

class MatchAdapter(
    private val onClick: (Match) -> Unit
) : RecyclerView.Adapter<MatchAdapter.Holder>() {

    private val items = mutableListOf<Match>()

    fun submit(list: List<Match>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_match, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val league: TextView = itemView.findViewById(R.id.txtLeague)
        private val status: TextView = itemView.findViewById(R.id.txtStatus)
        private val teams: TextView = itemView.findViewById(R.id.txtTeams)
        private val score: TextView = itemView.findViewById(R.id.txtScore)
        private val weather: TextView = itemView.findViewById(R.id.txtWeather)
        private val winLine: TextView = itemView.findViewById(R.id.txtWinLine)
        private val summary: TextView = itemView.findViewById(R.id.txtSummary)
        private val homeBar: ProgressBar = itemView.findViewById(R.id.barHome)
        private val drawBar: ProgressBar = itemView.findViewById(R.id.barDraw)
        private val awayBar: ProgressBar = itemView.findViewById(R.id.barAway)

        fun bind(match: Match) {
            val analysis = match.analysis
            league.text = "${match.country} · ${match.league}"
            teams.text = "${match.homeTeam}  vs  ${match.awayTeam}"
            when (match.status) {
                MatchStatus.LIVE -> {
                    status.text = "CANLI ${match.minute}'"
                    status.setBackgroundColor(Color.parseColor("#C62828"))
                    score.text = "${match.homeScore} - ${match.awayScore}"
                }
                MatchStatus.UPCOMING -> {
                    status.text = "BUGÜN ${match.kickoffLabel}"
                    status.setBackgroundColor(Color.parseColor("#1565C0"))
                    score.text = "vs"
                }
                MatchStatus.FINISHED -> {
                    status.text = "BİTTİ"
                    status.setBackgroundColor(Color.parseColor("#455A64"))
                    score.text = "${match.homeScore} - ${match.awayScore}"
                }
            }

            val w = match.weather
            weather.text = if (w != null) {
                "${w.condition} · ${w.temperatureC.toInt()}°C · rüzgar ${w.windKmh.toInt()} km/s · ${match.city}"
            } else {
                "${match.city} · hava yükleniyor"
            }

            if (analysis != null) {
                val detailed = match.homeHistory != null || match.dataSource.contains("geçmiş", true)
                winLine.text =
                    (if (detailed) "Detaylı oran: " else "Hızlı oran: ") +
                        "${match.homeTeam} %${analysis.homeWinPercent} · Ber. %${analysis.drawPercent} · ${match.awayTeam} %${analysis.awayWinPercent}"
                summary.text = analysis.summary
                homeBar.progress = analysis.homeWinPercent
                drawBar.progress = analysis.drawPercent
                awayBar.progress = analysis.awayWinPercent
            } else {
                winLine.text = "Analiz hazırlanıyor…"
                summary.text = ""
                homeBar.progress = 0
                drawBar.progress = 0
                awayBar.progress = 0
            }

            itemView.setOnClickListener { onClick(match) }
        }
    }
}
