package app.fetch.download

/** Pure naming and MIME rules, kept free of Android APIs so they are unit-testable. */
object MediaFiles {
    private val mimeByExtension = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska",
        "mov" to "video/quicktime", "avi" to "video/x-msvideo", "wmv" to "video/x-ms-wmv", "3gp" to "video/3gpp",
        "flv" to "video/x-flv", "ts" to "video/mp2t", "ogv" to "video/ogg",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg", "opus" to "audio/opus",
        "wav" to "audio/wav", "flac" to "audio/flac",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "pdf" to "application/pdf", "txt" to "text/plain", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "zip" to "application/zip", "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
        "apk" to "application/vnd.android.package-archive", "epub" to "application/epub+zip",
    )
    private val extensionByMime = mimeByExtension.entries.reversed().associate { (ext, mime) -> mime to ext } + mapOf(
        "video/mp4" to "mp4", "image/jpeg" to "jpg", "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a", "audio/webm" to "weba", "video/mp2t" to "ts",
    )
    /** Playlist/manifest extensions that must never survive into a saved file name. */
    private val streamExtensions = setOf("m3u8", "m3u", "mpd")
    private val genericMimes = setOf("", "application/octet-stream", "binary/octet-stream", "application/force-download", "application/download", "application/unknown")

    fun extensionOf(url: String): String =
        url.substringBefore('#').substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** Picks the first specific MIME type from [declared] (e.g. candidate hint, then server Content-Type), else infers from the URL. */
    fun resolveMime(streamType: StreamType, url: String, vararg declared: String?): String {
        if (streamType == StreamType.HLS) return "video/mp2t"
        val specific = declared.asSequence().map { it.orEmpty().substringBefore(';').trim().lowercase() }
            .firstOrNull { it !in genericMimes && '/' in it && ',' !in it && "mpegurl" !in it }
        return specific ?: mimeByExtension[extensionOf(url)] ?: "application/octet-stream"
    }

    /** Null for types we don't know; MediaStore then appends the right extension itself rather than a wrong guess. */
    fun extensionFor(mime: String): String? = extensionByMime[mime]

    fun kindOf(mime: String): MediaKind = when {
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        mime.startsWith("image/") -> MediaKind.IMAGE
        else -> MediaKind.OTHER
    }

    /** "index.m3u8" + video/mp2t → "index.ts"; "Mr. Bean" + video/mp4 → "Mr. Bean.mp4". */
    fun displayName(title: String, mime: String): String {
        val trimmed = title.trim().ifBlank { "download" }
        val existing = trimmed.substringAfterLast('.', "").lowercase()
        // Unknown type: keep the name as the server gave it (e.g. "archive.xyz") unless it's a manifest name.
        val ext = extensionFor(mime) ?: return if (existing in streamExtensions) trimmed.substringBeforeLast('.') else trimmed
        val base = if (existing in mimeByExtension || existing in streamExtensions) trimmed.substringBeforeLast('.') else trimmed
        return "${base.ifBlank { "download" }}.$ext"
    }

    fun safeTitle(value: String): String {
        val cleaned = if (value.contains("m2-res_") || value.startsWith("m2-")) "Video" else value
        return cleaned.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(90).ifBlank { "download" }
    }

    /** Total size from "bytes 0-0/12345"; null for "*" or malformed values. */
    fun contentRangeTotal(header: String?): Long? = header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
}
