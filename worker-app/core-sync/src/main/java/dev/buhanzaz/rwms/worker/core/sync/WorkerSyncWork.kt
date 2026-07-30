package dev.buhanzaz.rwms.worker.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** WorkManager's process-safe entry point does not depend on an Activity. */
class WorkerSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val userId = inputData.getString(KEY_USER_ID) ?: return Result.failure()
        val coordinator = EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerSyncEntryPoint::class.java,
        ).coordinator()
        return when (val outcome = coordinator.sync(userId)) {
            WorkerSyncOutcome.Complete -> Result.success()
            is WorkerSyncOutcome.Retry -> Result.retry()
            is WorkerSyncOutcome.AuthenticationRequired -> Result.failure()
        }
    }

    companion object {
        const val KEY_USER_ID = "userId"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerSyncEntryPoint {
    fun coordinator(): WorkerSyncCoordinator
}

@Singleton
class WorkerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun request(userId: String) {
        val work = OneTimeWorkRequestBuilder<WorkerSyncWorker>()
            .setInputData(androidx.work.workDataOf(WorkerSyncWorker.KEY_USER_ID to userId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(tag(userId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(userId),
            ExistingWorkPolicy.KEEP,
            work,
        )
    }

    companion object {
        fun uniqueName(userId: String) = "rwms-worker-sync-$userId"
        fun tag(userId: String) = "rwms-worker-sync-user-$userId"
    }
}
