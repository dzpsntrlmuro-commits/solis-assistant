package com.saftube.app.adblock

/**
 * YouTube / Google reklam ve izleme alanlarını engelleyen filtre.
 * Piped üzerinden gelen reklamsız akışlarla birlikte kullanılır.
 */
object AdBlocker {

    private val blockedHosts = setOf(
        "googleads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "www.googleadservices.com",
        "ade.googlesyndication.com",
        "ad.doubleclick.net",
        "static.doubleclick.net",
        "googleads4.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "tpc.googlesyndication.com",
        "www.googletagservices.com",
        "www.googletagmanager.com",
        "youtube.com/pagead",
        "youtube.com/ptracking",
        "youtube.com/api/stats/ads",
        "youtube.com/get_midroll_info",
        "youtube.com/pagead/adview",
        "youtube.com/pagead/conversion",
        "youtubei.googleapis.com/youtubei/v1/player/ad_break",
        "s.youtube.com/api/stats/qoe",
        "s.youtube.com/api/stats/watchtime",
        "adservice.google.com",
        "adservice.google.com.tr",
        "ad.youtube.com",
        "ads.youtube.com",
        "www.youtube.com/pagead",
        "www.youtube.com/ptracking",
        "m.youtube.com/pagead",
        "i.ytimg.com/generate_204",
        "pixel.facebook.com",
        "an.facebook.com"
    )

    private val blockedPathHints = listOf(
        "/pagead",
        "/ptracking",
        "/get_video_info",
        "ad_break",
        "adunit",
        "doubleclick",
        "googlesyndication",
        "googleadservices",
        "/pcs/view",
        "/pagead/adview",
        "midroll",
        "instream"
    )

    @Volatile
    private var blockedCount: Int = 0

    fun resetCount() {
        blockedCount = 0
    }

    fun getBlockedCount(): Int = blockedCount

    fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        val hostMatch = blockedHosts.any { lower.contains(it) }
        val pathMatch = blockedPathHints.any { lower.contains(it) }
        val block = hostMatch || pathMatch
        if (block) {
            blockedCount++
        }
        return block
    }

    fun isAdRelatedQuery(query: String): Boolean {
        val q = query.lowercase()
        return q.contains("sponsor") || q.contains("reklam") || q.contains("promo")
    }
}
