package app.fetch.download

import app.fetch.detection.AdMediaClassifier
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil

/** A parsed MPEG-DASH manifest (first period only), reduced to what is needed to fetch each representation. */
data class DashManifest(val durationSeconds: Double?, val isLive: Boolean, val periodCount: Int, val representations: List<Representation>) {
    enum class Kind { VIDEO, AUDIO, TEXT, OTHER }

    data class Representation(
        val id: String,
        val kind: Kind,
        val mimeType: String?,
        val codecs: String?,
        val bandwidth: Long?,
        val width: Int?,
        val height: Int?,
        val language: String?,
        val roles: Set<String>,
        val label: String?,
        val isProtected: Boolean,
        /** Initialization segment; null for self-contained single files. */
        val initUrl: String?,
        /** Media segments in order; a single entry with no [initUrl] is one complete file. */
        val segmentUrls: List<String>,
    )

    fun representation(id: String?): Representation? = representations.firstOrNull { it.id == id }

    companion object {
        private val templateToken = Regex("""\$(RepresentationID|Number|Bandwidth|Time)(?:%0(\d+)d)?\$""")
        private val isoDuration = Regex("""^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""")
        /** Refuse absurd templates rather than allocating millions of URLs. */
        private const val MAX_SEGMENTS = 100_000

        fun parse(manifestUrl: String, xml: String): DashManifest {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // No DTDs or external entities from untrusted manifests.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { isExpandEntityReferences = false }
            }
            val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())).documentElement
            if (root.name() != "MPD") throw UnsupportedStreamException("This is not a DASH manifest.")
            val isLive = root.getAttribute("type").equals("dynamic", ignoreCase = true)
            val mpdBase = resolve(manifestUrl, root.child("BaseURL")?.textContent)
            val periods = root.children("Period")
            val period = periods.firstOrNull() ?: return DashManifest(null, isLive, 0, emptyList())
            val duration = parseDuration(period.getAttribute("duration")) ?: parseDuration(root.getAttribute("mediaPresentationDuration"))
            val periodBase = resolve(mpdBase, period.child("BaseURL")?.textContent)

            val representations = period.children("AdaptationSet").flatMapIndexed { setIndex, set ->
                val setBase = resolve(periodBase, set.child("BaseURL")?.textContent)
                val setProtected = set.child("ContentProtection") != null
                val roles = set.children("Role").mapNotNull { it.getAttribute("value").ifBlank { null }?.lowercase() }.toSet()
                val label = set.child("Label")?.textContent?.trim()?.ifBlank { null }
                set.children("Representation").mapIndexed { repIndex, rep ->
                    val mime = rep.attr("mimeType") ?: set.attr("mimeType")
                    val codecs = rep.attr("codecs") ?: set.attr("codecs")
                    val id = rep.attr("id") ?: "$setIndex-$repIndex"
                    val bandwidth = rep.attr("bandwidth")?.toLongOrNull()
                    val repBase = resolve(setBase, rep.child("BaseURL")?.textContent)
                    val (initUrl, segments) = segmentsOf(rep, set, id, bandwidth, repBase, duration)
                    Representation(
                        id = id,
                        kind = kindOf(set.attr("contentType"), mime, codecs),
                        mimeType = mime,
                        codecs = codecs,
                        bandwidth = bandwidth,
                        width = (rep.attr("width") ?: set.attr("width"))?.toIntOrNull(),
                        height = (rep.attr("height") ?: set.attr("height"))?.toIntOrNull(),
                        language = rep.attr("lang") ?: set.attr("lang"),
                        roles = roles,
                        label = label,
                        isProtected = setProtected || rep.child("ContentProtection") != null,
                        initUrl = initUrl,
                        segmentUrls = segments,
                    )
                }
            }
            return DashManifest(duration, isLive, periods.size, representations)
        }

        private fun segmentsOf(rep: Element, set: Element, id: String, bandwidth: Long?, base: String, periodSeconds: Double?): Pair<String?, List<String>> {
            val template = rep.child("SegmentTemplate") ?: set.child("SegmentTemplate")
            if (template != null) {
                val setTemplate = set.child("SegmentTemplate")
                fun attr(name: String) = template.attr(name) ?: setTemplate?.attr(name)
                val media = attr("media") ?: return null to listOf(base)
                val timescale = attr("timescale")?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
                val startNumber = attr("startNumber")?.toLongOrNull() ?: 1L
                val init = attr("initialization")?.let { resolve(base, fill(it, id, bandwidth, null, null)) }
                val timeline = (template.child("SegmentTimeline") ?: setTemplate?.child("SegmentTimeline"))
                val urls = ArrayList<String>()
                if (timeline != null) {
                    var time = 0L
                    var number = startNumber
                    val entries = timeline.children("S")
                    entries.forEachIndexed { index, s ->
                        s.attr("t")?.toLongOrNull()?.let { time = it }
                        val d = s.attr("d")?.toLongOrNull() ?: throw UnsupportedStreamException("Malformed DASH segment timeline.")
                        var repeat = s.attr("r")?.toLongOrNull() ?: 0L
                        if (repeat < 0) {
                            // r = -1: repeat until the next S@t or the end of the period.
                            val end = entries.getOrNull(index + 1)?.attr("t")?.toLongOrNull() ?: ((periodSeconds ?: 0.0) * timescale).toLong()
                            repeat = ((end - time + d - 1) / d - 1).coerceAtLeast(0)
                        }
                        for (i in 0..repeat) {
                            urls += resolve(base, fill(media, id, bandwidth, number, time))
                            time += d; number++
                            if (urls.size > MAX_SEGMENTS) throw UnsupportedStreamException("This stream has too many segments.")
                        }
                    }
                } else {
                    val duration = attr("duration")?.toLongOrNull()?.takeIf { it > 0 }
                        ?: throw UnsupportedStreamException("This DASH stream has no segment duration.")
                    val seconds = periodSeconds ?: throw UnsupportedStreamException("This DASH stream has no known length.")
                    val count = ceil(seconds * timescale / duration).toLong()
                    if (count > MAX_SEGMENTS) throw UnsupportedStreamException("This stream has too many segments.")
                    for (i in 0 until count) urls += resolve(base, fill(media, id, bandwidth, startNumber + i, i * duration))
                }
                return init to urls
            }
            val list = rep.child("SegmentList") ?: set.child("SegmentList")
            if (list != null) {
                val media = list.children("SegmentURL").mapNotNull { it.attr("media") }
                // Byte-range-only lists address one file; fetching that file whole is equivalent.
                if (media.isNotEmpty()) {
                    return list.child("Initialization")?.attr("sourceURL")?.let { resolve(base, it) } to media.map { resolve(base, it) }
                }
            }
            return null to listOf(base)
        }

        private fun fill(template: String, id: String, bandwidth: Long?, number: Long?, time: Long?): String =
            templateToken.replace(template) { match ->
                val value = when (match.groupValues[1]) {
                    "RepresentationID" -> return@replace id
                    "Bandwidth" -> bandwidth ?: 0L
                    "Number" -> number ?: 0L
                    else -> time ?: 0L
                }
                val width = match.groupValues[2].toIntOrNull()
                if (width != null) value.toString().padStart(width, '0') else value.toString()
            }.replace("$$", "$")

        private fun kindOf(contentType: String?, mime: String?, codecs: String?): Kind {
            val type = contentType ?: mime?.substringBefore('/')
            return when {
                codecs != null && listOf("stpp", "wvtt", "ttml").any { codecs.startsWith(it) } -> Kind.TEXT
                type == "video" -> Kind.VIDEO
                type == "audio" -> Kind.AUDIO
                type == "text" || mime == "application/ttml+xml" -> Kind.TEXT
                Codecs.video(codecs) != null -> Kind.VIDEO
                Codecs.audio(codecs) != null -> Kind.AUDIO
                else -> Kind.OTHER
            }
        }

        fun parseDuration(value: String?): Double? {
            val match = value?.trim()?.let(isoDuration::matchEntire) ?: return null
            val (days, hours, minutes, seconds) = match.destructured
            return (days.toDoubleOrNull() ?: 0.0) * 86_400 + (hours.toDoubleOrNull() ?: 0.0) * 3_600 +
                (minutes.toDoubleOrNull() ?: 0.0) * 60 + (seconds.toDoubleOrNull() ?: 0.0)
        }

        private fun resolve(base: String, relative: String?): String =
            relative?.trim()?.ifEmpty { null }?.let { URL(URL(base), it).toString() } ?: base

        private fun Element.name() = localName ?: tagName.substringAfter(':')
        private fun Element.attr(name: String) = getAttribute(name).ifBlank { null }
        private fun Element.children(name: String): List<Element> =
            (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.name() == name }
        private fun Element.child(name: String) = children(name).firstOrNull()
    }
}

/** Turns a DASH manifest into user-facing qualities: one per resolution, each paired with the audio it will be merged with. */
object DashResolver {
    suspend fun resolve(candidate: MediaCandidate): ResolvedStream {
        val xml = HlsResolver.fetchText(candidate.url, candidate.requestHeaders)
        if (AdMediaClassifier.isVastDocument(xml)) return ResolvedStream(emptyList(), "Advertisement", isAd = true)
        return build(candidate.url, DashManifest.parse(candidate.url, xml))
    }

    fun build(manifestUrl: String, manifest: DashManifest): ResolvedStream {
        val claims = manifest.representations.flatMap { rep -> rep.segmentUrls + listOfNotNull(rep.initUrl) }.map { it.substringBefore('?') }.toSet()
        if (manifest.isLive) return ResolvedStream(emptyList(), "Live streams can't be downloaded.", claims)
        val media = manifest.representations.filter { it.kind == DashManifest.Kind.VIDEO || it.kind == DashManifest.Kind.AUDIO }
        val playable = media.filterNot { it.isProtected }
        if (media.isNotEmpty() && playable.isEmpty()) {
            return ResolvedStream(emptyList(), "This video is DRM-protected and can't be downloaded.", claims)
        }
        val videos = playable.filter { it.kind == DashManifest.Kind.VIDEO && Codecs.video(it.codecs) != "AV1" }
        val audios = playable.filter { it.kind == DashManifest.Kind.AUDIO }.map { rep ->
            AudioOption(
                key = rep.id, codecFamily = Codecs.audio(rep.codecs), language = rep.language, bitrate = rep.bandwidth,
                isDefault = "main" in rep.roles, label = rep.label,
                isAccessory = rep.roles.any { it == "commentary" || it == "description" },
            )
        }
        val languages = audios.mapNotNull { it.language }.toSet()
        val duration = manifest.durationSeconds

        val variants = if (videos.isEmpty()) {
            audios.sortedByDescending { it.bitrate ?: 0 }.distinctBy { it.language to it.codecFamily }.map { audio ->
                val rep = manifest.representation(audio.key)!!
                MediaVariant(
                    url = manifestUrl, streamType = StreamType.DASH, audioKey = audio.key,
                    label = "Audio" + (audio.bitrate?.let { " ${it / 1000} kbps" } ?: ""),
                    detail = listOfNotNull(audio.codecFamily, audio.language, estimate(rep.bandwidth, null, duration)?.asEstimatedSize()).joinToString(" · "),
                    estimatedBytes = estimate(rep.bandwidth, null, duration), bitrate = rep.bandwidth,
                )
            }
        } else {
            videos.mapNotNull { video ->
                val videoFamily = Codecs.video(video.codecs)
                val audio = AudioPolicy.choose(audios, videoFamily)
                // Offering a silent file because no audio could be combined with it would look like a bug.
                if (audios.isNotEmpty() && audio == null) return@mapNotNull null
                val audioRep = audio?.let { manifest.representation(it.key) }
                val bytes = estimate(video.bandwidth, audioRep?.bandwidth, duration)
                val codecs = listOfNotNull(videoFamily, audio?.codecFamily).joinToString(" + ").ifBlank { null }
                val languageNote = audio?.language?.takeIf { languages.size > 1 }?.let { "${it.uppercase()} audio" }
                MediaVariant(
                    url = manifestUrl, streamType = StreamType.DASH, videoKey = video.id, audioKey = audio?.key,
                    label = video.height?.let { "${it}p" } ?: video.bandwidth?.let { "${it / 1000} kbps" } ?: "Video",
                    detail = listOfNotNull(
                        if (video.width != null && video.height != null) "${video.width}x${video.height}" else null, codecs,
                        (video.bandwidth ?: 0).let { if (it > 0) "%.1f Mbps".format(it / 1_000_000.0) else null },
                        languageNote, bytes?.asEstimatedSize(), "no audio".takeIf { audio == null },
                    ).joinToString(" · "),
                    estimatedBytes = bytes, bitrate = video.bandwidth, height = video.height,
                )
            }
                // One entry per resolution: the most compatible codec, then the higher bitrate.
                .groupBy { it.height ?: it.bitrate?.toInt() }
                .map { (_, same) -> same.sortedWith(compareBy<MediaVariant> { Codecs.videoPreference(Codecs.video(manifest.representation(it.videoKey)?.codecs)) }.thenByDescending { it.bitrate ?: 0 }).first() }
                .sortedWith(compareByDescending<MediaVariant> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 })
        }
        val note = when {
            variants.isNotEmpty() -> null
            videos.isEmpty() && audios.isEmpty() -> "This manifest has no downloadable audio or video."
            else -> "The audio and video in this stream use codecs that can't be combined into one file."
        }
        return ResolvedStream(variants, note, claims, durationSeconds = duration)
    }

    private fun estimate(videoBandwidth: Long?, audioBandwidth: Long?, seconds: Double?): Long? {
        if (seconds == null || seconds <= 0 || videoBandwidth == null) return null
        return (((videoBandwidth + (audioBandwidth ?: 0)) / 8.0) * seconds).toLong()
    }
}
