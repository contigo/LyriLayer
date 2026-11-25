package com.chaoseye.musickit
import org.junit.Test
import org.junit.Assert.*
class MusicMatchingTest{
    private val q=MusicQuery("밤편지","아이유","Palette",253000)
    private val c=MusicCandidate(MusicSource.ITUNES_KR,"1","밤편지","IU","Palette",253000)
    @Test fun koreanAlias(){assertEquals(100,MusicMatching.score(q,c))}
    @Test fun wrongArtist(){assertEquals(0,MusicMatching.score(q,c.copy(artist="다른 가수")))}
    @Test fun wrongVersion(){assertEquals(0,MusicMatching.score(q,c.copy(title="밤편지 (Live)")))}
    @Test fun wrongDuration(){assertEquals(0,MusicMatching.score(q,c.copy(durationMs=270000)))}
    @Test fun fileName(){assertTrue(MusicMatching.identities(MusicQuery("01. 아이유 - 밤편지.mp3")).any{it.title=="밤편지"&&it.artist=="아이유"})}
}
