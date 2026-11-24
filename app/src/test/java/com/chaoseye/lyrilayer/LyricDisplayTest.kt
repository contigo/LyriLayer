package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test
class LyricDisplayTest {
    @Test fun sourceAndFilter(){
        assertTrue(LyricDisplay.aiGenerated("FOUND","ON_DEVICE_AI"))
        assertFalse(LyricDisplay.aiGenerated("FOUND","LRCLIB"))
        assertTrue(LyricDisplay.visible("ai","FOUND","ON_DEVICE_AI",false))
        assertTrue(LyricDisplay.visible("ai","NOT_FOUND","LRCLIB-v4",true))
        assertFalse(LyricDisplay.visible("ai","NOT_FOUND","LRCLIB-v4",false))
        assertTrue(LyricDisplay.visible("missing","NOT_FOUND",null,true))
        assertFalse(LyricDisplay.visible("missing","FOUND","ON_DEVICE_AI",false))
        assertTrue(LyricDisplay.registered("INSTRUMENTAL"))
        assertEquals("✦ AI 생성 가사",LyricDisplay.label("FOUND","ON_DEVICE_AI",true))
        assertEquals("싱크 가사",LyricDisplay.label("FOUND","LRCLIB",true))
        assertEquals("검색 실패",LyricDisplay.label("NOT_FOUND",null,false))
    }
    @Test fun editingAndTime(){
        assertEquals("ON_DEVICE_AI",LyricDisplay.editedSource("ON_DEVICE_AI",false))
        assertEquals("USER",LyricDisplay.editedSource("ON_DEVICE_AI",true))
        assertEquals("01:02.34",LyricDisplay.timestamp(62340))
    }
}
