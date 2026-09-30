package app.fetch.browser

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

data class SavedTab(val id: String, val url: String, val title: String)
data class Bookmark(val url: String, val title: String)
data class HistoryEntry(val url: String, val title: String, val visitedAt: Long)

/** Tabs, bookmarks and history, persisted so a process kill never loses the user's session. Main-thread only. */
class BrowserStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fetch_browser", Context.MODE_PRIVATE)
    val bookmarks = mutableStateListOf<Bookmark>()
    val history = mutableStateListOf<HistoryEntry>()

    init {
        bookmarks += readArray(KEY_BOOKMARKS) { Bookmark(it.getString("url"), it.optString("title")) }
        history += readArray(KEY_HISTORY) { HistoryEntry(it.getString("url"), it.optString("title"), it.optLong("at")) }
    }

    fun loadTabs(): Pair<List<SavedTab>, String?> =
        readArray(KEY_TABS) { SavedTab(it.getString("id"), it.optString("url"), it.optString("title")) } to prefs.getString(KEY_ACTIVE_TAB, null)

    fun saveTabs(tabs: List<SavedTab>, activeId: String) {
        val json = JSONArray().apply { tabs.forEach { put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title)) } }
        prefs.edit().putString(KEY_TABS, json.toString()).putString(KEY_ACTIVE_TAB, activeId).apply()
    }

    fun isBookmarked(url: String) = url.isNotBlank() && bookmarks.any { it.url == url }

    fun toggleBookmark(url: String, title: String) {
        if (url.isBlank()) return
        if (!bookmarks.removeAll { it.url == url }) bookmarks.add(0, Bookmark(url, title.ifBlank { UrlUtils.host(url) }))
        writeBookmarks()
    }

    fun removeBookmark(bookmark: Bookmark) {
        bookmarks.remove(bookmark)
        writeBookmarks()
    }

    fun recordVisit(url: String, title: String) {
        if (!url.startsWith("http")) return
        val last = history.firstOrNull()
        if (last?.url == url) history[0] = last.copy(title = title.ifBlank { last.title }, visitedAt = System.currentTimeMillis())
        else history.add(0, HistoryEntry(url, title, System.currentTimeMillis()))
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        writeHistory()
    }

    fun updateTitle(url: String, title: String) {
        val index = history.indexOfFirst { it.url == url }
        if (index == 0 && title.isNotBlank() && history[0].title != title) {
            history[0] = history[0].copy(title = title)
            writeHistory()
        }
    }

    fun clearHistory() {
        history.clear()
        writeHistory()
    }

    private fun writeBookmarks() = prefs.edit().putString(KEY_BOOKMARKS, JSONArray().apply {
        bookmarks.forEach { put(JSONObject().put("url", it.url).put("title", it.title)) }
    }.toString()).apply()

    private fun writeHistory() = prefs.edit().putString(KEY_HISTORY, JSONArray().apply {
        history.forEach { put(JSONObject().put("url", it.url).put("title", it.title).put("at", it.visitedAt)) }
    }.toString()).apply()

    private fun <T> readArray(key: String, decode: (JSONObject) -> T): List<T> = runCatching {
        val json = JSONArray(prefs.getString(key, null) ?: return emptyList())
        List(json.length()) { decode(json.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_TABS = "tabs"
        const val KEY_ACTIVE_TAB = "active_tab"
        const val KEY_BOOKMARKS = "bookmarks"
        const val KEY_HISTORY = "history"
        const val MAX_HISTORY = 300
    }
}
