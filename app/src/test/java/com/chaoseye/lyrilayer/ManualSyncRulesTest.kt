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
    @Test fun adjustOneLineByExactly100ms(){
        val times=listOf(1000L,2200L,3500L)
        assertEquals(listOf(1000L,2100L,3500L),ManualSyncRules.adjust(times,1,-100L,5000L))
        assertEquals(listOf(1000L,2300L,3500L),ManualSyncRules.adjust(times,1,100L,5000L))
        assertEquals(listOf(1000L,2200L,3500L),times)
        assertEquals(listOf(1000L,2400L,3500L),ManualSyncRules.adjust(ManualSyncRules.adjust(times,1,100L,5000L)!!,1,100L,5000L))
    }
    @Test fun adjustmentRejectsInvalidBoundaries(){
        assertNull(ManualSyncRules.adjust(listOf(1000L,1100L),1,-100L,5000L))
        assertNull(ManualSyncRules.adjust(listOf(1000L,-1L,1100L),0,100L,5000L))
        assertNull(ManualSyncRules.adjust(listOf(-1L),0,100L,5000L))
        assertNull(ManualSyncRules.adjust(listOf(50L),0,-100L,5000L))
        assertNull(ManualSyncRules.adjust(listOf(4900L),0,100L,5000L))
        assertNull(ManualSyncRules.adjust(listOf(1000L),1,100L,5000L))
        assertNull(ManualSyncRules.adjust(listOf(1000L),0,200L,5000L))
        assertTrue(ManualSyncRules.canAdjust(listOf(100L),0,-100L,5000L))
        assertEquals(listOf(0L),ManualSyncRules.adjust(listOf(100L),0,-100L,5000L))
    }
    @Test fun existingTimes(){
        val l=Lyric(track.key,plain="a\nb",synced="[00:01.00]a\n[00:02.00]b",offset=200)
        assertEquals(listOf(1200L,2200L),ManualSyncRules.initial(l,180000))
        assertEquals(listOf(-1L,-1L),ManualSyncRules.initial(l.copy(plain="c\nd"),180000))
    }
}
