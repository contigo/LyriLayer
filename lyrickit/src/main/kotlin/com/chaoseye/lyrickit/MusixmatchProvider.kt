package com.chaoseye.lyrickit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import java.io.IOException

internal fun musixmatchBody(raw: String): JSONObject? {
    val message = JSONObject(raw).getJSONObject("message")
    val status = message.getJSONObject("header").getInt("status_code")
    if (status == 404) return null
    if (status != 200) throw IOException("Musixmatch status $status")
    return message.getJSONObject("body")
}

internal fun musixmatchTracks(body: JSONObject): List<TrackCandidate> {
    val rows = body.getJSONArray("track_list")
    return (0 until rows.length()).mapNotNull { index ->
        val track = rows.optJSONObject(index)?.optJSONObject("track") ?: return@mapNotNull null
        val id = track.string("track_id") ?: return@mapNotNull null
        val title = track.string("track_name") ?: return@mapNotNull null
        val artist = track.string("artist_name") ?: return@mapNotNull null
        val duration = track.optLong("track_length").takeIf { it in 1..86400 }?.times(1000)
        TrackCandidate(id, SongInfo(title,artist,track.string("album_name"),duration))
    }.distinctBy { it.id }
}

// Opt-in only: caller supplies an API key authorized for the requested lyrics.
internal class MusixmatchProvider(private val http: LyricsHttp, private val apiKey: String) : LyricsProvider {
    override val source = Provider.MUSIXMATCH
    private suspend fun request(method: String, params: Map<String,String>): JSONObject? {
        val raw = http.get("https://api.musixmatch.com/ws/1.1/$method",params + mapOf("apikey" to apiKey,"format" to "json")) ?: return null
        return musixmatchBody(raw)
    }
    override suspend fun search(song: SongInfo): List<TrackCandidate> {
        val body = request("track.search",mapOf("q_track" to song.title,"q_artist" to song.artist,"page" to "1","page_size" to "20")) ?: return emptyList()
        return musixmatchTracks(body)
    }
    override suspend fun lyrics(candidate: TrackCandidate): LyricsResult? = supervisorScope {
        suspend fun attempt(method: String, params: Map<String,String>, field: String, key: String): Result<String?> = try {
            val value = request(method,params)?.optJSONObject(field)
            val restricted = value?.optInt("restricted",0) != 0
            Result.success(if(restricted) null else value?.string(key))
        } catch(e: CancellationException) { throw e } catch(e: Exception) { Result.failure(e) }
        val common = mapOf("track_id" to candidate.id)
        val subtitle = async { attempt("track.subtitle.get",common + ("subtitle_format" to "lrc"),"subtitle","subtitle_body") }
        val plain = async { attempt("track.lyrics.get",common,"lyrics","lyrics_body") }
        val timed = subtitle.await(); val text = plain.await()
        // Free/preview responses can include a truncation notice: do not register these as full lyrics.
        val full = text.getOrNull()?.takeUnless { it.contains("This Lyrics is NOT for Commercial use",true) || it.contains("*******") }
        result(candidate,source,timed.getOrNull()?.let(LrcParser::parse).orEmpty(),full)
            ?: run { timed.exceptionOrNull()?.let { throw it }; text.exceptionOrNull()?.let { throw it }; null }
    }
}
