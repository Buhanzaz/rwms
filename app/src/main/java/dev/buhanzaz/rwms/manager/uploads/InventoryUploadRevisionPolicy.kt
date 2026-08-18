package dev.buhanzaz.rwms.manager.uploads

import dev.buhanzaz.rwms.manager.media.problemCode
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import retrofit2.HttpException

/**
 * Refreshes the fence for a queued initial inspection without allowing a background retry to
 * overwrite an inspection that has already changed on the server.
 */
internal fun InventoryUploadCommand.rebasePendingInitialInspection(
    currentFinding: InventoryFindingDto,
): InventoryUploadCommand {
    check(currentFinding.id == findingId) {
        "Сервис вернул другую бытовку при обновлении очереди инвентаризации"
    }
    require(currentFinding.inspection == "NOT_INSPECTED") {
        INVENTORY_UPLOAD_INSPECTION_CHANGED_MESSAGE
    }
    require(currentFinding.mutationState in INVENTORY_SAVE_READY_MUTATION_STATES) {
        INVENTORY_UPLOAD_MUTATION_IN_PROGRESS_MESSAGE
    }
    return copy(expectedFindingRevision = currentFinding.findingRevision)
}

/** Rejects a retry when its finding left the active session and is no longer commandable. */
internal fun requireActiveInventoryFindingForUpload(
    currentFinding: InventoryFindingDto?,
): InventoryFindingDto =
    currentFinding ?: throw IllegalStateException(INVENTORY_UPLOAD_FINDING_NOT_ACTIVE_MESSAGE)

/**
 * Permits one reconciliation only after inventory service reports its standard stale-revision
 * conflict. The caller must still reread the active finding and apply [rebasePendingInitialInspection]
 * before issuing the next command, so this never authorizes overwriting a changed inspection.
 */
internal fun shouldRetryInventoryRevisionConflict(
    failure: Throwable,
    retryCount: Int,
): Boolean =
    retryCount < INVENTORY_REVISION_CONFLICT_MAX_RETRIES &&
        failure is HttpException &&
        failure.code() == INVENTORY_REVISION_CONFLICT_HTTP_STATUS &&
        failure.problemCode() == INVENTORY_VERSION_CONFLICT_PROBLEM_CODE &&
        failure.inventoryProblemDetail() == INVENTORY_REVISION_STALE_DETAIL

/** Explains why a retry must not overwrite a newer server-side inspection revision. */
internal const val INVENTORY_UPLOAD_INSPECTION_CHANGED_MESSAGE =
    "Осмотр этой бытовки уже изменён на сервере. Обновите инвентаризацию: " +
        "очередь не будет перезаписывать новые данные."

/** Explains why a queued command cannot proceed while source-asset creation is still in flight. */
internal const val INVENTORY_UPLOAD_MUTATION_IN_PROGRESS_MESSAGE =
    "Бытовка сейчас меняется на сервере. Обновите инвентаризацию и повторите отправку."

/** Explains why an inactive finding cannot receive an initial inspection command. */
internal const val INVENTORY_UPLOAD_FINDING_NOT_ACTIVE_MESSAGE =
    "Бытовка больше не входит в активную инвентаризацию. " +
        "Отправка не выполнена, фотографии сохранены в очереди."

private val INVENTORY_SAVE_READY_MUTATION_STATES = setOf("IDLE", "SOURCE_CREATED")

private const val INVENTORY_REVISION_CONFLICT_MAX_RETRIES = 1
private const val INVENTORY_REVISION_CONFLICT_HTTP_STATUS = 409
private const val INVENTORY_VERSION_CONFLICT_PROBLEM_CODE = "INVENTORY_VERSION_CONFLICT"
private const val INVENTORY_REVISION_STALE_DETAIL = "Inventory revision is stale"
private val INVENTORY_PROBLEM_DETAIL_PATTERN = Regex(""""detail"\s*:\s*"([^"]+)"""")

/** Reads a Problem Details detail without consuming the body required by the UI error mapper. */
private fun HttpException.inventoryProblemDetail(): String? {
    val source = response()?.errorBody()?.source() ?: return null
    val raw = runCatching {
        source.request(Long.MAX_VALUE)
        source.buffer.clone().readUtf8()
    }.getOrNull() ?: return null
    return INVENTORY_PROBLEM_DETAIL_PATTERN.find(raw)?.groupValues?.get(1)
}
