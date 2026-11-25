package com.chaoseye.lyrilayer

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName="music_info")
internal data class MusicInfo(@PrimaryKey val key:String,val title:String,val artist:String,val album:String,val durationMs:Long,val provider:String,val providerId:String,val score:Int,val artworkUrl:String,val releaseDate:String,val userConfirmed:Boolean=false)
@Dao internal interface MusicInfoDao {
    @Query("SELECT * FROM music_info") fun observe():Flow<List<MusicInfo>>
    @Query("SELECT * FROM music_info WHERE `key`=:key") suspend fun get(key:String):MusicInfo?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(info:MusicInfo)
    @Query("DELETE FROM music_info") suspend fun clearAll():Int
}
@Database(entities=[MusicInfo::class],version=1,exportSchema=false)
internal abstract class MusicInfoDb:RoomDatabase(){
    abstract fun dao():MusicInfoDao
    companion object{
        @Volatile private var instance:MusicInfoDb?=null
        fun get(c:Context)=instance?:synchronized(this){instance?:Room.databaseBuilder(c.applicationContext,MusicInfoDb::class.java,"music_info.db").build().also{instance=it}}
    }
}
internal object MusicInfoRepository {
    private val gate=Mutex()
    suspend fun canonical(c:Context,t:Track):Track=MusicInfoDb.get(c).dao().get(t.key)?.let{t.copy(title=it.title,artist=it.artist,album=it.album.ifBlank{t.album},fileName="")}?:t
    suspend fun acceptFingerprint(c:Context,t:Track,match:com.chaoseye.recognitionkit.RecognizedTrack):Boolean=gate.withLock{
        val dao=MusicInfoDb.get(c).dao()
        if(dao.get(t.key)?.userConfirmed==true)return@withLock false
        dao.put(MusicInfo(t.key,match.title,match.artist,match.album,t.duration,match.provider,match.id,0,match.artworkUrl,match.releaseDate,false))
        true
    }
    suspend fun clear(c:Context):Int=gate.withLock{MusicInfoDb.get(c).dao().clearAll()}
}
