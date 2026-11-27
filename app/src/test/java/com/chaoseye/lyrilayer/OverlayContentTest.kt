package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test

class OverlayContentTest {
    private fun content(line:String?=null,hasSync:Boolean=false,mode:String=PlainSync.ESTIMATE,estimated:String?=null,plain:String="",playing:Boolean=true,excluded:Boolean=false,permission:Boolean=true,enabled:Boolean=true,interactive:Boolean=true)=
        OverlayContent.saved(enabled,playing,permission,interactive,excluded,hasSync,line,mode,estimated,plain)

    @Test fun missingLyricsNeverCreateOverlay(){
        assertNull(content())
        assertNull(content(hasSync=true))
        assertNull(content(hasSync=true,line=" ",plain="not a fallback"))
        assertNull(content(mode=PlainSync.OFF,plain="stored"))
    }
    @Test fun storedLyricsOnly(){
        assertEquals(FloatingContent("가사"),content(line="가사",hasSync=true))
        assertEquals(FloatingContent("추정"),content(estimated="추정",plain="일반"))
        assertEquals(FloatingContent("일반"),content(mode=PlainSync.STATIC,estimated="추정",plain="일반"))
        assertEquals(FloatingContent("일반"),content(plain="일반"))
    }
    @Test fun respectsRestrictions(){
        assertNull(content(line="가사",hasSync=true,playing=false))
        assertNull(content(line="가사",hasSync=true,excluded=true))
        assertNull(content(line="가사",hasSync=true,permission=false))
        assertNull(content(line="가사",hasSync=true,enabled=false))
        assertNull(content(line="가사",hasSync=true,interactive=false))
    }
}
