package com.chaoseye.lyrickit

import kotlinx.coroutines.*

data class TrackCandidate(val id: String, val song: SongInfo)
interface LyricsProvider {
    val source: Provider
    suspend fun search(song: SongInfo): List<TrackCandidate>
    suspend fun lyrics(candidate: TrackCandidate): LyricsResult?
}
sealed interface SearchOutcome {
    data class Found(val result: LyricsResult, val failedProviders: Set<Provider> = emptySet()) : SearchOutcome
    data object NotFound : SearchOutcome
    data class Unavailable(val failedProviders: Set<Provider>) : SearchOutcome
}
class LyricKit(private val providers: List<LyricsProvider>, private val timeoutMs: Long = 12000, private val minimumScore: Float = 90f) {
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
            var failed = false
            try {
                withTimeoutOrNull(timeoutMs) {
                    val variants = SongNormalizer.searchVariants(song)
                    val candidates = mutableListOf<TrackCandidate>()
                    for (variant in variants) {
                        candidates += provider.search(variant)
                        if (candidates.isNotEmpty()) break
                    }
                    val matches = candidates.distinctBy { it.id }.map { candidate ->
                        val direct = LyricsMatcher.score(normalized,candidate.song)
                        val inferred = if (direct == 0f && SongNormalizer.truncatedCreditMatches(song,candidate.song)
                            && normalized.durationMs != null && candidate.song.durationMs != null)
                            LyricsMatcher.score(normalized.copy(artist=candidate.song.artist),candidate.song).coerceAtMost(90f) else 0f
                        candidate to maxOf(direct,inferred)
                    }
                        .filter { it.second >= minimumScore }.sortedByDescending { it.second }.take(3)
                    for ((candidate, score) in matches) {
                        try {
                            provider.lyrics(candidate)?.takeIf { it.syncedLines.isNotEmpty() || !it.plainLyrics.isNullOrBlank() }
                                ?.copy(song=candidate.song, provider=provider.source, matchScore=score)?.let { collected.add(it) }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true }
                        if (collected.any { it.matchScore >= score && it.syncedLines.isNotEmpty() }) break
                    }
                    true
                } ?: run { failed = true }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true }
            Report(provider.source, collected.maxWithOrNull(order), failed)
        } }.awaitAll()
        val failed = reports.filter { it.failed }.map { it.source }.toSet() + (sources - availableProviders)
        val best = reports.mapNotNull { it.result }.maxWithOrNull(order)
        when {
            best != null -> SearchOutcome.Found(best, failed)
            failed.isNotEmpty() -> SearchOutcome.Unavailable(failed)
            else -> SearchOutcome.NotFound
        }
    }
    private data class Report(val source: Provider, val result: LyricsResult?, val failed: Boolean)
    private companion object {
        val order = compareBy<LyricsResult> { it.matchScore }.thenBy { it.syncedLines.isNotEmpty() }.thenBy { -it.provider.ordinal }
    }
}
