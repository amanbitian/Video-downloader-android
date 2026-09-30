package app.fetch.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.ByteBuffer

/**
 * Copies the first video and first audio track found in [inputs] into one MP4 (H.264/H.265 + AAC) or WebM (VP8/VP9 +
 * Opus/Vorbis) file. No re-encoding: samples are moved as-is, interleaved by timestamp.
 */
object MediaRemuxer {
    private class Input(val extractor: MediaExtractor, val trackToMuxer: MutableMap<Int, Int> = HashMap())

    /** Returns the MIME type of the written file. */
    fun remux(inputs: List<File>, output: File, isCancelled: () -> Boolean = { false }): String {
        val opened = inputs.map { file -> Input(MediaExtractor().apply { setDataSource(file.path) }) }
        var muxer: MediaMuxer? = null
        try {
            var video: Pair<Input, Int>? = null
            var audio: Pair<Input, Int>? = null
            for (input in opened) for (track in 0 until input.extractor.trackCount) {
                val mime = input.extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && video == null) video = input to track
                if (mime.startsWith("audio/") && audio == null) audio = input to track
            }
            val videoFormat = video?.let { (input, track) -> input.extractor.getTrackFormat(track) }
            val audioFormat = audio?.let { (input, track) -> input.extractor.getTrackFormat(track) }
            if (videoFormat == null && audioFormat == null) throw UnsupportedStreamException("No audio or video found to save.")
            val container = Codecs.container(familyOf(videoFormat), familyOf(audioFormat))
                ?: throw UnsupportedStreamException("These audio and video formats can't be combined.")

            output.delete()
            muxer = MediaMuxer(output.path, if (container == Codecs.Container.WEBM) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoFormat?.takeIf { it.containsKey(MediaFormat.KEY_ROTATION) }?.let { muxer.setOrientationHint(it.getInteger(MediaFormat.KEY_ROTATION)) }
            listOfNotNull(video, audio).forEach { (input, track) ->
                input.extractor.selectTrack(track)
                input.trackToMuxer[track] = muxer.addTrack(input.extractor.getTrackFormat(track))
            }
            muxer.start()

            val active = opened.filter { it.trackToMuxer.isNotEmpty() }.toMutableList()
            // Streams often start at a non-zero clock (HLS PTS); rebase so the file starts at 0.
            val base = active.mapNotNull { it.extractor.sampleTime.takeIf { t -> t >= 0 } }.minOrNull() ?: 0L
            var buffer = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (active.isNotEmpty()) {
                if (isCancelled()) throw CancellationException()
                // Interleave: always write the earliest pending sample across inputs.
                val input = active.minBy { it.extractor.sampleTime }
                val extractor = input.extractor
                val muxerTrack = input.trackToMuxer[extractor.sampleTrackIndex]
                if (extractor.sampleTime < 0 || muxerTrack == null) {
                    if (!extractor.advance() || extractor.sampleTime < 0) active.remove(input)
                    continue
                }
                val needed = extractor.sampleSize.toInt()
                if (needed > buffer.capacity()) buffer = ByteBuffer.allocate(needed)
                val size = extractor.readSampleData(buffer, 0)
                if (size >= 0) {
                    val keyFrame = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    val time = (extractor.sampleTime - base).coerceAtLeast(0)
                    val isAac = extractor.getTrackFormat(extractor.sampleTrackIndex).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
                    if (!(isAac && writeAdtsFrames(muxer, muxerTrack, buffer, size, time, info))) {
                        info.set(0, size, time, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(muxerTrack, buffer, info)
                    }
                }
                if (!extractor.advance()) active.remove(input)
            }
            muxer.stop()
            return when {
                videoFormat != null -> container.mime
                container == Codecs.Container.MP4 -> "audio/mp4"
                else -> "audio/webm"
            }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        } finally {
            runCatching { muxer?.release() }
            opened.forEach { runCatching { it.extractor.release() } }
        }
    }

    private val adtsSampleRates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /**
     * Android's MPEG-TS extractor hands out AAC as runs of ADTS frames, headers included. MP4 needs one raw frame per
     * sample, so split the run, drop each header and give every frame its own timestamp. Returns false if not ADTS.
     */
    private fun writeAdtsFrames(muxer: MediaMuxer, track: Int, buffer: ByteBuffer, size: Int, startUs: Long, info: MediaCodec.BufferInfo): Boolean {
        val bytes = ByteArray(size)
        buffer.position(0)
        buffer.get(bytes, 0, size)
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        if (size < 7 || at(0) != 0xFF || (at(1) and 0xF6) != 0xF0) return false
        val data = ByteBuffer.wrap(bytes)
        var offset = 0
        var time = startUs
        while (offset + 7 <= size && at(offset) == 0xFF && (at(offset + 1) and 0xF6) == 0xF0) {
            val header = if (at(offset + 1) and 0x01 == 1) 7 else 9
            val frameLength = ((at(offset + 3) and 0x03) shl 11) or (at(offset + 4) shl 3) or (at(offset + 5) ushr 5)
            val sampleRate = adtsSampleRates.getOrNull((at(offset + 2) ushr 2) and 0x0F) ?: break
            if (frameLength <= header || offset + frameLength > size) break
            info.set(offset + header, frameLength - header, time, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            muxer.writeSampleData(track, data, info)
            time += 1024L * 1_000_000L / sampleRate
            offset += frameLength
        }
        return true
    }

    private fun familyOf(format: MediaFormat?): String? = when (format?.getString(MediaFormat.KEY_MIME)) {
        null -> null
        MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
        MediaFormat.MIMETYPE_VIDEO_HEVC -> "H.265"
        MediaFormat.MIMETYPE_VIDEO_VP9 -> "VP9"
        MediaFormat.MIMETYPE_VIDEO_VP8 -> "VP8"
        MediaFormat.MIMETYPE_AUDIO_AAC -> "AAC"
        MediaFormat.MIMETYPE_AUDIO_OPUS -> "Opus"
        MediaFormat.MIMETYPE_AUDIO_VORBIS -> "Vorbis"
        else -> "other"
    }
}
