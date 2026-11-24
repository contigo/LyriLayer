package com.chaoseye.lyrilayer

import android.content.Context
import android.provider.MediaStore
import android.content.ContentUris
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.Locale

data class Track(val title: String, val artist: String, val album: String, val duration: Long, val uri: String = "") {
    val key: String get() = listOf(title, artist, album, duration.toString()).joinToString("\u001f") { it.trim().lowercase(Locale.ROOT) }
}
@Entity(tableName = "lyrics")
data class Lyric(@PrimaryKey val key: String, val plain: String = "", val synced: String = "", val source: String = "", val status: String = "FOUND", val fetched: Long = System.currentTimeMillis(), val edited: Boolean = false, val offset: Long = 0)
@Dao interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE `key` = :key") suspend fun get(key: String): Lyric?
    @Query("SELECT * FROM lyrics") fun observe(): Flow<List<Lyric>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(lyric: Lyric)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(lyric: Lyric): Long
    @Query("UPDATE lyrics SET plain=:plain, synced=:synced, source=:source, status=:status, fetched=:fetched WHERE `key`=:key AND edited=0")
    suspend fun updateAuto(key: String, plain: String, synced: String, source: String, status: String, fetched: Long)
}
@Database(entities = [Lyric::class], version = 1, exportSchema = false)
abstract class LyricsDb : RoomDatabase() {
    abstract fun dao(): LyricsDao
    companion object {
        @Volatile private var instance: LyricsDb? = null
        fun get(c: Context): LyricsDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(c.applicationContext, LyricsDb::class.java, "lyrics.db").build().also { instance = it }
        }
    }
}
object Library {
    fun scan(c: Context): List<Track> {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf("_id", "title", "artist", "album", "duration")
        return buildList {
            c.contentResolver.query(uri, columns, "is_music != 0", null, "title COLLATE NOCASE ASC")?.use { r ->
                while (r.moveToNext()) add(Track(r.getString(1).orEmpty(), r.getString(2).orEmpty(), r.getString(3).orEmpty(), r.getLong(4), ContentUris.withAppendedId(uri,r.getLong(0)).toString()))
            }
        }
    }
}
