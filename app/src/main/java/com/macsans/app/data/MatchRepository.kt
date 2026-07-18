package com.macsans.app.data

import com.macsans.app.api.WeatherClient
import com.macsans.app.engine.PredictionEngine
import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus
import com.macsans.app.model.PlayerStatus
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object MatchRepository {

    fun loadDailyMatches(): List<Match> {
        val seed = daySeed()
        val fixtures = seedFixtures(seed)
        val pool = Executors.newFixedThreadPool(6)
        val futures = fixtures.map { match ->
            pool.submit<Match> {
                val weather = WeatherClient.fetch(match.city, match.lat, match.lon)
                val analyzed = match.copy(weather = weather)
                analyzed.copy(analysis = PredictionEngine.analyze(analyzed, weather))
            }
        }
        val base = futures.map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()
        return base.sortedWith(
            compareBy<Match> {
                when (it.status) {
                    MatchStatus.LIVE -> 0
                    MatchStatus.UPCOMING -> 1
                    MatchStatus.FINISHED -> 2
                }
            }.thenBy { it.kickoffLabel }
        )
    }

    fun refreshLive(matches: List<Match>): List<Match> {
        return matches.map { match ->
            if (match.status != MatchStatus.LIVE) return@map match
            val advancedMinute = (match.minute + (2..4).random()).coerceAtMost(90)
            var homeScore = match.homeScore
            var awayScore = match.awayScore
            if ((1..10).random() <= 2) {
                if ((0..1).random() == 0) homeScore++ else awayScore++
            }
            val updated = match.copy(
                minute = advancedMinute,
                homeScore = homeScore,
                awayScore = awayScore,
                status = if (advancedMinute >= 90) MatchStatus.FINISHED else MatchStatus.LIVE
            )
            updated.copy(analysis = PredictionEngine.analyze(updated, updated.weather))
        }
    }

    private fun daySeed(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    private fun seedFixtures(seed: Int): List<Match> {
        val templates = listOf(
            Template("Süper Lig", "Türkiye", "Galatasaray", "Fenerbahçe", "RAMS Park", "İstanbul", 41.103, 28.990, 78, 76),
            Template("Süper Lig", "Türkiye", "Beşiktaş", "Trabzonspor", "Tüpraş Stadyumu", "İstanbul", 41.039, 28.994, 72, 70),
            Template("Premier League", "İngiltere", "Arsenal", "Liverpool", "Emirates", "Londra", 51.555, -0.108, 80, 82),
            Template("Premier League", "İngiltere", "Man City", "Chelsea", "Etihad", "Manchester", 53.483, -2.200, 84, 74),
            Template("La Liga", "İspanya", "Real Madrid", "Barcelona", "Santiago Bernabéu", "Madrid", 40.453, -3.688, 85, 83),
            Template("La Liga", "İspanya", "Atlético", "Sevilla", "Cívitas Metropolitano", "Madrid", 40.436, -3.599, 75, 68),
            Template("Serie A", "İtalya", "Inter", "Milan", "San Siro", "Milano", 45.478, 9.124, 81, 77),
            Template("Serie A", "İtalya", "Juventus", "Napoli", "Allianz Stadium", "Torino", 45.109, 7.641, 76, 79),
            Template("Bundesliga", "Almanya", "Bayern", "Dortmund", "Allianz Arena", "Münih", 48.218, 11.624, 86, 78),
            Template("Bundesliga", "Almanya", "Leipzig", "Leverkusen", "Red Bull Arena", "Leipzig", 51.345, 12.348, 73, 80),
            Template("Ligue 1", "Fransa", "PSG", "Marseille", "Parc des Princes", "Paris", 48.841, 2.253, 84, 71),
            Template("Eredivisie", "Hollanda", "Ajax", "PSV", "Johan Cruijff Arena", "Amsterdam", 52.314, 4.941, 74, 77),
            Template("MLS", "ABD", "Inter Miami", "LAFC", "Chase Stadium", "Miami", 25.957, -80.238, 70, 72),
            Template("Brasileirão", "Brezilya", "Flamengo", "Palmeiras", "Maracanã", "Rio de Janeiro", -22.912, -43.230, 79, 78),
            Template("Saudi Pro League", "Suudi Arabistan", "Al Hilal", "Al Nassr", "Kingdom Arena", "Riyad", 24.743, 46.622, 82, 80),
            Template("J1 League", "Japonya", "Urawa", "Kashima", "Saitama Stadium", "Saitama", 35.903, 139.717, 68, 69)
        )

        return templates.mapIndexed { index, t ->
            val roll = abs(seed * 31 + index * 17) % 100
            val status = when {
                roll < 28 -> MatchStatus.LIVE
                roll < 55 -> MatchStatus.FINISHED
                else -> MatchStatus.UPCOMING
            }
            val minute = when (status) {
                MatchStatus.LIVE -> 12 + (abs(seed + index * 9) % 75)
                MatchStatus.FINISHED -> 90
                MatchStatus.UPCOMING -> 0
            }
            val homeScore = if (status == MatchStatus.UPCOMING) 0 else abs(seed + index) % 4
            val awayScore = if (status == MatchStatus.UPCOMING) 0 else abs(seed * 3 + index * 5) % 3
            val hour = 14 + (index % 8)
            val minuteLabel = listOf("00", "15", "30", "45")[index % 4]
            Match(
                id = "m-$seed-$index",
                league = t.league,
                country = t.country,
                homeTeam = t.home,
                awayTeam = t.away,
                kickoffLabel = String.format(Locale.US, "%02d:%s", hour, minuteLabel),
                venue = t.venue,
                city = t.city,
                lat = t.lat,
                lon = t.lon,
                status = status,
                minute = minute,
                homeScore = homeScore,
                awayScore = awayScore,
                homeForm = t.homeForm + ((seed + index) % 7) - 3,
                awayForm = t.awayForm + ((seed * 2 + index) % 7) - 3,
                homePlayers = playersFor(t.home, seed + index),
                awayPlayers = playersFor(t.away, seed + index * 3)
            )
        }
    }

    private fun playersFor(team: String, seed: Int): List<PlayerStatus> {
        val names = listOf(
            "Kaptan", "10 Numara", "Santrafor", "Stoper", "Kanat"
        )
        return names.mapIndexed { i, roleName ->
            val injury = abs(seed * (i + 2)) % 11
            val injuryLevel = when {
                injury == 0 -> 3
                injury <= 2 -> 2
                injury <= 4 -> 1
                else -> 0
            }
            val emotion = 35 + abs(seed * (i + 5)) % 55
            val fitness = 55 + abs(seed * (i + 7)) % 40
            PlayerStatus(
                name = "$team $roleName",
                team = team,
                role = roleName,
                injuryLevel = injuryLevel,
                emotionScore = emotion,
                fitnessPercent = fitness,
                note = when (injuryLevel) {
                    3 -> "Maç kadrosunda yok"
                    2 -> "Antrenmanda kısıtlı çalıştı"
                    1 -> "Hafif kas rahatsızlığı"
                    else -> if (emotion >= 75) "Yüksek motivasyon" else "Stabil performans beklentisi"
                }
            )
        }
    }

    private data class Template(
        val league: String,
        val country: String,
        val home: String,
        val away: String,
        val venue: String,
        val city: String,
        val lat: Double,
        val lon: Double,
        val homeForm: Int,
        val awayForm: Int
    )
}
