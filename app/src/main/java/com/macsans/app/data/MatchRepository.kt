package com.macsans.app.data

import android.content.Context
import com.macsans.app.api.FootballApiClient
import com.macsans.app.api.GeocodingClient
import com.macsans.app.api.WeatherClient
import com.macsans.app.engine.PredictionEngine
import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus
import com.macsans.app.model.PlayerStatus
import com.macsans.app.model.WinBreakdown
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
object MatchRepository {

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

        // One extra call for today's injuries (real absences)
        val injuriesByTeam = try {
            api.fetchInjuriesForDate()
        } catch (_: Exception) {
            emptyMap()
        }

        val pool = Executors.newFixedThreadPool(4)
        val futures = result.matches.map { match ->
            pool.submit<Match> {
                enrichMatch(match, injuriesByTeam, fetchPrediction = false, api = null)
            }
        }
        val enriched = try {
            futures.map { it.get(25, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }

        return LoadResult(
            matches = sortMatches(enriched),
            sourceNote = result.sourceNote + if (injuriesByTeam.isNotEmpty()) " · sakatlık verisi dahil" else "",
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
                    awayScore = live.awayScore
                )
            } else if (match.status == MatchStatus.LIVE) {
                // Was live, not in live feed anymore — re-check via keeping scores, mark finished lightly
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
        return enrichMatch(match, injuries, fetchPrediction = true, api = api, prediction = prediction)
    }

    private fun enrichMatch(
        match: Match,
        injuriesByTeam: Map<String, List<PlayerStatus>>,
        fetchPrediction: Boolean,
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

        val pred = prediction ?: if (fetchPrediction && api != null) api.fetchPrediction(match.id) else null
        val homeForm = pred?.homeForm?.let { formToScore(it) } ?: match.homeForm
        val awayForm = pred?.awayForm?.let { formToScore(it) } ?: match.awayForm

        // Emotional state approximated from form + injury burden + live score pressure
        val homePlayers = if (homeInj.isNotEmpty()) {
            homeInj.map { it.copy(emotionScore = emotionFromForm(homeForm, match, home = true)) }
        } else {
            listOf(
                PlayerStatus(
                    name = "${match.homeTeam} kadro",
                    team = match.homeTeam,
                    role = "Takım",
                    injuryLevel = 0,
                    emotionScore = emotionFromForm(homeForm, match, home = true),
                    fitnessPercent = homeForm.coerceIn(40, 95),
                    note = "Kritik sakatlık raporu yok / API’de listelenmedi"
                )
            )
        }
        val awayPlayers = if (awayInj.isNotEmpty()) {
            awayInj.map { it.copy(emotionScore = emotionFromForm(awayForm, match, home = false)) }
        } else {
            listOf(
                PlayerStatus(
                    name = "${match.awayTeam} kadro",
                    team = match.awayTeam,
                    role = "Takım",
                    injuryLevel = 0,
                    emotionScore = emotionFromForm(awayForm, match, home = false),
                    fitnessPercent = awayForm.coerceIn(40, 95),
                    note = "Kritik sakatlık raporu yok / API’de listelenmedi"
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
            dataSource = "API-Football"
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
        // API may return "65" percent or "WWDLW"
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
