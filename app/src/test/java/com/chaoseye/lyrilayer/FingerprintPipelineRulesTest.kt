package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test
class FingerprintPipelineRulesTest {
    private val info=MusicInfo("k","Song","Artist","",180000,"SHAZAM_FINGERPRINT","id",0,"","",false)
    @Test fun pendingIncludesKnownSongWithoutLyrics(){
        assertTrue(FingerprintPipelineRules.needsWork(info,null))
        assertTrue(FingerprintPipelineRules.needsWork(info,Lyric("k",status="NOT_FOUND")))
        assertFalse(FingerprintPipelineRules.needsWork(info,Lyric("k",plain="line")))
        assertTrue(FingerprintPipelineRules.needsWork(null,Lyric("k",plain="line")))
        assertTrue(FingerprintPipelineRules.needsWork(info.copy(provider="BUGS"),Lyric("k",plain="line")))
        assertFalse(FingerprintPipelineRules.needsWork(info.copy(provider="BUGS",userConfirmed=true),Lyric("k",plain="line")))
    }
    @Test fun completionAndProgress(){
        assertEquals("done",FingerprintPipelineRules.outcome(true,true))
        assertEquals("partial",FingerprintPipelineRules.outcome(true,false))
        assertEquals("partial",FingerprintPipelineRules.outcome(false,true))
        assertEquals("failed",FingerprintPipelineRules.outcome(false,false))
        assertEquals(0,FingerprintPipelineRules.percent(1,0))
        assertEquals(25,FingerprintPipelineRules.percent(1,4))
        assertEquals(100,FingerprintPipelineRules.percent(5,4))
        assertTrue(FingerprintPipelineRules.lyricReady(Lyric("k",status="INSTRUMENTAL")))
    }
}
