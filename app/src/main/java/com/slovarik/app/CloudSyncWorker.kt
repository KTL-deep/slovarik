package com.slovarik.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

class CloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (CloudSyncRuntime.configuration(applicationContext) == null) return Result.success()
        try {
            return CloudSyncRuntime.mutex.withLock {
                withContext(Dispatchers.IO) {
                    Store(applicationContext).use { store ->
                        val bound = SyncLocalStore(store).boundAccount() ?: return@use Result.success()
                        val auth = CloudSyncRuntime.auth(applicationContext)
                        val uid = auth.currentUser?.uid ?: return@use Result.success()
                        if (uid != bound) {
                            auth.signOut()
                            return@use Result.success()
                        }
                        if (withTimeout(90_000) { CloudSyncRuntime.sync(applicationContext, store, uid) }) Result.success() else Result.retry()
                    }
                }
            }
        } catch (_: TimeoutCancellationException) { return if (runAttemptCount >= 5) Result.success() else Result.retry() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            return if (CloudSyncRuntime.permanent(error) || runAttemptCount >= 5) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "cloud_sync_periodic"
        private const val ONCE = "cloud_sync_once"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(network).build())
        }

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CloudSyncWorker>().setConstraints(network)
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(ONCE)
        }
    }
}
