package com.chaoseye.lyrilayer
internal object AutoInstallRules {
    fun shouldStart(ready:Boolean,foreground:Boolean,manual:Boolean,autoEnabled:Boolean,identity:String,lastPrompt:String?,permissionPending:String?,permissionGranted:Boolean):Boolean{
        if(!ready||!foreground||identity.isBlank())return false
        if(manual)return true
        if(permissionPending==identity&&permissionGranted)return true
        return autoEnabled&&lastPrompt!=identity
    }
}
