package com.matchpredictor.app.data.models

data class FootballMatch(
    val id: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeTeamId: String?,
    val awayTeamId: String?,
    val homeBadge: String?,
    val awayBadge: String?,
    val league: String,
    val leagueBadge: String?,
    val venue: String,
    val city: String,
    val country: String,
    val date: String,
    val time: String,
    val status: MatchStatus,
    val homeScore: Int?,
    val awayScore: Int?,
    val minute: String?,
    val weather: WeatherInfo? = null,
    val homeForm: TeamForm? = null,
    val awayForm: TeamForm? = null,
    val prediction: MatchPrediction? = null
)

enum class MatchStatus {
    SCHEDULED, LIVE, HALFTIME, FINISHED, POSTPONED, UNKNOWN;

    companion object {
        fun fromSportsDb(status: String?): MatchStatus = when (status?.uppercase()) {
            "NS", "TBD", "SCHEDULED" -> SCHEDULED
            "1H", "2H", "ET", "BT", "P", "LIVE", "IN PLAY", "IN_PLAY" -> LIVE
            "HT", "HALFTIME" -> HALFTIME
            "FT", "AET", "PEN", "FINISHED" -> FINISHED
            "PST", "POSTPONED", "CANC" -> POSTPONED
            else -> UNKNOWN
        }
    }

    fun isLive(): Boolean = this == LIVE || this == HALFTIME
}

data class WeatherInfo(
    val temperature: Double,
    val humidity: Int,
    val precipitation: Double,
    val windSpeed: Double,
    val weatherCode: Int,
    val description: String,
    val impactScore: Double,
    val impactExplanation: String
)

data class TeamForm(
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val recentResults: List<String>,
    val moraleScore: Double,
    val moraleExplanation: String
)

data class MatchPrediction(
    val homeWinPercent: Int,
    val drawPercent: Int,
    val awayWinPercent: Int,
    val confidence: String,
    val factors: List<PredictionFactor>
)

data class PredictionFactor(
    val name: String,
    val impact: String,
    val detail: String,
    val scoreDelta: Double
)
