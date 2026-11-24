package com.chaoseye.lyrickit

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ConnectException
import javax.net.ssl.SSLException
import org.json.JSONException

data class SearchFailure(val reason: String, val retryable: Boolean, val httpStatus: Int? = null)
class HttpFailure(val status: Int, val retryAfterMs: Long? = null) : IOException("HTTP $status")
object SearchFailures {
    fun classify(e: Exception): SearchFailure = when(e) {
        is HttpFailure -> SearchFailure(when(e.status){
            401,403 -> "접근 거부"
            429 -> "요청 제한"
            in 500..599 -> "서버 오류"
            else -> "HTTP 오류"
        }, e.status in listOf(408,429,500,502,503,504),e.status)
        is SocketTimeoutException -> SearchFailure("응답 시간 초과",true)
        is UnknownHostException -> SearchFailure("DNS 연결 실패",true)
        is SSLException -> SearchFailure("TLS 인증 오류",false)
        is ConnectException -> SearchFailure("서버 연결 실패",true)
        is JSONException -> SearchFailure("응답 형식 오류",false)
        is IOException -> SearchFailure("네트워크 또는 응답 오류",true)
        else -> SearchFailure("응답 처리 오류",false)
    }
    fun describe(f: SearchFailure): String = f.reason + (f.httpStatus?.let { " (HTTP $it)" } ?: "")
    fun label(p: Provider): String = when(p) {
        Provider.GENIE -> "지니"
        Provider.BUGS -> "벅스"
        Provider.MUSIXMATCH -> "Musixmatch"
    }
}
