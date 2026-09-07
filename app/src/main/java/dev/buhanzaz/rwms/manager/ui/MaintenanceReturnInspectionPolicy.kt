package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.ReturnEstimateInspectionDto
import dev.buhanzaz.rwms.manager.uploads.BackgroundUploadOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val RETURN_INSPECTION_DELAYS_MS =
    listOf(250L, 500L, 1_000L, 1_500L, 2_000L, 2_500L, 3_000L)

/** Identifies a submitted estimate that has just left this process's observed outbox. */
internal fun removedEstimateCompletions(
    previous: List<BackgroundUploadOperation>,
    current: List<BackgroundUploadOperation>,
): List<BackgroundUploadOperation> {
    val currentIds = current.mapTo(hashSetOf(), BackgroundUploadOperation::id)
    return previous.filter { operation ->
        operation.id !in currentIds &&
            operation.maintenance?.let { command ->
                command.mode == "ESTIMATE" && command.submitRequest != null
            } == true
    }
}

/** Waits for the asynchronous inventory import without ever retrying the completed command. */
internal suspend fun awaitReturnEstimateInspection(
    read: suspend () -> ReturnEstimateInspectionDto,
    delaysMs: List<Long> = RETURN_INSPECTION_DELAYS_MS,
    wait: suspend (Long) -> Unit = { delay(it) },
): ReturnEstimateInspectionDto {
    var inspection = read()
    for (delayMs in delaysMs) {
        if (inspection.state != "PENDING") return inspection
        wait(delayMs)
        inspection = read()
    }
    return inspection
}

/** Starts inventory polling only after maintenance proves this exact estimate is completed. */
internal suspend fun awaitCompletedReturnEstimateInspection(
    readEstimateLifecycle: suspend () -> String,
    readInspection: suspend () -> ReturnEstimateInspectionDto,
    delaysMs: List<Long> = RETURN_INSPECTION_DELAYS_MS,
    wait: suspend (Long) -> Unit = { delay(it) },
): ReturnEstimateInspectionDto? {
    val lifecycle = try {
        readEstimateLifecycle()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        return null
    }
    if (lifecycle != "COMPLETED") return null
    return awaitReturnEstimateInspection(readInspection, delaysMs, wait)
}

/** Keeps pending acknowledgements private to the currently verified workspace. */
internal fun retainReturnConfirmationsForScope(
    confirmations: List<InventoryReturnConfirmation>,
    ownerAccountId: String?,
    warehouseId: String?,
): List<InventoryReturnConfirmation> = confirmations.filter { confirmation ->
    confirmation.ownerAccountId == ownerAccountId && confirmation.warehouseId == warehouseId
}

/** Appends one acknowledgement without replacing earlier unacknowledged completions. */
internal fun enqueueReturnConfirmation(
    confirmations: List<InventoryReturnConfirmation>,
    confirmation: InventoryReturnConfirmation,
): List<InventoryReturnConfirmation> =
    confirmations.filterNot { it.operationId == confirmation.operationId } + confirmation
