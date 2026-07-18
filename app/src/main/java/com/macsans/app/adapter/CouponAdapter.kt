package com.macsans.app.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.macsans.app.R
import com.macsans.app.model.Coupon
import com.macsans.app.model.CouponLegStatus
import com.macsans.app.model.CouponPickType
import com.macsans.app.model.CouponStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CouponAdapter : RecyclerView.Adapter<CouponAdapter.Holder>() {

    private val items = mutableListOf<Coupon>()

    fun submit(list: List<Coupon>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_coupon, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.txtCouponTitle)
        private val status: TextView = itemView.findViewById(R.id.txtCouponStatus)
        private val note: TextView = itemView.findViewById(R.id.txtCouponNote)
        private val legs: TextView = itemView.findViewById(R.id.txtCouponLegs)

        fun bind(coupon: Coupon) {
            val df = SimpleDateFormat("d MMM HH:mm", Locale("tr"))
            title.text = "${coupon.legs.size} maçlı kupon · ${df.format(Date(coupon.createdAt))} · kombine ~%${coupon.combinedChance}"
            status.text = when (coupon.status) {
                CouponStatus.OPEN -> "BEKLİYOR"
                CouponStatus.WON -> "TUTTU ✓"
                CouponStatus.LOST -> "TUTMADI ✗"
                CouponStatus.PARTIAL -> "KISMİ"
            }
            status.setTextColor(
                when (coupon.status) {
                    CouponStatus.WON -> Color.parseColor("#66BB6A")
                    CouponStatus.LOST -> Color.parseColor("#EF5350")
                    CouponStatus.PARTIAL -> Color.parseColor("#FFCA28")
                    CouponStatus.OPEN -> Color.parseColor("#E8B84A")
                }
            )
            note.text = coupon.note
            legs.text = coupon.legs.joinToString("\n\n") { leg ->
                val pick = when (leg.pick) {
                    CouponPickType.HOME -> "1"
                    CouponPickType.DRAW -> "X"
                    CouponPickType.AWAY -> "2"
                }
                val result = when (leg.status) {
                    CouponLegStatus.PENDING -> "bekliyor"
                    CouponLegStatus.HIT -> "DOĞRU · skor ${leg.finalScore} · sonuç ${leg.actualResult}"
                    CouponLegStatus.MISS -> "YANLIŞ · skor ${leg.finalScore} · sonuç ${leg.actualResult}"
                }
                "${leg.homeTeam} vs ${leg.awayTeam}\n" +
                    "Seçim: $pick · tahmin %${leg.predictedPercent} · ${leg.confidenceLabel}\n" +
                    "Durum: $result"
            }
        }
    }
}
