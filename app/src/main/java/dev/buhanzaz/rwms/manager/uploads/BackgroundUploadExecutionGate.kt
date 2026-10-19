package dev.buhanzaz.rwms.manager.uploads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Bounds concurrent cabin uploads while retaining one execution per durable operation. Registered
 * workers include permit waiters and remain registered through authentication cleanup, so the
 * workspace can cancel scheduled work and await every old-session worker before replacing auth.
 */
internal class BackgroundUploadExecutionGate(
    parallelism: Int = BACKGROUND_UPLOAD_OPERATION_PARALLELISM,
) {
    private val permits = Semaphore(parallelism)
    private val finalizationMutex = Mutex()
    private val lock = Any()
    private val operations = mutableMapOf<OperationKey, OperationEntry>()
    private var registeredWorkers = 0
    private var idle = CompletableDeferred(Unit)

    /** Includes queued retries and [onFinished] in the lifetime observed by [awaitIdle]. */
    suspend fun <T> execute(
        scope: BackgroundUploadScope,
        operationId: String,
        onFinished: () -> Unit,
        action: suspend () -> T,
    ): T {
        val key = OperationKey(scope, operationId)
        val entry = synchronized(lock) {
            if (registeredWorkers == 0) idle = CompletableDeferred()
            registeredWorkers += 1
            operations.getOrPut(key, ::OperationEntry).also { it.users += 1 }
        }
        try {
            return entry.mutex.withLock {
                permits.withPermit {
                    currentCoroutineContext().ensureActive()
                    action()
                }
            }
        } finally {
            try {
                onFinished()
            } finally {
                val completed = synchronized(lock) {
                    entry.users -= 1
                    val operationCompleted = if (entry.users == 0) {
                        operations.remove(key)?.idle
                    } else {
                        null
                    }
                    registeredWorkers -= 1
                    operationCompleted to idle.takeIf { registeredWorkers == 0 }
                }
                completed.first?.complete(Unit)
                completed.second?.complete(Unit)
            }
        }
    }

    /** Serializes final command preflight and writes that may share an inventory session fence. */
    suspend fun <T> finalize(action: suspend () -> T): T =
        finalizationMutex.withLock { action() }

    /** Call after cancelling scheduled workers while the workspace prevents new scheduling. */
    suspend fun awaitIdle() {
        while (true) {
            val pending = synchronized(lock) {
                if (registeredWorkers == 0) return
                idle
            }
            pending.await()
        }
    }

    /** Joins one cancelled operation without waiting for other cabins that are still uploading. */
    suspend fun awaitOperationIdle(scope: BackgroundUploadScope, operationId: String) {
        val key = OperationKey(scope, operationId)
        while (true) {
            val pending = synchronized(lock) { operations[key]?.idle } ?: return
            pending.await()
        }
    }

    /** Uses the immutable account/warehouse pair as part of duplicate-operation exclusion. */
    private data class OperationKey(
        val scope: BackgroundUploadScope,
        val operationId: String,
    )

    /** Keeps one mutex alive until its executing worker and every queued replacement have left. */
    private class OperationEntry {
        val mutex = Mutex()
        val idle = CompletableDeferred<Unit>()
        var users = 0
    }
}

internal const val BACKGROUND_UPLOAD_OPERATION_PARALLELISM = 3
