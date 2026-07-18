package com.macsans.app.data

import android.content.Context
import com.macsans.app.api.FootballApiClient
import com.macsans.app.api.GeocodingClient
import com.macsans.app.api.HistoricalWeatherClient
import com.macsans.app.api.WeatherClient
import com.macsans.app.engine.PredictionEngine
import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus
import com.macsans.app.model.PlayerStatus
import com.macsans.app.model.TeamHistoryProfile
import com.macsans.app.model.WinBreakdown
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
// MatchOddsCache in same package

object MatchRepository {

    private val priorityCountries = setOf(
        "Turkey", "Türkiye", "England", "Spain", "Italy", "Germany", "France",
        "Netherlands", "Portugal", "Brazil", "Argentina", "USA", "World",
        "Belgium", "Scotland", "Saudi-Arabia", "Japan", "Mexico"
    )

    data class LoadResult(
        val matches: List<Match>,
        val sourceNote: String,
        val error: String? = null,
        val usingRealApi: Boolean
    )

    fun loadDailyMatches(context: Context): LoadResult {
        val key = ApiKeyStore.get(context)
        if (key.isBlank()) {
            return LoadResult(
                matches = emptyList(),
                sourceNote = "API anahtarı gerekli",
                error = "Gerçek sonuçlar için API-Football anahtarı gir. Ücretsiz: dashboard.api-football.com",
                usingRealApi = false
            )
        }

        val api = FootballApiClient(key)
        val result = api.fetchTodayFixtures()
        if (result.error != null && result.matches.isEmpty()) {
            return LoadResult(emptyList(), result.sourceNote, result.error, true)
        }

        // Free plan: listeyi öncelikli liglerle sınırla (hava için istek patlamasın)
        val prioritized = prioritize(result.matches).take(48)

        val injuriesByTeam = try {
            api.fetchInjuriesForDate()
        } catch (_: Exception) {
            emptyMap()
        }

        val pool = Executors.newFixedThreadPool(4)
        val futures = prioritized.map { match ->
            pool.submit<Match> {
                enrichMatch(match, injuriesByTeam, withHistory = false, api = null)
            }
        }
        val enriched = try {
            futures.map { it.get(25, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }

        // Detayda sabitlenen oranları listeye geri yaz
        val merged = MatchOddsCache.mergeInto(enriched)
        return LoadResult(
            matches = sortMatches(merged),
            sourceNote = "${result.sourceNote} · gösterilen ${merged.size}" +
                if (injuriesByTeam.isNotEmpty()) " · sakatlık dahil" else "",
            error = result.error,
            usingRealApi = true
        )
    }

    fun refreshLive(context: Context, current: List<Match>): List<Match> {
        val key = ApiKeyStore.get(context)
        if (key.isBlank()) return current
        val api = FootballApiClient(key)
        val liveResult = api.fetchLiveFixtures()
        if (liveResult.matches.isEmpty() && liveResult.error != null) return current

        val liveById = liveResult.matches.associateBy { it.id }
        return current.map { match ->
            val live = liveById[match.id]
            val updated = if (live != null) {
                match.copy(
                    status = live.status,
                    minute = live.minute,
                    homeScore = live.homeScore,
                    awayScore = live.awayScore,
                    homeTeamId = live.homeTeamId,
                    awayTeamId = live.awayTeamId
                )
            } else if (match.status == MatchStatus.LIVE) {
                match.copy(status = MatchStatus.FINISHED, minute = 90)
            } else {
                match
            }
            updated.copy(
                analysis = PredictionEngine.analyze(
                    updated,
                    updated.weather,
                    apiPercents = extractApiPercents(updated.analysis),
                    apiAdvice = updated.apiAdvice
                )
            )
        }.let { sortMatches(it) }
    }

    fun loadMatchDetail(context: Context, match: Match): Match {
        val key = ApiKeyStore.get(context)
        if (key.isBlank()) return match
        val api = FootballApiClient(key)
        val prediction = api.fetchPrediction(match.id)
        val injuries = api.fetchInjuriesForDate()
        val detailed = enrichMatch(match, injuries, withHistory = true, api = api, prediction = prediction)
        MatchOddsCache.put(detailed)
        return detailed
    }

    private fun prioritize(matches: List<Match>): List<Match> {
        return matches.sortedWith(
            compareBy<Match> {
                when (it.status) {
                    MatchStatus.LIVE -> 0
                    MatchStatus.UPCOMING -> 1
                    MatchStatus.FINISHED -> 2
                }
            }.thenBy { m ->
                if (priorityCountries.any { c ->
                        m.country.equals(c, true) || m.country.contains(c, true)
                    }
                ) 0 else 1
            }.thenBy { it.kickoffLabel }
        )
    }

    private fun enrichMatch(
        match: Match,
        injuriesByTeam: Map<String, List<PlayerStatus>>,
        withHistory: Boolean,
        api: FootballApiClient?,
        prediction: FootballApiClient.PredictionPercents? = null
    ): Match {
        val coords = if (match.lat == 0.0 && match.lon == 0.0 && match.city.isNotBlank()) {
            GeocodingClient.lookup(match.city, match.country)
        } else {
            match.lat to match.lon
        }
        val weather = if (coords.first != 0.0 || coords.second != 0.0) {
            WeatherClient.fetch(match.city, coords.first, coords.second)
        } else {
            null
        }

        val homeInj = injuriesByTeam[match.homeTeam].orEmpty()
        val awayInj = injuriesByTeam[match.awayTeam].orEmpty()

        val pred = prediction ?: if (withHistory && api != null) api.fetchPrediction(match.id) else null

        var homeHistory: TeamHistoryProfile? = match.homeHistory
        var awayHistory: TeamHistoryProfile? = match.awayHistory

        if (withHistory && api != null) {
            homeHistory = buildTeamHistory(api, match.homeTeam, match.homeTeamId, coords.first, coords.second)
            awayHistory = buildTeamHistory(api, match.awayTeam, match.awayTeamId, coords.first, coords.second)
        }

        val homeForm = homeHistory?.formScore
            ?: pred?.homeForm?.let { formToScore(it) }
            ?: match.homeForm
        val awayForm = awayHistory?.formScore
            ?: pred?.awayForm?.let { formToScore(it) }
            ?: match.awayForm

        val homeEmotionBase = emotionFromForm(homeForm, match, home = true) -
            ((homeHistory?.collapseScore ?: 0) * 0.35).toInt()
        val awayEmotionBase = emotionFromForm(awayForm, match, home = false) -
            ((awayHistory?.collapseScore ?: 0) * 0.35).toInt()

        val homePlayers = if (homeInj.isNotEmpty()) {
            homeInj.map {
                it.copy(
                    emotionScore = homeEmotionBase.coerceIn(10, 98),
                    note = it.note + (homeHistory?.let { h -> " · çöküş ${h.collapseScore}" } ?: "")
                )
            }
        } else {
            listOf(
                PlayerStatus(
                    name = "${match.homeTeam} kadro",
                    team = match.homeTeam,
                    role = "Takım",
                    injuryLevel = 0,
                    emotionScore = homeEmotionBase.coerceIn(10, 98),
                    fitnessPercent = homeForm.coerceIn(40, 95),
                    note = homeHistory?.collapseNote ?: "Sakatlık listesi boş"
                )
            )
        }
        val awayPlayers = if (awayInj.isNotEmpty()) {
            awayInj.map {
                it.copy(
                    emotionScore = awayEmotionBase.coerceIn(10, 98),
                    note = it.note + (awayHistory?.let { h -> " · çöküş ${h.collapseScore}" } ?: "")
                )
            }
        } else {
            listOf(
                PlayerStatus(
                    name = "${match.awayTeam} kadro",
                    team = match.awayTeam,
                    role = "Takım",
                    injuryLevel = 0,
                    emotionScore = awayEmotionBase.coerceIn(10, 98),
                    fitnessPercent = awayForm.coerceIn(40, 95),
                    note = awayHistory?.collapseNote ?: "Sakatlık listesi boş"
                )
            )
        }

        val withData = match.copy(
            lat = coords.first,
            lon = coords.second,
            weather = weather,
            homeForm = homeForm,
            awayForm = awayForm,
            homePlayers = homePlayers,
            awayPlayers = awayPlayers,
            apiAdvice = pred?.advice ?: match.apiAdvice,
            dataSource = "API-Football + Muro geçmiş analiz",
            homeHistory = homeHistory,
            awayHistory = awayHistory
        )

        val apiPercents = pred?.let { Triple(it.home, it.draw, it.away) }
        val analysis = PredictionEngine.analyze(
            withData,
            weather,
            apiPercents = apiPercents,
            apiAdvice = withData.apiAdvice
        )
        return withData.copy(analysis = analysis)
    }

    private fun buildTeamHistory(
        api: FootballApiClient,
        teamName: String,
        teamId: Long,
        lat: Double,
        lon: Double
    ): TeamHistoryProfile? {
        if (teamId <= 0L) return null
        val past = api.fetchTeamLastFixtures(teamId, last = 5)
        if (past.isEmpty()) return null
        // Geçmiş maç günlerinin hava arşivi (Open-Meteo, ücretsiz)
        val weatherDays = past.map { p ->
            HistoricalWeatherClient.fetchDay(lat, lon, p.date)
                ?: HistoricalWeatherClient.DayWeather(p.date, 18.0, 0.0, 12.0, false)
        }
        return api.buildHistoryProfile(teamName, past, weatherDays)
    }

    private fun emotionFromForm(form: Int, match: Match, home: Boolean): Int {
        var score = form.coerceIn(25, 95)
        if (match.status == MatchStatus.LIVE) {
            val delta = match.homeScore - match.awayScore
            score += when {
                home && delta > 0 -> 8
                home && delta < 0 -> -10
                !home && delta < 0 -> 8
                !home && delta > 0 -> -10
                else -> 0
            }
        }
        return score.coerceIn(15, 98)
    }

    private fun formToScore(raw: String): Int {
        raw.replace("%", "").trim().toIntOrNull()?.let { return it.coerceIn(1, 99) }
        if (raw.isBlank()) return 60
        var pts = 50
        raw.uppercase().forEach { c ->
            when (c) {
                'W' -> pts += 8
                'D' -> pts += 2
                'L' -> pts -= 8
            }
        }
        return pts.coerceIn(20, 95)
    }

    private fun extractApiPercents(analysis: WinBreakdown?): Triple<Int, Int, Int>? {
        if (analysis == null) return null
        // Don't reuse already-adjusted percents as API base on refresh — keep as soft prior
        return Triple(analysis.homeWinPercent, analysis.drawPercent, analysis.awayWinPercent)
    }

    private fun sortMatches(list: List<Match>): List<Match> {
        return list.sortedWith(
            compareBy<Match> {
                when (it.status) {
                    MatchStatus.LIVE -> 0
                    MatchStatus.UPCOMING -> 1
                    MatchStatus.FINISHED -> 2
                }
            }.thenBy { it.kickoffLabel }
        )
    }
}
