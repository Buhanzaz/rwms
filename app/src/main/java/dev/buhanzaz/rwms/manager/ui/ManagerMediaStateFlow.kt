package dev.buhanzaz.rwms.manager.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi

/**
 * Pins downloaded media before publishing an editor snapshot and releases it only after its last
 * editor reference disappears. Local capture/draft URIs are outside the remote cache index.
 * Every mutation, including update's compare-and-set, shares the same ownership boundary.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class ManagerMediaStateFlow(
    private val retain: (String) -> Boolean,
    private val release: (String) -> Unit,
    private val delegate: MutableStateFlow<ManagerUiState> = MutableStateFlow(ManagerUiState()),
) : MutableStateFlow<ManagerUiState> by delegate {
    private val lock = Any()
    private var closed = false

    init {
        delegate.value.remoteMediaUris().forEach { retain(it) }
    }

    override var value: ManagerUiState
        get() = delegate.value
        set(value) = synchronized(lock) { publish(value) }

    override fun compareAndSet(expect: ManagerUiState, update: ManagerUiState): Boolean =
        synchronized(lock) {
            if (delegate.value != expect) return@synchronized false
            publish(update)
            true
        }

    override fun tryEmit(value: ManagerUiState): Boolean {
        this.value = value
        return true
    }

    override suspend fun emit(value: ManagerUiState) {
        this.value = value
    }

    /** Ends the ViewModel's ownership once; late canceled work cannot acquire new state claims. */
    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            delegate.value.remoteMediaUris().forEach(release)
        }
    }

    private fun publish(next: ManagerUiState) {
        val previous = delegate.value
        if (previous == next) return
        if (closed) {
            delegate.value = next
            return
        }
        val before = previous.remoteMediaUris()
        val after = next.remoteMediaUris()
        (after - before).forEach { retain(it) }
        delegate.value = next
        (before - after).forEach(release)
    }
}

/** Uses one claim for a URI shared by any of the simultaneously retained editors. */
private fun ManagerUiState.remoteMediaUris(): Set<String> = buildSet {
    addAll(inventoryEditor?.persistedPhotoMedia.orEmpty().keys)
    addAll(maintenanceEditor?.readyPhotoUris.orEmpty().values)
    acceptanceEditor?.reviewMedia?.let { media ->
        addAll(media.photoUris())
    }
}

/** Enumerates all inline acceptance scopes, which may share the same downloaded file. */
internal fun AcceptanceReviewMediaState.photoUris(): List<String> = buildList {
    addAll(cabin.photoUris)
    workByStageId.values.forEach { works -> works.values.forEach { addAll(it.photoUris) } }
    resultByStageId.values.forEach { addAll(it.photoUris) }
}
