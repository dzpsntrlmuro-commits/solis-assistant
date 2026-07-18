package com.macsans.app.engine

import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus
import com.macsans.app.model.PlayerStatus
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
            // Start from real API-Football prediction, then adjust with live/weather/injury
            home = apiPercents.first.toDouble()
            draw = apiPercents.second.toDouble()
            away = apiPercents.third.toDouble()

            val weatherShift = weatherShift(w)
            home += weatherShift.first * 0.6
            away += weatherShift.second * 0.6
            draw += weatherShift.third * 0.6

            home -= injuryPenalty(match.homePlayers) * 0.7
            away -= injuryPenalty(match.awayPlayers) * 0.7
            home += emotionBonus(match.homePlayers)
            away += emotionBonus(match.awayPlayers)
        } else {
            home = 40.0 + match.homeForm * 0.35
            away = 40.0 + match.awayForm * 0.35
            draw = 20.0

            val weatherShift = weatherShift(w)
            home += weatherShift.first
            away += weatherShift.second
            draw += weatherShift.third

            home -= injuryPenalty(match.homePlayers)
            away -= injuryPenalty(match.awayPlayers)
            home += emotionBonus(match.homePlayers)
            away += emotionBonus(match.awayPlayers)
        }

        var liveNote = "Maç henüz başlamadı; oranlar gerçek fikstür + hava + sakatlık verisine göre."
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

        val weatherFactor = "Hava ${w.condition}, ${w.temperatureC}°C, rüzgar ${w.windKmh} km/s, yağış ${w.precipitationMm} mm. ${w.impactNote}"
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
            summary = summary
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
            "Gerçek sakatlık/ceza (API-Football): " + parts.joinToString(" · ")
        }
    }

    private fun buildEmotionNote(match: Match): String {
        if (match.homePlayers.isEmpty() && match.awayPlayers.isEmpty()) {
            return "Duygusal durum: form ve canlı skor baskısından türetildi."
        }
        val homeAvg = match.homePlayers.map { it.emotionScore }.average().roundToInt()
        val awayAvg = match.awayPlayers.map { it.emotionScore }.average().roundToInt()
        return "Moral (form + canlı skor): ${match.homeTeam} ${moodLabel(homeAvg)} ($homeAvg/100), " +
            "${match.awayTeam} ${moodLabel(awayAvg)} ($awayAvg/100)."
    }

    private fun buildFormNote(match: Match, apiAdvice: String?): String {
        val base = "Form skoru: ${match.homeTeam} ${match.homeForm}/100 · ${match.awayTeam} ${match.awayForm}/100"
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
        return "$favorite önde (%${maxOf(home, draw, away)}). " +
            "Kaynak: ${match.dataSource}. Hava: ${weather.condition}.${adviceBit}"
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
            notes += "Şiddetli yağış top kontrolünü zorlaştırır, skorlar düşebilir"
        } else if (precip >= 1) {
            score -= 1.0
            notes += "Hafif yağış pas temposunu yavaşlatabilir"
        }
        if (wind >= 30) {
            score -= 2.0
            notes += "Kuvvetli rüzgar orta ve uzun pasları bozar"
        }
        if (temp <= 2) {
            score -= 1.5
            notes += "Soğuk hava deplasman adaptasyonunu zorlar"
        } else if (temp >= 32) {
            score -= 1.5
            notes += "Sıcaklık temposu düşürür, sakatlık riski artar"
        }
        if (notes.isEmpty()) notes += "Hava koşulları dengeli; oyun akışına nötr etki"
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
        else -> "gergin / baskı altında"
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
