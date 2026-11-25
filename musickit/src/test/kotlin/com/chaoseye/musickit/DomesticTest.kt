package com.chaoseye.musickit
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
class DomesticTest {
    @Test fun bugsFixture(){
        val rows=parseBugsMusic(JSONObject("""{"list":[{"track_id":"1","track_title":"밤편지","artists":[{"artist_nm":"아이유"}],"album_title":"Palette","len":"4:13"}]}"""))
        assertEquals("밤편지",rows.single().title);assertEquals(253000L,rows.single().durationMs);assertEquals("Palette",rows.single().album)
    }
    @Test fun genieFixture(){
        val rows=parseGenieMusic("""<table><tr class="list" songid="123"><td class="info"><a class="title ellipsis">밤편지</a><a class="artist ellipsis">아이유</a><a class="albumtitle">Palette</a><span class="duration">4:13</span></td></tr></table>""")
        assertEquals("아이유",rows.single().artist);assertEquals("123",rows.single().id)
    }
    @Test fun albumConflict()=runBlocking{
        fun provider(s:MusicSource,a:String)=object:MusicProvider{
            override val source=s
            override suspend fun search(query:MusicQuery)=listOf(MusicCandidate(s,"1","밤편지","아이유",a,253000))
        }
        val result=MusicKit(listOf(provider(MusicSource.BUGS,"Palette"),provider(MusicSource.ITUNES_KR,"Compilation"))).identify(MusicQuery("밤편지","아이유",durationMs=253000))
        assertNull(result.verified);assertEquals(2,result.matches.size)
    }
}
