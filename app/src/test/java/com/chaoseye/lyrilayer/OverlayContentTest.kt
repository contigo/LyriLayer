package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test

class OverlayContentTest {
    private fun content(line:String?=null,hasSync:Boolean=false,status:String?=null,searching:Boolean=false,ai:Int?=null,showMissing:Boolean=true,playing:Boolean=true,excluded:Boolean=false,permission:Boolean=true,enabled:Boolean=true,interactive:Boolean=true,error:String="")=
        OverlayContent.decide(enabled,playing,permission,interactive,excluded,showMissing,"곡 제목","가수",line,hasSync,status,searching,error,ai)

    @Test fun missingSearchAndAi(){
        assertEquals("등록된 가사가 없습니다.",content(status="NOT_FOUND")!!.detail)
        assertEquals("가사 검색 중…",content(searching=true)!!.detail)
        assertEquals(65,content(ai=65)!!.progress)
        assertEquals("가수 - 곡 제목",content(ai=65)!!.text)
        assertEquals("일반 가사만 등록됨 · 싱크 가사 없음",content(status="FOUND")!!.detail)
        assertEquals("연주곡 · 가사 없음",content(status="INSTRUMENTAL")!!.detail)
    }
    @Test fun syncedLyricsTakePriority(){
        assertEquals(FloatingContent("가사"),content(line="가사",hasSync=true,ai=70))
        assertNull(content(showMissing=false))
        assertEquals("가사",content(line="가사",showMissing=false)!!.text)
        assertEquals("첫 가사 시작 대기",content(hasSync=true,error="첫 가사 시작 대기")!!.detail)
    }
    @Test fun respectsRestrictions(){
        assertNull(content(playing=false))
        assertNull(content(excluded=true))
        assertNull(content(permission=false))
        assertNull(content(enabled=false))
        assertNull(content(interactive=false))
    }
}
