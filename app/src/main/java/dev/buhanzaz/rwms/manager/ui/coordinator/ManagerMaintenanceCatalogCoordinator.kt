package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.auth.ManagerAuthState
import dev.buhanzaz.rwms.manager.network.CatalogLinkDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import java.time.ZonedDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 */

/** Read-only catalog capability required by maintenance and inventory workflows. */
internal interface ManagerMaintenanceCatalogAccess {
    val nodesById: Map<String, CatalogNodeDto>

    /** Restores or refreshes the catalog only for the active verified workspace. */
    suspend fun ensureMaintenanceCatalog(warehouseId: String)

    /** Restores an encrypted snapshot only when it belongs to the current account and warehouse. */
    suspend fun restoreMaintenanceCatalogFromDisk(warehouseId: String): Boolean
}

/** Lifecycle commands through which the workspace owns catalog freshness scheduling. */
internal interface ManagerMaintenanceCatalogWorkspacePort {
    /** Binds disk access to the account established by the authoritative `/me` response. */
    fun activateVerifiedAccount(ownerAccountId: String)

    /** Cancels catalog work and removes the in-memory principal identity on session teardown. */
    fun clearVerifiedAccount()

    /** Cancels refresh jobs and drops all account-derived catalog values from memory. */
    fun clearMemory()

    /** Restores an encrypted snapshot only after account and warehouse verification. */
    suspend fun restoreMaintenanceCatalogFromDisk(warehouseId: String): Boolean

    /** Starts the single refresh scheduler for the current verified workspace. */
    fun startMaintenanceCatalogScheduler()

    /** Requests an immediate conditional refresh for the current verified workspace. */
    fun requestMaintenanceCatalogSync()

    /** Requests the scheduled refresh only when its encrypted attempt slot is due. */
    fun requestMaintenanceCatalogSyncIfDue()
}

/**
 * Owns the active maintenance catalog snapshot, its disk cache and scheduled freshness checks.
 * An open editor is intentionally not mutated when a newer catalog becomes available.
 */
internal class ManagerMaintenanceCatalogCoordinator(
    private val runtime: ManagerCommandRuntime,
    private val backend: RwmsBackend,
    private val maintenanceCatalogCache: MaintenanceCatalogCache,
) : ManagerMaintenanceCatalogAccess, ManagerMaintenanceCatalogWorkspacePort {
    private val mutableState
        get() = runtime.mutableState
    private val viewModelScope
        get() = runtime.scope
    private val maintenanceCatalogMutex = Mutex()
    private var verifiedAccountId: String? = null
    private var maintenanceCatalogWarehouseId: String? = null
    private var maintenanceCatalogNodesById: Map<String, CatalogNodeDto> = emptyMap()
    private var maintenanceCatalogRevision: ActiveMaintenanceCatalogRevision? = null
    private var maintenanceCatalogActiveVersionEtag: String? = null
    private var maintenanceCatalogSyncJob: Job? = null
    private var maintenanceCatalogSchedulerJob: Job? = null

    override val nodesById: Map<String, CatalogNodeDto>
        get() = maintenanceCatalogNodesById

    private fun command(block: suspend () -> Unit) = runtime.command(block)

    private fun requireWarehouseId(): String = runtime.requireWarehouseId()

    private fun requireVerifiedAccountId(): String = requireNotNull(verifiedAccountId) {
        "Кэш каталога недоступен до проверки учётной записи"
    }

    override fun activateVerifiedAccount(ownerAccountId: String) {
        require(ownerAccountId.isNotBlank()) { "Не указан владелец кэша каталога" }
        if (verifiedAccountId != ownerAccountId) clearMemory()
        verifiedAccountId = ownerAccountId
    }

    override fun clearVerifiedAccount() {
        clearMemory()
        verifiedAccountId = null
    }

    /**
     * A foregrounded app first checks the small active-version projection. If the catalog has
     * changed, it reloads the snapshot; an open editor is left untouched and receives the
     * explicit refresh action instead.
     */
    fun checkMaintenanceCatalogOnResume() {
        if (mutableState.value.authState != ManagerAuthState.SignedIn ||
            verifiedAccountId == null
        ) {
            return
        }
        startMaintenanceCatalogScheduler()
        requestMaintenanceCatalogSync()
    }

    fun refreshMaintenanceCatalog() = command {
        val ownerAccountId = requireVerifiedAccountId()
        val warehouseId = requireWarehouseId()
        maintenanceCatalogMutex.withLock {
            val catalog = fetchMaintenanceCatalog(warehouseId)
            if (!isCurrentScope(ownerAccountId, warehouseId)) return@withLock
            persistMaintenanceCatalog(ownerAccountId, warehouseId, catalog)
            maintenanceCatalogCache.markAttemptSlot(
                ownerAccountId = ownerAccountId,
                warehouseId = warehouseId,
                slot = maintenanceCatalogSyncSlot(ZonedDateTime.now()),
            )
            applyMaintenanceCatalog(ownerAccountId, warehouseId, catalog)
        }
    }

    override suspend fun ensureMaintenanceCatalog(warehouseId: String) {
        val ownerAccountId = requireVerifiedAccountId()
        maintenanceCatalogMutex.withLock {
            if (!isCurrentScope(ownerAccountId, warehouseId)) return@withLock
            restoreMaintenanceCatalogFromDisk(warehouseId)
            val needsInitialCatalog = shouldReloadMaintenanceCatalog(
                requestedWarehouseId = warehouseId,
                cachedWarehouseId = maintenanceCatalogWarehouseId,
                cachedRevision = maintenanceCatalogRevision,
                cachedNodeCount = maintenanceCatalogNodesById.size,
            )
            val activeRevision = if (needsInitialCatalog) {
                fetchActiveMaintenanceCatalogRevision(
                    warehouseId = warehouseId,
                    ifNoneMatch = null,
                    cachedRevision = null,
                )
            } else {
                try {
                    fetchActiveMaintenanceCatalogRevision(
                        warehouseId = warehouseId,
                        ifNoneMatch = maintenanceCatalogActiveVersionEtag,
                        cachedRevision = maintenanceCatalogRevision,
                    )
                } catch (failure: Throwable) {
                    if (canUseCachedReadAfter(failure)) {
                        // A valid local snapshot remains usable while the device is offline.
                        return@withLock
                    }
                    throw failure
                }
            }
            if (!needsInitialCatalog && maintenanceCatalogRevision == activeRevision.value) {
                updateMaintenanceCatalogActiveVersionEtag(
                    ownerAccountId,
                    warehouseId,
                    activeRevision.etag,
                )
                return@withLock
            }

            val catalog = fetchMaintenanceCatalog(
                warehouseId = warehouseId,
                revision = activeRevision.value,
                activeVersionEtag = activeRevision.etag,
            )
            if (!isCurrentScope(ownerAccountId, warehouseId)) return@withLock
            persistMaintenanceCatalog(ownerAccountId, warehouseId, catalog)
            maintenanceCatalogCache.markAttemptSlot(
                ownerAccountId = ownerAccountId,
                warehouseId = warehouseId,
                slot = maintenanceCatalogSyncSlot(ZonedDateTime.now()),
            )
            applyMaintenanceCatalogWhenSafe(ownerAccountId, warehouseId, catalog)
        }
        requestMaintenanceCatalogSyncIfDue()
    }

    override suspend fun restoreMaintenanceCatalogFromDisk(warehouseId: String): Boolean {
        if (mutableState.value.selectedWarehouseId != warehouseId) return false
        val ownerAccountId = requireVerifiedAccountId()
        val cached = maintenanceCatalogCache.read(ownerAccountId, warehouseId) ?: return false
        if (!isCurrentScope(ownerAccountId, warehouseId)) return false
        if (cached.warehouseId != warehouseId) return false
        val operationalNodes = cached.nodes.filter(CatalogNodeDto::isOperationalEstimateNode)
        if (operationalNodes.isEmpty()) {
            if (mutableState.value.selectedWarehouseId == warehouseId) {
                maintenanceCatalogCache.clear(ownerAccountId, warehouseId)
            }
            return false
        }

        val needsInitialCatalog = shouldReloadMaintenanceCatalog(
            requestedWarehouseId = warehouseId,
            cachedWarehouseId = maintenanceCatalogWarehouseId,
            cachedRevision = maintenanceCatalogRevision,
            cachedNodeCount = maintenanceCatalogNodesById.size,
        )
        val canReplaceCurrentCatalog = mutableState.value.maintenanceEditor == null
        if (needsInitialCatalog ||
            canReplaceCurrentCatalog && maintenanceCatalogRevision != cached.revision
        ) {
            applyMaintenanceCatalog(
                ownerAccountId = ownerAccountId,
                warehouseId = warehouseId,
                catalog = LoadedMaintenanceCatalog(
                    revision = cached.revision,
                    activeVersionEtag = cached.activeVersionEtag,
                    allNodes = cached.nodes,
                    operationalNodes = operationalNodes,
                    links = cached.links,
                ),
            )
        }
        return true
    }

    private suspend fun persistMaintenanceCatalog(
        ownerAccountId: String,
        warehouseId: String,
        catalog: LoadedMaintenanceCatalog,
    ) {
        if (!isCurrentScope(ownerAccountId, warehouseId)) return
        maintenanceCatalogCache.write(
            CachedMaintenanceCatalog(
                ownerAccountId = ownerAccountId,
                warehouseId = warehouseId,
                revision = catalog.revision,
                activeVersionEtag = catalog.activeVersionEtag,
                nodes = catalog.allNodes,
                links = catalog.links,
            ),
        )
    }

    private fun applyMaintenanceCatalog(
        ownerAccountId: String,
        warehouseId: String,
        catalog: LoadedMaintenanceCatalog,
    ) {
        if (!isCurrentScope(ownerAccountId, warehouseId)) return
        maintenanceCatalogWarehouseId = warehouseId
        maintenanceCatalogNodesById = catalog.allNodes.associateBy(CatalogNodeDto::id)
        maintenanceCatalogRevision = catalog.revision
        maintenanceCatalogActiveVersionEtag = catalog.activeVersionEtag
        mutableState.update {
            it.copy(
                maintenanceCatalogNodes = catalog.allNodes.filter(CatalogNodeDto::active),
                maintenanceCatalogLinks = catalog.links,
                maintenanceCatalogRefreshAvailable = false,
            )
        }
    }

    private fun applyMaintenanceCatalogWhenSafe(
        ownerAccountId: String,
        warehouseId: String,
        catalog: LoadedMaintenanceCatalog,
    ) {
        if (!isCurrentScope(ownerAccountId, warehouseId)) return
        val editorIsOpen = mutableState.value.maintenanceEditor != null
        if (editorIsOpen &&
            maintenanceCatalogRevision != null &&
            maintenanceCatalogRevision != catalog.revision
        ) {
            mutableState.update {
                it.copy(maintenanceCatalogRefreshAvailable = true)
            }
        } else {
            applyMaintenanceCatalog(ownerAccountId, warehouseId, catalog)
        }
    }

    override fun requestMaintenanceCatalogSync() {
        val current = mutableState.value
        val warehouseId = current.selectedWarehouseId ?: return
        val ownerAccountId = verifiedAccountId ?: return
        if (current.authState != ManagerAuthState.SignedIn) return
        if (maintenanceCatalogSyncJob?.isActive == true) return
        maintenanceCatalogSyncJob = viewModelScope.launch {
            syncMaintenanceCatalog(ownerAccountId, warehouseId, scheduled = false)
        }
    }

    override fun requestMaintenanceCatalogSyncIfDue() {
        val current = mutableState.value
        val warehouseId = current.selectedWarehouseId ?: return
        val ownerAccountId = verifiedAccountId ?: return
        if (current.authState != ManagerAuthState.SignedIn) return
        if (maintenanceCatalogSyncJob?.isActive == true) return
        maintenanceCatalogSyncJob = viewModelScope.launch {
            if (!shouldAttemptMaintenanceCatalogSync(
                    lastAttemptSlot = maintenanceCatalogCache.lastAttemptSlot(
                        ownerAccountId,
                        warehouseId,
                    ),
                    now = ZonedDateTime.now(),
                )
            ) {
                return@launch
            }
            syncMaintenanceCatalog(ownerAccountId, warehouseId, scheduled = true)
        }
    }

    private suspend fun syncMaintenanceCatalog(
        ownerAccountId: String,
        warehouseId: String,
        scheduled: Boolean,
    ) {
        maintenanceCatalogMutex.withLock {
            if (!isCurrentScope(ownerAccountId, warehouseId)) return@withLock
            val now = ZonedDateTime.now()
            if (scheduled && !shouldAttemptMaintenanceCatalogSync(
                    lastAttemptSlot = maintenanceCatalogCache.lastAttemptSlot(
                        ownerAccountId,
                        warehouseId,
                    ),
                    now = now,
                )
            ) {
                return@withLock
            }

            if (scheduled) {
                // Record the slot before the request so a failed scheduled refresh does not
                // hammer the gateway. Foreground checks remain available after that failure.
                maintenanceCatalogCache.markAttemptSlot(
                    ownerAccountId = ownerAccountId,
                    warehouseId = warehouseId,
                    slot = maintenanceCatalogSyncSlot(now),
                )
            }
            val activeRevision = try {
                fetchActiveMaintenanceCatalogRevision(
                    warehouseId = warehouseId,
                    ifNoneMatch = maintenanceCatalogActiveVersionEtag,
                    cachedRevision = maintenanceCatalogRevision,
                )
            } catch (_: Throwable) {
                return@withLock
            }
            if (!isCurrentScope(ownerAccountId, warehouseId)) return@withLock
            if (maintenanceCatalogRevision == activeRevision.value) {
                updateMaintenanceCatalogActiveVersionEtag(
                    ownerAccountId,
                    warehouseId,
                    activeRevision.etag,
                )
                return@withLock
            }

            val catalog = try {
                fetchMaintenanceCatalog(
                    warehouseId = warehouseId,
                    revision = activeRevision.value,
                    activeVersionEtag = activeRevision.etag,
                )
            } catch (_: Throwable) {
                return@withLock
            }
            if (!isCurrentScope(ownerAccountId, warehouseId)) return@withLock
            persistMaintenanceCatalog(ownerAccountId, warehouseId, catalog)
            applyMaintenanceCatalogWhenSafe(ownerAccountId, warehouseId, catalog)
        }
    }

    override fun startMaintenanceCatalogScheduler() {
        if (mutableState.value.authState != ManagerAuthState.SignedIn ||
            mutableState.value.selectedWarehouseId == null ||
            verifiedAccountId == null ||
            maintenanceCatalogSchedulerJob?.isActive == true
        ) {
            return
        }
        maintenanceCatalogSchedulerJob = viewModelScope.launch {
            while (isActive) {
                delay(millisUntilNextMaintenanceCatalogSync(ZonedDateTime.now()))
                requestMaintenanceCatalogSyncIfDue()
            }
        }
    }

    private suspend fun fetchActiveMaintenanceCatalogRevision(
        warehouseId: String,
        ifNoneMatch: String?,
        cachedRevision: ActiveMaintenanceCatalogRevision?,
    ): ConditionalRead<ActiveMaintenanceCatalogRevision> {
        val response = backend.api.catalogVersions(
            warehouseId = warehouseId,
            page = 0,
            size = 200,
            lifecycle = "ACTIVE",
            ifNoneMatch = ifNoneMatch,
        )
        if (response.code() == 304) {
            return ConditionalRead(
                value = requireNotNull(cachedRevision) {
                    "Сервер подтвердил старый каталог, которого нет на телефоне"
                },
                etag = response.headers()["ETag"] ?: ifNoneMatch,
            )
        }
        if (!response.isSuccessful) throw HttpException(response)
        val active = requireNotNull(response.body())
            .items
            .firstOrNull { it.lifecycle == "ACTIVE" }
            ?: throw IllegalStateException(
                "Активный каталог смет и ремонтов ещё не опубликован",
            )
        return ConditionalRead(
            value = ActiveMaintenanceCatalogRevision(active.id, active.version),
            etag = response.headers()["ETag"],
        )
    }

    private suspend fun fetchMaintenanceCatalog(
        warehouseId: String,
    ): LoadedMaintenanceCatalog {
        val active = fetchActiveMaintenanceCatalogRevision(
            warehouseId = warehouseId,
            ifNoneMatch = null,
            cachedRevision = null,
        )
        return fetchMaintenanceCatalog(
            warehouseId = warehouseId,
            revision = active.value,
            activeVersionEtag = active.etag,
        )
    }

    private suspend fun fetchMaintenanceCatalog(
        warehouseId: String,
        revision: ActiveMaintenanceCatalogRevision,
        activeVersionEtag: String?,
    ): LoadedMaintenanceCatalog {
        val nodes = backend.api.catalogNodes(revision.id, warehouseId)
        val links = backend.api.catalogLinks(revision.id, warehouseId)
        val operationalNodes = nodes.filter(CatalogNodeDto::isOperationalEstimateNode)
        if (operationalNodes.isEmpty()) {
            throw IllegalStateException(
                "В активном каталоге нет доступных работ и материалов",
            )
        }
        return LoadedMaintenanceCatalog(
            revision = revision,
            activeVersionEtag = activeVersionEtag,
            allNodes = nodes,
            operationalNodes = operationalNodes,
            links = links,
        )
    }

    private suspend fun updateMaintenanceCatalogActiveVersionEtag(
        ownerAccountId: String,
        warehouseId: String,
        etag: String?,
    ) {
        if (!isCurrentScope(ownerAccountId, warehouseId)) return
        val retained = etag ?: maintenanceCatalogActiveVersionEtag
        if (retained == maintenanceCatalogActiveVersionEtag) return
        maintenanceCatalogActiveVersionEtag = retained
        val cached = maintenanceCatalogCache.read(ownerAccountId, warehouseId)
        if (cached?.warehouseId == warehouseId && cached.revision == maintenanceCatalogRevision) {
            maintenanceCatalogCache.write(cached.copy(activeVersionEtag = retained))
        }
    }

    private fun isCurrentScope(ownerAccountId: String, warehouseId: String): Boolean =
        verifiedAccountId == ownerAccountId &&
            mutableState.value.selectedWarehouseId == warehouseId

    /** Drops only in-memory catalog state and jobs; the account-scoped disk snapshot remains intact. */
    override fun clearMemory() {
        maintenanceCatalogSyncJob?.cancel()
        maintenanceCatalogSyncJob = null
        maintenanceCatalogSchedulerJob?.cancel()
        maintenanceCatalogSchedulerJob = null
        maintenanceCatalogWarehouseId = null
        maintenanceCatalogNodesById = emptyMap()
        maintenanceCatalogRevision = null
        maintenanceCatalogActiveVersionEtag = null
    }

    /** One internally consistent catalog revision assembled before cache and UI publication. */
    private data class LoadedMaintenanceCatalog(
        val revision: ActiveMaintenanceCatalogRevision,
        val activeVersionEtag: String?,
        val allNodes: List<CatalogNodeDto>,
        val operationalNodes: List<CatalogNodeDto>,
        val links: List<CatalogLinkDto>,
    )
}
