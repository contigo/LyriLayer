package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test
class AiQueueRulesTest {
    private val track=Track("Song","Artist","Album",200000,"content://media/1","song.mp3")
    @Test fun candidates(){
        assertEquals(AiQueueRules.EXTRACT,AiQueueRules.kind(track,null))
        assertEquals(AiQueueRules.EXTRACT,AiQueueRules.kind(track,Lyric(track.key,status="NOT_FOUND")))
        assertEquals(AiQueueRules.ALIGN,AiQueueRules.kind(track,Lyric(track.key,plain="Lyrics")))
        assertNull(AiQueueRules.kind(track,Lyric(track.key,synced="[00:01.00]Lyrics")))
        assertNull(AiQueueRules.kind(track,Lyric(track.key,status="INSTRUMENTAL")))
        assertNull(AiQueueRules.kind(track.copy(excluded=true),null))
        assertNull(AiQueueRules.kind(track.copy(duration=720001),null))
    }
    @Test fun protectsExistingLyrics(){
        val old=Lyric(track.key,plain="Original",fetched=1)
        assertEquals(AiQueueRules.fingerprint(old),AiQueueRules.fingerprint(old.copy(fetched=2)))
        assertNotEquals(AiQueueRules.fingerprint(old),AiQueueRules.fingerprint(old.copy(plain="Changed")))
        assertNotEquals(AiQueueRules.fingerprint(null),AiQueueRules.fingerprint(old))
    }
}
