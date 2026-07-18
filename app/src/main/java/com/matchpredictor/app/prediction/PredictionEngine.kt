package com.matchpredictor.app.prediction

import com.matchpredictor.app.api.SportsDbClient
import com.matchpredictor.app.data.models.*
import kotlin.math.roundToInt

class PredictionEngine {

    fun buildTeamForm(teamId: String?, teamName: String, events: List<SportsDbClient.TeamEventResult>): TeamForm {
        if (events.isEmpty()) {
            return TeamForm(0, 0, 0, 0, 0, emptyList(), 0.5,
                "$teamName için son maç verisi bulunamadı")
        }

        var wins = 0; var draws = 0; var losses = 0
        var gf = 0; var ga = 0
        val results = mutableListOf<String>()

        for (event in events) {
            val (teamGoals, oppGoals) = if (event.isHome) {
                event.homeScore to event.awayScore
            } else {
                event.awayScore to event.homeScore
            }
            gf += teamGoals; ga += oppGoals
            val opponent = if (event.isHome) event.awayTeam else event.homeTeam
            when {
                teamGoals > oppGoals -> { wins++; results.add("G $teamGoals-$oppGoals ($opponent)") }
                teamGoals < oppGoals -> { losses++; results.add("M $teamGoals-$oppGoals ($opponent)") }
                else -> { draws++; results.add("B $teamGoals-$oppGoals ($opponent)") }
            }
        }

        val morale = (wins * 1.0 + draws * 0.4) / events.size.coerceAtLeast(1)
        val explanation = when {
            wins >= 4 -> "$teamName son ${events.size} maçta mükemmel formda ($wins galibiyet)"
            wins >= 3 -> "$teamName iyi bir seride ($wins galibiyet, $draws beraberlik, $losses mağlubiyet)"
            losses >= 3 -> "$teamName moral olarak düşük ($losses mağlubiyet son ${events.size} maçta)"
            else -> "$teamName karışık form gösteriyor ($wins-$draws-$losses)"
        }

        return TeamForm(wins, draws, losses, gf, ga, results, morale.coerceIn(0.0, 1.0), explanation)
    }

    fun predict(
        match: FootballMatch,
        homeForm: TeamForm?,
        awayForm: TeamForm?,
        weather: WeatherInfo?
    ): MatchPrediction {
        var homeStrength = 0.45
        var awayStrength = 0.35
        var drawBias = 0.20
        val factors = mutableListOf<PredictionFactor>()

        // Ev sahibi avantajı
        homeStrength += 0.08
        factors.add(PredictionFactor(
            "Ev Sahibi Avantajı", "Ev sahibi lehine", "Ev sahibi takım kendi sahasında oynuyor", 0.08
        ))

        // Form / duygusal durum (gerçek son maç verilerinden)
        homeForm?.let { form ->
            val delta = (form.moraleScore - 0.5) * 0.20
            homeStrength += delta
            factors.add(PredictionFactor(
                "Duygusal Durum (Ev)", formLabel(form.moraleScore),
                form.moraleExplanation, delta
            ))
        }
        awayForm?.let { form ->
            val delta = (form.moraleScore - 0.5) * 0.20
            awayStrength += delta
            factors.add(PredictionFactor(
                "Duygusal Durum (Dep)", formLabel(form.moraleScore),
                form.moraleExplanation, delta
            ))
        }

        // Hava durumu etkisi
        weather?.let { w ->
            drawBias += kotlin.math.abs(w.impactScore) * 0.3
            homeStrength += w.impactScore * 0.5
            awayStrength += w.impactScore * 0.5
            factors.add(PredictionFactor(
                "Hava Durumu", w.description,
                "${w.temperature.roundToInt()}°C, rüzgar ${w.windSpeed.roundToInt()} km/s, yağış ${w.precipitation}mm. ${w.impactExplanation}",
                w.impactScore
            ))
        }

        // Canlı maç momentumu
        if (match.status.isLive() && match.homeScore != null && match.awayScore != null) {
            val scoreDiff = match.homeScore - match.awayScore
            val momentum = scoreDiff * 0.06
            homeStrength += momentum
            awayStrength -= momentum
            factors.add(PredictionFactor(
                "Canlı Skor Momentumu",
                if (scoreDiff > 0) "Ev sahibi önde" else if (scoreDiff < 0) "Deplasman önde" else "Berabere",
                "Anlık skor: ${match.homeScore}-${match.awayScore}. Önde olan takımın kazanma şansı artar",
                momentum
            ))
        }

        // Gol averajı formu
        homeForm?.let { f ->
            val attack = if (f.goalsFor > 0) f.goalsFor.toDouble() / (f.wins + f.draws + f.losses) else 0.0
            if (attack > 2.0) {
                homeStrength += 0.05
                factors.add(PredictionFactor("Hücum Formu (Ev)", "Güçlü", "Maç başına ${"%.1f".format(attack)} gol", 0.05))
            }
        }
        awayForm?.let { f ->
            val attack = if (f.goalsFor > 0) f.goalsFor.toDouble() / (f.wins + f.draws + f.losses) else 0.0
            if (attack > 2.0) {
                awayStrength += 0.05
                factors.add(PredictionFactor("Hücum Formu (Dep)", "Güçlü", "Maç başına ${"%.1f".format(attack)} gol", 0.05))
            }
        }

        val total = (homeStrength + awayStrength + drawBias).coerceAtLeast(0.01)
        var homePct = (homeStrength / total * 100).roundToInt()
        var drawPct = (drawBias / total * 100).roundToInt()
        var awayPct = 100 - homePct - drawPct
        if (awayPct < 0) { awayPct = 0; drawPct = 100 - homePct }

        val confidence = when {
            maxOf(homePct, drawPct, awayPct) >= 55 -> "Yüksek"
            maxOf(homePct, drawPct, awayPct) >= 45 -> "Orta"
            else -> "Düşük (denk maç)"
        }

        return MatchPrediction(homePct, drawPct, awayPct, confidence, factors)
    }

    private fun formLabel(score: Double): String = when {
        score >= 0.75 -> "Çok İyi Moral"
        score >= 0.55 -> "İyi Moral"
        score >= 0.45 -> "Orta Moral"
        score >= 0.25 -> "Düşük Moral"
        else -> "Kötü Moral"
    }
}
