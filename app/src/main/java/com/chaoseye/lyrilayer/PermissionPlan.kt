package com.chaoseye.lyrilayer

internal enum class RequiredAccess(val bit:Int,val label:String,val explanation:String) {
    AUDIO(1,"음악 및 오디오 접근","음악 파일 목록과 내장 가사를 읽고 기기에서 AI 가사를 생성하는 데 필요합니다."),
    LISTENER(2,"알림 접근","삼성 뮤직의 MediaSession을 통해 음악 재생 상태를 읽습니다. 알림 본문은 수집하지 않습니다."),
    OVERLAY(4,"다른 앱 위에 표시","홈 화면이나 다른 앱을 사용하는 동안에도 재생 중인 가사를 플로팅으로 표시합니다.")
}

internal object PermissionPlan {
    fun next(audio:Boolean,listener:Boolean,overlay:Boolean,attempted:Int):RequiredAccess? =
        listOf(RequiredAccess.AUDIO to audio,RequiredAccess.LISTENER to listener,RequiredAccess.OVERLAY to overlay)
            .firstOrNull{(access,granted)->!granted&&(attempted and access.bit)==0}?.first
}
