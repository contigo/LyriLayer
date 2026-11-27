package com.chaoseye.lyrilayer

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.UUID

class BatchWorker(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result {
        val session=inputData.getString(SESSION_KEY)
        if(!isSessionActive(session))return Result.failure(workDataOf("lastError" to "수집 중단 · 앱에서 다시 시작하세요"))
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
        val retryUris=if(same)prefs.getStringSet("retryUris",emptySet()).orEmpty().toMutableSet() else mutableSetOf<String>()
        var retryDone=if(same)prefs.getInt("retryDone",0) else 0
        val log=if(same)prefs.getString("errorLog","").orEmpty().lines().filter{it.isNotBlank()}.toMutableList() else mutableListOf<String>()
        fun record(t:Track,error:String){lastError=error.take(250);log.add((t.title.take(70)+" · "+lastError).take(180));while(log.size>20)log.removeAt(0)}
        fun snapshot(current:String="",finished:Boolean=false)=workDataOf("done" to done,"total" to tracks.size,"found" to found,"missing" to missing,"errors" to errors,"lastError" to lastError,"current" to current.take(180),"finished" to finished,"retryPending" to retryUris.size,"errorLog" to log.takeLast(15).joinToString("\n"))
        fun checkpoint(){
            prefs.edit().putString("id",id.toString()).putString("fingerprint",fingerprint).putInt("done",done).putInt("total",tracks.size).putInt("found",found).putInt("missing",missing).putInt("errors",errors).putString("lastError",lastError).putStringSet("retryUris",retryUris.toSet()).putInt("retryDone",retryDone).putString("errorLog",log.joinToString("\n")).commit()
        }
        checkpoint()
        setProgress(snapshot())
        val start=SystemClock.elapsedRealtime()
        while(done<tracks.size){
            currentCoroutineContext().ensureActive()
            if(!isSessionActive(session))return Result.failure(snapshot())
            // Continue only within the same explicitly started foreground session.
            if(SystemClock.elapsedRealtime()-start>6*60*1000L){checkpoint();return Result.retry()}
            val t=tracks[done]
            setProgress(snapshot("${t.artist} - ${t.title}"))
            try {
                val l=withTimeout(65_000){Repository.resolve(applicationContext,t)}
                if(l.status=="NOT_FOUND")missing++ else found++
            } catch(e:TimeoutCancellationException){errors++;retryUris.add(t.uri);record(t,"검색 시간 초과")}
            catch(e:CancellationException){throw e}
            catch(e:Exception){errors++;if(e is OnlineSearchException && e.retryable)retryUris.add(t.uri);record(t,e.message.orEmpty())}
            done++
            checkpoint()
            setProgress(snapshot())
        }
        val byUri=tracks.associateBy{it.uri}
        for(uri in retryUris.toList()){
            currentCoroutineContext().ensureActive()
            if(!isSessionActive(session))return Result.failure(snapshot())
            if(SystemClock.elapsedRealtime()-start>6*60*1000L){checkpoint();return Result.retry()}
            val t=byUri[uri]
            if(t==null){retryUris.remove(uri);checkpoint();continue}
            delay(2000)
            setProgress(snapshot("실패 곡 재검색: "+t.title))
            try {
                val l=withTimeout(65_000){Repository.resolve(applicationContext,t,true)}
                errors=(errors-1).coerceAtLeast(0)
                if(l.status=="NOT_FOUND")missing++ else found++
            }catch(e:TimeoutCancellationException){record(t,"재검색 시간 초과")}
            catch(e:CancellationException){throw e}
            catch(e:Exception){record(t,e.message.orEmpty())}
            retryUris.remove(uri);retryDone++;checkpoint();setProgress(snapshot())
        }
        return Result.success(snapshot(finished=true))
    }
    companion object {
        const val NAME="lyrics_batch"
        private const val SESSION_KEY="foreground_session"
        @Volatile private var foregroundSession:String?=null
        fun enterForeground(){if(foregroundSession==null)foregroundSession=UUID.randomUUID().toString()}
        fun leaveForeground(c:Context){
            foregroundSession=null
            WorkManager.getInstance(c.applicationContext).cancelUniqueWork(NAME)
        }
        private fun isSessionActive(session:String?)=session!=null&&session==foregroundSession
        fun savedProgress(c:Context,id:String):Data {
            val p=c.getSharedPreferences("batch_progress",Context.MODE_PRIVATE)
            if(p.getString("id","")!=id)return Data.EMPTY
            return workDataOf("done" to p.getInt("done",0),"total" to p.getInt("total",0),"found" to p.getInt("found",0),"missing" to p.getInt("missing",0),"errors" to p.getInt("errors",0),"lastError" to p.getString("lastError","").orEmpty(),"retryPending" to p.getStringSet("retryUris",emptySet()).orEmpty().size,"errorLog" to p.getString("errorLog","").orEmpty().takeLast(2700))
        }
        fun start(c:Context):Operation {
            val session=checkNotNull(foregroundSession){"앱 화면에서 수집을 시작하세요."}
            val request=OneTimeWorkRequestBuilder<BatchWorker>()
                .setInputData(workDataOf(SESSION_KEY to session))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR,10,java.util.concurrent.TimeUnit.SECONDS).build()
            return WorkManager.getInstance(c).enqueueUniqueWork(NAME,ExistingWorkPolicy.KEEP,request)
        }
    }
}
