package com.chaoseye.lyrilayer
internal object UpdateRules {
    const val PACKAGE="com.chaoseye.lyrilayer.debug"
    const val CERT="7e179e25aaf7980cad8b94c12d1a172b5736260cd7d34fc31d568687be1f7e17"
    const val ASSET="LyriLayer-dev.apk"
    fun version(raw:String)=Regex("^v?([0-9]{1,6})\\.([0-9]{1,6})\\.([0-9]{1,6})$").matchEntire(raw)?.groupValues?.drop(1)?.map{it.toInt()}
    fun compare(a:String,b:String):Int {
        val x=version(a)?:return -1
        val y=version(b)?:return 0
        for(i in 0..2)if(x[i]!=y[i])return x[i].compareTo(y[i])
        return 0
    }
    fun url(tag:String)="https://github.com/contigo/LyriLayer/releases/download/"+tag+"/"+ASSET
    fun valid(tag:String,url:String,size:Long,digest:String)=version(tag)!=null&&tag.startsWith("v")&&url==url(tag)&&size in 1..100_000_000L&&Regex("sha256:[0-9a-fA-F]{64}").matches(digest)
}
