package app.fetch.browser

/**
 * Turns "Amazing Trip - YouTube" into "Amazing Trip". Conservative on purpose: a trailing segment is removed only when
 * it names the site itself (og:site_name or the domain's brand), so titles like "Spider-Man - Official Trailer" survive.
 */
object TitleNormalizer {
    private val separators = listOf(" | ", " - ", " – ", " — ", " : ", " · ", " • ", " / ")
    private val unreadCount = Regex("""^\(\d+\+?\)\s+""")
    private val secondLevel = setOf("co", "com", "org", "net", "ac", "gov", "edu", "or", "ne")

    fun clean(title: String, siteName: String? = null, host: String? = null): String {
        val original = title.trim().replace(Regex("\\s+"), " ")
        val names = listOfNotNull(siteName, host?.let(::brandOf)).map(::key).filter { it.length >= 2 }.toSet()
        var result = original.replace(unreadCount, "")
        if (names.isNotEmpty()) {
            var changed = true
            while (changed) {
                changed = false
                for (separator in separators) {
                    val index = result.lastIndexOf(separator)
                    if (index <= 0) continue
                    val tail = key(result.substring(index + separator.length))
                    if (tail in names || names.any { tail == "${it}com" || tail == "${it}tv" }) {
                        result = result.substring(0, index).trim()
                        changed = true
                        break
                    }
                }
            }
        }
        return result.ifBlank { original }
    }

    /** "m.youtube.com" → "youtube", "news.bbc.co.uk" → "bbc". */
    fun brandOf(host: String): String? {
        val labels = host.lowercase().removePrefix("www.").split('.').filter(String::isNotEmpty)
        if (labels.size < 2) return labels.firstOrNull()
        return if (labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in secondLevel) labels[labels.size - 3]
        else labels[labels.size - 2]
    }

    private fun key(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
}
