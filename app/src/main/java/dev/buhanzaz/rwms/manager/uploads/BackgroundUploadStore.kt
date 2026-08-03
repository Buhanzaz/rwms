package dev.buhanzaz.rwms.manager.uploads

import android.content.Context
import android.util.AtomicFile
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.ExplicitNullJsonAdapterFactory
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BackgroundUploadStore private constructor(
    context: Context,
) {
    private val rootDirectory = File(context.filesDir, ROOT_DIRECTORY_NAME).apply { mkdirs() }
    private val stateFile = AtomicFile(File(rootDirectory, STATE_FILE_NAME))
    private val adapter = Moshi.Builder()
        .add(ExplicitNullJsonAdapterFactory)
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(BackgroundUploadStoreDocument::class.java)
    private val lock = Any()
    private val mutableOperations = MutableStateFlow(readDocument().operations)

    val operations: StateFlow<List<BackgroundUploadOperation>> =
        mutableOperations.asStateFlow()

    fun operation(id: String): BackgroundUploadOperation? =
        mutableOperations.value.firstOrNull { it.id == id }

    fun put(operation: BackgroundUploadOperation) {
        updateAll { current ->
            (current.filterNot { it.id == operation.id } + operation)
                .sortedBy(BackgroundUploadOperation::createdAtEpochMillis)
        }
    }

    fun update(
        operationId: String,
        transform: (BackgroundUploadOperation) -> BackgroundUploadOperation,
    ): BackgroundUploadOperation? {
        var result: BackgroundUploadOperation? = null
        updateAll { current ->
            current.map { operation ->
                if (operation.id == operationId) {
                    transform(operation).also { result = it }
                } else {
                    operation
                }
            }
        }
        return result
    }

    fun remove(operationId: String) {
        updateAll { current -> current.filterNot { it.id == operationId } }
        operationDirectory(operationId).deleteRecursively()
    }

    fun operationDirectory(operationId: String): File =
        File(rootDirectory, operationId).apply { mkdirs() }

    private fun updateAll(
        transform: (List<BackgroundUploadOperation>) -> List<BackgroundUploadOperation>,
    ) {
        synchronized(lock) {
            val updated = transform(mutableOperations.value)
            writeDocument(BackgroundUploadStoreDocument(operations = updated))
            mutableOperations.value = updated
        }
    }

    private fun readDocument(): BackgroundUploadStoreDocument = synchronized(lock) {
        if (!stateFile.baseFile.exists()) return@synchronized BackgroundUploadStoreDocument()
        runCatching {
            stateFile.openRead().bufferedReader().use { reader ->
                adapter.fromJson(reader.readText())
            }
        }.getOrNull() ?: BackgroundUploadStoreDocument()
    }

    private fun writeDocument(document: BackgroundUploadStoreDocument) {
        var output: FileOutputStream? = null
        try {
            output = stateFile.startWrite()
            output.write(adapter.toJson(document).toByteArray(Charsets.UTF_8))
            output.flush()
            stateFile.finishWrite(output)
            output = null
        } catch (failure: Throwable) {
            output?.let(stateFile::failWrite)
            throw failure
        }
    }

    companion object {
        private const val ROOT_DIRECTORY_NAME = "background-uploads"
        private const val STATE_FILE_NAME = "queue.json"

        @Volatile
        private var instance: BackgroundUploadStore? = null

        fun get(context: Context): BackgroundUploadStore =
            instance ?: synchronized(this) {
                instance ?: BackgroundUploadStore(context.applicationContext).also {
                    instance = it
                }
            }

        internal fun resetForTests() {
            synchronized(this) { instance = null }
        }
    }
}
