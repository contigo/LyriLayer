package com.chaoseye.lyrilayer

import android.content.Context
import android.provider.MediaStore
import android.os.Build
import android.content.ContentUris
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.Locale

data class Track(val title: String, val artist: String, val album: String, val duration: Long, val uri: String = "", val fileName: String = "", val excluded: Boolean = false) {
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
    fun scan(c:Context):List<Track> = allAudio(c).filterNot { it.excluded }
    fun allAudio(c:Context):List<Track> {
        val uri=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val folder=if(Build.VERSION.SDK_INT>=29) "relative_path" else "_data"
        val columns=mutableListOf("_id","title","artist","album","duration","_display_name",folder,"is_music","is_alarm","is_notification","is_ringtone")
        if(Build.VERSION.SDK_INT>=31)columns.add("is_recording")
        if(Build.VERSION.SDK_INT>=29)columns.add("_data")
        fun query(projection:List<String>):List<Track> = buildList {
            c.contentResolver.query(uri,projection.toTypedArray(),null,null,"title COLLATE NOCASE ASC")?.use { r ->
                while(r.moveToNext()){
                    val title=r.getString(1).orEmpty(); val name=r.getString(5).orEmpty()
                    val relative=r.getString(6).orEmpty()
                    val dataIndex=r.getColumnIndex("_data")
                    val absolute=if(dataIndex>=0)r.getString(dataIndex).orEmpty() else ""
                    val path=if(Build.VERSION.SDK_INT>=29)relative else absolute
                    val flagged=(r.getColumnIndex("is_recording").let{it>=0&&r.getInt(it)!=0})||r.getInt(8)!=0||r.getInt(9)!=0||r.getInt(10)!=0
                    val excluded=MusicRules.excluded(path,name,title,flagged)||MusicRules.excluded(absolute,"")||MusicRules.excluded(path.trimEnd('/')+"/"+name,"")||r.getInt(7)==0
                    add(Track(title,r.getString(2).orEmpty(),r.getString(3).orEmpty(),r.getLong(4),ContentUris.withAppendedId(uri,r.getLong(0)).toString(),name,excluded))
                }
            }
        }
        return try { query(columns) } catch(e:IllegalArgumentException){
            if(Build.VERSION.SDK_INT>=29)columns.remove("_data")
            try { query(columns) } catch(second:IllegalArgumentException){ if(columns.remove("is_recording"))query(columns) else throw second }
        }
    }
}
