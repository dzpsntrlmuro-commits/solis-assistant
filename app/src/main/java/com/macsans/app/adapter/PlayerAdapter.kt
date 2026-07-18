package com.macsans.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.macsans.app.R
import com.macsans.app.engine.PredictionEngine
import com.macsans.app.model.PlayerStatus

class PlayerAdapter(
    private val players: List<PlayerStatus>
) : RecyclerView.Adapter<PlayerAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_player, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(players[position])
    }

    override fun getItemCount(): Int = players.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.txtPlayerName)
        private val meta: TextView = itemView.findViewById(R.id.txtPlayerMeta)
        private val note: TextView = itemView.findViewById(R.id.txtPlayerNote)

        fun bind(player: PlayerStatus) {
            name.text = "${player.name} · ${player.role}"
            meta.text =
                "${player.team} · sakatlık: ${PredictionEngine.injuryLabel(player.injuryLevel)} · " +
                    "duygu: ${PredictionEngine.moodLabel(player.emotionScore)} (${player.emotionScore}) · " +
                    "form: %${player.fitnessPercent}"
            note.text = player.note
        }
    }
}
