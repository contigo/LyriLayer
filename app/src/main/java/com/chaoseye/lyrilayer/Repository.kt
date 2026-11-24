package com.chaoseye.lyrilayer

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Repository {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val gate = Mutex()
    suspend fun resolve(c: Context, t: Track, force: Boolean = false): Lyric = gate.withLock {
        withContext(Dispatchers.IO) {
            val dao=LyricsDb.get(c).dao()
            val old=dao.get(t.key)
            if(old != null && (old.edited || (!force && (old.status == "FOUND" || old.status == "INSTRUMENTAL" || System.currentTimeMillis()-old.fetched < 86400000)))) return@withContext old
            val url="https://lrclib.net/api/get".toHttpUrl().newBuilder().addQueryParameter("track_name",t.title).addQueryParameter("artist_name",t.artist).apply {
                if(t.album.isNotBlank()) addQueryParameter("album_name",t.album)
                if(t.duration>0) addQueryParameter("duration",((t.duration+500)/1000).toString())
            }.build()
            val result=client.newCall(Request.Builder().url(url).header("User-Agent","LyriLayer/0.1.0 (Android)").build()).execute().use { r ->
                when {
                    r.code==404 -> Lyric(t.key,status="NOT_FOUND",source="LRCLIB")
                    !r.isSuccessful -> throw IOException("LRCLIB HTTP ${r.code}")
                    else -> {
                        val j=JSONObject(r.body?.string() ?: throw IOException("Empty response"))
                        val plain=if(j.isNull("plainLyrics")) "" else j.optString("plainLyrics")
                        val synced=if(j.isNull("syncedLyrics")) "" else j.optString("syncedLyrics")
                        Lyric(t.key,plain,synced,"LRCLIB",when { j.optBoolean("instrumental") -> "INSTRUMENTAL"; plain.isNotBlank() || synced.isNotBlank() -> "FOUND"; else -> "NOT_FOUND" })
                    }
                }
            }
            ensureActive()
            if (old?.status == "FOUND" && result.status != "FOUND") return@withContext old
            if(dao.insert(result)==-1L) dao.updateAuto(result.key,result.plain,result.synced,result.source,result.status,result.fetched)
            delay(350)
            dao.get(t.key) ?: result
        }
    }
}
