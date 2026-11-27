package com.chaoseye.ailyrickit.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.util.concurrent.CancellationException

/** Blocking decoder. Invoke from a worker thread; never modifies or uploads source audio.
 * PCM output is accumulated to at most 12 minutes. Timings use contiguous decoded frames;
 * container discontinuities and gapless trim need device verification. */
class AndroidAudioDecoder(context: Context) {
    private val appContext = context.applicationContext
    fun decode(uri: Uri, cancelled: () -> Boolean = { false },
        progress: (decodedMs: Long, durationMs: Long?) -> Unit = { _, _ -> }): FloatArray {
        fun checkCancel() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Audio decode cancelled") }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            checkCancel()
            extractor.setDataSource(appContext, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track")
            val input = extractor.getTrackFormat(index)
            val mime = requireNotNull(input.getString(MediaFormat.KEY_MIME))
            val duration = if (input.containsKey(MediaFormat.KEY_DURATION)) input.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 }?.div(1000) else null
            require(duration == null || duration <= 720000L) { "Audio exceeds 12 minutes" }
            extractor.selectTrack(index)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(input, null, null, 0)
            decoder.start()
            var converter: Pcm16kConverter? = null
            var signature: Triple<Int, Int, Pcm16kConverter.Format>? = null
            fun configureOutput(format: MediaFormat): Pcm16kConverter {
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                val pcm = when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> Pcm16kConverter.Format.U8
                    AudioFormat.ENCODING_PCM_16BIT -> Pcm16kConverter.Format.S16
                    AudioFormat.ENCODING_PCM_FLOAT -> Pcm16kConverter.Format.F32
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> Pcm16kConverter.Format.S24
                    AudioFormat.ENCODING_PCM_32BIT -> Pcm16kConverter.Format.S32
                    else -> error("Unsupported PCM encoding: $encoding")
                }
                val sig = Triple(rate, channels, pcm)
                check(signature == null || signature == sig || converter?.size == 0) { "PCM format changed mid-stream" }
                if (signature != sig || converter == null) { converter = Pcm16kConverter(rate, channels, pcm); signature = sig }
                return checkNotNull(converter)
            }
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var lastActivity = System.nanoTime(); var lastProgress = -1000L
            while (!outputDone) {
                checkCancel()
                var advanced = false
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(10000)
                    if (i >= 0) {
                        val b = checkNotNull(decoder.getInputBuffer(i)); b.clear()
                        val n = extractor.readSampleData(b, 0)
                        if (n < 0) { decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(i, 0, n, extractor.sampleTime.coerceAtLeast(0), 0); extractor.advance() }
                        advanced = true
                    }
                }
                when (val i = decoder.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { configureOutput(decoder.outputFormat); advanced = true }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (i >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val b = checkNotNull(decoder.getOutputBuffer(i)).duplicate()
                                b.clear(); b.position(info.offset); b.limit(info.offset + info.size)
                                val c = converter ?: configureOutput(decoder.outputFormat)
                                c.append(b)
                                val ms = c.size.toLong() / 16
                                if (ms - lastProgress >= 250) { progress(ms, duration); lastProgress = ms }
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { decoder.releaseOutputBuffer(i, false) }
                        advanced = true
                    }
                }
                if (advanced) lastActivity = System.nanoTime()
                check(System.nanoTime() - lastActivity < 30_000_000_000L) { "Audio decoder stalled" }
            }
            checkCancel()
            val pcm = checkNotNull(converter) { "Decoder produced no PCM" }.finish()
            require(pcm.isNotEmpty()) { "Audio is empty" }
            progress(pcm.size.toLong() / 16, duration)
            return pcm
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            extractor.release()
        }
    }
}
