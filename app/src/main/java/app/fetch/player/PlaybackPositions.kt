package app.fetch.player

import android.content.Context

/** Remembers where each video was left (MX-style resume). Bounded: the oldest entries are dropped past [MAX_ENTRIES]. */
class PlaybackPositions(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fetch_player_positions", Context.MODE_PRIVATE)

    /** Saved position for [key], already filtered by [PlayerMath.resumeFrom]. */
    fun resumePosition(key: String, durationMs: Long): Long {
        val saved = prefs.getString(key.storeKey(), null)?.substringBefore(':')?.toLongOrNull() ?: return 0L
        return PlayerMath.resumeFrom(saved, durationMs)
    }

    fun save(key: String, positionMs: Long, durationMs: Long) {
        // Finished (or barely started) videos start from the beginning next time.
        if (PlayerMath.resumeFrom(positionMs, durationMs) == 0L) { prefs.edit().remove(key.storeKey()).apply(); return }
        val editor = prefs.edit().putString(key.storeKey(), "$positionMs:${System.currentTimeMillis()}")
        val all = prefs.all
        if (all.size > MAX_ENTRIES) {
            all.entries.sortedBy { (it.value as? String)?.substringAfter(':')?.toLongOrNull() ?: 0L }
                .take(all.size - MAX_ENTRIES).forEach { editor.remove(it.key) }
        }
        editor.apply()
    }

    fun clear(key: String) = prefs.edit().remove(key.storeKey()).apply()

    private fun String.storeKey() = "p_" + hashCode().toString(16) + "_" + length

    private companion object {
        const val MAX_ENTRIES = 300
    }
}
