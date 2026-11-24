package com.chaoseye.lyrilayer

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ai_queue")
internal data class AiQueueEntry(
    @PrimaryKey val key: String,
    val title: String, val artist: String, val kind: String,
    val order: Long,
    val state: String = "pending",
    val message: String = "",
    val draft: String = "",
    val plain: String = "",
    val baseline: String = "",
    val uncertain: Int = 0,
    val language: String = "auto"
)
@Dao
internal interface AiQueueDao {
    @Query("DELETE FROM ai_queue") suspend fun clearAll(): Int
    @Query("SELECT * FROM ai_queue ORDER BY `order`, `key`") fun observe(): Flow<List<AiQueueEntry>>
    @Query("SELECT * FROM ai_queue ORDER BY `order`, `key`") suspend fun all(): List<AiQueueEntry>
    @Query("SELECT * FROM ai_queue WHERE state='pending' ORDER BY `order`, `key` LIMIT 1") suspend fun nextPending(): AiQueueEntry?
    @Query("SELECT * FROM ai_queue WHERE `key`=:key") suspend fun get(key: String): AiQueueEntry?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(entries: List<AiQueueEntry>)
    @Update suspend fun update(entry: AiQueueEntry)
    @Query("UPDATE ai_queue SET state='pending', message='이전 분석 중단 · 다시 시작 가능' WHERE state='running'") suspend fun recover()
    @Query("UPDATE ai_queue SET state='pending', message='', draft='', plain='', baseline='', uncertain=0 WHERE state='failed'") suspend fun retryFailed()
    @Query("DELETE FROM ai_queue WHERE `key`=:key AND state!='running'") suspend fun remove(key: String)
    @Query("DELETE FROM ai_queue WHERE state IN ('saved','skipped')") suspend fun clearFinished()
}
@Database(entities=[AiQueueEntry::class], version=1, exportSchema=false)
internal abstract class AiQueueDb: RoomDatabase() {
    abstract fun dao(): AiQueueDao
    companion object {
        @Volatile private var instance: AiQueueDb? = null
        fun get(context: Context): AiQueueDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AiQueueDb::class.java, "ai_queue.db").build().also { instance = it }
        }
    }
}
