package com.macsans.app.data

import com.macsans.app.model.Match
import java.util.concurrent.ConcurrentHashMap

/**
 * Detayda hesaplanan (geçmiş dahil) oranları saklar;
 * listeye dönünce oranlar zıplamaz.
 */
object MatchOddsCache {
    private val byId = ConcurrentHashMap<String, Match>()

    fun put(match: Match) {
        byId[match.id] = match
    }

    fun get(id: String): Match? = byId[id]

    fun mergeInto(list: List<Match>): List<Match> {
        return list.map { m ->
            val cached = byId[m.id] ?: return@map m
            // Canlı skor güncel kalsın, analiz/geçmiş cache'ten
            cached.copy(
                status = m.status,
                minute = m.minute,
                homeScore = m.homeScore,
                awayScore = m.awayScore,
                weather = m.weather ?: cached.weather,
                analysis = cached.analysis,
                homeHistory = cached.homeHistory,
                awayHistory = cached.awayHistory,
                homePlayers = cached.homePlayers.ifEmpty { m.homePlayers },
                awayPlayers = cached.awayPlayers.ifEmpty { m.awayPlayers },
                homeForm = cached.homeForm,
                awayForm = cached.awayForm,
                apiAdvice = cached.apiAdvice ?: m.apiAdvice,
                dataSource = cached.dataSource
            )
        }
    }
}
