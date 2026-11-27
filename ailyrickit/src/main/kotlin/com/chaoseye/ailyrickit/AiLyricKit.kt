package com.chaoseye.ailyrickit

import android.content.Context
import android.net.Uri
import com.chaoseye.ailyrickit.audio.AndroidAudioDecoder
import com.chaoseye.ailyrickit.engine.*
import com.chaoseye.ailyrickit.model.ModelVerifier
import java.io.Closeable
import java.io.File
import java.util.concurrent.CancellationException

/** Synchronous, caller-owned session. Create/use/close on a worker thread.
 * No automatic execution, playback listener, network, database or UI dependencies. */
class AiLyricKit private constructor(private val decoder: AndroidAudioDecoder, private val engine: LyricEngine) : Closeable {
    private var closed = false
    @Synchronized fun extract(uri: Uri, language: String = "auto", threads: Int = 4,
        cancelled: () -> Boolean = { false }, progress: (ExtractionProgress) -> Unit = {},
        preview: (String) -> Unit = {}): ExtractionResult {
        check(!closed)
        require(language in setOf("auto", "ko", "en", "ja") && threads in 1..8)
        val pcm = decoder.decode(uri, cancelled) { ms, total ->
            progress(ExtractionProgress("audio", ms.toInt(), total?.toInt() ?: 0))
        }
        return engine.extract(pcm, language, threads, cancelled, progress, preview)
    }
    @Synchronized override fun close() { if (!closed) { closed = true; engine.close() } }
    companion object {
        fun open(context: Context, model: File, trustedSha256: String = ModelVerifier.MODEL_SHA256, cancelled: () -> Boolean = { false }): AiLyricKit {
            ModelVerifier.verify(model, trustedSha256, cancelled)
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Open cancelled")
            val decoder = AndroidAudioDecoder(context)
            return AiLyricKit(decoder, LyricEngine(NativeWhisperBackend(model)))
        }
    }
}
