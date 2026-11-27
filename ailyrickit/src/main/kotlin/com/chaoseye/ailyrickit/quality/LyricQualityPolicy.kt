package com.chaoseye.ailyrickit.quality

import java.text.Normalizer
import java.util.Locale

/** Pure quality policy; no dependency on app UI, Room, JNI or network.
 * The host provides timestamped candidates and clean-segment assessment scores.
 * Energy activity is not a vocal-presence probability. Held lines require review.
 */
object LyricQualityPolicy {
    const val VERSION = 10
    const val MAX_LANGUAGE_RETRY_CHUNKS = 4
    const val MAX_SHORT_RETRY_CHUNKS = 6
    enum class Action { KEEP, REVIEW, REJECT }
    enum class Source { ORIGINAL, VOCAL }
    data class Segment(val text: String, val startMs: Long, val endMs: Long,
                       val confidence: Double? = null, val activity: Double? = null) {
        init { require(startMs >= 0 && endMs > startMs) }
    }
    data class Held(val segment: Segment, val action: Action,
                    val reason: String = "possible promotional artifact")
    data class Partition(val clean: List<Segment>, val held: List<Held>)
    data class Candidate(val cleanScore: Double, val segments: List<Segment>,
                         val held: List<Held> = emptyList(), val flags: List<String> = emptyList()) {
        init { require(cleanScore.isFinite()) }
    }
    data class LanguagePlan(val primary: String, val ambiguous: Boolean,
                            val alternative: String?, val scores: Map<String, Double>)
    private val promo = Regex("구독(?:과|및)?좋아요|좋아요(?:와|및)?구독|시청해?주셔서감사|thank(?:you|s)forwatching|please(?:likeand)?subscribe|likeand(?:please)?subscribe|subscribeto(?:my|our|the)channel|ご視聴(?:ありがとう|いただき|頂き)|チャンネル登録|ご覧いただきありがとう|字幕(?:制作|作成|提供)|자막(?:제공|제작)|subtitlesby|captionsby")
    fun normalize(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return buildString { normalized.codePoints().forEach { cp -> if (Character.isLetterOrDigit(cp)) appendCodePoint(cp) } }
    }
    fun promotional(text: String) = promo.containsMatchIn(normalize(text))
    fun action(segment: Segment, source: Source): Action {
        if (!promotional(segment.text)) return Action.KEEP
        val activity = segment.activity
        val confidence = segment.confidence
        return if ((activity != null && activity < .20) || (confidence != null && confidence < .45) ||
            (source == Source.VOCAL && activity != null && activity < .35)) Action.REJECT else Action.REVIEW
    }
    fun inspect(segments: List<Segment>, source: Source): Map<Int, Action> {
        require(segments.zipWithNext().all { (a,b) -> a.startMs <= b.startMs })
        val actions = sortedMapOf<Int, Action>()
        segments.forEachIndexed { i,s -> action(s,source).let { if (it != Action.KEEP) actions[i] = it } }
        for (i in segments.indices) {
            var joined = segments[i].text
            var standalone = promotional(joined)
            for (j in i+1 until minOf(i+3, segments.size)) {
                if (segments[j].startMs - segments[j-1].endMs > 700) break
                joined += " " + segments[j].text
                standalone = standalone || promotional(segments[j].text)
                if (!standalone && promotional(joined)) for (k in i..j) actions.putIfAbsent(k, Action.REVIEW)
            }
        }
        return actions
    }
    fun quarantine(segments: List<Segment>, source: Source): Partition {
        val findings = inspect(segments, source)
        return Partition(segments.filterIndexed { i,_ -> i !in findings },
            segments.mapIndexedNotNull { i,s -> findings[i]?.let { Held(s,it) } })
    }
    fun rank(candidate: Candidate): Double = candidate.cleanScore - minOf(3.0, .75 * candidate.held.size)
    /** Returns null instead of emitting only quarantined lyrics. Preserve candidates for review. */
    fun select(candidates: List<Candidate>): Candidate? = candidates.filter { it.segments.isNotEmpty() }
        .maxByOrNull(::rank)
    fun retryTargetMs(flags: List<String>, heldCount: Int): Long = when {
        heldCount > 0 || flags.any { "artifact" in it || "repetition" in it } -> 5000
        flags.any { "mixed language" in it || "script" in it || "long lyric" in it } -> 10000
        else -> 15000
    }
    fun retryPriority(candidate: Candidate?): Int {
        if (candidate == null) return 100
        return (if (candidate.held.isNotEmpty()) 50 else 0) +
            (if ("mixed language or unexpected script" in candidate.flags) 20 else 0) +
            (if (candidate.segments.isEmpty()) 10 else 0) + candidate.flags.size
    }
    fun languagePlan(scores: Map<String, Double>, primary: String): LanguagePlan {
        val sorted = scores.entries.filter { it.key in setOf("ko", "en", "ja") && it.value.isFinite() && it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String,Double>> { it.value }.thenBy { it.key })
        val ambiguous = sorted.size > 1 && sorted[1].value >= .70 * sorted[0].value
        return LanguagePlan(primary, ambiguous, if (ambiguous) sorted.firstOrNull { it.key != primary }?.key else null,
            sorted.associate { it.key to it.value })
    }
}
