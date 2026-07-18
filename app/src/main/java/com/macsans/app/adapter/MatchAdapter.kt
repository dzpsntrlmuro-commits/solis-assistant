package com.macsans.app.adapter

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.macsans.app.R
import com.macsans.app.data.CouponStore
import com.macsans.app.engine.PredictionEngine
import com.macsans.app.model.CouponLeg
import com.macsans.app.model.CouponPickType
import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus

class MatchAdapter(
    private val onOpenDetail: (Match) -> Unit,
    private val onPickChanged: () -> Unit
) : RecyclerView.Adapter<MatchAdapter.Holder>() {

    private val items = mutableListOf<Match>()

    fun submit(list: List<Match>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun refreshSelections() {
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
        private val pickTitle: TextView = itemView.findViewById(R.id.txtPickTitle)
        private val pickRow: View = itemView.findViewById(R.id.pickRow)
        private val btnHome: TextView = itemView.findViewById(R.id.btnPickHome)
        private val btnDraw: TextView = itemView.findViewById(R.id.btnPickDraw)
        private val btnAway: TextView = itemView.findViewById(R.id.btnPickAway)
        private val pickState: TextView = itemView.findViewById(R.id.txtPickState)
        private val openDetail: TextView = itemView.findViewById(R.id.btnOpenDetail)

        fun bind(match: Match) {
            val ctx = itemView.context
            val analysis = match.analysis ?: PredictionEngine.analyze(match, match.weather)
            val slipPick = CouponStore.loadSlip(ctx)
                .firstOrNull { it.matchId == match.id }?.pick
            val canPick = match.status != MatchStatus.FINISHED

            league.text = "${match.country} · ${match.league}"
            teams.text = "${match.homeTeam}  vs  ${match.awayTeam}"
            when (match.status) {
                MatchStatus.LIVE -> {
                    status.text = "CANLI ${match.minute}'"
                    status.setBackgroundColor(Color.parseColor("#C62828"))
                    score.text = "${match.homeScore} - ${match.awayScore}"
                }
                MatchStatus.UPCOMING -> {
                    status.text = "OLMAMIŞ ${match.kickoffLabel}"
                    status.setBackgroundColor(Color.parseColor("#2E7D32"))
                    score.text = "vs · başlama ${match.kickoffLabel}"
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
                "${match.city}"
            }

            winLine.text =
                "Oran: 1 %${analysis.homeWinPercent} · X %${analysis.drawPercent} · 2 %${analysis.awayWinPercent}"
            summary.text = analysis.summary
            homeBar.progress = analysis.homeWinPercent
            drawBar.progress = analysis.drawPercent
            awayBar.progress = analysis.awayWinPercent

            if (!canPick) {
                pickTitle.visibility = View.GONE
                pickRow.visibility = View.GONE
                pickState.text = "Maç bitti — kupon seçilemez"
                pickState.setTextColor(Color.parseColor("#78909C"))
            } else {
                pickTitle.visibility = View.VISIBLE
                pickRow.visibility = View.VISIBLE
                val markHome = if (slipPick == CouponPickType.HOME) "✓ " else ""
                val markDraw = if (slipPick == CouponPickType.DRAW) "✓ " else ""
                val markAway = if (slipPick == CouponPickType.AWAY) "✓ " else ""
                btnHome.text = "${markHome}1\n%${analysis.homeWinPercent}"
                btnDraw.text = "${markDraw}X\n%${analysis.drawPercent}"
                btnAway.text = "${markAway}2\n%${analysis.awayWinPercent}"
                stylePicks(slipPick)

                if (slipPick != null) {
                    val pct = when (slipPick) {
                        CouponPickType.HOME -> analysis.homeWinPercent
                        CouponPickType.DRAW -> analysis.drawPercent
                        CouponPickType.AWAY -> analysis.awayWinPercent
                    }
                    val label = when (slipPick) {
                        CouponPickType.HOME -> "1 EV"
                        CouponPickType.DRAW -> "X BERABERLİK"
                        CouponPickType.AWAY -> "2 DEPLASMAN"
                    }
                    pickState.text = "✓ SEÇİLİ: $label · %$pct · ${CouponStore.confidenceLabel(pct)}"
                    pickState.setTextColor(Color.parseColor("#C8E6C9"))
                    pickState.setTypeface(pickState.typeface, Typeface.BOLD)
                    itemView.setBackgroundResource(R.drawable.bg_match_row_selected)
                } else {
                    pickState.text = "Henüz seçilmedi — kutuya dokun"
                    pickState.setTextColor(Color.parseColor("#A7C4B5"))
                    itemView.setBackgroundResource(R.drawable.bg_match_row)
                }

                btnHome.setOnClickListener { togglePick(match, CouponPickType.HOME) }
                btnDraw.setOnClickListener { togglePick(match, CouponPickType.DRAW) }
                btnAway.setOnClickListener { togglePick(match, CouponPickType.AWAY) }
            }

            openDetail.setOnClickListener { onOpenDetail(match) }
            itemView.setOnClickListener { onOpenDetail(match) }
        }

        private fun stylePicks(selectedPick: CouponPickType?) {
            btnHome.setBackgroundResource(
                if (selectedPick == CouponPickType.HOME) R.drawable.bg_pick_home_on else R.drawable.bg_pick_idle
            )
            btnDraw.setBackgroundResource(
                if (selectedPick == CouponPickType.DRAW) R.drawable.bg_pick_draw_on else R.drawable.bg_pick_idle
            )
            btnAway.setBackgroundResource(
                if (selectedPick == CouponPickType.AWAY) R.drawable.bg_pick_away_on else R.drawable.bg_pick_idle
            )
            btnHome.setTextColor(
                if (selectedPick == CouponPickType.HOME) Color.WHITE else Color.parseColor("#E8F5E9")
            )
            btnDraw.setTextColor(
                if (selectedPick == CouponPickType.DRAW) Color.parseColor("#1B1B1B") else Color.parseColor("#E8F5E9")
            )
            btnAway.setTextColor(
                if (selectedPick == CouponPickType.AWAY) Color.WHITE else Color.parseColor("#E8F5E9")
            )
            btnHome.alpha = if (selectedPick == null || selectedPick == CouponPickType.HOME) 1f else 0.45f
            btnDraw.alpha = if (selectedPick == null || selectedPick == CouponPickType.DRAW) 1f else 0.45f
            btnAway.alpha = if (selectedPick == null || selectedPick == CouponPickType.AWAY) 1f else 0.45f
        }

        private fun togglePick(match: Match, pick: CouponPickType) {
            val ctx = itemView.context
            val current = CouponStore.loadSlip(ctx).firstOrNull { it.matchId == match.id }?.pick
            if (current == pick) {
                CouponStore.removeSlipLeg(ctx, match.id)
            } else {
                val analysis = match.analysis ?: PredictionEngine.analyze(match, match.weather)
                val percent = when (pick) {
                    CouponPickType.HOME -> analysis.homeWinPercent
                    CouponPickType.DRAW -> analysis.drawPercent
                    CouponPickType.AWAY -> analysis.awayWinPercent
                }
                CouponStore.addOrReplaceSlipLeg(
                    ctx,
                    CouponLeg(
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
                )
            }
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
            onPickChanged()
        }
    }
}
