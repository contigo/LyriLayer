package com.chaoseye.lyrickit

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EngineTest {
    private val song = SongInfo("노래", "가수", durationMs = 180000)
    @Test fun providerFailureDetails() = runBlocking {
        val failed=object:LyricsProvider{
            override val source=Provider.GENIE
            override suspend fun search(song:SongInfo):List<TrackCandidate> { throw HttpFailure(429,1000) }
            override suspend fun lyrics(candidate:TrackCandidate):LyricsResult?=null
        }
        val outcome=LyricKit(listOf(failed)).searchDetailed(song) as SearchOutcome.Unavailable
        assertEquals(429,outcome.failures[Provider.GENIE]?.httpStatus)
        assertTrue(outcome.failures[Provider.GENIE]?.retryable==true)
        assertFalse(SearchFailures.classify(HttpFailure(403)).retryable)
        assertTrue(SearchFailures.classify(java.net.SocketTimeoutException()).retryable)
    }
    @Test fun match() {
        assertEquals(95f, LyricsMatcher.score(song, song), 0f)
        assertEquals(0f, LyricsMatcher.score(song, song.copy(artist="다른 가수")), 0f)
        assertEquals(0f, LyricsMatcher.score(song, song.copy(title="노래 (Live)")), 0f)
        assertEquals(0f, LyricsMatcher.score(song, song.copy(durationMs=200000)), 0f)
        assertTrue(LyricsMatcher.score(song, song.copy(durationMs=null)) < 90f)
    }
    @Test fun multilingualArtistAndDecoratedTitle() {
        val clean=SongInfo("I Really Want to Stay at Your House","Rosa Walton & Hallie Coggins",durationMs=246000)
        val bugs=clean.copy(artist="Rosa Walton(로사 월튼), Hallie Coggins")
        assertEquals(95f,LyricsMatcher.score(clean,bugs),0f)
        assertEquals(0f,LyricsMatcher.score(clean,bugs.copy(artist="Rosa Walton, Other Artist")),0f)
        val raw=SongInfo("cyberpunk 2077 soundtrack - i really want to stay at your house by rosa walton & ha","",durationMs=246000)
        assertEquals("i really want to stay at your house",SongNormalizer.normalize(raw).title)
        assertTrue(SongNormalizer.truncatedCreditMatches(raw,bugs))
        assertFalse(SongNormalizer.truncatedCreditMatches(raw,bugs.copy(artist="Rosa Walton, Other Artist")))
    }
    @Test fun genieSearchRows() {
        val html="""<table><tr class="list" songid="42"><td class="info">
            <a class="title ellipsis" title="Example">Example</a>
            <a class="artist ellipsis" title="Artist">Artist</a>
            <span class="duration">04:06</span></td></tr></table>"""
        val track=parseGenieTracks(html).single()
        assertEquals("42",track.id)
        assertEquals(SongInfo("Example","Artist",durationMs=246000),track.song)
        assertTrue(parseGenieTracks("<html></html>").isEmpty())
    }
    @Test fun truncatedSearchRetriesAndRejectsAmbiguity() = runBlocking {
        val raw=SongInfo("Example soundtrack - Example by First Artist & ha","",durationMs=246000)
        val exact=SongInfo("Example","First Artist & Hallie",durationMs=246000)
        val alternate=exact.copy(artist="First Artist & Hannah")
        var broadCalls=0
        val fake=object:LyricsProvider {
            override val source=Provider.BUGS
            override suspend fun search(song:SongInfo):List<TrackCandidate> {
                if(song.artist.contains("&")) return listOf(TrackCandidate("noise",exact.copy(title="Unrelated")))
                broadCalls++
                return listOf(TrackCandidate("1",exact),TrackCandidate("2",alternate))
            }
            override suspend fun lyrics(candidate:TrackCandidate):LyricsResult =
                LyricsResult(candidate.song,source,0f,"A",null,emptyList())
        }
        assertTrue(LyricKit(listOf(fake)).searchDetailed(raw) is SearchOutcome.NotFound)
        assertEquals(1,broadCalls)
    }
    @Test fun parses() {
        val lines = LrcParser.fromBugs("7.305|첫 줄＃8.3|둘째 줄")
        assertEquals(7305L, lines.first().startTimeMs)
        assertEquals(lines,LrcParser.parse(LrcParser.format(lines)))
        assertEquals(1234L,parseGenie("null({\"1234\":\"A &amp; B\"})").single().startTimeMs)
        assertEquals("A & B",parseGenie("null({\"1234\":\"A &amp; B\"})").single().text)
    }
    @Test fun musixmatchParsing() {
        val body = musixmatchBody("""{"message":{"header":{"status_code":200},"body":{"track_list":[{"track":{"track_id":42,"track_name":"노래","artist_name":"가수","track_length":180}}]}}}""")!!
        val candidate = musixmatchTracks(body).single()
        assertEquals("42", candidate.id)
        assertEquals(song,candidate.song)
        assertNull(musixmatchBody("""{"message":{"header":{"status_code":404}}}"""))
        try {
            musixmatchBody("""{"message":{"header":{"status_code":401}}}""")
            fail("Unauthorized response must not be a missing lyric")
        } catch (_: IOException) {}
        assertFalse(Provider.MUSIXMATCH in createLyricKit().availableProviders)
        assertTrue(Provider.MUSIXMATCH in createLyricKit("fixture-key").availableProviders)
    }
    private fun provider(p: Provider, failure: Boolean = false, found: Boolean = true) = object: LyricsProvider {
        override val source = p
        override suspend fun search(song: SongInfo): List<TrackCandidate> {
            if (failure) throw IOException("fixture")
            return if (found) listOf(TrackCandidate("1", song)) else emptyList()
        }
        override suspend fun lyrics(candidate: TrackCandidate): LyricsResult = LyricsResult(candidate.song,p,0f,"A","[00:01.000]A",listOf(LyricLine(1000,text="A")))
    }
    @Test fun outcomes() = runBlocking {
        assertTrue(LyricKit(listOf(provider(Provider.GENIE,false,false))).searchDetailed(song) is SearchOutcome.NotFound)
        assertTrue(LyricKit(listOf(provider(Provider.GENIE,true))).searchDetailed(song) is SearchOutcome.Unavailable)
        val found = LyricKit(listOf(provider(Provider.GENIE,true),provider(Provider.BUGS))).searchDetailed(song) as SearchOutcome.Found
        assertEquals(Provider.BUGS,found.result.provider)
        assertEquals(setOf(Provider.GENIE),found.failedProviders)
        assertTrue(LyricKit(emptyList()).searchDetailed(song,setOf(Provider.MUSIXMATCH)) is SearchOutcome.Unavailable)
    }
}
