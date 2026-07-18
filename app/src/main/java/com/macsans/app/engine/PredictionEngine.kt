package com.macsans.app.engine

import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus
import com.macsans.app.model.PlayerStatus
import com.macsans.app.model.WeatherInfo
import com.macsans.app.model.WinBreakdown
import kotlin.math.max
import kotlin.math.roundToInt

object PredictionEngine {

    fun analyze(match: Match, weather: WeatherInfo?): WinBreakdown {
        val w = weather ?: defaultWeather(match.city)

        var home = 40.0 + match.homeForm * 0.35
        var away = 40.0 + match.awayForm * 0.35
        var draw = 20.0

        // Weather impact — home teams usually adapt better to local climate
        val weatherShift = weatherShift(w)
        home += weatherShift.first
        away += weatherShift.second
        draw += weatherShift.third

        val homeInjury = injuryPenalty(match.homePlayers)
        val awayInjury = injuryPenalty(match.awayPlayers)
        home -= homeInjury
        away -= awayInjury

        val homeEmotion = emotionBonus(match.homePlayers)
        val awayEmotion = emotionBonus(match.awayPlayers)
        home += homeEmotion
        away += awayEmotion

        var liveNote = "Maç henüz başlamadı; oranlar öncesi form + şartlara göre hesaplandı."
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
            liveNote = "Maç bitti: ${match.homeScore}-${match.awayScore}."
        }

        val normalized = normalize(home, draw, away)

        val weatherFactor = "Hava ${w.condition}, ${w.temperatureC}°C, rüzgar ${w.windKmh} km/s, yağış ${w.precipitationMm} mm. ${w.impactNote}"
        val injuryFactor = buildInjuryNote(match)
        val emotionFactor = buildEmotionNote(match)
        val formFactor = "Form: ${match.homeTeam} ${match.homeForm}/100 · ${match.awayTeam} ${match.awayForm}/100"
        val summary = buildSummary(match, normalized.first, normalized.second, normalized.third, w)

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
            "Kritik sakatlık yok; kadrolar büyük ölçüde hazır."
        } else {
            "Sakatlık/eksik etkisi: " + parts.joinToString(" · ")
        }
    }

    private fun buildEmotionNote(match: Match): String {
        val homeAvg = match.homePlayers.map { it.emotionScore }.average().roundToInt()
        val awayAvg = match.awayPlayers.map { it.emotionScore }.average().roundToInt()
        val homeMood = moodLabel(homeAvg)
        val awayMood = moodLabel(awayAvg)
        return "Duygusal durum: ${match.homeTeam} $homeMood ($homeAvg/100), ${match.awayTeam} $awayMood ($awayAvg/100)."
    }

    private fun buildSummary(
        match: Match,
        home: Int,
        draw: Int,
        away: Int,
        weather: WeatherInfo
    ): String {
        val favorite = when {
            home >= away && home >= draw -> match.homeTeam
            away >= home && away >= draw -> match.awayTeam
            else -> "Beraberlik"
        }
        return "$favorite önde görünüyor (%${maxOf(home, draw, away)}). " +
            "Kararı etkileyen başlıca unsurlar: ${weather.condition.lowercase()} hava, " +
            "sakatlık dengesi ve canlı skor akışı."
    }

    private fun normalize(home: Double, draw: Double, away: Double): Triple<Int, Int, Int> {
        var h = max(1.0, home)
        var d = max(1.0, draw)
        var a = max(1.0, away)
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
