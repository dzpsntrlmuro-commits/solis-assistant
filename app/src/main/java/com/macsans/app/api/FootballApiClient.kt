package com.macsans.app.api

import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus
import com.macsans.app.model.PlayerStatus
import com.macsans.app.model.TeamHistoryProfile
// HistoricalWeatherClient is in same package
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * API-Football (api-sports.io) client.
 * Free key: https://dashboard.api-football.com/
 * Header: x-apisports-key
 */
class FootballApiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    data class ApiResult(
        val matches: List<Match>,
        val sourceNote: String,
        val error: String? = null
    )

    data class PredictionPercents(
        val home: Int,
        val draw: Int,
        val away: Int,
        val advice: String,
        val homeForm: String,
        val awayForm: String
    )

    fun fetchTodayFixtures(): ApiResult {
        if (apiKey.isBlank()) {
            return ApiResult(emptyList(), "API anahtarı yok", "API anahtarı girilmedi")
        }
        val date = todayUtc()
        return try {
            val json = getJson("https://v3.football.api-sports.io/fixtures?date=$date&timezone=Europe/Istanbul")
            val errors = json.opt("errors")
            if (errors is JSONObject && errors.length() > 0) {
                return ApiResult(emptyList(), "API hatası", errors.toString())
            }
            if (errors is JSONArray && errors.length() > 0) {
                return ApiResult(emptyList(), "API hatası", errors.toString())
            }
            val response = json.optJSONArray("response") ?: JSONArray()
            val matches = mutableListOf<Match>()
            for (i in 0 until response.length()) {
                parseFixture(response.getJSONObject(i))?.let { matches.add(it) }
            }
            ApiResult(
                matches = matches,
                sourceNote = "API-Football · $date · ${matches.size} maç"
            )
        } catch (e: Exception) {
            ApiResult(emptyList(), "Bağlantı hatası", e.message)
        }
    }

    fun fetchLiveFixtures(): ApiResult {
        if (apiKey.isBlank()) {
            return ApiResult(emptyList(), "API anahtarı yok", "API anahtarı girilmedi")
        }
        return try {
            val json = getJson("https://v3.football.api-sports.io/fixtures?live=all&timezone=Europe/Istanbul")
            val response = json.optJSONArray("response") ?: JSONArray()
            val matches = mutableListOf<Match>()
            for (i in 0 until response.length()) {
                parseFixture(response.getJSONObject(i))?.let { matches.add(it) }
            }
            ApiResult(matches, "Canlı · ${matches.size} maç")
        } catch (e: Exception) {
            ApiResult(emptyList(), "Canlı yenileme hatası", e.message)
        }
    }

    fun fetchInjuriesForDate(): Map<String, List<PlayerStatus>> {
        if (apiKey.isBlank()) return emptyMap()
        return try {
            val date = todayUtc()
            val json = getJson("https://v3.football.api-sports.io/injuries?date=$date")
            val response = json.optJSONArray("response") ?: return emptyMap()
            val byTeam = mutableMapOf<String, MutableList<PlayerStatus>>()
            for (i in 0 until response.length()) {
                val item = response.getJSONObject(i)
                val player = item.optJSONObject("player") ?: continue
                val team = item.optJSONObject("team") ?: continue
                val teamName = team.optString("name")
                val reason = player.optString("reason", "Sakatlık/ceza")
                val type = player.optString("type", "")
                val level = when {
                    type.contains("Missing", true) || reason.contains("Suspended", true) -> 3
                    type.contains("Doubt", true) || reason.contains("Doubt", true) -> 2
                    else -> 2
                }
                val status = PlayerStatus(
                    name = player.optString("name", "Oyuncu"),
                    team = teamName,
                    role = type.ifBlank { "Kadro" },
                    injuryLevel = level,
                    emotionScore = 35,
                    fitnessPercent = if (level >= 3) 20 else 45,
                    note = reason.ifBlank { "Maç kadrosunda sorunlu" }
                )
                byTeam.getOrPut(teamName) { mutableListOf() }.add(status)
            }
            byTeam
        } catch (_: Exception) {
            emptyMap()
        }
    }

    data class PastFixture(
        val date: String,
        val homeName: String,
        val awayName: String,
        val homeGoals: Int,
        val awayGoals: Int,
        val isHome: Boolean,
        val result: Char, // W D L from team perspective
        val venueCity: String
    )

    fun fetchTeamLastFixtures(teamId: Long, last: Int = 5): List<PastFixture> {
        if (apiKey.isBlank() || teamId <= 0L) return emptyList()
        return try {
            val json = getJson("https://v3.football.api-sports.io/fixtures?team=$teamId&last=$last")
            val response = json.optJSONArray("response") ?: return emptyList()
            val out = mutableListOf<PastFixture>()
            for (i in 0 until response.length()) {
                val obj = response.getJSONObject(i)
                val fixture = obj.optJSONObject("fixture") ?: continue
                val teams = obj.optJSONObject("teams") ?: continue
                val goals = obj.optJSONObject("goals") ?: continue
                val home = teams.optJSONObject("home") ?: continue
                val away = teams.optJSONObject("away") ?: continue
                val status = fixture.optJSONObject("status")?.optString("short").orEmpty()
                if (status !in listOf("FT", "AET", "PEN")) continue
                val homeId = home.optLong("id")
                val isHome = homeId == teamId
                val hg = goals.optInt("home", 0)
                val ag = goals.optInt("away", 0)
                val result = when {
                    hg == ag -> 'D'
                    isHome && hg > ag -> 'W'
                    !isHome && ag > hg -> 'W'
                    else -> 'L'
                }
                val dateIso = fixture.optString("date", "")
                val date = if (dateIso.length >= 10) dateIso.substring(0, 10) else ""
                val city = fixture.optJSONObject("venue")?.optString("city").orEmpty()
                out += PastFixture(
                    date = date,
                    homeName = home.optString("name"),
                    awayName = away.optString("name"),
                    homeGoals = hg,
                    awayGoals = ag,
                    isHome = isHome,
                    result = result,
                    venueCity = city
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun buildHistoryProfile(
        teamName: String,
        past: List<PastFixture>,
        weatherDays: List<HistoricalWeatherClient.DayWeather>
    ): TeamHistoryProfile {
        var w = 0; var d = 0; var l = 0
        var gf = 0; var ga = 0
        val form = StringBuilder()
        val lines = mutableListOf<String>()
        past.forEach { p ->
            when (p.result) {
                'W' -> w++
                'D' -> d++
                else -> l++
            }
            form.append(p.result)
            if (p.isHome) {
                gf += p.homeGoals; ga += p.awayGoals
            } else {
                gf += p.awayGoals; ga += p.homeGoals
            }
            lines += "${p.date}: ${p.homeName} ${p.homeGoals}-${p.awayGoals} ${p.awayName} (${p.result})"
        }
        val formScore = ((w * 3 + d).toDouble() / (past.size.coerceAtLeast(1) * 3) * 100).roundToInt()

        // Emotional collapse: consecutive losses, heavy defeats, goal drought
        var collapse = 0
        var streakL = 0
        for (p in past) {
            if (p.result == 'L') streakL++ else break
        }
        collapse += streakL * 18
        val heavyLosses = past.count {
            val conceded = if (it.isHome) it.awayGoals - it.homeGoals else it.homeGoals - it.awayGoals
            it.result == 'L' && conceded >= 2
        }
        collapse += heavyLosses * 12
        if (gf == 0 && past.isNotEmpty()) collapse += 15
        if (ga - gf >= 5) collapse += 10
        collapse = collapse.coerceIn(0, 100)

        val collapseNote = when {
            collapse >= 70 -> "Yüksek duygusal çöküş: üst üste yenilgi / ağır skorlar baskısı"
            collapse >= 40 -> "Orta düzey moral düşüşü: son maçlarda kırılganlık var"
            collapse >= 20 -> "Hafif baskı: formda dalgalanma"
            else -> "Moral görece stabil"
        }

        // Weather vs results on hard days
        var hardPlayed = 0
        var hardPoints = 0
        past.forEachIndexed { idx, p ->
            val day = weatherDays.getOrNull(idx) ?: return@forEachIndexed
            if (day.hardCondition) {
                hardPlayed++
                hardPoints += when (p.result) {
                    'W' -> 3
                    'D' -> 1
                    else -> 0
                }
            }
        }
        val wetRecord = if (hardPlayed == 0) {
            "Zorlu hava geçmişi yok / veri yok"
        } else {
            "Zorlu havada $hardPlayed maç · $hardPoints puan (ort ${(hardPoints.toDouble() / hardPlayed).let { String.format(Locale.US, "%.1f", it) }})"
        }
        val weatherTrend = if (hardPlayed > 0 && hardPoints.toDouble() / hardPlayed < 1.0) {
            "Geçmişte zorlu havada zayıf; bugünkü hava oranları düşürür"
        } else if (hardPlayed > 0) {
            "Zorlu havada dirençli; hava dezavantajı sınırlı"
        } else {
            "Geçmiş hava-maç korelasyonu sınırlı"
        }

        return TeamHistoryProfile(
            teamName = teamName,
            formString = form.toString(),
            formScore = formScore.coerceIn(5, 99),
            wins = w,
            draws = d,
            losses = l,
            goalsFor = gf,
            goalsAgainst = ga,
            collapseScore = collapse,
            collapseNote = collapseNote,
            weatherTrendNote = weatherTrend,
            wetConditionRecord = wetRecord,
            recentLines = lines
        )
    }

    fun fetchPrediction(fixtureId: String): PredictionPercents? {
        if (apiKey.isBlank() || fixtureId.isBlank()) return null
        return try {
            val json = getJson("https://v3.football.api-sports.io/predictions?fixture=$fixtureId")
            val response = json.optJSONArray("response") ?: return null
            if (response.length() == 0) return null
            val item = response.getJSONObject(0)
            val predictions = item.optJSONObject("predictions") ?: return null
            val percent = predictions.optJSONObject("percent") ?: return null
            val comparison = item.optJSONObject("comparison")
            val teams = item.optJSONObject("teams")
            val homeForm = teams?.optJSONObject("home")?.optJSONObject("league")
                ?.optJSONObject("form")?.optString("form")
                ?: teams?.optJSONObject("home")?.optString("last_5", "")
                ?: ""
            val awayForm = teams?.optJSONObject("away")?.optJSONObject("league")
                ?.optJSONObject("form")?.optString("form")
                ?: teams?.optJSONObject("away")?.optString("last_5", "")
                ?: ""
            val formHome = comparison?.optJSONObject("form")?.optString("home")?.replace("%", "")
            val formAway = comparison?.optJSONObject("form")?.optString("away")?.replace("%", "")
            PredictionPercents(
                home = parsePercent(percent.optString("home")),
                draw = parsePercent(percent.optString("draw")),
                away = parsePercent(percent.optString("away")),
                advice = predictions.optString("advice", ""),
                homeForm = formHome?.takeIf { it.isNotBlank() } ?: homeForm,
                awayForm = formAway?.takeIf { it.isNotBlank() } ?: awayForm
            )
        } catch (_: Exception) {
            null
        }
    }

    fun validateKey(): Pair<Boolean, String> {
        if (apiKey.isBlank()) return false to "Anahtar boş"
        if (apiKey.startsWith("ghp_") || apiKey.startsWith("github_pat_")) {
            return false to "Bu bir GitHub token’ı. API-Football anahtarı değil. dashboard.api-football.com adresinden al."
        }
        if (apiKey.startsWith("AQ.") || apiKey.startsWith("AIza")) {
            return false to "Bu Google/Gemini anahtarı görünüyor. Maç verisi için API-Football anahtarı gerekli."
        }
        return try {
            val json = getJson("https://v3.football.api-sports.io/status")
            val response = json.optJSONObject("response")
            if (response != null) {
                val account = response.optJSONObject("account")
                val requests = response.optJSONObject("requests")
                val current = requests?.optInt("current", 0) ?: 0
                val limit = requests?.optInt("limit_day", 100) ?: 100
                val email = account?.optString("email", "hesap") ?: "hesap"
                true to "Bağlandı: $email · bugün $current/$limit istek kullanıldı"
            } else {
                val errors = json.opt("errors")?.toString() ?: "Bilinmeyen hata"
                false to errors
            }
        } catch (e: Exception) {
            false to (e.message ?: "Doğrulama başarısız")
        }
    }

    private fun parseFixture(obj: JSONObject): Match? {
        val fixture = obj.optJSONObject("fixture") ?: return null
        val league = obj.optJSONObject("league") ?: JSONObject()
        val teams = obj.optJSONObject("teams") ?: return null
        val goals = obj.optJSONObject("goals") ?: JSONObject()
        val home = teams.optJSONObject("home") ?: return null
        val away = teams.optJSONObject("away") ?: return null
        val statusObj = fixture.optJSONObject("status") ?: JSONObject()
        val short = statusObj.optString("short", "NS")
        val elapsed = statusObj.optInt("elapsed", 0)
        val venue = fixture.optJSONObject("venue")
        val city = venue?.optString("city")?.takeIf { it.isNotBlank() && it != "null" }
            ?: league.optString("country", "")
        val venueName = venue?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
            ?: "Stadyum"
        val status = mapStatus(short)
        val dateIso = fixture.optString("date", "")
        val kickoff = formatKickoff(dateIso)
        val homeScore = goals.optInt("home", 0).takeIf { !goals.isNull("home") } ?: 0
        val awayScore = goals.optInt("away", 0).takeIf { !goals.isNull("away") } ?: 0

        return Match(
            id = fixture.optLong("id").toString(),
            league = league.optString("name", "Lig"),
            country = league.optString("country", ""),
            homeTeam = home.optString("name", "Ev"),
            awayTeam = away.optString("name", "Deplasman"),
            homeTeamId = home.optLong("id"),
            awayTeamId = away.optLong("id"),
            kickoffLabel = kickoff,
            venue = venueName,
            city = city.ifBlank { league.optString("country", "Bilinmiyor") },
            lat = 0.0,
            lon = 0.0,
            status = status,
            minute = if (status == MatchStatus.LIVE) elapsed.coerceAtLeast(1) else if (status == MatchStatus.FINISHED) 90 else 0,
            homeScore = if (status == MatchStatus.UPCOMING) 0 else homeScore,
            awayScore = if (status == MatchStatus.UPCOMING) 0 else awayScore,
            homeForm = 60,
            awayForm = 60,
            homePlayers = emptyList(),
            awayPlayers = emptyList(),
            apiAdvice = null,
            dataSource = "API-Football"
        )
    }

    private fun mapStatus(short: String): MatchStatus = when (short.uppercase(Locale.US)) {
        "1H", "2H", "HT", "ET", "BT", "P", "LIVE", "INT", "SUSP" -> MatchStatus.LIVE
        "FT", "AET", "PEN", "AWD", "WO" -> MatchStatus.FINISHED
        else -> MatchStatus.UPCOMING
    }

    private fun formatKickoff(iso: String): String {
        if (iso.isBlank()) return "--:--"
        return try {
            val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            val outFmt = SimpleDateFormat("HH:mm", Locale("tr"))
            outFmt.timeZone = TimeZone.getTimeZone("Europe/Istanbul")
            val d = inFmt.parse(iso) ?: return iso.substring(11, 16)
            outFmt.format(d)
        } catch (_: Exception) {
            if (iso.length >= 16) iso.substring(11, 16) else "--:--"
        }
    }

    private fun parsePercent(raw: String): Int {
        return raw.replace("%", "").trim().toIntOrNull()?.coerceIn(0, 100) ?: 0
    }

    private fun todayUtc(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Europe/Istanbul")
        return fmt.format(Date())
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader("x-apisports-key", apiKey)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${body.take(200)}")
            }
            return JSONObject(body.ifBlank { "{}" })
        }
    }
}
