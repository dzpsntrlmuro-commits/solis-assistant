package com.macsans.app.engine

import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus
import com.macsans.app.model.PlayerStatus
import com.macsans.app.model.TeamHistoryProfile
import com.macsans.app.model.WeatherInfo
import com.macsans.app.model.WinBreakdown
import kotlin.math.max
import kotlin.math.roundToInt

object PredictionEngine {

    fun analyze(
        match: Match,
        weather: WeatherInfo?,
        apiPercents: Triple<Int, Int, Int>? = null,
        apiAdvice: String? = null
    ): WinBreakdown {
        val w = weather ?: defaultWeather(match.city)

        var home: Double
        var draw: Double
        var away: Double

        if (apiPercents != null && match.status != MatchStatus.FINISHED) {
            home = apiPercents.first.toDouble()
            draw = apiPercents.second.toDouble()
            away = apiPercents.third.toDouble()
        } else {
            home = 40.0 + match.homeForm * 0.35
            away = 40.0 + match.awayForm * 0.35
            draw = 20.0
        }

        // 1) Bugünkü hava
        val weatherShift = weatherShift(w)
        home += weatherShift.first
        away += weatherShift.second
        draw += weatherShift.third

        // 2) Geçmiş maçlarda benzer zorlu hava performansı
        val histWeather = historicalWeatherAdjust(match, w)
        home += histWeather.first
        away += histWeather.second
        draw += histWeather.third

        // 3) Sakatlık
        home -= injuryPenalty(match.homePlayers)
        away -= injuryPenalty(match.awayPlayers)

        // 4) Duygusal durum + çöküş (geçmiş yenilgi serisi)
        home += emotionBonus(match.homePlayers)
        away += emotionBonus(match.awayPlayers)
        val collapse = collapseAdjust(match.homeHistory, match.awayHistory)
        home += collapse.first
        away += collapse.second
        draw += collapse.third

        // 5) Geçmiş form skoru
        match.homeHistory?.let { home += (it.formScore - 50) * 0.12 }
        match.awayHistory?.let { away += (it.formScore - 50) * 0.12 }

        var liveNote = "Oranlar: geçmiş form + hava arşivi + sakatlık + duygusal çöküş ile düzenlendi."
        if (match.status == MatchStatus.LIVE) {
            val scoreDelta = match.homeScore - match.awayScore
            val minuteFactor = match.minute / 90.0
            home += scoreDelta * 6.0 * (0.5 + minuteFactor)
            away -= scoreDelta * 6.0 * (0.5 + minuteFactor)
            if (scoreDelta == 0) draw += 4.0 + minuteFactor * 3
            liveNote = when {
                scoreDelta > 0 -> "Canlı skor ${match.homeScore}-${match.awayScore} (${match.minute}'): ev sahibi avantajı arttı."
                scoreDelta < 0 -> "Canlı skor ${match.homeScore}-${match.awayScore} (${match.minute}'): deplasman avantajı arttı."
                else -> "Canlı skor berabere (${match.minute}'): beraberlik ihtimali yükseldi."
            }
        } else if (match.status == MatchStatus.FINISHED) {
            when {
                match.homeScore > match.awayScore -> {
                    home = 100.0; away = 0.0; draw = 0.0
                }
                match.homeScore < match.awayScore -> {
                    home = 0.0; away = 100.0; draw = 0.0
                }
                else -> {
                    home = 0.0; away = 0.0; draw = 100.0
                }
            }
            liveNote = "Maç bitti (gerçek skor): ${match.homeScore}-${match.awayScore}."
        }

        val normalized = normalize(home, draw, away)
        val historyFactor = buildHistoryFactor(match)
        val weatherFactor = "Bugün: ${w.condition}, ${w.temperatureC}°C, rüzgar ${w.windKmh} km/s, yağış ${w.precipitationMm} mm. ${w.impactNote}"
        val injuryFactor = buildInjuryNote(match)
        val emotionFactor = buildEmotionNote(match)
        val formFactor = buildFormNote(match, apiAdvice)
        val summary = buildSummary(match, normalized.first, normalized.second, normalized.third, w, apiAdvice)

        return WinBreakdown(
            homeWinPercent = normalized.first,
            drawPercent = normalized.second,
            awayWinPercent = normalized.third,
            weatherFactor = weatherFactor,
            injuryFactor = injuryFactor,
            emotionFactor = emotionFactor,
            formFactor = formFactor,
            liveFactor = liveNote,
            summary = summary,
            historyFactor = historyFactor
        )
    }

    private fun historicalWeatherAdjust(match: Match, today: WeatherInfo): Triple<Double, Double, Double> {
        val todayHard = today.precipitationMm >= 2.0 || today.windKmh >= 28.0 ||
            today.temperatureC <= 2.0 || today.temperatureC >= 32.0
        if (!todayHard) return Triple(0.0, 0.0, 0.0)

        var homeAdj = 0.0
        var awayAdj = 0.0
        var drawAdj = 1.0

        match.homeHistory?.let { h ->
            if (h.wetConditionRecord.contains("zayıf", true) ||
                h.weatherTrendNote.contains("zayıf", true)
            ) {
                homeAdj -= 4.0
            } else if (h.weatherTrendNote.contains("dirençli", true)) {
                homeAdj += 2.0
            }
        }
        match.awayHistory?.let { a ->
            if (a.weatherTrendNote.contains("zayıf", true)) {
                awayAdj -= 4.5
            } else if (a.weatherTrendNote.contains("dirençli", true)) {
                awayAdj += 1.5
            } else {
                awayAdj -= 1.5 // deplasmanda zorlu hava ekstra
            }
        }
        return Triple(homeAdj, awayAdj, drawAdj)
    }

    private fun collapseAdjust(
        home: TeamHistoryProfile?,
        away: TeamHistoryProfile?
    ): Triple<Double, Double, Double> {
        val h = (home?.collapseScore ?: 0) / 100.0
        val a = (away?.collapseScore ?: 0) / 100.0
        // Çöküş kendi kazanma şansını düşürür, rakibe avantaj verir
        return Triple(
            -h * 10.0 + a * 3.0,
            -a * 10.0 + h * 3.0,
            (h + a) * 2.0
        )
    }

    private fun weatherShift(w: WeatherInfo): Triple<Double, Double, Double> {
        var home = 0.0
        var away = 0.0
        var draw = 0.0

        when {
            w.precipitationMm >= 5 -> {
                home += 3.0
                away -= 2.0
                draw += 2.5
            }
            w.precipitationMm >= 1 -> {
                home += 1.5
                draw += 1.0
            }
        }
        when {
            w.windKmh >= 35 -> {
                away -= 2.5
                draw += 2.0
            }
            w.windKmh >= 22 -> away -= 1.0
        }
        when {
            w.temperatureC <= 2 -> {
                home += 2.0
                away -= 2.5
            }
            w.temperatureC >= 32 -> {
                home -= 1.0
                away -= 1.5
                draw += 1.5
            }
        }
        return Triple(home, away, draw)
    }

    private fun injuryPenalty(players: List<PlayerStatus>): Double {
        if (players.isEmpty()) return 0.0
        return players.sumOf {
            when (it.injuryLevel) {
                3 -> 4.5
                2 -> 2.5
                1 -> 1.0
                else -> 0.0
            } + max(0, 70 - it.fitnessPercent) * 0.03
        }
    }

    private fun emotionBonus(players: List<PlayerStatus>): Double {
        if (players.isEmpty()) return 0.0
        val avg = players.map { it.emotionScore }.average()
        return (avg - 55) * 0.08
    }

    private fun buildHistoryFactor(match: Match): String {
        val h = match.homeHistory
        val a = match.awayHistory
        if (h == null && a == null) {
            return "Geçmiş maç profili için maç detayını aç (son 5 maç + arşiv hava çekilir)."
        }
        val parts = mutableListOf<String>()
        h?.let {
            parts += "${it.teamName}: form ${it.formString} (${it.formScore}/100), " +
                "çöküş ${it.collapseScore}/100 — ${it.collapseNote}. ${it.wetConditionRecord}."
        }
        a?.let {
            parts += "${it.teamName}: form ${it.formString} (${it.formScore}/100), " +
                "çöküş ${it.collapseScore}/100 — ${it.collapseNote}. ${it.wetConditionRecord}."
        }
        return parts.joinToString("\n")
    }

    private fun buildInjuryNote(match: Match): String {
        val homeOut = match.homePlayers.filter { it.injuryLevel >= 2 }
        val awayOut = match.awayPlayers.filter { it.injuryLevel >= 2 }
        val parts = mutableListOf<String>()
        if (homeOut.isNotEmpty()) {
            parts += "${match.homeTeam}: ${homeOut.joinToString { "${it.name} (${injuryLabel(it.injuryLevel)})" }}"
        }
        if (awayOut.isNotEmpty()) {
            parts += "${match.awayTeam}: ${awayOut.joinToString { "${it.name} (${injuryLabel(it.injuryLevel)})" }}"
        }
        return if (parts.isEmpty()) {
            "API sakatlık listesinde kritik eksik yok (veya lig kapsamı dışında)."
        } else {
            "Gerçek sakatlık/ceza: " + parts.joinToString(" · ")
        }
    }

    private fun buildEmotionNote(match: Match): String {
        val homeCollapse = match.homeHistory?.collapseScore
        val awayCollapse = match.awayHistory?.collapseScore
        val homeAvg = match.homePlayers.map { it.emotionScore }.average().takeIf { match.homePlayers.isNotEmpty() }
            ?.roundToInt()
            ?: (100 - (homeCollapse ?: 30))
        val awayAvg = match.awayPlayers.map { it.emotionScore }.average().takeIf { match.awayPlayers.isNotEmpty() }
            ?.roundToInt()
            ?: (100 - (awayCollapse ?: 30))
        val extra = buildString {
            match.homeHistory?.let { append(" ${it.teamName}: ${it.collapseNote}.") }
            match.awayHistory?.let { append(" ${it.teamName}: ${it.collapseNote}.") }
        }
        return "Moral: ${match.homeTeam} ${moodLabel(homeAvg)} ($homeAvg/100), " +
            "${match.awayTeam} ${moodLabel(awayAvg)} ($awayAvg/100).$extra"
    }

    private fun buildFormNote(match: Match, apiAdvice: String?): String {
        val h = match.homeHistory
        val a = match.awayHistory
        val base = if (h != null || a != null) {
            "Son maçlar: ${match.homeTeam} ${h?.formString ?: "?"} (${match.homeForm}/100) · " +
                "${match.awayTeam} ${a?.formString ?: "?"} (${match.awayForm}/100)"
        } else {
            "Form skoru: ${match.homeTeam} ${match.homeForm}/100 · ${match.awayTeam} ${match.awayForm}/100"
        }
        return if (!apiAdvice.isNullOrBlank()) "$base · API önerisi: $apiAdvice" else base
    }

    private fun buildSummary(
        match: Match,
        home: Int,
        draw: Int,
        away: Int,
        weather: WeatherInfo,
        apiAdvice: String?
    ): String {
        val favorite = when {
            home >= away && home >= draw -> match.homeTeam
            away >= home && away >= draw -> match.awayTeam
            else -> "Beraberlik"
        }
        val adviceBit = if (!apiAdvice.isNullOrBlank()) " API: $apiAdvice." else ""
        val collapseBit = when {
            (match.homeHistory?.collapseScore ?: 0) >= 50 ->
                " ${match.homeTeam} duygusal çöküş baskısında."
            (match.awayHistory?.collapseScore ?: 0) >= 50 ->
                " ${match.awayTeam} duygusal çöküş baskısında."
            else -> ""
        }
        return "Muro oranı: $favorite önde (%${maxOf(home, draw, away)}). " +
            "Hava ${weather.condition.lowercase()}.$collapseBit$adviceBit"
    }

    private fun normalize(home: Double, draw: Double, away: Double): Triple<Int, Int, Int> {
        var h = max(0.0, home)
        var d = max(0.0, draw)
        var a = max(0.0, away)
        if (h + d + a <= 0.0) {
            h = 1.0; d = 1.0; a = 1.0
        }
        val sum = h + d + a
        var hi = ((h / sum) * 100).roundToInt()
        var di = ((d / sum) * 100).roundToInt()
        var ai = 100 - hi - di
        if (ai < 0) {
            hi += ai
            ai = 0
        }
        return Triple(
            hi.coerceIn(0, 100),
            di.coerceIn(0, 100),
            ai.coerceIn(0, 100)
        )
    }

    fun weatherImpact(temp: Double, wind: Double, precip: Double, @Suppress("UNUSED_PARAMETER") condition: String): Pair<Double, String> {
        var score = 0.0
        val notes = mutableListOf<String>()
        if (precip >= 5) {
            score -= 2.5
            notes += "Şiddetli yağış top kontrolünü zorlaştırır"
        } else if (precip >= 1) {
            score -= 1.0
            notes += "Hafif yağış temposu yavaşlatabilir"
        }
        if (wind >= 30) {
            score -= 2.0
            notes += "Kuvvetli rüzgar pasları bozar"
        }
        if (temp <= 2) {
            score -= 1.5
            notes += "Soğuk hava deplasmanı zorlar"
        } else if (temp >= 32) {
            score -= 1.5
            notes += "Sıcaklık temposu düşürür"
        }
        if (notes.isEmpty()) notes += "Hava nötr etki"
        return score to notes.joinToString(". ") + "."
    }

    fun injuryLabel(level: Int): String = when (level) {
        3 -> "oynamıyor"
        2 -> "şüpheli/kısıtlı"
        1 -> "hafif rahatsız"
        else -> "sağlam"
    }

    fun moodLabel(score: Int): String = when {
        score >= 80 -> "çok yüksek moral"
        score >= 65 -> "iyi moral"
        score >= 45 -> "dengeli"
        score >= 30 -> "düşük moral"
        else -> "duygusal çöküş riski"
    }

    private fun defaultWeather(city: String) = WeatherInfo(
        city = city,
        temperatureC = 18.0,
        humidity = 55,
        windKmh = 12.0,
        precipitationMm = 0.0,
        condition = "Açık",
        impactScore = 0.0,
        impactNote = "Hava verisi yok; nötr etki varsayıldı."
    )
}
