package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test
class AutoInstallRulesTest {
    private val id="v0.1.22:sha256:abc"
    private fun check(ready:Boolean=true,foreground:Boolean=true,manual:Boolean=false,auto:Boolean=true,last:String?=null,pending:String?=null,granted:Boolean=true)=AutoInstallRules.shouldStart(ready,foreground,manual,auto,id,last,pending,granted)
    @Test fun newReleaseOpensOnce(){assertTrue(check());assertFalse(check(last=id));assertTrue(check(last="older"))}
    @Test fun requiresReadyForegroundAndPreference(){assertFalse(check(ready=false));assertFalse(check(foreground=false));assertFalse(check(auto=false));assertTrue(check(manual=true,auto=false,last=id))}
    @Test fun permissionContinuation(){assertTrue(check(last=id,pending=id));assertFalse(check(last=id,pending=id,granted=false));assertFalse(check(last=id))}
}
