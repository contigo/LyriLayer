package com.chaoseye.lyrickit

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EngineTest {
    private val song = SongInfo("노래", "가수", durationMs = 180000)
    @Test fun match() {
        assertEquals(95f, LyricsMatcher.score(song, song), 0f)
        assertEquals(0f, LyricsMatcher.score(song, song.copy(artist="다른 가수")), 0f)
        assertEquals(0f, LyricsMatcher.score(song, song.copy(title="노래 (Live)")), 0f)
        assertEquals(0f, LyricsMatcher.score(song, song.copy(durationMs=200000)), 0f)
        assertTrue(LyricsMatcher.score(song, song.copy(durationMs=null)) < 90f)
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
