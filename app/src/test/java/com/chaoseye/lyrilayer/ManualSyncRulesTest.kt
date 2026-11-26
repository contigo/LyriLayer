package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test
class ManualSyncRulesTest {
    private val track=Track("Song","Artist","Album",180000,"content://media/1")
    @Test fun listEligibility(){
        assertTrue(ManualSyncRules.plainOnly(track,Lyric(track.key,plain="a\nb")))
        assertFalse(ManualSyncRules.plainOnly(track,Lyric(track.key,plain="a",synced="[00:01.00]a")))
        assertFalse(ManualSyncRules.plainOnly(track,Lyric(track.key,status="INSTRUMENTAL")))
        assertFalse(ManualSyncRules.plainOnly(track.copy(uri=""),Lyric(track.key,plain="a")))
    }
    @Test fun orderedMarks(){
        val initial=listOf(-1L,-1L,-1L)
        val a=ManualSyncRules.mark(initial,0,1234,180000)!!
        assertEquals(listOf(1230L,-1L,-1L),a)
        assertNull(ManualSyncRules.mark(a,1,1231,180000))
        val b=ManualSyncRules.mark(a,2,3000,180000)!!
        assertNull(ManualSyncRules.mark(b,1,4000,180000))
        val c=ManualSyncRules.mark(b,1,2000,180000)!!
        assertTrue(ManualSyncRules.valid(c,180000))
        assertFalse(ManualSyncRules.valid(b,180000))
        assertEquals(1,ManualSyncRules.nextIndex(b,2))
    }
    @Test fun existingTimes(){
        val l=Lyric(track.key,plain="a\nb",synced="[00:01.00]a\n[00:02.00]b",offset=200)
        assertEquals(listOf(1200L,2200L),ManualSyncRules.initial(l,180000))
        assertEquals(listOf(-1L,-1L),ManualSyncRules.initial(l.copy(plain="c\nd"),180000))
    }
}
