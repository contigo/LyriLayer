package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test
class OverlayTestTest {
    @Test fun onlyPlayingWithPermissionAndScreenOn(){
        for(p in listOf(false,true)) for(a in listOf(false,true)) for(s in listOf(false,true))
            assertEquals(p&&a&&s,OverlayTest.status(p,a,s)==null)
    }
    @Test fun reasons(){
        assertTrue(OverlayTest.status(true,false,true)!!.contains("권한"))
        assertTrue(OverlayTest.status(false,true,true)!!.contains("재생 대기"))
        assertTrue(OverlayTest.status(true,true,false)!!.contains("화면 꺼짐"))
    }
}
