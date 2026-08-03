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
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class BackgroundUploadCoordinator(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val store = BackgroundUploadStore.get(applicationContext)
    private val workManager = WorkManager.getInstance(applicationContext)

    val operations: StateFlow<List<BackgroundUploadOperation>> = store.operations

    suspend fun enqueue(draft: BackgroundUploadDraft): String = withContext(Dispatchers.IO) {
        val operationId = UUID.randomUUID().toString()
        val operationDirectory = store.operationDirectory(operationId)
        try {
            val photos = draft.photos.mapIndexed { index, photo ->
                copyPhoto(operationDirectory, index, photo)
            }
            val now = System.currentTimeMillis()
            val operation = BackgroundUploadOperation(
                id = operationId,
                area = draft.area,
                title = draft.title,
                subtitle = draft.subtitle,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                photos = photos,
                inventory = draft.inventory,
                maintenance = draft.maintenance,
                acceptance = draft.acceptance,
                transferArrival = draft.transferArrival,
                returnAction = draft.returnAction,
            )
            store.put(operation)
            schedule(operationId, ExistingWorkPolicy.REPLACE)
            operationId
        } catch (failure: Throwable) {
            operationDirectory.deleteRecursively()
            throw failure
        }
    }

    fun resumePending() {
        store.operations.value
            .filter { it.status != BackgroundUploadStatus.FAILED }
            .forEach { schedule(it.id, ExistingWorkPolicy.KEEP) }
    }

    fun pause() {
        workManager.cancelAllWorkByTag(BackgroundUploadWorker.WORK_TAG)
    }

    fun retry(operationId: String) {
        val now = System.currentTimeMillis()
        val updated = store.update(operationId) { operation ->
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
        } ?: return
        schedule(updated.id, ExistingWorkPolicy.REPLACE)
    }

    fun retryPhoto(operationId: String, photoId: String) {
        val now = System.currentTimeMillis()
        val updated = store.update(operationId) { operation ->
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
        } ?: return
        schedule(updated.id, ExistingWorkPolicy.REPLACE, photoId)
    }

    private fun schedule(
        operationId: String,
        policy: ExistingWorkPolicy,
        photoId: String? = null,
    ) {
        val input = Data.Builder()
            .putString(BackgroundUploadWorker.INPUT_OPERATION_ID, operationId)
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
            .build()
        workManager.enqueueUniqueWork(workName(operationId), policy, request)
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
            rotationDegrees = pending.rotationDegrees,
            lineId = pending.lineId,
        )
    }

    private fun openSource(uri: Uri) = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> File(requireNotNull(uri.path)).inputStream()
        else -> applicationContext.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Не удалось прочитать фотографию")
    }

    companion object {
        private fun workName(operationId: String) = "rwms-background-upload-$operationId"
    }
}
