package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test
class UpdateRulesTest {
    @Test fun versions(){
        assertTrue(UpdateRules.compare("v0.1.20","0.1.9")>0)
        assertEquals(0,UpdateRules.compare("v0.1.19","0.1.19"))
        assertTrue(UpdateRules.compare("v0.1.18","0.1.19")<0)
        assertNull(UpdateRules.version("v0.1.20-rc"))
    }
    @Test fun assets(){
        val tag="v0.1.20";val url=UpdateRules.url(tag);val digest="sha256:"+"a".repeat(64)
        assertTrue(UpdateRules.valid(tag,url,16000000,digest))
        assertFalse(UpdateRules.valid(tag,"https://example.org/app.apk",16000000,digest))
        assertFalse(UpdateRules.valid(tag,url,16000000,""))
        assertFalse(UpdateRules.valid(tag,url,100000001,digest))
    }
}
