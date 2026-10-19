package dev.buhanzaz.rwms.manager.uploads

import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventoryFrozenPlanDto
import dev.buhanzaz.rwms.manager.network.InventoryFrozenPlanLineDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanLineInputDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanSelectionDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.ObservationDto
import dev.buhanzaz.rwms.manager.network.ObservationInput
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * A lost save response must not leave an accepted inspection behind its old revision fence.
 * Acknowledges only an equivalent complete result; unavailable reads and ambiguous frozen plans
 * remain queued. Cancellation is never converted into either a match or a retry decision.
 */
internal suspend fun recoverSavedInventoryInspection(
    command: InventoryUploadCommand,
    media: List<MediaReferenceDto>,
    coverMediaId: String?,
    planSelection: InventoryPlanSelectionDto?,
    loadFinding: suspend () -> InventoryFindingDto?,
): Boolean = try {
    loadFinding()?.let { finding ->
        matchesSavedInventoryInspection(command, media, coverMediaId, planSelection, finding)
    } == true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

/** Compares submitted intent, not only inspection status or the number of frozen plan lines. */
internal fun matchesSavedInventoryInspection(
    command: InventoryUploadCommand,
    media: List<MediaReferenceDto>,
    coverMediaId: String?,
    planSelection: InventoryPlanSelectionDto?,
    finding: InventoryFindingDto,
): Boolean =
    finding.id == command.findingId &&
        finding.inventoryId == command.inventoryId &&
        finding.findingRevision > command.expectedFindingRevision &&
        finding.mutationState in setOf("IDLE", "SOURCE_CREATED") &&
        finding.inspection == command.inspection &&
        finding.comment == command.comment.trim { it <= ' ' } &&
        observationsMatch(command.passportObservation, finding.passportObservation) &&
        observationsMatch(command.equipmentObservation, finding.equipmentObservation) &&
        mediaReferencesMatch(media, finding.media) &&
        finding.coverMediaId == coverMediaId &&
        plansMatch(planSelection, finding.frozenPlan)

private fun observationsMatch(expected: ObservationInput, actual: ObservationDto): Boolean =
    expected.presence == actual.presence && jsonValuesMatch(expected.value, actual.value)

/** Moshi can decode JSON numbers as different Number subtypes across durable/HTTP boundaries. */
private fun jsonValuesMatch(expected: Any?, actual: Any?): Boolean = when {
    expected is Number && actual is Number ->
        isProvableJsonNumber(expected) && isProvableJsonNumber(actual) &&
            decimalValuesMatch(expected.toString(), actual.toString())
    expected is Map<*, *> && actual is Map<*, *> ->
        expected.keys == actual.keys && expected.all { (key, value) ->
            jsonValuesMatch(value, actual[key])
        }
    expected is List<*> && actual is List<*> ->
        expected.size == actual.size && expected.zip(actual).all { (left, right) ->
            jsonValuesMatch(left, right)
        }
    else -> expected == actual
}

/** An Any-valued Moshi number beyond the binary type's exact integer range is ambiguous. */
private fun isProvableJsonNumber(value: Number): Boolean = when (value) {
    is Double -> value.isFinite() && kotlin.math.abs(value) <= 9_007_199_254_740_991.0
    is Float -> value.isFinite() && kotlin.math.abs(value) <= 16_777_215.0f
    else -> true
}

private fun mediaReferencesMatch(
    expected: List<MediaReferenceDto>,
    actual: List<MediaReferenceDto>,
): Boolean = expected.size == actual.size &&
    expected.map(MediaReferenceDto::mediaId).distinct().size == expected.size &&
    actual.map(MediaReferenceDto::mediaId).distinct().size == actual.size &&
    expected.toSet() == actual.toSet()

private fun plansMatch(
    expected: InventoryPlanSelectionDto?,
    actual: InventoryFrozenPlanDto?,
): Boolean {
    if (expected == null || actual == null) return expected == null && actual == null
    if (expected.mode != "MANUAL" ||
        expected.mode != actual.mode ||
        expected.priority != actual.priority ||
        expected.coverMediaId != actual.coverMediaId ||
        expected.movementToRepair != actual.movementToRepair ||
        expected.forceCapitalRepair != actual.forceCapitalRepair ||
        expected.logisticsPlanningMode != actual.logisticsPlanningMode ||
        expected.logisticsScheduledDate != actual.logisticsScheduledDate ||
        expected.lines.size != actual.lines.size
    ) {
        return false
    }
    // Freeze preserves line order; stage canonicalization is checked separately using complete
    // same-catalog routing evidence and the identity of each surviving submitted stage.
    return expected.lines.zip(actual.lines).all { (selected, frozen) ->
        planLinesMatch(selected, frozen, actual)
    } && planStagesMatch(expected, actual)
}

/**
 * Maintenance keeps the first submitted stage per queue, then phase-sorts the survivors. Later
 * same-queue inventory stages add no effect. Prove every selected route from this frozen catalog
 * and compare the survivors' original deterministic identities, never just the final stage count.
 */
private fun planStagesMatch(
    expected: InventoryPlanSelectionDto,
    actual: InventoryFrozenPlanDto,
): Boolean {
    if (expected.stages.isEmpty() || expected.stages.withIndex().any { (index, stage) ->
            stage.order != index || stage.kind != "REPAIR_WORK"
        }
    ) {
        return false
    }
    val routes = expected.stages.map { stage ->
        frozenRouteForNode(actual, stage.catalogNodeId) ?: return false
    }
    if (routes.groupBy { it.queueId }.any { (_, witnesses) ->
            witnesses.map { it.queueType }.distinct().size != 1
        }
    ) {
        return false
    }
    val survivors = expected.stages.zip(routes).distinctBy { (_, route) -> route.queueId }
    val actualByQueue = actual.stages.associateBy { it.routingQueueId }
    if (survivors.size != actual.stages.size || actualByQueue.size != actual.stages.size ||
        actual.stages.map { it.id }.distinct().size != actual.stages.size ||
        actual.stages.withIndex().any { (index, stage) -> stage.order != index }
    ) {
        return false
    }
    return survivors.all { (selected, route) ->
        val frozen = actualByQueue[route.queueId] ?: return@all false
        val id = UUID.nameUUIDFromBytes(
            "${actual.catalogVersionId}:${selected.catalogNodeId}:${selected.kind}:${selected.order}"
                .toByteArray(StandardCharsets.UTF_8),
        ).toString()
        frozen.id == id && frozen.catalogNodeId == selected.catalogNodeId &&
            frozen.kind == selected.kind && frozen.routingQueueType == route.queueType
    }
}

private fun planLinesMatch(
    expected: InventoryPlanLineInputDto,
    actual: InventoryFrozenPlanLineDto,
    plan: InventoryFrozenPlanDto,
): Boolean {
    if (expected.aggregationKind != actual.sourceKind ||
        !decimalValuesMatch(expected.quantity, actual.quantity) ||
        normalizedPlanText(expected.groupComment) != actual.groupComment ||
        !mediaReferencesMatch(expected.mediaReferences, actual.mediaReferences)
    ) {
        return false
    }
    return when (expected.aggregationKind) {
        "CATALOG" -> expected.catalogNodeId != null &&
            expected.catalogNodeId == actual.catalogNodeId &&
            actual.catalogVersionId == plan.catalogVersionId &&
            expected.routingCatalogNodeId == null && expected.description == null &&
            expected.type == null && expected.unit == null &&
            expected.unitPriceMinor == null && expected.normativeMinutes == null

        "MANUAL" -> expected.catalogNodeId == null && actual.catalogNodeId == null &&
            actual.catalogVersionId == null && expected.type == actual.lineType &&
            normalizedPlanText(expected.description) == actual.description &&
            normalizedPlanText(expected.unit) == actual.unit &&
            expected.unitPriceMinor == actual.unitPriceMinor &&
            expected.normativeMinutes?.let { decimalValuesMatch(it, actual.normativeMinutes) } == true &&
            manualLineRouteMatches(expected, actual, plan)

        else -> false
    }
}

/**
 * The public frozen line omits its technical routingCatalogNodeId. A stage frozen from that
 * exact node, or a catalog line from it, is a same-catalog witness for its effective queue;
 * absent/contradictory evidence stays fail-closed. Today's catalog is not such proof.
 */
private fun manualLineRouteMatches(
    expected: InventoryPlanLineInputDto,
    actual: InventoryFrozenPlanLineDto,
    plan: InventoryFrozenPlanDto,
): Boolean {
    val nodeId = expected.routingCatalogNodeId ?: return false
    val route = frozenRouteForNode(plan, nodeId) ?: return false
    return route.queueId == actual.routingQueueId && route.queueType == actual.routingQueueType
}

/** A queue's UUID, not its display name, owns stage coalescing and work-line allocation. */
private data class FrozenInventoryRoute(val queueId: String, val queueType: String)

private fun frozenRouteForNode(plan: InventoryFrozenPlanDto, nodeId: String): FrozenInventoryRoute? {
    val witnesses = plan.stages.filter { it.catalogNodeId == nodeId }.map { stage ->
        FrozenInventoryRoute(stage.routingQueueId, stage.routingQueueType)
    } + plan.lines.filter { line ->
        line.sourceKind == "CATALOG" && line.catalogVersionId == plan.catalogVersionId &&
            line.catalogNodeId == nodeId
    }.map { line ->
        FrozenInventoryRoute(line.routingQueueId ?: return null, line.routingQueueType ?: return null)
    }
    val distinct = witnesses.distinct()
    return distinct.singleOrNull()?.takeIf { it.queueId.isNotBlank() && it.queueType.isNotBlank() }
}

private fun decimalValuesMatch(expected: String, actual: String): Boolean {
    val left = expected.toBigDecimalOrNull() ?: return false
    val right = actual.toBigDecimalOrNull() ?: return false
    return left.compareTo(right) == 0
}

/** Matches maintenance's Java trim plus ASCII-whitespace collapse, without case folding. */
private fun normalizedPlanText(value: String?): String? = value
    ?.trim { it <= ' ' }
    ?.replace(Regex("[ \\t\\n\\u000B\\f\\r]+"), " ")
    ?.takeUnless { it.isEmpty() || it.all(Character::isWhitespace) }

/** Carries only an explicit, fixed queue-recovery explanation to the upload UI. */
internal class InventoryUploadConflictException(val userMessage: String) :
    IllegalStateException(userMessage)
