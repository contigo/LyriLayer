package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test
class OverlayTestTest {
    @Test fun onlyPlayingWithPermissionAndScreenOn(){
        for(p in listOf(false,true)) for(a in listOf(false,true)) for(s in listOf(false,true))
            assertEquals(p&&a&&s,OverlayTest.status(p,a,s)==null)
    }
    @Test fun currentTrackTitle(){
        assertEquals("[테스트] Ditto",OverlayTest.text("Ditto"))
        assertEquals("[테스트] 사건의 지평선",OverlayTest.text(" 사건의 지평선 "))
        assertEquals("[테스트] 삼성 뮤직 재생 중 · 곡 정보 대기",OverlayTest.text(" "))
    }
    @Test fun reasons(){
        assertTrue(OverlayTest.status(true,false,true)!!.contains("권한"))
        assertTrue(OverlayTest.status(false,true,true)!!.contains("재생 대기"))
        assertTrue(OverlayTest.status(true,true,false)!!.contains("화면 꺼짐"))
    }
}
