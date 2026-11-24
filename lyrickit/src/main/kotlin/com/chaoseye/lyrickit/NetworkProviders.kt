package com.chaoseye.lyrickit

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class LyricsHttp(private val client: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(8, TimeUnit.SECONDS).build()) {
    suspend fun get(url: String, params: Map<String, String> = emptyMap(), headers: Map<String,String> = emptyMap()): String? =
        suspendCancellableCoroutine { continuation ->
            val target = url.toHttpUrl().newBuilder().apply { params.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
            val request = Request.Builder().url(target).header("User-Agent", "LyricKit/1.0").apply { headers.forEach { (k,v) -> header(k,v) } }.build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try { val value = response.use { if (it.code == 404) null else if (!it.isSuccessful) throw IOException("HTTP ${it.code}") else it.body?.string() ?: throw IOException("Empty response") }; if (continuation.isActive) continuation.resume(value) }
                    catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
}
internal fun duration(value: String): Long? {
    val p = value.trim().split(':').map { it.toLongOrNull() ?: return null }
    if (p.size !in 2..3 || p.any { it < 0 } || p.last() >= 60) return null
    return p.fold(0L) { a,b -> a*60+b } * 1000
}
internal fun JSONObject.string(key: String): String? = if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
internal fun result(c: TrackCandidate, source: Provider, lines: List<LyricLine>, plain: String?): LyricsResult? {
    val text = plain?.replace("\r\n", "\n")?.trim()?.takeIf { it.isNotEmpty() } ?: lines.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }
    if (text == null && lines.isEmpty()) return null
    return LyricsResult(c.song,source,0f,text,lines.takeIf { it.isNotEmpty() }?.let(LrcParser::format),lines)
}
internal class GenieProvider(private val http: LyricsHttp) : LyricsProvider {
    override val source = Provider.GENIE
    override suspend fun search(song: SongInfo): List<TrackCandidate> {
        suspend fun query(value: String): List<TrackCandidate> {
            val body = http.get("https://www.genie.co.kr/search/searchMain", mapOf("query" to value)) ?: return emptyList()
            return parseGenieTracks(body)
        }
        val combined = listOf(song.title,song.artist).filter { it.isNotBlank() }.joinToString(" ")
        val first = query(combined)
        if (song.artist.isBlank() || first.any { LyricsMatcher.score(song,it.song) >= 85f }) return first
        // Some Genie searches do not tokenize combined English artist/title text.
        // Retry only the title; the engine still validates title and artist before fetching lyrics.
        return (first + query(song.title)).distinctBy { it.id }
    }
    override suspend fun lyrics(candidate: TrackCandidate): LyricsResult? {
        val body = http.get("https://dn.genie.co.kr/app/purchase/get_msl.asp",mapOf("path" to "a", "songid" to candidate.id),mapOf("Referer" to "https://www.genie.co.kr/")) ?: return null
        return result(candidate,source,parseGenie(body),null)
    }
}
internal fun parseGenieTracks(body: String): List<TrackCandidate> =
    Jsoup.parse(body).select("tr.list[songid]").mapNotNull { row ->
        fun display(selector: String): String? {
            val el = row.selectFirst(selector) ?: return null
            el.select("span.icon,span[class^=icon-]").remove()
            return el.attr("title").ifBlank { el.text() }.trim().takeIf { it.isNotEmpty() }
        }
        val id=row.attr("songid").takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return@mapNotNull null
        val title=display("td.info a.title.ellipsis") ?: return@mapNotNull null
        val artist=display("td.info a.artist.ellipsis") ?: return@mapNotNull null
        TrackCandidate(id,SongInfo(title,artist,album=display("td.info a.albumtitle"),durationMs=duration(row.select("td.info span.duration").text())))
    }.distinctBy { it.id }

internal fun parseGenie(body: String): List<LyricLine> {
    if (body.isBlank() || body.trim().equals("NOT FOUND LYRICS",true)) return emptyList()
    val start = body.indexOf('('); val end = body.lastIndexOf(')')
    if (start < 0 || end <= start) throw IOException("Unexpected Genie response")
    val json = JSONObject(body.substring(start+1,end))
    return json.keys().asSequence().mapNotNull { key ->
        val ms = key.toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        val text = (json.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        LyricLine(ms,text=Parser.unescapeEntities(text,false))
    }.sortedBy { it.startTimeMs }.toList()
}
internal class BugsProvider(private val http: LyricsHttp) : LyricsProvider {
    override val source = Provider.BUGS
    override suspend fun search(song: SongInfo): List<TrackCandidate> {
        val body = http.get("https://m.bugs.co.kr/api/getSearchList",mapOf("type" to "track","query" to "${song.title} ${song.artist}","page" to "1","size" to "30")) ?: return emptyList()
        val rows = JSONObject(body).optJSONArray("list") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = row.string("track_id") ?: return@mapNotNull null
            val title = row.string("track_title") ?: return@mapNotNull null
            val artists = row.optJSONArray("artists") ?: return@mapNotNull null
            val artist = (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.string("artist_nm") }.joinToString(", ")
            if (artist.isBlank()) return@mapNotNull null
            TrackCandidate(id,SongInfo(title,artist,durationMs=duration(row.string("len").orEmpty())))
        }.distinctBy { it.id }
    }
    override suspend fun lyrics(candidate: TrackCandidate): LyricsResult? = supervisorScope {
        suspend fun attempt(mode: String): Result<String?> = try {
            Result.success(http.get("https://music.bugs.co.kr/player/lyrics/$mode/${candidate.id}")?.let { JSONObject(it).string("lyrics") })
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        val timed = async { attempt("T") }
        val text = async { attempt("N") }
        val t = timed.await(); val p = text.await()
        result(candidate,source,t.getOrNull()?.let(LrcParser::fromBugs).orEmpty(),p.getOrNull())
            ?: run { t.exceptionOrNull()?.let { throw it }; p.exceptionOrNull()?.let { throw it }; null }
    }
}
fun createLyricKit(musixmatchApiKey: String? = null): LyricKit {
    val http = LyricsHttp()
    val providers = mutableListOf<LyricsProvider>(GenieProvider(http), BugsProvider(http))
    musixmatchApiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { providers.add(MusixmatchProvider(http,it)) }
    return LyricKit(providers)
}
