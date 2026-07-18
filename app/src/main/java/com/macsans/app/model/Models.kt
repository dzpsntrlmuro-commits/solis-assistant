package com.macsans.app.model

data class WeatherInfo(
    val city: String,
    val temperatureC: Double,
    val humidity: Int,
    val windKmh: Double,
    val precipitationMm: Double,
    val condition: String,
    val impactScore: Double,
    val impactNote: String
)

data class PlayerStatus(
    val name: String,
    val team: String,
    val role: String,
    val injuryLevel: Int,       // 0 none .. 3 out
    val emotionScore: Int,      // 0 low .. 100 high morale
    val fitnessPercent: Int,
    val note: String
)

/** Son maçların özeti: form, hava etkisi, duygusal çöküş */
data class TeamHistoryProfile(
    val teamName: String,
    val formString: String,          // örn WWLDW
    val formScore: Int,              // 0-100
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val collapseScore: Int,          // 0-100 yüksek = duygusal çöküş
    val collapseNote: String,
    val weatherTrendNote: String,
    val wetConditionRecord: String,  // yağışlı/zorlu havada skor
    val recentLines: List<String>
)

data class WinBreakdown(
    val homeWinPercent: Int,
    val drawPercent: Int,
    val awayWinPercent: Int,
    val weatherFactor: String,
    val injuryFactor: String,
    val emotionFactor: String,
    val formFactor: String,
    val liveFactor: String,
    val summary: String,
    val historyFactor: String = ""
)

enum class MatchStatus {
    UPCOMING, LIVE, FINISHED
}

data class Match(
    val id: String,
    val league: String,
    val country: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeTeamId: Long = 0L,
    val awayTeamId: Long = 0L,
    val kickoffLabel: String,
    val venue: String,
    val city: String,
    val lat: Double,
    val lon: Double,
    val status: MatchStatus,
    val minute: Int,
    val homeScore: Int,
    val awayScore: Int,
    val homeForm: Int,
    val awayForm: Int,
    val homePlayers: List<PlayerStatus>,
    val awayPlayers: List<PlayerStatus>,
    var weather: WeatherInfo? = null,
    var analysis: WinBreakdown? = null,
    val apiAdvice: String? = null,
    val dataSource: String = "demo",
    val homeHistory: TeamHistoryProfile? = null,
    val awayHistory: TeamHistoryProfile? = null
)
