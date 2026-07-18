package com.temiztube.app.data

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed downloader for NewPipe Extractor.
 * Blocks known YouTube ad / tracking hosts so accompanying requests never load ads.
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
                        .readTimeout(30, TimeUnit.SECONDS)
                        .connectTimeout(30, TimeUnit.SECONDS)
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
        "ade.googlesyndication.com"
    )

    private val blockedPathMarkers = listOf(
        "youtube.com/pagead",
        "youtube.com/ptracking",
        "youtube.com/api/stats/ads",
        "s.ytimg.com/yts/jsbin/www-pagead"
    )

    fun shouldBlock(url: String): Boolean {
        val lower = url.lowercase()
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
