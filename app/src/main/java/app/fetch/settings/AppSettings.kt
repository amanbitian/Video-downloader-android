package app.fetch.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class SettingsValues(
    val maxConcurrentDownloads: Int = 3,
    val wifiOnly: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Browser-level blocking of known ad and tracker requests. Ads are never offered as downloads either way. */
    val blockAds: Boolean = true,
    /** Sites (registrable domains) where the blocker is off because it breaks them. */
    val adAllowedSites: Set<String> = emptySet(),
)

/** User preferences shared by the UI and the download service (which may start without the activity). */
object AppSettings {
    private val state = MutableStateFlow(SettingsValues())
    val values: StateFlow<SettingsValues> = state.asStateFlow()
    @Volatile private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("fetch_settings", Context.MODE_PRIVATE)
        state.value = SettingsValues(
            maxConcurrentDownloads = p.getInt(KEY_MAX, 3).coerceIn(1, MAX_CONCURRENT),
            wifiOnly = p.getBoolean(KEY_WIFI, false),
            theme = runCatching { ThemeMode.valueOf(p.getString(KEY_THEME, null)!!) }.getOrDefault(ThemeMode.SYSTEM),
            blockAds = p.getBoolean(KEY_BLOCK_ADS, true),
            adAllowedSites = p.getStringSet(KEY_AD_SITES, emptySet()).orEmpty().toSet(),
        )
        prefs = p
    }

    fun update(transform: (SettingsValues) -> SettingsValues) {
        val next = state.updateAndGet(transform)
        prefs?.edit()?.putInt(KEY_MAX, next.maxConcurrentDownloads)?.putBoolean(KEY_WIFI, next.wifiOnly)?.putString(KEY_THEME, next.theme.name)
            ?.putBoolean(KEY_BLOCK_ADS, next.blockAds)?.putStringSet(KEY_AD_SITES, next.adAllowedSites)?.apply()
    }

    const val MAX_CONCURRENT = 5
    private const val KEY_MAX = "max_concurrent"
    private const val KEY_WIFI = "wifi_only"
    private const val KEY_THEME = "theme"
    private const val KEY_BLOCK_ADS = "block_ads"
    private const val KEY_AD_SITES = "ad_allowed_sites"
}
