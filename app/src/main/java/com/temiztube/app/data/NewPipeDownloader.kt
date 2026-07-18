package com.temiztube.app.data

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed downloader for NewPipe Extractor (search only).
 */
class NewPipeDownloader private constructor(
    private val client: OkHttpClient
) : Downloader() {

    override fun execute(request: Request): Response {
        val url = request.url()
        if (AdBlockFilter.shouldBlock(url)) {
            return Response(204, "Blocked", emptyMap(), "", url)
        }

        val builder = okhttp3.Request.Builder()
            .url(url)
            .method(
                request.httpMethod(),
                request.dataToSend()?.toRequestBody(null)
            )

        for ((name, values) in request.headers()) {
            if (values.size > 1) {
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            } else if (values.isNotEmpty()) {
                builder.header(name, values[0])
            }
        }

        if (request.headers()["User-Agent"].isNullOrEmpty()) {
            builder.header("User-Agent", USER_AGENT)
        }
        if (request.headers()["Accept-Language"].isNullOrEmpty()) {
            builder.header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
        }
        if (request.headers()["Cookie"].isNullOrEmpty()) {
            builder.header("Cookie", "CONSENT=YES+; SOCS=CAI")
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("Too many requests", url)
            }
            val body = response.body?.string().orEmpty()
            val headers = LinkedHashMap<String, List<String>>()
            for (name in response.headers.names()) {
                headers[name] = response.headers.values(name)
            }
            return Response(
                response.code,
                response.message,
                headers,
                body,
                response.request.url.toString()
            )
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        @Volatile
        private var instance: NewPipeDownloader? = null

        fun getInstance(): NewPipeDownloader {
            return instance ?: synchronized(this) {
                instance ?: NewPipeDownloader(
                    OkHttpClient.Builder()
                        .readTimeout(20, TimeUnit.SECONDS)
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .build()
                ).also { instance = it }
            }
        }
    }
}

object AdBlockFilter {
    private val blockedHosts = setOf(
        "pagead2.googlesyndication.com",
        "googleads.g.doubleclick.net",
        "www.googleadservices.com",
        "static.doubleclick.net",
        "ad.doubleclick.net",
        "adservice.google.com",
        "adservice.google.com.tr",
        "tpc.googlesyndication.com",
        "partner.googleadservices.com",
        "ade.googlesyndication.com",
        "pagead.l.doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "doubleclick.net",
        "ads.youtube.com",
        "advertise.bingads.microsoft.com",
        "ad.youtube.com",
        "s0.2mdn.net",
        "securepubads.g.doubleclick.net",
        "www.googletagservices.com",
        "www.googletagmanager.com",
        "fundingchoicesmessages.google.com"
    )

    private val blockedPathMarkers = listOf(
        "/pagead",
        "/ptracking",
        "/api/stats/ads",
        "/pc/ads",
        "/get_midroll",
        "/youtubei/v1/player/ad",
        "www-pagead",
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com"
    )

    fun shouldBlock(url: String): Boolean {
        val lower = url.lowercase()
        // Never block the actual video media CDN or player assets needed for playback
        if (lower.contains("googlevideo.com") && !lower.contains("ad")) return false
        if (lower.contains("ytimg.com") && !lower.contains("pagead")) return false
        if (lower.contains("jnn-pa.googleapis.com")) return false

        if (blockedPathMarkers.any { lower.contains(it) }) return true
        val hostPart = lower
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
        return blockedHosts.any { host ->
            hostPart == host || hostPart.endsWith(".$host")
        }
    }
}
