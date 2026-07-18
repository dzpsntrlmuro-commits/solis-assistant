package com.matchpredictor.app.data

import com.matchpredictor.app.api.SportsDbClient
import com.matchpredictor.app.api.WeatherClient
import com.matchpredictor.app.data.models.FootballMatch
import com.matchpredictor.app.prediction.PredictionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MatchRepository(
    private val sportsDb: SportsDbClient = SportsDbClient(),
    private val weatherClient: WeatherClient = WeatherClient(),
    private val predictionEngine: PredictionEngine = PredictionEngine()
) {

    suspend fun getDailyMatches(): Result<List<FootballMatch>> = withContext(Dispatchers.IO) {
        try {
            val today = LocalDate.now().toString()
            val matches = sportsDb.fetchTodayMatches()
                .filter { it.date == today || it.status.isLive() }
            if (matches.isEmpty()) {
                Result.failure(Exception("Bugün için gerçek maç verisi bulunamadı. İnternet bağlantınızı kontrol edin."))
            } else {
                Result.success(enrichMatches(matches))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Gerçek maç verisi alınamadı: ${e.message}"))
        }
    }

    suspend fun getLiveMatches(): Result<List<FootballMatch>> = withContext(Dispatchers.IO) {
        try {
            val matches = sportsDb.fetchLiveMatches()
            if (matches.isEmpty()) {
                Result.success(emptyList())
            } else {
                Result.success(enrichMatches(matches))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMatchDetail(matchId: String): FootballMatch? = withContext(Dispatchers.IO) {
        val all = sportsDb.fetchTodayMatches()
        val match = all.find { it.id == matchId } ?: return@withContext null
        enrichMatches(listOf(match)).firstOrNull()
    }

    private fun enrichMatches(matches: List<FootballMatch>): List<FootballMatch> {
        return matches.map { match ->
            val weather = weatherClient.fetchWeatherForLocation(match.city, match.country, match.venue)
            val homeForm = match.homeTeamId?.let { id ->
                val events = sportsDb.fetchTeamLastEvents(id)
                predictionEngine.buildTeamForm(id, match.homeTeam, events)
            }
            val awayForm = match.awayTeamId?.let { id ->
                val events = sportsDb.fetchTeamLastEvents(id)
                predictionEngine.buildTeamForm(id, match.awayTeam, events)
            }
            val prediction = predictionEngine.predict(match, homeForm, awayForm, weather)
            match.copy(weather = weather, homeForm = homeForm, awayForm = awayForm, prediction = prediction)
        }
    }
}
