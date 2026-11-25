package com.chaoseye.lyrilayer

import android.content.Context
import androidx.room.*
import com.chaoseye.musickit.*
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
    private val engine by lazy{createMusicKit()}
    private val gate=Mutex()
    suspend fun search(c:Context,t:Track):MusicSearch=gate.withLock{
        require(!t.excluded&&!MusicRules.excluded("",t.fileName,t.title))
        val result=engine.identify(MusicQuery(t.title,t.artist,t.album,t.duration,t.fileName))
        val dao=MusicInfoDb.get(c).dao()
        if(dao.get(t.key)?.userConfirmed!=true)result.verified?.let{dao.put(info(t,it,false))}
        result
    }
    private fun info(t:Track,m:MusicMatch,manual:Boolean)=m.candidate.let{MusicInfo(t.key,it.title,it.artist,it.album,it.durationMs,it.source.name,it.id,m.score,it.artworkUrl,it.releaseDate,manual)}
    suspend fun select(c:Context,t:Track,m:MusicMatch)=gate.withLock{MusicInfoDb.get(c).dao().put(info(t,m,true))}
    suspend fun canonical(c:Context,t:Track):Track=MusicInfoDb.get(c).dao().get(t.key)?.let{t.copy(title=it.title,artist=it.artist,album=it.album.ifBlank{t.album},fileName="")}?:t
    suspend fun clear(c:Context):Int=gate.withLock{MusicInfoDb.get(c).dao().clearAll()}
}
