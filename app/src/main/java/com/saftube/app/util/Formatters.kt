package com.saftube.app.util

import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

object Formatters {

    private val tr = Locale("tr", "TR")

    fun views(count: Long): String {
        if (count <= 0) return "—"
        return when {
            count >= 1_000_000_000 -> String.format(tr, "%.1f Mr", count / 1_000_000_000.0)
            count >= 1_000_000 -> String.format(tr, "%.1f Mn", count / 1_000_000.0)
            count >= 1_000 -> String.format(tr, "%.1f B", count / 1_000.0)
            else -> NumberFormat.getIntegerInstance(tr).format(count)
        }
    }

    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = TimeUnit.SECONDS.toHours(seconds)
        val m = TimeUnit.SECONDS.toMinutes(seconds) % 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(tr, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(tr, "%d:%02d", m, s)
        }
    }
}
