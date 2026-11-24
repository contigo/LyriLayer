package com.chaoseye.lyrickit
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assume.assumeTrue

class LiveSearchTest {
    @Test fun cyberpunk() = runBlocking {
        assumeTrue(System.getenv("LYRICKIT_LIVE_TEST") == "1")
        val raw = "cyberpunk 2077 soundtrack - i really want to stay at your house by rosa walton & ha"
        val clean = SongInfo("I Really Want to Stay at Your House", "Rosa Walton & Hallie Coggins", "Cyberpunk 2077: Radio, Vol. 2 (Original Soundtrack)",246000)
        val http = LyricsHttp()
        val providers = listOf(GenieProvider(http), BugsProvider(http))
        for (provider in providers) {
            for ((label,song) in listOf("raw" to SongInfo(raw,""),"normalized" to clean)) {
                try {
                    val tracks=withTimeout(15000) { provider.search(song) }
                    println("LIVE ${provider.source} $label candidates=${tracks.size}")
                    for (track in tracks.take(5)) {
                        println("LIVE candidate id=${track.id} title=${track.song.title} artist=${track.song.artist} duration=${track.song.durationMs} score=${LyricsMatcher.score(clean,track.song)}")
                    }
                    val known=tracks.firstOrNull { it.id == if(provider.source==Provider.GENIE) "100529466" else "81992537" }
                    if(known!=null) {
                        val lyric=withTimeout(15000) { provider.lyrics(known) }
                        println("LIVE ${provider.source} $label plain=${!lyric?.plainLyrics.isNullOrBlank()} syncLines=${lyric?.syncedLines?.size ?: 0}")
                    }
                } catch(e: TimeoutCancellationException) { println("LIVE ${provider.source} $label timeout") }
                catch(e: CancellationException) { throw e }
                catch(e: Exception) { println("LIVE ${provider.source} $label error=${e.javaClass.simpleName}") }
            }
        }
        // Diagnostic only: distinguish failure to find a track from unavailable lyrics.
        try {
            val genie=GenieProvider(http)
            val known=genie.lyrics(TrackCandidate("100529466",clean))
            println("LIVE GENIE known-id plain=${!known?.plainLyrics.isNullOrBlank()} syncLines=${known?.syncedLines?.size ?: 0}")
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { println("LIVE GENIE known-id error=${e.javaClass.simpleName}") }
        val outcome=createLyricKit().searchDetailed(clean)
        when(outcome) {
            is SearchOutcome.Found -> println("LIVE engine found provider=${outcome.result.provider} score=${outcome.result.matchScore} plain=${!outcome.result.plainLyrics.isNullOrBlank()} syncLines=${outcome.result.syncedLines.size} failures=${outcome.failedProviders}")
            is SearchOutcome.Unavailable -> println("LIVE engine unavailable ${outcome.failedProviders}")
            SearchOutcome.NotFound -> println("LIVE engine not found")
        }
        val rawOutcome=createLyricKit().searchDetailed(SongInfo(raw,"",durationMs=246000))
        println("LIVE raw engine " + when(rawOutcome) {
            is SearchOutcome.Found -> "found provider="+rawOutcome.result.provider+" score="+rawOutcome.result.matchScore+" syncLines="+rawOutcome.result.syncedLines.size
            is SearchOutcome.Unavailable -> "unavailable "+rawOutcome.failedProviders
            SearchOutcome.NotFound -> "not found"
        })
    }
}
