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
import kotlin.random.Random

/**
 * Runs one authenticated sync attempt without depending on an Activity.
 * WorkManager performs only explicitly classified transient retries, bounded
 * by [WorkerSyncRetryPolicy], after terminal authorization and protocol
 * outcomes have already stopped the chain. A durable visible conflict finishes
 * the background run and waits for explicit acknowledgement in the task UI.
 */
class WorkerSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    /**
     * Maps one coordinator outcome to a terminal WorkManager result or to one
     * budgeted retry. Cancellation deliberately escapes: WorkManager owns it,
     * and a cancelled operation must not create another attempt.
     */
    override suspend fun doWork(): Result {
        val userId = inputData.getString(KEY_USER_ID) ?: return Result.failure()
        val coordinator = EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerSyncEntryPoint::class.java,
        ).coordinator()
        return when (workerRunDisposition(coordinator.sync(userId), runAttemptCount)) {
            WorkerRunDisposition.SUCCESS -> Result.success()
            WorkerRunDisposition.RETRY -> Result.retry()
            WorkerRunDisposition.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val KEY_USER_ID = "userId"
    }
}

/** Terminal scheduling decision kept separate from Android's opaque WorkManager result type. */
internal enum class WorkerRunDisposition {
    SUCCESS,
    RETRY,
    FAILURE,
}

/**
 * Maps durable sync truth to WorkManager behavior. A persisted conflict is a
 * successful background run because only explicit user acknowledgement may
 * close it; treating it as failure would hide the actionable state in a
 * terminal scheduler result.
 */
internal fun workerRunDisposition(
    outcome: WorkerSyncOutcome,
    runAttemptCount: Int,
): WorkerRunDisposition = when (outcome) {
    WorkerSyncOutcome.Complete,
    is WorkerSyncOutcome.Deferred,
    is WorkerSyncOutcome.Conflict,
    -> WorkerRunDisposition.SUCCESS
    is WorkerSyncOutcome.AuthenticationRequired,
    is WorkerSyncOutcome.UserActionRequired,
    is WorkerSyncOutcome.Failed,
    -> WorkerRunDisposition.FAILURE
    is WorkerSyncOutcome.Retry -> {
        if (WorkerSyncRetryPolicy.shouldUseWorkManagerRetry(runAttemptCount)) {
            WorkerRunDisposition.RETRY
        } else {
            WorkerRunDisposition.FAILURE
        }
    }
}

/**
 * Defines the bounded WorkManager recovery budget for one worker sync job.
 * The jittered backoff seed is persisted on the unique request, while
 * [runAttemptCount][CoroutineWorker.runAttemptCount] caps retry responses so a
 * transient gateway fault cannot create an infinite retry chain.
 */
internal object WorkerSyncRetryPolicy {
    const val MAX_SYNC_ATTEMPTS = 4
    private const val MIN_INITIAL_BACKOFF_MILLIS = 10_000L
    private const val INITIAL_BACKOFF_JITTER_MILLIS = 5_000L

    /** Returns whether WorkManager may schedule a retry after this completed run. */
    fun shouldUseWorkManagerRetry(runAttemptCount: Int): Boolean =
        runAttemptCount in 0 until (MAX_SYNC_ATTEMPTS - 1)

    /**
     * Produces the jittered initial seed for WorkManager's exponential backoff.
     * The random source is injectable for deterministic tests; WorkManager
     * persists the selected value with the request rather than sleeping inside
     * the worker process.
     */
    fun jitteredInitialBackoffMillis(random: Random = Random.Default): Long =
        MIN_INITIAL_BACKOFF_MILLIS + random.nextLong(INITIAL_BACKOFF_JITTER_MILLIS + 1)
}

/**
 * Gives the process-created worker its authenticated sync coordinator without
 * coupling the WorkManager boundary to an Activity or UI lifecycle.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerSyncEntryPoint {
    fun coordinator(): WorkerSyncCoordinator
}

/**
 * Coalesces external refresh demand into one connected worker sync job.
 */
@Singleton
class WorkerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Coalesces a user's sync demand into one network-constrained WorkManager job; a new trigger
     * does not replace a job that is already running or queued.
     */
    fun request(userId: String) {
        val work = OneTimeWorkRequestBuilder<WorkerSyncWorker>()
            .setInputData(androidx.work.workDataOf(WorkerSyncWorker.KEY_USER_ID to userId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkerSyncRetryPolicy.jitteredInitialBackoffMillis(),
                TimeUnit.MILLISECONDS,
            )
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
