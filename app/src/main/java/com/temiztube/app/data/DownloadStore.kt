package com.temiztube.app.data

import android.content.Context
import com.temiztube.app.model.DownloadItem
import com.temiztube.app.model.DownloadKind
import com.temiztube.app.model.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class DownloadStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    fun get(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    fun create(
        videoId: String,
        title: String,
        kind: DownloadKind,
        fileName: String
    ): DownloadItem {
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            videoId = videoId,
            title = title,
            kind = kind,
            status = DownloadStatus.QUEUED,
            fileName = fileName
        )
        upsert(item)
        return item
    }

    fun upsert(item: DownloadItem) {
        val next = _items.value.toMutableList()
        val idx = next.indexOfFirst { it.id == item.id }
        if (idx >= 0) next[idx] = item else next.add(0, item)
        _items.value = next.sortedByDescending { it.createdAt }
        persist(next)
    }

    fun update(
        id: String,
        transform: (DownloadItem) -> DownloadItem
    ): DownloadItem? {
        val current = get(id) ?: return null
        val updated = transform(current).copy(updatedAt = System.currentTimeMillis())
        upsert(updated)
        return updated
    }

    fun remove(id: String) {
        val next = _items.value.filterNot { it.id == id }
        _items.value = next
        persist(next)
    }

    private fun persist(list: List<DownloadItem>) {
        val arr = JSONArray()
        list.take(100).forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("videoId", item.videoId)
                    .put("title", item.title)
                    .put("kind", item.kind.name)
                    .put("status", item.status.name)
                    .put("progressPercent", item.progressPercent)
                    .put("fileName", item.fileName)
                    .put("localPath", item.localPath)
                    .put("publicUri", item.publicUri)
                    .put("errorMessage", item.errorMessage)
                    .put("createdAt", item.createdAt)
                    .put("updatedAt", item.updatedAt)
                    .put("bytesDownloaded", item.bytesDownloaded)
                    .put("totalBytes", item.totalBytes)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun load(): List<DownloadItem> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        DownloadItem(
                            id = o.getString("id"),
                            videoId = o.optString("videoId"),
                            title = o.optString("title"),
                            kind = runCatching {
                                DownloadKind.valueOf(o.optString("kind", DownloadKind.VIDEO.name))
                            }.getOrDefault(DownloadKind.VIDEO),
                            status = runCatching {
                                DownloadStatus.valueOf(o.optString("status", DownloadStatus.FAILED.name))
                            }.getOrDefault(DownloadStatus.FAILED),
                            progressPercent = o.optInt("progressPercent"),
                            fileName = o.optString("fileName"),
                            localPath = o.optString("localPath").takeIf { it.isNotBlank() },
                            publicUri = o.optString("publicUri").takeIf { it.isNotBlank() },
                            errorMessage = o.optString("errorMessage").takeIf { it.isNotBlank() },
                            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                            bytesDownloaded = o.optLong("bytesDownloaded"),
                            totalBytes = o.optLong("totalBytes", -1L)
                        )
                    )
                }
            }.sortedByDescending { it.createdAt }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS = "murotube_downloads"
        private const val KEY = "items"

        @Volatile
        private var instance: DownloadStore? = null

        fun get(context: Context): DownloadStore {
            return instance ?: synchronized(this) {
                instance ?: DownloadStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
