package com.chaoseye.ailyrickit.engine

import com.chaoseye.ailyrickit.quality.BoundedLanguagePolicy as L
import com.chaoseye.ailyrickit.quality.LyricQualityPolicy as P
import java.io.File

/** Owns one CPU whisper.cpp context. The caller must close it. */
class NativeWhisperBackend(model: File) : InferenceBackend {
    private var handle: Long
    init {
        require(model.isFile && model.canRead()) { "Model file not found" }
        handle = nativeOpen(model.absolutePath)
        check(handle != 0L) { "Whisper model open failed" }
    }
    @Synchronized override fun detect(pcm: FloatArray, threads: Int): L.Vote? {
        check(handle != 0L)
        val values = nativeDetect(handle, pcm, threads)?.toString(Charsets.UTF_8)?.split('\t') ?: return null
        return L.Vote(values[0],values.getOrNull(1)?.toDoubleOrNull())
    }
    @Synchronized override fun decode(pcm: FloatArray, language: String, threads: Int): List<P.Segment> {
        check(handle != 0L)
        val bytes=nativeDecode(handle,pcm,threads,language) ?: error("Whisper inference failed")
        return bytes.toString(Charsets.UTF_8).lineSequence().filter{it.isNotBlank()}.mapNotNull{line ->
            val f=line.split('\t',limit=4); if(f.size!=4)return@mapNotNull null
            val a=f[0].toLongOrNull() ?: return@mapNotNull null; val b=f[1].toLongOrNull() ?: return@mapNotNull null
            if(a<0||b<=a)return@mapNotNull null
            P.Segment(f[3],a,b,f[2].toDoubleOrNull()?.takeIf{it.isFinite() && it in 0.0..1.0})
        }.toList()
    }
    @Synchronized override fun close() { if(handle!=0L){ val h=handle; handle=0; nativeClose(h) } }
    private external fun nativeOpen(path:String):Long
    private external fun nativeDetect(handle:Long,pcm:FloatArray,threads:Int):ByteArray?
    private external fun nativeDecode(handle:Long,pcm:FloatArray,threads:Int,language:String):ByteArray?
    private external fun nativeClose(handle:Long)
    companion object { init { System.loadLibrary("ailyrickit_whisper") } }
}
