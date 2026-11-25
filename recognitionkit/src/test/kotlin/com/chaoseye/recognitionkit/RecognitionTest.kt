package com.chaoseye.recognitionkit
import org.junit.Assert.*
import org.junit.Test
class RecognitionTest{
    @Test fun windows(){
        assertEquals(listOf(104000L,49000L,159000L),SampleWindows.starts(220000,12000,true))
        assertEquals(listOf(0L),SampleWindows.starts(6000,6000,true))
    }
    @Test fun match(){
        val result=ShazamProvider().parse("""{"matches":[{"id":"a"}],"track":{"key":"1","title":"Song","subtitle":"Artist","sections":[{"type":"SONG","metadata":[{"title":"Album","text":"Record"}]}]}}""") as RecognitionResult.Success
        assertEquals("Record",result.track.album)
        assertEquals("Artist",result.track.artist)
        assertEquals(RecognitionResult.NoMatch,ShazamProvider().parse("""{"matches":[]}"""))
    }
}
