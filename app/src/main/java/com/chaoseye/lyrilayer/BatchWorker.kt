package com.chaoseye.lyrilayer

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*

class BatchWorker(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result {
        val tracks=try { withContext(Dispatchers.IO) { Library.scan(applicationContext) } } catch(e: SecurityException) { return Result.failure() }
        var found=0; var missing=0; var errors=0
        for((i,t) in tracks.withIndex()) {
            ensureActive()
            try { val l=Repository.resolve(applicationContext,t); if(l.status=="NOT_FOUND") missing++ else found++ }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { errors++; if(errors>=3) return Result.retry() }
            setProgress(workDataOf("done" to i+1,"total" to tracks.size,"found" to found,"missing" to missing,"errors" to errors))
        }
        return if(errors>0) Result.retry() else Result.success(workDataOf("done" to tracks.size,"total" to tracks.size,"found" to found,"missing" to missing))
    }
    private suspend fun ensureActive() { currentCoroutineContext().ensureActive() }
    companion object {
        const val NAME="lyrics_batch"
        fun start(c: Context) {
            val request=OneTimeWorkRequestBuilder<BatchWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(c).enqueueUniqueWork(NAME,ExistingWorkPolicy.KEEP,request)
        }
    }
}
