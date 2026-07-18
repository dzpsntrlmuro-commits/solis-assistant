package com.macsans.app.data

import android.content.Context
import com.macsans.app.api.FootballApiClient
import com.macsans.app.model.Coupon
import com.macsans.app.model.CouponLeg
import com.macsans.app.model.CouponLegStatus
import com.macsans.app.model.CouponPickType
import com.macsans.app.model.CouponStatus
import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus

object CouponSettler {

    fun settleAll(context: Context, knownMatches: List<Match> = emptyList()): List<Coupon> {
        val key = ApiKeyStore.get(context)
        val api = if (key.isNotBlank()) FootballApiClient(key) else null
        val byId = knownMatches.associateBy { it.id }.toMutableMap()

        val coupons = CouponStore.loadCoupons(context).map { coupon ->
            if (coupon.status != CouponStatus.OPEN && coupon.status != CouponStatus.PARTIAL) {
                return@map coupon
            }
            val updatedLegs = coupon.legs.map { leg ->
                if (leg.status != CouponLegStatus.PENDING) {
                    leg
                } else {
                    val match = byId[leg.matchId] ?: api?.fetchFixtureById(leg.matchId)?.also {
                        byId[it.id] = it
                    }
                    if (match == null || match.status != MatchStatus.FINISHED) {
                        leg
                    } else {
                        settleLeg(leg, match)
                    }
                }
            }
            val status = summarize(updatedLegs)
            val hits = updatedLegs.count { it.status == CouponLegStatus.HIT }
            val misses = updatedLegs.count { it.status == CouponLegStatus.MISS }
            val pending = updatedLegs.count { it.status == CouponLegStatus.PENDING }
            val note = when (status) {
                CouponStatus.WON -> "Tuttu · $hits/${updatedLegs.size} doğru · kombine tahmini ~%${coupon.combinedChance}"
                CouponStatus.LOST -> "Tutmadı · $misses yanlış · $hits doğru · tahmin gücü ~%${coupon.combinedChance}"
                CouponStatus.PARTIAL -> "Kısmi · $hits doğru · $misses yanlış · $pending bekliyor"
                CouponStatus.OPEN -> "Bekliyor · $pending maç sonuçlanmadı · kombine ~%${coupon.combinedChance}"
            }
            coupon.copy(legs = updatedLegs, status = status, note = note)
        }
        CouponStore.saveAll(context, coupons)
        return coupons
    }

    private fun settleLeg(leg: CouponLeg, match: Match): CouponLeg {
        val actual = when {
            match.homeScore > match.awayScore -> CouponPickType.HOME
            match.homeScore < match.awayScore -> CouponPickType.AWAY
            else -> CouponPickType.DRAW
        }
        val actualLabel = when (actual) {
            CouponPickType.HOME -> "1 (Ev)"
            CouponPickType.DRAW -> "X (Beraberlik)"
            CouponPickType.AWAY -> "2 (Deplasman)"
        }
        val hit = actual == leg.pick
        return leg.copy(
            status = if (hit) CouponLegStatus.HIT else CouponLegStatus.MISS,
            actualResult = actualLabel,
            finalScore = "${match.homeScore}-${match.awayScore}"
        )
    }

    private fun summarize(legs: List<CouponLeg>): CouponStatus {
        val pending = legs.any { it.status == CouponLegStatus.PENDING }
        val misses = legs.any { it.status == CouponLegStatus.MISS }
        val hits = legs.count { it.status == CouponLegStatus.HIT }
        return when {
            pending && misses -> CouponStatus.PARTIAL
            pending -> CouponStatus.OPEN
            misses -> CouponStatus.LOST
            hits == legs.size -> CouponStatus.WON
            else -> CouponStatus.PARTIAL
        }
    }
}
