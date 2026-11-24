package com.chaoseye.lyrilayer

internal object OverlayTest {
    fun text(title:String):String = "[테스트] "+title.trim().ifBlank{"삼성 뮤직 재생 중 · 곡 정보 대기"}
    // null means the test overlay may be displayed.
    fun status(playing:Boolean, permission:Boolean, interactive:Boolean):String? = when {
        !permission -> "테스트: 다른 앱 위에 표시 권한 필요"
        !playing -> "테스트: 삼성 뮤직 재생 대기"
        !interactive -> "테스트: 화면 꺼짐"
        else -> null
    }
}
