package com.matchpredictor.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.matchpredictor.app.R
import com.matchpredictor.app.data.models.FootballMatch
import com.matchpredictor.app.data.models.MatchStatus

class MatchAdapter(
    private val onClick: (FootballMatch) -> Unit
) : ListAdapter<FootballMatch, MatchAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_match, parent, false)
        return ViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(itemView: View, private val onClick: (FootballMatch) -> Unit) :
        RecyclerView.ViewHolder(itemView) {

        private val tvLeague: TextView = itemView.findViewById(R.id.tvLeague)
        private val tvHomeTeam: TextView = itemView.findViewById(R.id.tvHomeTeam)
        private val tvAwayTeam: TextView = itemView.findViewById(R.id.tvAwayTeam)
        private val tvScore: TextView = itemView.findViewById(R.id.tvScore)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvPrediction: TextView = itemView.findViewById(R.id.tvPrediction)
        private val tvWeather: TextView = itemView.findViewById(R.id.tvWeather)

        fun bind(match: FootballMatch) {
            tvLeague.text = match.league
            tvHomeTeam.text = match.homeTeam
            tvAwayTeam.text = match.awayTeam
            tvTime.text = "${match.date} ${match.time.take(5)}"

            when {
                match.status.isLive() && match.homeScore != null && match.awayScore != null -> {
                    tvScore.text = "${match.homeScore} - ${match.awayScore}"
                    tvStatus.text = itemView.context.getString(R.string.live)
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.live_red))
                    tvStatus.visibility = View.VISIBLE
                }
                match.status == MatchStatus.FINISHED && match.homeScore != null -> {
                    tvScore.text = "${match.homeScore} - ${match.awayScore}"
                    tvStatus.text = itemView.context.getString(R.string.finished)
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_secondary))
                    tvStatus.visibility = View.VISIBLE
                }
                else -> {
                    tvScore.text = "vs"
                    tvStatus.text = itemView.context.getString(R.string.scheduled)
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.primary))
                    tvStatus.visibility = View.VISIBLE
                }
            }

            match.prediction?.let { pred ->
                tvPrediction.text = "🏠 %${pred.homeWinPercent}  ⚖ %${pred.drawPercent}  ✈ %${pred.awayWinPercent}"
                tvPrediction.visibility = View.VISIBLE
            } ?: run { tvPrediction.visibility = View.GONE }

            match.weather?.let { w ->
                tvWeather.text = "🌡 ${w.temperature.toInt()}°C ${w.description}"
                tvWeather.visibility = View.VISIBLE
            } ?: run { tvWeather.visibility = View.GONE }

            itemView.setOnClickListener { onClick(match) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FootballMatch>() {
            override fun areItemsTheSame(a: FootballMatch, b: FootballMatch) = a.id == b.id
            override fun areContentsTheSame(a: FootballMatch, b: FootballMatch) = a == b
        }
    }
}
