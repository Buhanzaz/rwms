package dev.buhanzaz.rwms.manager.uploads

import android.content.Context
import android.util.AtomicFile
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.ExplicitNullJsonAdapterFactory
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class BackgroundUploadStore private constructor(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val rootDirectory by lazy {
        File(applicationContext.filesDir, ROOT_DIRECTORY_NAME)
    }
    private val stateFile by lazy {
        AtomicFile(File(rootDirectory, STATE_FILE_NAME))
    }
    private val lock = Any()
    private val adapter by lazy {
        Moshi.Builder()
            .add(ExplicitNullJsonAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
            .adapter(BackgroundUploadStoreDocument::class.java)
    }
    private val mutableOperations = MutableStateFlow<List<BackgroundUploadOperation>>(emptyList())

    @Volatile
    private var initialized = false

    val operations: StateFlow<List<BackgroundUploadOperation>> =
        mutableOperations.asStateFlow()

    /**
     * Restores the durable outbox without making the app's main-thread ViewModel construction
     * pay for reflective adapter creation or disk IO. The operation is idempotent for all
     * callers in this process.
     */
    suspend fun initialize() {
        if (initialized) return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (initialized) return@synchronized
                rootDirectory.mkdirs()
                mutableOperations.value = readDocument().operations
                initialized = true
            }
        }
    }

    fun operation(id: String): BackgroundUploadOperation? {
        requireInitialized()
        return mutableOperations.value.firstOrNull { it.id == id }
    }

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

    fun operationDirectory(operationId: String): File = requireInitialized().let {
        File(rootDirectory, operationId).apply { mkdirs() }
    }

    private fun updateAll(
        transform: (List<BackgroundUploadOperation>) -> List<BackgroundUploadOperation>,
    ) {
        requireInitialized()
        synchronized(lock) {
            val updated = transform(mutableOperations.value)
            writeDocument(BackgroundUploadStoreDocument(operations = updated))
            mutableOperations.value = updated
        }
    }

    private fun requireInitialized() {
        check(initialized) {
            "Очередь фоновых загрузок ещё не восстановлена"
        }
    }

    private fun readDocument(): BackgroundUploadStoreDocument {
        if (!stateFile.baseFile.exists()) return BackgroundUploadStoreDocument()
        return runCatching {
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
