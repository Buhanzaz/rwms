package dev.buhanzaz.rwms.manager.ui

import android.app.Application
import dev.buhanzaz.rwms.manager.auth.ManagerAuthState
import dev.buhanzaz.rwms.manager.network.CurrentUserDto
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.network.WarehouseDto
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** Invalidates editor-only transient searches when the workspace is replaced. */
internal interface ManagerMaintenanceEditorResetPort {
    fun invalidateAssetSearch()
}

/**
 * Owns signed-in workspace bootstrapping, selected warehouse replacement, reachability probing,
 * and background-upload lifecycle transitions. It does not retain a parallel UI state.
 */
internal class ManagerWorkspaceCoordinator(
    private val runtime: ManagerCommandRuntime,
    private val backend: RwmsBackend,
    private val backgroundUploads: Deferred<BackgroundUploadCoordinator>,
    private val preference: WarehousePreference,
    private val commandKeys: StableCommandKeys,
    private val catalogWorkspace: ManagerMaintenanceCatalogWorkspacePort,
    private val editorReset: ManagerMaintenanceEditorResetPort,
) {
    private val mutableState
        get() = runtime.mutableState
    private val viewModelScope
        get() = runtime.scope
    private var connectivityMonitorJob: Job? = null
    private var connectivityProbeJob: Job? = null
    private var backgroundUploadsLifecycleJob: Job? = null

    /**
     * Applies the auth stream transition after the facade has published [ManagerUiState.authState].
     * Keeping this here prevents login/session side effects from leaking into individual screens.
     */
    suspend fun onAuthState(authState: ManagerAuthState) {
        if (authState == ManagerAuthState.SignedIn) {
            pauseBackgroundUploads()
            stopConnectivityMonitor()
            catalogWorkspace.clearVerifiedAccount()
            if (loadWorkspace()) startConnectivityMonitor()
        } else {
            pauseBackgroundUploads()
            stopConnectivityMonitor()
            catalogWorkspace.clearVerifiedAccount()
            editorReset.invalidateAssetSearch()
            mutableState.value = ManagerUiState(authState = authState)
        }
    }

    /**
     * Waits for any previous account's worker and authentication snapshot to close before a new
     * login can replace the encrypted OAuth session.
     */
    fun login(username: String, password: String) {
        viewModelScope.launch {
            try {
                pauseBackgroundUploads()
                backend.auth.login(username, password)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                runtime.handleFailure(failure)
            }
        }
    }

    /**
     * Joins all account-bound background work before revoking and clearing the OAuth session.
     * A failed local shutdown leaves the session intact instead of permitting an unsafe switch.
     */
    fun logout() {
        viewModelScope.launch {
            try {
                pauseBackgroundUploads()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                runtime.handleFailure(failure)
                return@launch
            }
            stopConnectivityMonitor()
            catalogWorkspace.clearVerifiedAccount()
            backend.auth.logout()
            editorReset.invalidateAssetSearch()
            commandKeys.clear()
            mutableState.value = ManagerUiState(authState = ManagerAuthState.SignedOut)
        }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(message = null) }
    }

    fun retryBackgroundUpload(operationId: String) {
        viewModelScope.launch {
            backgroundUploads.await().retry(operationId)
        }
    }

    fun retryBackgroundPhoto(operationId: String, photoId: String) {
        viewModelScope.launch {
            backgroundUploads.await().retryPhoto(operationId, photoId)
        }
    }

    fun confirmUnaccountedFurnitureBackgroundUpload(operationId: String) {
        viewModelScope.launch {
            try {
                backgroundUploads.await().confirmUnaccountedFurniture(operationId)
                runtime.message("Списание без учёта склада подтверждено и добавлено в очередь")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                runtime.handleFailure(failure)
            }
        }
    }

    fun cancelBackgroundUpload(operationId: String) {
        viewModelScope.launch {
            try {
                backgroundUploads.await().cancel(operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                runtime.handleFailure(failure)
            }
        }
    }

    private suspend fun pauseBackgroundUploads() {
        backgroundUploadsLifecycleJob?.cancelAndJoin()
        backgroundUploadsLifecycleJob = null
        backgroundUploads.await().pause()
    }

    fun checkServerConnection() {
        if (mutableState.value.authState != ManagerAuthState.SignedIn ||
            connectivityProbeJob?.isActive == true
        ) {
            return
        }
        connectivityProbeJob = viewModelScope.launch { probeServerConnection() }
    }

    /**
     * Rebinds durable client state to an already verified visible warehouse and resumes only that
     * partition after the previous WorkManager scope has stopped.
     */
    fun selectWarehouse(warehouseId: String) {
        val current = mutableState.value
        if (current.warehouseSelectionLocked ||
            current.warehouses.none { it.id == warehouseId }
        ) {
            return
        }
        val ownerAccountId = current.currentUser?.id ?: return
        backgroundUploadsLifecycleJob?.cancel()
        backgroundUploadsLifecycleJob = viewModelScope.launch {
            try {
                val uploads = backgroundUploads.await()
                uploads.activateVerifiedScope(ownerAccountId, warehouseId)
                if (mutableState.value.currentUser?.id != ownerAccountId ||
                    mutableState.value.authState != ManagerAuthState.SignedIn
                ) {
                    uploads.pause()
                    return@launch
                }
                preference.save(warehouseId)
                commandKeys.clear()
                clearMaintenanceCatalogMemory()
                mutableState.update {
                    it.copy(
                        selectedWarehouseId = warehouseId,
                        inventorySession = null,
                        inventoryFindings = emptyList(),
                        inventoryRentalItems = emptyList(),
                        returns = emptyList(),
                        shipments = emptyList(),
                        selectedShipment = null,
                        shipmentFurnitureReadiness = null,
                        transfers = emptyList(),
                        selectedTransfer = null,
                        transferFurnitureReadiness = null,
                        transferEditor = null,
                        transferArrivalLineId = null,
                        transferPhotoUris = emptyList(),
                        transferReadyMedia = emptyList(),
                        logisticsAssetLabels = emptyMap(),
                        estimates = emptyList(),
                        repairs = emptyList(),
                        repairTaskBoard = null,
                        acceptanceRepairs = emptyList(),
                        acceptanceEditor = null,
                        maintenanceCatalogNodes = emptyList(),
                        maintenanceCatalogLinks = emptyList(),
                        maintenanceCatalogRefreshAvailable = false,
                        maintenanceAssetLabels = emptyMap(),
                        maintenanceReturnMetadata = emptyMap(),
                        maintenanceEditor = null,
                        maintenanceFurnitureEditor = null,
                        assetSearch = "",
                        assetSearchResults = emptyList(),
                        assetSearchBusy = false,
                        assetSearchCompletedQuery = null,
                        assetSearchFailedQuery = null,
                    )
                }
                catalogWorkspace.restoreMaintenanceCatalogFromDisk(warehouseId)
                catalogWorkspace.startMaintenanceCatalogScheduler()
                catalogWorkspace.requestMaintenanceCatalogSyncIfDue()
                uploads.resumePending()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                runtime.handleFailure(failure)
            }
        }
    }

    /**
     * Resolves `/me`, roles and live warehouse grants before binding any durable client state.
     * Returning false leaves uploads and catalog storage unbound and therefore invisible.
     */
    private suspend fun loadWorkspace(): Boolean {
        runtime.setBusy(true)
        try {
            backend.warmUpTransport()
            val user = backend.api.currentUser()
            if (user.principalType != "USER" || user.globalRole !in MANAGER_ROLES) {
                backend.auth.invalidate(
                    "Эта роль не может входить в приложение руководителя",
                )
                return false
            }
            val allWarehouses = backend.api.warehouses().filter(WarehouseDto::active)
            val visible = WarehouseAccessPolicy.visibleWarehouses(user, allWarehouses)
            if (visible.isEmpty()) {
                throw IllegalStateException("Пользователю не назначен доступ ни к одному складу")
            }
            val saved = preference.read()
            val selected = saved?.takeIf { id -> visible.any { it.id == id } }
                ?: visible.first().id
            preference.save(selected)
            catalogWorkspace.activateVerifiedAccount(user.id)
            val uploads = backgroundUploads.await()
            uploads.activateVerifiedScope(user.id, selected)
            mutableState.update {
                it.copy(
                    currentUser = user,
                    warehouses = visible,
                    selectedWarehouseId = selected,
                    warehouseSelectionLocked =
                        !user.warehouseAccessAll && visible.size == 1,
                    serverReachable = true,
                    message = null,
                )
            }
            catalogWorkspace.restoreMaintenanceCatalogFromDisk(selected)
            catalogWorkspace.startMaintenanceCatalogScheduler()
            catalogWorkspace.requestMaintenanceCatalogSyncIfDue()
            uploads.resumePending()
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            runCatching { backgroundUploads.await().pause() }
            catalogWorkspace.clearVerifiedAccount()
            mutableState.update { it.copy(serverReachable = false) }
            runtime.handleFailure(failure)
            return false
        } finally {
            runtime.setBusy(false)
        }
    }

    private fun startConnectivityMonitor() {
        if (mutableState.value.authState != ManagerAuthState.SignedIn ||
            connectivityMonitorJob?.isActive == true
        ) {
            return
        }
        connectivityMonitorJob = viewModelScope.launch {
            while (isActive) {
                probeServerConnection()
                delay(SERVER_CONNECTIVITY_CHECK_MILLIS)
            }
        }
    }

    private fun stopConnectivityMonitor() {
        connectivityMonitorJob?.cancel()
        connectivityMonitorJob = null
        connectivityProbeJob?.cancel()
        connectivityProbeJob = null
    }

    private suspend fun probeServerConnection() {
        try {
            backend.api.currentUser()
            mutableState.update { it.copy(serverReachable = true) }
        } catch (failure: Throwable) {
            mutableState.update { it.copy(serverReachable = false) }
            if (failure is HttpException && failure.code() == 401) {
                backend.auth.invalidate("Сессия завершена. Войдите снова")
            }
        }
    }

    private fun clearMaintenanceCatalogMemory() {
        catalogWorkspace.clearMemory()
        editorReset.invalidateAssetSearch()
    }
}

/**
 * Defines manager UI state or presentation policy; server state and command authorization remain authoritative.
 *
 * Applies the server-provided warehouse grants consistently while a coordinator chooses a visible
 * warehouse or validates a manager action. It deliberately has no local permission cache.
 */
object WarehouseAccessPolicy {
    fun visibleWarehouses(
        user: CurrentUserDto,
        warehouses: List<WarehouseDto>,
    ): List<WarehouseDto> {
        if (user.warehouseAccessAll) return warehouses
        val allowed = user.warehouseAccesses.map { it.warehouseId }.toSet()
        return warehouses.filter { it.id in allowed }
    }

    fun hasAccess(
        user: CurrentUserDto,
        warehouseId: String,
        requiredLevel: String,
    ): Boolean {
        if (user.warehouseAccessAll) return true
        val required = WAREHOUSE_ACCESS_ORDER[requiredLevel] ?: return false
        val actual = user.warehouseAccesses
            .firstOrNull { it.warehouseId == warehouseId }
            ?.level
            ?.let(WAREHOUSE_ACCESS_ORDER::get)
            ?: return false
        return actual >= required
    }
}

/** Validates a command against the currently published server-sourced manager grants. */
internal fun ManagerUiState.requireManagerWarehouseAccess(
    warehouseId: String,
    level: String,
) {
    val user = requireNotNull(currentUser) {
        "Не удалось проверить права пользователя"
    }
    require(WarehouseAccessPolicy.hasAccess(user, warehouseId, level)) {
        "Недостаточно прав для операции на складе"
    }
}

private val WAREHOUSE_ACCESS_ORDER = mapOf(
    "VIEW" to 0,
    "EDIT" to 1,
    "MANAGE" to 2,
)

/** Stores only the last selected warehouse preference; server workspace loading revalidates it. */
internal class WarehousePreference(application: Application) {
    private val preferences = application.getSharedPreferences(
        "rwms_manager_ui",
        Application.MODE_PRIVATE,
    )

    fun read(): String? = preferences.getString(KEY, null)

    fun save(warehouseId: String) {
        preferences.edit().putString(KEY, warehouseId).apply()
    }

    private companion object {
        const val KEY = "last_service_warehouse_id"
    }
}

private val MANAGER_ROLES =
    setOf("SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER")

private const val SERVER_CONNECTIVITY_CHECK_MILLIS = 30_000L
