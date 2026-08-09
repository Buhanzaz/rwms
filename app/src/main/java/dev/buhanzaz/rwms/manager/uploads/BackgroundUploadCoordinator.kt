package dev.buhanzaz.rwms.manager.uploads

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.system.Os
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the verified workspace scope for durable uploads. Every queue mutation and WorkManager
 * schedule is serialized with scope replacement, so logout can await cancellation before the
 * shared OAuth repository is cleared or another account signs in.
 */
class BackgroundUploadCoordinator(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val store = BackgroundUploadStore.get(applicationContext)
    private val workManager = WorkManager.getInstance(applicationContext)
    private val lifecycleMutex = Mutex()
    private var activeScope: BackgroundUploadScope? = null

    val operations: StateFlow<List<BackgroundUploadOperation>> = store.operations

    /** Restores and, when necessary, quarantines the durable queue before it can be observed. */
    suspend fun initialize() {
        store.initialize()
    }

    /**
     * Replaces the active scope only after `/me` verified [ownerAccountId] and the workspace
     * selected [warehouseId]. Existing work is cancelled and joined before the replacement is
     * visible, preventing an old worker from crossing the session boundary.
     */
    suspend fun activateVerifiedScope(
        ownerAccountId: String,
        warehouseId: String,
    ) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            store.initialize()
            val next = BackgroundUploadScope(ownerAccountId, warehouseId)
            if (activeScope == next) {
                BackgroundUploadDraftScopeRegistry.replace(next)
                store.activateScope(next)
                return@withLock
            }
            activeScope = null
            BackgroundUploadDraftScopeRegistry.replace(null)
            store.activateScope(null)
            cancelAllWorkAndAwait()
            activeScope = next
            BackgroundUploadDraftScopeRegistry.replace(next)
            store.activateScope(next)
        }
    }

    /**
     * Copies selected originals into the durable app-private queue before scheduling one unique
     * connected worker, so process death cannot turn a chosen photo into an untracked command.
     */
    suspend fun enqueue(draft: BackgroundUploadDraft): String = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            store.initialize()
            val scope = requireActiveScope()
            val scopedDraft = draft.bindTo(scope)
            val operationId = UUID.randomUUID().toString()
            val operationDirectory = store.operationDirectory(scope, operationId)
            var persisted = false
            try {
                val photos = scopedDraft.photos.mapIndexed { index, photo ->
                    copyPhoto(operationDirectory, index, photo)
                }
                val now = System.currentTimeMillis()
                val operation = BackgroundUploadOperation(
                    id = operationId,
                    area = scopedDraft.area,
                    title = scopedDraft.title,
                    subtitle = scopedDraft.subtitle,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    photos = photos,
                    inventory = scopedDraft.inventory,
                    maintenance = scopedDraft.maintenance,
                    acceptance = scopedDraft.acceptance,
                    transferArrival = scopedDraft.transferArrival,
                    returnAction = scopedDraft.returnAction,
                    ownerAccountId = scope.ownerAccountId,
                    warehouseId = scope.warehouseId,
                )
                store.put(operation)
                persisted = true
                schedule(scope, operationId, ExistingWorkPolicy.REPLACE)
                operationId
            } catch (failure: Throwable) {
                if (persisted) {
                    store.remove(scope, operationId)
                } else {
                    operationDirectory.deleteRecursively()
                }
                throw failure
            }
        }
    }

    /** Re-schedules only non-failed entries in the already verified active scope. */
    suspend fun resumePending() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            store.initialize()
            val scope = requireActiveScope()
            store.operations(scope)
                .filter { it.status != BackgroundUploadStatus.FAILED }
                .forEach { schedule(scope, it.id, ExistingWorkPolicy.KEEP) }
        }
    }

    /**
     * Hides the queue immediately, then waits for every manager upload worker to run its
     * cancellation/finally path before the caller clears or replaces authentication state.
     */
    suspend fun pause() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            store.initialize()
            activeScope = null
            BackgroundUploadDraftScopeRegistry.replace(null)
            store.activateScope(null)
            cancelAllWorkAndAwait()
        }
    }

    /** Requeues a failed operation only inside the currently verified scope. */
    suspend fun retry(operationId: String) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            store.initialize()
            val scope = requireActiveScope()
            val now = System.currentTimeMillis()
            val updated = store.update(scope, operationId) { operation ->
                operation.copy(
                    updatedAtEpochMillis = now,
                    status = BackgroundUploadStatus.QUEUED,
                    stage = "Ожидание сети",
                    error = null,
                    photos = operation.photos.map { photo ->
                        if (photo.status == BackgroundPhotoStatus.FAILED) {
                            photo.copy(status = BackgroundPhotoStatus.QUEUED, error = null)
                        } else {
                            photo
                        }
                    },
                )
            } ?: return@withLock
            schedule(scope, updated.id, ExistingWorkPolicy.REPLACE)
        }
    }

    /** Requeues one failed photo without granting access to an operation in another scope. */
    suspend fun retryPhoto(operationId: String, photoId: String) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            store.initialize()
            val scope = requireActiveScope()
            val now = System.currentTimeMillis()
            val updated = store.update(scope, operationId) { operation ->
                operation.copy(
                    updatedAtEpochMillis = now,
                    status = BackgroundUploadStatus.QUEUED,
                    stage = "Ожидание сети",
                    error = null,
                    photos = operation.photos.map { photo ->
                        if (photo.id == photoId) {
                            photo.copy(status = BackgroundPhotoStatus.QUEUED, error = null)
                        } else {
                            photo
                        }
                    },
                )
            } ?: return@withLock
            schedule(scope, updated.id, ExistingWorkPolicy.REPLACE, photoId)
        }
    }

    /**
     * Requeues only a maintenance completion that the server explicitly stopped because the
     * returning cabin has no recorded furniture composition. A changed command receives a new
     * idempotency key; the rejected `false` request must never be replayed with a new body.
     */
    suspend fun confirmUnaccountedFurniture(operationId: String) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            store.initialize()
            val scope = requireActiveScope()
            val now = System.currentTimeMillis()
            var confirmed = false
            val updated = store.update(scope, operationId) { operation ->
                val maintenance = operation.maintenance
                if (
                    maintenance == null ||
                        !maintenance.requiresUnaccountedFurnitureConfirmation ||
                        maintenance.submitRequest == null
                ) {
                    operation
                } else {
                    confirmed = true
                    operation.copy(
                        updatedAtEpochMillis = now,
                        status = BackgroundUploadStatus.QUEUED,
                        stage = "Ожидание сети",
                        error = null,
                        maintenance = maintenance.copy(
                            allowUnaccountedFurniture = true,
                            requiresUnaccountedFurnitureConfirmation = false,
                            submitIdempotencyKey = UUID.randomUUID().toString(),
                        ),
                    )
                }
            } ?: return@withLock
            if (confirmed) {
                schedule(scope, updated.id, ExistingWorkPolicy.REPLACE)
            }
        }
    }

    /**
     * Stops only this device's durable upload outbox entry. This never calls RWMS, so media or
     * a final command already accepted by the server remain there. Waiting for WorkManager's
     * cancellation operation before deleting the local originals prevents a queued worker from
     * being started after its outbox entry has been removed.
     */
    suspend fun cancel(operationId: String) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            store.initialize()
            val scope = requireActiveScope()
            if (store.operation(scope, operationId) == null) return@withLock
            workManager.cancelUniqueWork(workName(scope, operationId)).result.get()
            BackgroundUploadWorker.awaitIdle()
            store.remove(scope, operationId)
        }
    }

    private suspend fun schedule(
        scope: BackgroundUploadScope,
        operationId: String,
        policy: ExistingWorkPolicy,
        photoId: String? = null,
    ) {
        val input = Data.Builder()
            .putString(BackgroundUploadWorker.INPUT_OPERATION_ID, operationId)
            .putString(BackgroundUploadWorker.INPUT_OWNER_ACCOUNT_ID, scope.ownerAccountId)
            .putString(BackgroundUploadWorker.INPUT_WAREHOUSE_ID, scope.warehouseId)
            .apply { photoId?.let { putString(BackgroundUploadWorker.INPUT_PHOTO_ID, it) } }
            .build()
        val request = OneTimeWorkRequestBuilder<BackgroundUploadWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(BackgroundUploadWorker.WORK_TAG)
            .addTag(workScopeTag(scope))
            .build()
        workManager.enqueueUniqueWork(workName(scope, operationId), policy, request).result.get()
    }

    private fun requireActiveScope(): BackgroundUploadScope = requireNotNull(activeScope) {
        "Фоновые загрузки недоступны до проверки учётной записи и склада"
    }

    private suspend fun cancelAllWorkAndAwait() {
        workManager.cancelAllWorkByTag(BackgroundUploadWorker.WORK_TAG).result.get()
        BackgroundUploadWorker.awaitIdle()
    }

    private fun BackgroundUploadDraft.bindTo(scope: BackgroundUploadScope): BackgroundUploadDraft {
        require(this.scope == scope) {
            "Черновик фоновой загрузки принадлежит другой учётной записи или складу"
        }
        return this
    }

    private fun copyPhoto(
        directory: File,
        index: Int,
        pending: PendingBackgroundPhoto,
    ): BackgroundUploadPhoto {
        val source = Uri.parse(pending.uri)
        val sourceName = source.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
            ?: "Фотография ${index + 1}"
        val safeExtension = sourceName.substringAfterLast('.', "bin")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "bin"
        val photoId = UUID.randomUUID().toString()
        val destination = File(directory, "$photoId.$safeExtension")
        val linked = source.takeIf { it.scheme == ContentResolver.SCHEME_FILE }
            ?.path
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.let { sourceFile ->
                runCatching {
                    // Camera/gallery originals are already app-owned files. A hard link makes
                    // the durable outbox hand-off immediate while preserving the exact EXIF
                    // bytes and keeping the editor's source path intact until it closes.
                    Os.link(sourceFile.absolutePath, destination.absolutePath)
                }.isSuccess
            } == true
        if (!linked) {
            openSource(source).use { input ->
                destination.outputStream().buffered().use(input::copyTo)
            }
        }
        require(destination.length() > 0L) { "Фотография $sourceName пуста" }
        return BackgroundUploadPhoto(
            id = photoId,
            sourceName = sourceName,
            durableUri = Uri.fromFile(destination).toString(),
            owner = pending.owner,
            sortOrder = pending.sortOrder,
            cover = pending.cover,
            lineId = pending.lineId,
        )
    }

    private fun openSource(uri: Uri) = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> File(requireNotNull(uri.path)).inputStream()
        else -> applicationContext.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Не удалось прочитать фотографию")
    }

    companion object {
        internal fun workName(
            scope: BackgroundUploadScope,
            operationId: String,
        ) = "rwms-background-upload-v2-${scope.workKey()}-${stableHash(operationId)}"

        internal fun workScopeTag(scope: BackgroundUploadScope) =
            "rwms-background-upload-scope-${scope.workKey()}"

        private fun BackgroundUploadScope.workKey(): String =
            stableHash("$ownerAccountId\u0000$warehouseId")

        private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
