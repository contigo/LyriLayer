package com.chaoseye.ailyrickit.engine

import com.chaoseye.ailyrickit.quality.BoundedLanguagePolicy as L
import com.chaoseye.ailyrickit.quality.LyricQualityPolicy as P
import com.chaoseye.ailyrickit.quality.PrimaryOnlyPolicy
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.*

/** Input is mono 16 kHz PCM, normalized to [-1,1]. No UI, network or DB. */
interface InferenceBackend : Closeable {
    fun detect(pcm: FloatArray, threads: Int): L.Vote?
    /** Times are local to the PCM window, in milliseconds. */
    fun decode(pcm: FloatArray, language: String, threads: Int): List<P.Segment>
}

data class ExtractionResult(val language: String?, val lines: List<P.Segment>,
    val review: List<P.Segment>, val unresolvedWindows: List<LongRange>,
    val probeCount: Int, val decodedWindows: Int) {
    val lrc: String get() = lines.joinToString("\n") {
        val t = it.startMs
        String.format(Locale.ROOT, "[%02d:%02d.%02d]%s", t / 60000, (t / 1000) % 60, (t / 10) % 100, it.text)
    }
}

/** audio: milliseconds (total=0 unknown); language/decode: operation counts. */
data class ExtractionProgress(val phase: String, val completed: Int, val total: Int)

/** V16-inspired voting and raw-context policy. A backend is opened once,
 * reused across songs, and closed explicitly. Calls are serialized.
 * Cancellation is cooperative between backend calls, NOT native mid-call abort. */
class LyricEngine(private val backend: InferenceBackend) : Closeable {
    private var closed = false
    @Synchronized override fun close() {
        if (!closed) { closed = true; backend.close() }
    }
    @Synchronized fun extract(pcm: FloatArray, language: String = "auto", threads: Int = 4,
        cancelled: () -> Boolean = { false }, progress: (ExtractionProgress) -> Unit = {},
        preview: (String) -> Unit = {}): ExtractionResult {
        check(!closed)
        require(language in setOf("auto", "ko", "en", "ja"))
        require(threads in 1..8)
        require(pcm.isNotEmpty() && pcm.size <= 16000 * 60 * 12)
        require(pcm.all { it.isFinite() && it in -1f..1f })
        fun checkCancel() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Extraction cancelled") }
        checkCancel()
        val (energy, windows) = AudioWindows.plan(pcm)
        if (windows.isEmpty()) return ExtractionResult(null, emptyList(), emptyList(), emptyList(), 0, 0)
        var probes = 0
        val selected = if (language != "auto") language else {
            val candidates = windows.filter { it.activity >= .25 }.ifEmpty { windows }
            val count = minOf(7, candidates.size)
            val indices = (0 until count).map { Math.rint(it.toDouble() * (candidates.size - 1) / maxOf(1, count - 1)).toInt() }.distinct()
            val votes = mutableListOf<Pair<Int, L.Vote>>()
            for (i in L.probeOrder(indices.size)) {
                checkCancel()
                val k = indices[i]
                backend.detect(candidates[k].pcm(pcm), threads)?.let { vote ->
                    if (vote.probability == null || (vote.probability.isFinite() && vote.probability in 0.0..1.0)) votes.add(k to vote)
                }
                probes++
                checkCancel()
                progress(ExtractionProgress("language", probes, indices.size))
                if (L.lockedWinner(votes.map { it.second }, indices.size - probes) != null) break
            }
            val scores = linkedMapOf<String, Double>()
            for ((_, v) in votes.sortedBy { it.first }) scores[v.language] = (scores[v.language] ?: 0.0) + (v.probability ?: 1.0)
            scores.filterKeys { it in setOf("ko", "en", "ja") }.maxByOrNull { it.value }?.key
                ?: throw IllegalStateException("No supported language vote; select ko, en or ja manually")
        }
        checkCancel()
        val clean = mutableListOf<P.Segment>(); val review = mutableListOf<P.Segment>(); val gaps = mutableListOf<LongRange>()
        for ((i, w) in windows.withIndex()) {
            checkCancel()
            val raw = backend.decode(w.pcm(pcm), selected, threads)
            checkCancel()
            val partition = PrimaryOnlyPolicy.partition(raw, w.end-w.start, w.coreStart-w.start, w.coreEnd-w.start)
            fun absolute(s: P.Segment) = s.copy(startMs=s.startMs+w.start, endMs=s.endMs+w.start)
            fun valid(s: P.Segment) = '\uFFFD' !in s.text && P.normalize(s.text).isNotEmpty() && AudioWindows.activity(energy,s.startMs,s.endMs) >= .08
            val held = partition.review.map(::absolute).filter(::valid)
            val keep = partition.clean.map(::absolute).filter(::valid)
            review.addAll(held)
            val checked = P.quarantine(keep.sortedBy { it.startMs }, P.Source.ORIGINAL)
            review.addAll(checked.held.map { it.segment }); clean.addAll(checked.clean)
            if (checked.clean.isEmpty() || held.isNotEmpty() || checked.held.isNotEmpty()) gaps.add(w.coreStart until w.coreEnd)
            preview(ExtractionResult(selected, merge(clean), emptyList(), emptyList(), probes, i+1).lrc)
            progress(ExtractionProgress("decode", i+1, windows.size))
        }
        checkCancel()
        return ExtractionResult(selected, merge(clean), review.sortedBy { it.startMs }, gaps, probes, windows.size)
    }

    private fun merge(clean: List<P.Segment>): List<P.Segment> {
        // Never fabricate token timings; preserve later chorus repetitions.
        val merged = mutableListOf<P.Segment>()
        for (s in clean.sortedWith(compareBy<P.Segment>{it.startMs}.thenBy{it.endMs})) {
            val prior = merged.lastOrNull()
            if (prior != null && s.startMs < prior.endMs && P.normalize(prior.text)==P.normalize(s.text)) {
                merged[merged.lastIndex]=prior.copy(endMs=maxOf(prior.endMs,s.endMs)); continue
            }
            val a=maxOf(s.startMs,prior?.endMs ?: 0)
            if (a<s.endMs) merged.add(s.copy(startMs=a))
        }
        return merged
    }
}

internal object AudioWindows {
    data class Window(val start: Long, val end: Long, val coreStart: Long, val coreEnd: Long, val activity: Double) {
        fun pcm(samples: FloatArray) = samples.copyOfRange((start*16).toInt(), minOf(samples.size,(end*16).toInt()))
    }
    private const val FLOOR = -65.0
    fun activity(e: DoubleArray,a:Long,b:Long):Double {
        val lo=(a/50).toInt().coerceIn(0,e.size); val hi=((b+49)/50).toInt().coerceIn(lo,e.size)
        return if (hi==lo) 0.0 else (lo until hi).count{e[it]>=FLOOR}.toDouble()/(hi-lo)
    }
    fun plan(pcm: FloatArray): Pair<DoubleArray,List<Window>> {
        val duration=pcm.size.toLong()/16
        val e=DoubleArray((pcm.size+799)/800){i ->
            var ss=0.0; for(j in i*800 until minOf((i+1)*800,pcm.size)) ss+=pcm[j].toDouble()*pcm[j]
            20*log10(maxOf(sqrt(ss/800),1e-8))
        }
        val runs=mutableListOf<Pair<Int,Int>>(); var a=-1
        for(i in 0..e.size){
            val active=i<e.size && e[i]>=FLOOR
            if(active && a<0) a=i
            if(!active && a>=0){ if(i-a>=3){ val p=runs.lastOrNull(); if(p!=null && a-p.second<=16) runs[runs.lastIndex]=p.first to i else runs.add(a to i) }; a=-1 }
        }
        val spans=mutableListOf<Pair<Int,Int>>()
        for((x,y) in runs){ val lo=maxOf(0,x-7); val hi=minOf(e.size,y+7); val p=spans.lastOrNull(); if(p!=null&&lo<=p.second) spans[spans.lastIndex]=p.first to maxOf(p.second,hi) else spans.add(lo to hi) }
        val pieces=mutableListOf<Pair<Int,Int>>()
        for((x,y) in spans){ var lo=x; while(y-lo>500){
            val first=lo+200; val last=minOf(lo+500,y-200)
            val cut=(first..last).minByOrNull { k -> ((k-3..k+3).sumOf{e.getOrElse(it){0.0}}/7)+abs(k-(lo+400))*.025 } ?: first
            pieces.add(lo to cut); lo=cut
        }; if(y>lo) pieces.add(lo to y) }
        val merged=mutableListOf<Pair<Int,Int>>()
        for((x,y) in pieces){val p=merged.lastOrNull(); if(p!=null && x-p.second<=40 && y-p.first<=500 && (y-x<200||p.second-p.first<200)) merged[merged.lastIndex]=p.first to y else merged.add(x to y)}
        return e to merged.mapNotNull{(x,y)-> val ca=x*50L; val cb=minOf(duration,y*50L); if(cb<=ca)null else Window(maxOf(0,ca-1250),minOf(duration,cb+1250),ca,cb,activity(e,ca,cb)) }
    }
}
