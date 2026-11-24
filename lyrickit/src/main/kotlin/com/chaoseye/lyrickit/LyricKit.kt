package com.chaoseye.lyrickit

import kotlinx.coroutines.*

data class TrackCandidate(val id: String, val song: SongInfo)
interface LyricsProvider {
    val source: Provider
    suspend fun search(song: SongInfo): List<TrackCandidate>
    suspend fun lyrics(candidate: TrackCandidate): LyricsResult?
}
sealed interface SearchOutcome {
    data class Found(val result: LyricsResult, val failedProviders: Set<Provider> = emptySet(), val failures: Map<Provider,SearchFailure> = emptyMap()) : SearchOutcome
    data object NotFound : SearchOutcome
    data class Unavailable(val failedProviders: Set<Provider>, val failures: Map<Provider,SearchFailure> = emptyMap()) : SearchOutcome
}
class LyricKit(private val providers: List<LyricsProvider>, private val timeoutMs: Long = 18000, private val minimumScore: Float = 90f) {
    init { require(timeoutMs > 0); require(minimumScore in 0f..100f); require(providers.map { it.source }.distinct().size == providers.size) }
    val availableProviders: Set<Provider> get() = providers.map { it.source }.toSet()
    suspend fun search(song: SongInfo, sources: Set<Provider> = availableProviders): LyricsResult? =
        (searchDetailed(song, sources) as? SearchOutcome.Found)?.result
    suspend fun searchDetailed(song: SongInfo, sources: Set<Provider> = availableProviders): SearchOutcome = supervisorScope {
        require(song.title.isNotBlank())
        val normalized = SongNormalizer.normalize(song)
        if(normalized.artist.isBlank()) return@supervisorScope SearchOutcome.NotFound
        val selected = providers.filter { it.source in sources }
        val reports = selected.map { provider -> async {
            val collected = mutableListOf<LyricsResult>()
            var failure: SearchFailure? = null
            try {
                withTimeoutOrNull(timeoutMs) {
                    fun score(candidate: TrackCandidate): Float {
                        val direct = LyricsMatcher.score(normalized,candidate.song)
                        val a=normalized.durationMs
                        val b=candidate.song.durationMs
                        val inferred = if (direct == 0f && SongNormalizer.truncatedCreditMatches(song,candidate.song)
                            && a != null && b != null && a > 0 && b > 0 && kotlin.math.abs(a-b) <= 2000L)
                            LyricsMatcher.score(normalized.copy(artist=candidate.song.artist),candidate.song).coerceAtMost(90f) else 0f
                        return maxOf(direct,inferred)
                    }
                    val candidates = mutableListOf<TrackCandidate>()
                    val variants = SongNormalizer.searchVariants(song)
                    for (variant in variants) {
                        candidates += provider.search(variant)
                        // Truncated credits require the broader variant to detect ambiguous artists.
                        if (variants.size == 1 || candidates.any { LyricsMatcher.score(normalized,it.song) >= minimumScore }) break
                    }
                    val ranked = candidates.distinctBy { it.id }.map { it to score(it) }.filter { it.second >= minimumScore }
                    val inferredArtists=ranked.filter { LyricsMatcher.score(normalized,it.first.song) == 0f }
                        .map { it.first.song.artist.trim().lowercase(java.util.Locale.ROOT) }.distinct()
                    val matches = ranked.filter { inferredArtists.size <= 1 || LyricsMatcher.score(normalized,it.first.song) >= minimumScore }
                        .sortedByDescending { it.second }.take(3)
                    for ((candidate, score) in matches) {
                        try {
                            provider.lyrics(candidate)?.takeIf { it.syncedLines.isNotEmpty() || !it.plainLyrics.isNullOrBlank() }
                                ?.copy(song=candidate.song, provider=provider.source, matchScore=score)?.let { collected.add(it) }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = SearchFailures.classify(e) }
                        if (collected.any { it.matchScore >= score && it.syncedLines.isNotEmpty() }) break
                    }
                    true
                } ?: run { if(failure == null) failure = SearchFailure("제공처 검색 시간 초과",true) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = SearchFailures.classify(e) }
            Report(provider.source, collected.maxWithOrNull(order), failure)
        } }.awaitAll()
        val failures = reports.mapNotNull { report -> report.failure?.let { report.source to it } }.toMap() + (sources - availableProviders).associateWith { SearchFailure("제공처 미설정",false) }
        val failed = failures.keys
        val best = reports.mapNotNull { it.result }.maxWithOrNull(order)
        when {
            best != null -> SearchOutcome.Found(best, failed, failures)
            failed.isNotEmpty() -> SearchOutcome.Unavailable(failed, failures)
            else -> SearchOutcome.NotFound
        }
    }
    private data class Report(val source: Provider, val result: LyricsResult?, val failure: SearchFailure?)
    private companion object {
        val order = compareBy<LyricsResult> { it.matchScore }.thenBy { it.syncedLines.isNotEmpty() }.thenBy { -it.provider.ordinal }
    }
}
