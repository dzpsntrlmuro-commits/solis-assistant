package com.matchpredictor.app.api

import com.matchpredictor.app.data.models.FootballMatch
import com.matchpredictor.app.data.models.MatchStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class SportsDbClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://www.thesportsdb.com/api/v1/json/3"
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun fetchMatchesForDate(date: LocalDate): List<FootballMatch> {
        val dateStr = date.format(dateFormat)
        val url = "$baseUrl/eventsday.php?d=$dateStr&s=Soccer"
        return parseEvents(fetchJson(url))
    }

    fun fetchTodayMatches(): List<FootballMatch> {
        val today = LocalDate.now()
        val matches = mutableListOf<FootballMatch>()
        matches.addAll(fetchMatchesForDate(today.minusDays(1)))
        matches.addAll(fetchMatchesForDate(today))
        matches.addAll(fetchMatchesForDate(today.plusDays(1)))

        // Büyük liglerden ek gerçek maç verisi
        MAJOR_LEAGUE_IDS.forEach { leagueId ->
            matches.addAll(fetchLeagueMatchesForDate(leagueId, today))
        }

        return matches.distinctBy { it.id }.sortedBy { "${it.date} ${it.time}" }
    }

    fun fetchLeagueMatchesForDate(leagueId: String, date: LocalDate): List<FootballMatch> {
        val season = if (date.monthValue >= 7) date.year else date.year - 1
        val url = "$baseUrl/eventsseason.php?id=$leagueId&s=$season-${season + 1}"
        val all = parseEvents(fetchJson(url))
        val dateStr = date.format(dateFormat)
        return all.filter { it.date == dateStr }
    }

    fun fetchLiveMatches(): List<FootballMatch> {
        val today = LocalDate.now()
        val all = mutableListOf<FootballMatch>()
        all.addAll(fetchMatchesForDate(today.minusDays(1)))
        all.addAll(fetchMatchesForDate(today))
        return all.filter { it.status.isLive() }.distinctBy { it.id }
    }

    fun fetchTeamLastEvents(teamId: String): List<TeamEventResult> {
        val url = "$baseUrl/eventslast.php?id=$teamId"
        val json = fetchJson(url) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        val events = mutableListOf<TeamEventResult>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val homeTeam = item.optString("strHomeTeam")
            val awayTeam = item.optString("strAwayTeam")
            val homeScore = item.optString("intHomeScore", "").toIntOrNull() ?: continue
            val awayScore = item.optString("intAwayScore", "").toIntOrNull() ?: continue
            val homeId = item.optString("idHomeTeam")
            events.add(
                TeamEventResult(
                    homeTeam = homeTeam,
                    awayTeam = awayTeam,
                    homeScore = homeScore,
                    awayScore = awayScore,
                    isHome = homeId == teamId
                )
            )
        }
        return events.take(5)
    }

    private fun fetchJson(url: String): JSONObject? {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return JSONObject(body)
        }
    }

    private fun parseEvents(json: JSONObject?): List<FootballMatch> {
        if (json == null) return emptyList()
        val events = json.optJSONArray("events") ?: return emptyList()
        val matches = mutableListOf<FootballMatch>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val homeScore = e.optString("intHomeScore", "").toIntOrNull()
            val awayScore = e.optString("intAwayScore", "").toIntOrNull()
            matches.add(
                FootballMatch(
                    id = e.optString("idEvent"),
                    homeTeam = e.optString("strHomeTeam"),
                    awayTeam = e.optString("strAwayTeam"),
                    homeTeamId = e.optString("idHomeTeam").takeIf { it.isNotBlank() },
                    awayTeamId = e.optString("idAwayTeam").takeIf { it.isNotBlank() },
                    homeBadge = e.optString("strHomeTeamBadge").takeIf { it.isNotBlank() },
                    awayBadge = e.optString("strAwayTeamBadge").takeIf { it.isNotBlank() },
                    league = e.optString("strLeague"),
                    leagueBadge = e.optString("strLeagueBadge").takeIf { it.isNotBlank() },
                    venue = e.optString("strVenue"),
                    city = e.optString("strCity"),
                    country = e.optString("strCountry"),
                    date = e.optString("dateEvent"),
                    time = e.optString("strTime", ""),
                    status = MatchStatus.fromSportsDb(e.optString("strStatus")),
                    homeScore = homeScore,
                    awayScore = awayScore,
                    minute = e.optString("strProgress", "").takeIf { it.isNotBlank() }
                )
            )
        }
        return matches
    }

    data class TeamEventResult(
        val homeTeam: String,
        val awayTeam: String,
        val homeScore: Int,
        val awayScore: Int,
        val isHome: Boolean
    )

    companion object {
        // TheSportsDB gerçek lig ID'leri
        private val MAJOR_LEAGUE_IDS = listOf(
            "4328", // Premier League
            "4335", // La Liga
            "4331", // Bundesliga
            "4332", // Serie A
            "4334", // Ligue 1
            "4480", // Süper Lig
            "4346", // MLS
            "4351", // Brasileirão
            "4356", // Eredivisie
            "4344", // Liga MX
            "4481", // UEFA Champions League
        )
    }
}
