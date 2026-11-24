package com.chaoseye.lyrilayer

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import kotlinx.coroutines.*
import java.security.MessageDigest

class BatchWorker(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result {
        val tracks=try { withContext(Dispatchers.IO) { Library.scan(applicationContext).sortedBy { it.uri } } } catch(e:SecurityException) { return Result.failure(workDataOf("lastError" to "음악 파일 접근 권한을 확인하세요")) }
        val prefs=applicationContext.getSharedPreferences("batch_progress",Context.MODE_PRIVATE)
        val digest=MessageDigest.getInstance("SHA-256")
        tracks.forEach { digest.update((it.uri+"\u0000"+it.key+"\n").toByteArray(Charsets.UTF_8)) }
        val fingerprint=digest.digest().joinToString("") { "%02x".format(it) }
        val same=prefs.getString("id","")==id.toString()&&prefs.getString("fingerprint","")==fingerprint
        var done=if(same)prefs.getInt("done",0).coerceIn(0,tracks.size) else 0
        var found=if(same)prefs.getInt("found",0) else 0
        var missing=if(same)prefs.getInt("missing",0) else 0
        var errors=if(same)prefs.getInt("errors",0) else 0
        var lastError=if(same)prefs.getString("lastError","").orEmpty() else ""
        fun snapshot(current:String="",finished:Boolean=false)=workDataOf("done" to done,"total" to tracks.size,"found" to found,"missing" to missing,"errors" to errors,"lastError" to lastError,"current" to current.take(180),"finished" to finished)
        fun checkpoint(){
            prefs.edit().putString("id",id.toString()).putString("fingerprint",fingerprint).putInt("done",done).putInt("total",tracks.size).putInt("found",found).putInt("missing",missing).putInt("errors",errors).putString("lastError",lastError).commit()
        }
        checkpoint()
        setProgress(snapshot())
        val start=SystemClock.elapsedRealtime()
        while(done<tracks.size){
            currentCoroutineContext().ensureActive()
            // Resume with the same work ID before WorkManager's normal execution limit.
            if(SystemClock.elapsedRealtime()-start>6*60*1000L){checkpoint();return Result.retry()}
            val t=tracks[done]
            setProgress(snapshot("${t.artist} - ${t.title}"))
            try {
                val l=withTimeout(45_000){Repository.resolve(applicationContext,t)}
                if(l.status=="NOT_FOUND")missing++ else found++
            } catch(e:TimeoutCancellationException){errors++;lastError="검색 시간 초과"}
            catch(e:CancellationException){throw e}
            catch(e:Exception){errors++;lastError=e.message.orEmpty().take(250)}
            done++
            checkpoint()
            setProgress(snapshot())
        }
        return Result.success(snapshot(finished=true))
    }
    companion object {
        const val NAME="lyrics_batch"
        fun savedProgress(c:Context,id:String):Data {
            val p=c.getSharedPreferences("batch_progress",Context.MODE_PRIVATE)
            if(p.getString("id","")!=id)return Data.EMPTY
            return workDataOf("done" to p.getInt("done",0),"total" to p.getInt("total",0),"found" to p.getInt("found",0),"missing" to p.getInt("missing",0),"errors" to p.getInt("errors",0),"lastError" to p.getString("lastError","").orEmpty())
        }
        fun start(c:Context) {
            val request=OneTimeWorkRequestBuilder<BatchWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR,10,java.util.concurrent.TimeUnit.SECONDS).build()
            WorkManager.getInstance(c).enqueueUniqueWork(NAME,ExistingWorkPolicy.KEEP,request)
        }
    }
}
