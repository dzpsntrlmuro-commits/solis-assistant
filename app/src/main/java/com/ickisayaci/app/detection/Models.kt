package com.ickisayaci.app.detection

data class DrinkDetection(
    val label: String,
    val turkishLabel: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class DrinkCountResult(
    val total: Int,
    val detections: List<DrinkDetection>,
    val breakdown: Map<String, Int>
) {
    fun speechText(): String {
        if (total <= 0) {
            return "Kamerada içki göremiyorum."
        }
        val parts = breakdown.entries
            .sortedByDescending { it.value }
            .joinToString(", ") { (label, count) ->
                when (count) {
                    1 -> "1 $label"
                    else -> "$count $label"
                }
            }
        return when (total) {
            1 -> "Kamerada 1 içki var. $parts."
            else -> "Kamerada $total içki var. $parts."
        }
    }

    fun summaryText(): String {
        if (total <= 0) return "İçki bulunamadı"
        val detail = breakdown.entries
            .sortedByDescending { it.value }
            .joinToString(" · ") { "${it.value} ${it.key}" }
        return "$total içki — $detail"
    }
}
