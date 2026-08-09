package dev.buhanzaz.rwms.manager.uploads

import android.content.Context
import android.util.AtomicFile
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.ExplicitNullJsonAdapterFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Persists the manager upload outbox while enforcing immutable account-and-warehouse ownership
 * on every lookup and mutation. The exposed flow is empty until the workspace activates a scope,
 * so another signed-in principal cannot observe or retry retained work.
 */
class BackgroundUploadStore private constructor(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val rootDirectory by lazy {
        File(applicationContext.filesDir, CURRENT_ROOT_DIRECTORY_NAME)
    }
    private val legacyRootDirectory by lazy {
        File(applicationContext.filesDir, LEGACY_ROOT_DIRECTORY_NAME)
    }
    private val quarantineRootDirectory by lazy {
        File(applicationContext.filesDir, QUARANTINE_ROOT_DIRECTORY_NAME)
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
    private var allOperations: List<BackgroundUploadOperation> = emptyList()
    private val mutableOperations = MutableStateFlow<List<BackgroundUploadOperation>>(emptyList())
    private var activeScope: BackgroundUploadScope? = null

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
                quarantineLegacyOwnerlessStorage()
                rootDirectory.mkdirs()
                allOperations = readDocument().operations
                publishVisibleOperations()
                initialized = true
            }
        }
    }

    /** Publishes only operations owned by the currently verified workspace scope. */
    fun activateScope(scope: BackgroundUploadScope?) {
        requireInitialized()
        synchronized(lock) {
            activeScope = scope
            publishVisibleOperations()
        }
    }

    /** Returns an immutable snapshot containing only rows owned by [scope]. */
    fun operations(scope: BackgroundUploadScope): List<BackgroundUploadOperation> {
        requireInitialized()
        return synchronized(lock) { allOperations.filter { it.belongsTo(scope) } }
    }

    /** Finds an operation only when both its identifier and immutable owner match [scope]. */
    fun operation(
        scope: BackgroundUploadScope,
        id: String,
    ): BackgroundUploadOperation? {
        requireInitialized()
        return synchronized(lock) {
            allOperations.firstOrNull { it.id == id && it.belongsTo(scope) }
        }
    }

    /** Persists a fully scoped operation without replacing another account's same-named row. */
    fun put(operation: BackgroundUploadOperation) {
        require(operation.hasDurableScope()) {
            "Нельзя сохранить фоновую загрузку без проверенного владельца и склада"
        }
        updateAll { current ->
            (current.filterNot {
                it.id == operation.id &&
                    it.ownerAccountId == operation.ownerAccountId &&
                    it.warehouseId == operation.warehouseId
            } + operation)
                .sortedBy(BackgroundUploadOperation::createdAtEpochMillis)
        }
    }

    /**
     * Applies one atomic queue mutation while forbidding changes to the operation identity,
     * account or warehouse.
     */
    fun update(
        scope: BackgroundUploadScope,
        operationId: String,
        transform: (BackgroundUploadOperation) -> BackgroundUploadOperation,
    ): BackgroundUploadOperation? {
        var result: BackgroundUploadOperation? = null
        updateAll { current ->
            current.map { operation ->
                if (operation.id == operationId && operation.belongsTo(scope)) {
                    transform(operation).also { updated ->
                        require(updated.id == operation.id && updated.belongsTo(scope)) {
                            "Владелец, склад и идентификатор фоновой загрузки неизменяемы"
                        }
                        result = updated
                    }
                } else {
                    operation
                }
            }
        }
        return result
    }

    /** Removes one scoped row and its app-private originals without touching another scope. */
    fun remove(
        scope: BackgroundUploadScope,
        operationId: String,
    ) {
        var removed = false
        updateAll { current ->
            current.filterNot { operation ->
                (operation.id == operationId && operation.belongsTo(scope)).also { matched ->
                    removed = removed || matched
                }
            }
        }
        if (removed) operationDirectoryPath(scope, operationId).deleteRecursively()
    }

    /** Returns the hashed, account-and-warehouse-partitioned directory for one operation. */
    fun operationDirectory(
        scope: BackgroundUploadScope,
        operationId: String,
    ): File = requireInitialized().let {
        operationDirectoryPath(scope, operationId).apply { mkdirs() }
    }

    private fun updateAll(
        transform: (List<BackgroundUploadOperation>) -> List<BackgroundUploadOperation>,
    ) {
        requireInitialized()
        synchronized(lock) {
            val updated = transform(allOperations)
            writeDocument(
                BackgroundUploadStoreDocument(
                    schemaVersion = CURRENT_BACKGROUND_UPLOAD_SCHEMA_VERSION,
                    operations = updated,
                ),
            )
            allOperations = updated
            publishVisibleOperations()
        }
    }

    private fun publishVisibleOperations() {
        val scope = activeScope
        mutableOperations.value = if (scope == null) {
            emptyList()
        } else {
            allOperations.filter { it.belongsTo(scope) }
        }
    }

    private fun requireInitialized() {
        check(initialized) {
            "Очередь фоновых загрузок ещё не восстановлена"
        }
    }

    private fun readDocument(): BackgroundUploadStoreDocument {
        if (!stateFile.baseFile.exists()) return emptyDocument()
        val restored = runCatching {
            stateFile.openRead().bufferedReader().use { reader ->
                adapter.fromJson(reader.readText())
            }
        }.getOrNull()
        if (restored == null ||
            restored.schemaVersion != CURRENT_BACKGROUND_UPLOAD_SCHEMA_VERSION ||
            restored.operations.any { !it.hasDurableScope() }
        ) {
            quarantineInvalidCurrentDocument()
            return emptyDocument()
        }
        return restored
    }

    private fun emptyDocument() = BackgroundUploadStoreDocument(
        schemaVersion = CURRENT_BACKGROUND_UPLOAD_SCHEMA_VERSION,
    )

    /**
     * Moves the complete schema-1 root without reading retained media. Its rows have no account
     * owner, so assigning them to whichever user signs in next would be unsafe.
     */
    private fun quarantineLegacyOwnerlessStorage() {
        if (!legacyRootDirectory.exists()) return
        quarantineRootDirectory.mkdirs()
        val target = File(
            quarantineRootDirectory,
            "ownerless-v1-${UUID.randomUUID()}",
        )
        check(legacyRootDirectory.renameTo(target)) {
            "Не удалось изолировать старую очередь фоновых загрузок"
        }
    }

    private fun quarantineInvalidCurrentDocument() {
        val source = stateFile.baseFile
        if (!source.exists()) return
        quarantineRootDirectory.mkdirs()
        check(
            source.renameTo(
                File(
                    quarantineRootDirectory,
                    "invalid-v2-queue-${UUID.randomUUID()}.json",
                ),
            ),
        ) { "Не удалось изолировать повреждённую очередь фоновых загрузок" }
    }

    private fun operationDirectoryPath(
        scope: BackgroundUploadScope,
        operationId: String,
    ): File = File(
        File(File(rootDirectory, OPERATIONS_DIRECTORY_NAME), scope.storageKey()),
        stableHash(operationId),
    )

    private fun BackgroundUploadOperation.hasDurableScope(): Boolean =
        ownerAccountId.isNotBlank() && warehouseId.isNotBlank()

    private fun BackgroundUploadOperation.belongsTo(scope: BackgroundUploadScope): Boolean =
        ownerAccountId == scope.ownerAccountId && warehouseId == scope.warehouseId

    private fun BackgroundUploadScope.storageKey(): String = stableHash(
        "$ownerAccountId\u0000$warehouseId",
    )

    private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

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
        private const val LEGACY_ROOT_DIRECTORY_NAME = "background-uploads"
        private const val CURRENT_ROOT_DIRECTORY_NAME = "background-uploads-v2"
        private const val QUARANTINE_ROOT_DIRECTORY_NAME = "background-uploads-quarantine"
        private const val OPERATIONS_DIRECTORY_NAME = "operations"
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
