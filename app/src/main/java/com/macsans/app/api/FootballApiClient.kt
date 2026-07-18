package com.macsans.app.api

import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus
import com.macsans.app.model.PlayerStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

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
