package com.chaoseye.ailyrickit.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Streaming interleaved PCM -> normalized mono 16 kHz. Rational area averaging
 * preserves frame counts across decoder buffers, with modest downsampling filtering.
 * Not a studio-quality resampler. Output contains only complete 16 kHz samples. */
internal class Pcm16kConverter(private val rate: Int, private val channels: Int,
    private val format: Format, private val maxSamples: Int = 16000 * 60 * 12) {
    enum class Format(val bytes: Int) { U8(1), S16(2), S24(3), S32(4), F32(4) }
    init { require(rate in 8000..384000 && channels in 1..32 && maxSamples > 0) }
    private var out = FloatArray(min(16000 * 10, maxSamples))
    private var count = 0
    private var remaining = rate
    private var sum = 0.0
    val size: Int get() = count
    fun append(buffer: ByteBuffer) {
        val b = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        require(b.remaining() % (format.bytes * channels) == 0) { "Incomplete PCM frame" }
        while (b.hasRemaining()) {
            var mono = 0.0
            repeat(channels) {
                val x = when (format) {
                    Format.U8 -> ((b.get().toInt() and 255) - 128) / 128.0
                    Format.S16 -> b.short / 32768.0
                    Format.S24 -> { val a = b.get().toInt() and 255; val c = b.get().toInt() and 255; val d = b.get().toInt(); (a or (c shl 8) or (d shl 16)) / 8388608.0 }
                    Format.S32 -> b.int / 2147483648.0
                    Format.F32 -> b.float.toDouble()
                }
                mono += if (x.isFinite()) x.coerceIn(-1.0, 1.0) else 0.0
            }
            mono /= channels
            var ticks = 16000
            while (ticks > 0) {
                val n = min(ticks, remaining)
                sum += mono * n; remaining -= n; ticks -= n
                if (remaining == 0) {
                    check(count < maxSamples) { "Audio exceeds 12 minute PCM limit" }
                    if (count == out.size) out = out.copyOf(min(maxSamples, out.size * 2))
                    out[count++] = (sum / rate).toFloat().coerceIn(-1f, 1f)
                    sum = 0.0; remaining = rate
                }
            }
        }
    }
    fun finish(): FloatArray = out.copyOf(count)
}
