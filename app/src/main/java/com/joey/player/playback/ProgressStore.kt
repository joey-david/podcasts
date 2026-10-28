package com.joey.player.playback

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ItemProgress(
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Podcasts count as played once you are within 30 s (or 3%) of the end. */
    val finished: Boolean
        get() = durationMs > 0L && (positionMs >= durationMs - 30_000L || fraction >= 0.97f)
}

data class QueueItem(
    val mediaId: String,
    val title: String,
    val folder: String,
    val isVideo: Boolean,
    val artworkUri: String? = null,
)

data class SavedQueue(
    val items: List<QueueItem>,
    val index: Int,
    val positionMs: Long,
)

/**
 * Per-item listening progress shared by the playback service and the UI (same process).
 * Every episode remembers where you stopped, not just the last one.
 */
object ProgressStore {
    private const val PREFS = "podder_progress"
    private const val QUEUE_KEY = "\u0000queue"
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow<Map<String, ItemProgress>>(emptyMap())
    val progress: StateFlow<Map<String, ItemProgress>> = state.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        state.value = prefs.all.mapNotNull { (key, value) ->
            if (key == QUEUE_KEY) return@mapNotNull null
            decode(value as? String)?.let { key to it }
        }.toMap()
    }

    fun get(mediaId: String): ItemProgress? = state.value[mediaId]

    fun save(mediaId: String, positionMs: Long, durationMs: Long) {
        if (mediaId.isBlank() || durationMs <= 0L) return
        val item = ItemProgress(positionMs.coerceIn(0L, durationMs), durationMs, System.currentTimeMillis())
        prefs.edit().putString(mediaId, "${item.positionMs},${item.durationMs},${item.updatedAt}").apply()
        state.value = state.value + (mediaId to item)
    }

    fun markPlayed(mediaId: String, durationMs: Long) {
        val duration = durationMs.takeIf { it > 0L } ?: get(mediaId)?.durationMs ?: 1L
        save(mediaId, duration, duration)
    }

    fun reset(mediaId: String) {
        prefs.edit().remove(mediaId).apply()
        state.value = state.value - mediaId
    }

    /** Where to start an item: saved spot unless it was finished (then from the top). */
    fun startPositionFor(mediaId: String): Long {
        val saved = get(mediaId) ?: return 0L
        return if (saved.finished || saved.positionMs < 5_000L) 0L else (saved.positionMs - 2_000L).coerceAtLeast(0L)
    }

    fun saveQueue(queue: SavedQueue) {
        val items = JSONArray()
        queue.items.forEach { item ->
            items.put(
                JSONObject()
                    .put("id", item.mediaId)
                    .put("t", item.title)
                    .put("f", item.folder)
                    .put("v", item.isVideo)
                    .put("a", item.artworkUri.orEmpty()),
            )
        }
        val json = JSONObject().put("items", items).put("index", queue.index).put("pos", queue.positionMs)
        prefs.edit().putString(QUEUE_KEY, json.toString()).apply()
    }

    fun loadQueue(): SavedQueue? = runCatching {
        val json = JSONObject(prefs.getString(QUEUE_KEY, null) ?: return null)
        val array = json.getJSONArray("items")
        val items = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            QueueItem(
                mediaId = item.getString("id"),
                title = item.optString("t"),
                folder = item.optString("f"),
                isVideo = item.optBoolean("v"),
                artworkUri = item.optString("a").ifBlank { null },
            )
        }
        SavedQueue(items, json.optInt("index").coerceIn(0, (items.size - 1).coerceAtLeast(0)), json.optLong("pos"))
    }.getOrNull()?.takeIf { it.items.isNotEmpty() }

    private fun decode(raw: String?): ItemProgress? {
        val parts = raw?.split(',') ?: return null
        if (parts.size < 3) return null
        return ItemProgress(
            positionMs = parts[0].toLongOrNull() ?: return null,
            durationMs = parts[1].toLongOrNull() ?: return null,
            updatedAt = parts[2].toLongOrNull() ?: 0L,
        )
    }
}
