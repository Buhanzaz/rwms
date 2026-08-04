package dev.buhanzaz.rwms.manager.ui

import android.content.Context
import android.util.AtomicFile
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.EstimateDto
import dev.buhanzaz.rwms.manager.network.ExplicitNullJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.EstimatePageDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventorySessionDto
import dev.buhanzaz.rwms.manager.network.RepairPageDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.TaskBoardSnapshotDto
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Best-effort, account-scoped read snapshots for manager screens.
 *
 * A matching server 304 reuses the snapshot immediately; a transient transport failure can only
 * fall back to the last server-verified snapshot. The cache never becomes business-data
 * authority: every command is still sent to the owning service with its concurrency controls.
 */
internal class ManagerReadCache(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    /**
     * Creating reflective Moshi adapters, hashing cache keys and reading AtomicFile snapshots are
     * all deferred until a caller enters the IO-dispatched API below. This keeps ViewModel
     * construction and all UI-thread calls free of cold-start cache work.
     */
    private val storage by lazy {
        Storage(File(applicationContext.filesDir, DIRECTORY_NAME))
    }

    suspend fun readMaintenance(scope: ManagerReadCacheScope): CachedMaintenanceRead? =
        withContext(Dispatchers.IO) { storage.readMaintenance(scope) }

    suspend fun writeMaintenance(scope: ManagerReadCacheScope, value: CachedMaintenanceRead) {
        withContext(Dispatchers.IO) { storage.writeMaintenance(scope, value) }
    }

    suspend fun readRepairQueue(scope: ManagerReadCacheScope): CachedRepairQueueRead? =
        withContext(Dispatchers.IO) { storage.readRepairQueue(scope) }

    suspend fun writeRepairQueue(scope: ManagerReadCacheScope, value: CachedRepairQueueRead) {
        withContext(Dispatchers.IO) { storage.writeRepairQueue(scope, value) }
    }

    suspend fun readInventory(scope: ManagerReadCacheScope): CachedInventoryRead? =
        withContext(Dispatchers.IO) { storage.readInventory(scope) }

    suspend fun writeInventory(scope: ManagerReadCacheScope, value: CachedInventoryRead) {
        withContext(Dispatchers.IO) { storage.writeInventory(scope, value) }
    }

    internal suspend fun clear(scope: ManagerReadCacheScope) {
        withContext(Dispatchers.IO) { storage.clear(scope) }
    }

    private class Storage(
        private val directory: File,
    ) {
        private val moshi = Moshi.Builder()
            .add(ExplicitNullJsonAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
        private val maintenanceAdapter =
            moshi.adapter(CachedMaintenanceRead::class.java).serializeNulls()
        private val repairQueueAdapter =
            moshi.adapter(CachedRepairQueueRead::class.java).serializeNulls()
        private val inventoryAdapter =
            moshi.adapter(CachedInventoryRead::class.java).serializeNulls()

        fun readMaintenance(scope: ManagerReadCacheScope): CachedMaintenanceRead? =
            read(scope, MAINTENANCE_FILE, maintenanceAdapter)

        fun writeMaintenance(scope: ManagerReadCacheScope, value: CachedMaintenanceRead) {
            write(scope, MAINTENANCE_FILE, maintenanceAdapter, value)
        }

        fun readRepairQueue(scope: ManagerReadCacheScope): CachedRepairQueueRead? =
            read(scope, REPAIR_QUEUE_FILE, repairQueueAdapter)

        fun writeRepairQueue(scope: ManagerReadCacheScope, value: CachedRepairQueueRead) {
            write(scope, REPAIR_QUEUE_FILE, repairQueueAdapter, value)
        }

        fun readInventory(scope: ManagerReadCacheScope): CachedInventoryRead? =
            read(scope, INVENTORY_FILE, inventoryAdapter)

        fun writeInventory(scope: ManagerReadCacheScope, value: CachedInventoryRead) {
            write(scope, INVENTORY_FILE, inventoryAdapter, value)
        }

        fun clear(scope: ManagerReadCacheScope) {
            FILE_NAMES.forEach { name -> file(scope, name).delete() }
        }

        private fun <T> read(
            scope: ManagerReadCacheScope,
            name: String,
            adapter: JsonAdapter<T>,
        ): T? {
            val atomic = AtomicFile(file(scope, name))
            val payload = runCatching {
                atomic.openRead().use { input ->
                    input.readBytes().toString(StandardCharsets.UTF_8)
                }
            }.getOrNull() ?: return null
            return runCatching { adapter.fromJson(payload) }.getOrNull()
        }

        private fun <T> write(
            scope: ManagerReadCacheScope,
            name: String,
            adapter: JsonAdapter<T>,
            value: T,
        ) {
            val payload = runCatching { adapter.toJson(value) }.getOrNull() ?: return
            if (!directory.exists() && !directory.mkdirs()) return
            val atomic = AtomicFile(file(scope, name))
            val output = runCatching { atomic.startWrite() }.getOrNull() ?: return
            try {
                output.write(payload.toByteArray(StandardCharsets.UTF_8))
                atomic.finishWrite(output)
            } catch (_: Throwable) {
                atomic.failWrite(output)
            }
        }

        private fun file(scope: ManagerReadCacheScope, name: String): File = File(
            directory,
            "$name-${scope.accountId.safeFilePart()}-${scope.warehouseId.safeFilePart()}.json",
        )

        private fun String.safeFilePart(): String = MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }

    private companion object {
        const val DIRECTORY_NAME = "manager-read-cache"
        const val MAINTENANCE_FILE = "maintenance"
        const val REPAIR_QUEUE_FILE = "repair-queue"
        const val INVENTORY_FILE = "inventory"
        val FILE_NAMES = listOf(MAINTENANCE_FILE, REPAIR_QUEUE_FILE, INVENTORY_FILE)
    }
}

internal data class ManagerReadCacheScope(
    val accountId: String,
    val warehouseId: String,
)

internal data class CachedMaintenanceRead(
    val estimates: EstimatePageDto? = null,
    val estimatesEtag: String? = null,
    val repairs: RepairPageDto? = null,
    val repairsEtag: String? = null,
    val assetLabels: Map<String, String> = emptyMap(),
)

internal data class CachedRepairQueueRead(
    val rootSnapshot: TaskBoardSnapshotDto,
    val rootEtag: String? = null,
    val snapshots: List<TaskBoardSnapshotDto> = emptyList(),
    val etagsByDate: Map<String, String> = emptyMap(),
)

internal data class CachedInventoryRead(
    val activeEtag: String? = null,
    val rentalItems: List<RentalItemDto> = emptyList(),
    val session: InventorySessionDto? = null,
    val findings: List<InventoryFindingDto> = emptyList(),
)
