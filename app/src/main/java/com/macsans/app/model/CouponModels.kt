package com.macsans.app.model

enum class CouponPickType { HOME, DRAW, AWAY }

enum class CouponLegStatus { PENDING, HIT, MISS }

enum class CouponStatus { OPEN, WON, LOST, PARTIAL }

data class CouponLeg(
    val matchId: String,
    val homeTeam: String,
    val awayTeam: String,
    val league: String,
    val kickoffLabel: String,
    val pick: CouponPickType,
    val predictedPercent: Int,
    val confidenceLabel: String,
    val homeWinPercent: Int,
    val drawPercent: Int,
    val awayWinPercent: Int,
    var status: CouponLegStatus = CouponLegStatus.PENDING,
    var actualResult: String = "",
    var finalScore: String = ""
)

data class Coupon(
    val id: String,
    val createdAt: Long,
    val legs: List<CouponLeg>,
    var status: CouponStatus = CouponStatus.OPEN,
    var note: String = ""
) {
    val combinedChance: Int
        get() {
            if (legs.isEmpty()) return 0
            var p = 1.0
            legs.forEach { p *= (it.predictedPercent.coerceIn(1, 99) / 100.0) }
            return (p * 100).toInt().coerceIn(1, 99)
        }
}
