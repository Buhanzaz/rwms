package dev.buhanzaz.rwms.manager.uploads

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventoryFrozenPlanDto
import dev.buhanzaz.rwms.manager.network.InventoryFrozenPlanLineDto
import dev.buhanzaz.rwms.manager.network.InventoryFrozenPlanStageDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanLineInputDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanSelectionDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanStageSelectionDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.ObservationDto
import dev.buhanzaz.rwms.manager.network.ObservationInput
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Regression coverage for an inspection committed before its response or queue update was lost. */
class InventoryUploadRecoveryPolicyTest {
    @Test
    fun `recognizes saved READY before its old revision fence rejects a retry`() = runBlocking {
        val queued = command()
        val saved = finding(queued)

        assertThat(runCatching { queued.preparePendingInspectionFence(saved) }.exceptionOrNull())
            .isInstanceOf(InventoryUploadConflictException::class.java)
        assertThat(recover(queued) { saved }).isTrue()
        assertThat(queued.expectedFindingRevision).isEqualTo(2L)
        assertThat(queued.inspectionSaved).isFalse()
    }

    @Test
    fun `recognizes twelve catalog lines in MANUAL capital plan with fixed scale quantities`() {
        val queued = command(planSelection())

        assertThat(matches(queued, finding(queued))).isTrue()
    }

    @Test
    fun `recognizes canonical stage reorder using identities that preserve submitted order`() {
        val queued = command(planSelection())
        val saved = finding(queued)
        val plan = requireNotNull(saved.frozenPlan)
        val reordered = plan.stages.reversed().mapIndexed { index, stage -> stage.copy(order = index) }

        assertThat(matches(queued, saved.copy(frozenPlan = plan.copy(stages = reordered)))).isTrue()
    }

    @Test
    fun `recovers four submitted stages merged into two whose surviving original orders are 3 and 0`() {
        val queued = command(mergedStageSelection())
        val saved = mergedStageFinding(queued)

        assertThat(queued.planSelection!!.stages).hasSize(4)
        assertThat(saved.frozenPlan!!.stages).hasSize(2)
        assertThat(matches(queued, saved)).isTrue()
    }

    @Test
    fun `merged stages need an exact consistent routing witness for every discarded selection`() {
        val queued = command(mergedStageSelection())
        val saved = mergedStageFinding(queued)
        val selection = queued.planSelection!!
        val missingWitness = queued.copy(planSelection = selection.copy(stages = selection.stages.map {
            if (it.order == 1) it.copy(catalogNodeId = "missing-route-node") else it
        }))
        assertThat(matches(missingWitness, saved)).isFalse()
        val changedRoute = saved.frozenPlan!!.copy(lines = saved.frozenPlan.lines.map {
            if (it.catalogNodeId == "node-2") it.copy(routingQueueId = "unselected-queue") else it
        })
        assertThat(matches(queued, saved.copy(frozenPlan = changedRoute))).isFalse()
        val changedQueueType = saved.frozenPlan.copy(lines = saved.frozenPlan.lines.map {
            if (it.catalogNodeId == "node-2") it.copy(routingQueueType = "OTHER") else it
        })
        assertThat(matches(queued, saved.copy(frozenPlan = changedQueueType))).isFalse()
        val contradictory = saved.frozenPlan.copy(stages = saved.frozenPlan.stages.map {
            if (it.catalogNodeId == "node-0") it.copy(routingQueueType = "OTHER") else it
        })
        assertThat(matches(queued, saved.copy(frozenPlan = contradictory))).isFalse()
    }

    @Test
    fun `merged recovery rejects another first submitted stage even when final queue count matches`() {
        val queued = command(mergedStageSelection())
        val saved = mergedStageFinding(queued)
        val selection = queued.planSelection!!
        val changedFirst = queued.copy(planSelection = selection.copy(stages = selection.stages.map {
            when (it.order) {
                0 -> it.copy(catalogNodeId = "node-2")
                1 -> it.copy(catalogNodeId = "node-0")
                else -> it
            }
        }))

        assertThat(matches(changedFirst, saved)).isFalse()
    }

    @Test
    fun `rejects wrong identity revision state and inspection`() {
        val queued = command()
        val saved = finding(queued)
        listOf(
            saved.copy(id = "different-finding"),
            saved.copy(inventoryId = "different-inventory"),
            saved.copy(findingRevision = queued.expectedFindingRevision),
            saved.copy(findingRevision = queued.expectedFindingRevision - 1),
            saved.copy(inspection = "NOT_INSPECTED"),
            saved.copy(mutationState = "SOURCE_CREATE_PENDING"),
            saved.copy(comment = "different observation"),
        ).forEach { changed -> assertThat(matches(queued, changed)).isFalse() }
    }

    @Test
    fun `compares complete passport and equipment including presence nested data and array order`() {
        val queued = command()
        val saved = finding(queued)
        assertThat(matches(queued, saved)).isTrue()
        listOf(
            saved.copy(passportObservation = ObservationDto("ABSENT", null)),
            saved.copy(passportObservation = ObservationDto("PRESENT", mapOf("width" to 9))),
            saved.copy(passportObservation = ObservationDto("PRESENT", mapOf("width" to "6"))),
            saved.copy(equipmentObservation = ObservationDto("EXPLICIT_EMPTY", emptyList<Any>())),
            saved.copy(equipmentObservation = ObservationDto(
                "PRESENT", listOf(mapOf("id" to "chair", "quantity" to 4)),
            )),
            saved.copy(equipmentObservation = ObservationDto(
                "PRESENT", listOf(mapOf("quantity" to 2, "id" to "chair", "extra" to true)),
            )),
        ).forEach { changed -> assertThat(matches(queued, changed)).isFalse() }
        val ordered = queued.copy(equipmentObservation = ObservationInput(
            "PRESENT", listOf(mapOf("id" to "chair"), mapOf("id" to "table")),
        ))
        val reversed = finding(ordered).copy(equipmentObservation = ObservationDto(
            "PRESENT", listOf(mapOf("id" to "table"), mapOf("id" to "chair")),
        ))
        assertThat(matches(ordered, reversed)).isFalse()
    }

    @Test
    fun `compares exact media generations and cover without depending on response order`() {
        val queued = command()
        val saved = finding(queued)
        assertThat(matches(queued, saved.copy(media = saved.media.reversed()))).isTrue()
        listOf(
            saved.copy(media = saved.media.dropLast(1)),
            saved.copy(media = saved.media + MediaReferenceDto("extra", 1)),
            saved.copy(media = saved.media.map { it.copy(generation = it.generation + 1) }),
            saved.copy(media = listOf(saved.media.first(), saved.media.first())),
            saved.copy(coverMediaId = "photo-2"),
        ).forEach { changed -> assertThat(matches(queued, changed)).isFalse() }
    }

    @Test
    fun `does not acknowledge large JSON integers rounded to the same Double`() {
        val left = 9_007_199_254_740_992L.toDouble()
        val right = 9_007_199_254_740_993L.toDouble()
        assertThat(left).isEqualTo(right)
        val queued = command().copy(passportObservation = ObservationInput("PRESENT", mapOf("id" to left)))
        val saved = finding(queued).copy(passportObservation = ObservationDto("PRESENT", mapOf("id" to right)))

        assertThat(matches(queued, saved)).isFalse()
    }

    @Test
    fun `checks every plan choice rather than only counts priority and movement`() {
        val queued = command(planSelection())
        val saved = finding(queued)
        val plan = requireNotNull(saved.frozenPlan)
        listOf(
            plan.copy(mode = "AUTO"),
            plan.copy(priority = 4),
            plan.copy(coverMediaId = "different-cover"),
            plan.copy(movementToRepair = true),
            plan.copy(forceCapitalRepair = false),
            plan.copy(logisticsPlanningMode = "AUTO"),
            plan.copy(logisticsScheduledDate = "2026-09-08"),
            plan.copy(lines = plan.lines.dropLast(1)),
            plan.copy(stages = plan.stages.dropLast(1)),
        ).forEach { changed -> assertThat(matches(queued, saved.copy(frozenPlan = changed))).isFalse() }
        assertThat(matches(queued, saved.copy(frozenPlan = null))).isFalse()
        assertThat(matches(command(), finding(command()).copy(frozenPlan = plan))).isFalse()
    }

    @Test
    fun `rejects changed same-count catalog lines and work media assignments`() {
        val queued = command(planSelection())
        val saved = finding(queued)
        val plan = requireNotNull(saved.frozenPlan)
        val line = plan.lines.first()
        listOf(
            line.copy(sourceKind = "MANUAL"),
            line.copy(catalogNodeId = "another-node"),
            line.copy(catalogVersionId = "another-version"),
            line.copy(quantity = "7.000000"),
            line.copy(groupComment = "another work comment"),
            line.copy(mediaReferences = listOf(MediaReferenceDto("line-photo", 2))),
            line.copy(mediaReferences = emptyList()),
        ).forEach { changed ->
            assertThat(matches(queued, saved.copy(frozenPlan = plan.copy(
                lines = listOf(changed) + plan.lines.drop(1),
            )))).isFalse()
        }
        assertThat(matches(queued, saved.copy(frozenPlan = plan.copy(lines = plan.lines.reversed()))))
            .isFalse()
    }

    @Test
    fun `rejects changed stage node kind submitted order and duplicate or missing identities`() {
        val queued = command(planSelection())
        val saved = finding(queued)
        val plan = requireNotNull(saved.frozenPlan)
        val stage = plan.stages.first()
        listOf(
            stage.copy(catalogNodeId = "another-node"),
            stage.copy(kind = "FURNITURE"),
            stage.copy(id = "another-submitted-order"),
            plan.stages.last(),
        ).forEach { changed ->
            assertThat(matches(queued, saved.copy(frozenPlan = plan.copy(
                stages = listOf(changed) + plan.stages.drop(1),
            )))).isFalse()
        }
        val changedSelection = queued.planSelection!!.copy(stages = queued.planSelection.stages.map {
            it.copy(order = it.order + 1)
        })
        assertThat(matches(queued.copy(planSelection = changedSelection), saved)).isFalse()
    }

    @Test
    fun `matches complete manual line only with exact frozen routing-node witness`() {
        val queued = manualCommand()
        val saved = manualFinding(queued)

        assertThat(matches(queued, saved)).isTrue()
        val plan = requireNotNull(saved.frozenPlan)
        val line = plan.lines.single()
        listOf(
            line.copy(lineType = "MATERIAL"),
            line.copy(description = "Другая работа"),
            line.copy(unit = "м"),
            line.copy(unitPriceMinor = 124),
            line.copy(normativeMinutes = "4.000"),
            line.copy(routingQueueId = "another-queue"),
            line.copy(routingQueueType = "OTHER"),
            line.copy(routingQueueId = null),
        ).forEach { changed ->
            assertThat(matches(queued, saved.copy(frozenPlan = plan.copy(lines = listOf(changed)))))
                .isFalse()
        }
        val ambiguous = queued.copy(planSelection = queued.planSelection!!.copy(
            lines = queued.planSelection.lines.map { it.copy(routingCatalogNodeId = "unproven-node") },
        ))
        assertThat(matches(ambiguous, saved)).isFalse()
    }

    @Test
    fun `unknown plan modes and invalid numeric evidence fail closed`() {
        val queued = command(planSelection())
        val saved = finding(queued)
        val unsupported = queued.copy(planSelection = queued.planSelection!!.copy(mode = "AUTO"))
        assertThat(matches(unsupported, saved.copy(frozenPlan = saved.frozenPlan!!.copy(mode = "AUTO"))))
            .isFalse()
        val badNumber = queued.copy(planSelection = queued.planSelection.copy(
            lines = queued.planSelection.lines.map { it.copy(quantity = "NaN") },
        ))
        assertThat(matches(badNumber, saved)).isFalse()
    }

    @Test
    fun `a failed reconciliation read does not acknowledge but a later retry can recover`() = runBlocking {
        val queued = command()

        assertThat(recover(queued) { throw IOException("unavailable") }).isFalse()
        assertThat(recover(queued) { null }).isFalse()
        assertThat(recover(queued) { finding(queued) }).isTrue()
    }

    @Test
    fun `cancellation is rethrown without acknowledging the saved command`() = runBlocking {
        val cancelled = CancellationException("scope was closed")
        val failure = runCatching { recover(command()) { throw cancelled } }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancelled)
    }

    @Test
    fun `known conflicts expose fixed recovery instruction and preserve the queue`() {
        val message = backgroundUploadFailureMessage(
            InventoryUploadConflictException(INVENTORY_UPLOAD_INSPECTION_CHANGED_MESSAGE),
        ) { error("No HTTP error expected") }

        assertThat(message).contains(INVENTORY_UPLOAD_INSPECTION_CHANGED_MESSAGE)
        assertThat(message).contains("Данные и фотографии сохранены в очереди")
    }

    private suspend fun recover(
        command: InventoryUploadCommand,
        loadFinding: suspend () -> InventoryFindingDto?,
    ) = recoverSavedInventoryInspection(
        command, command.existingMedia, command.existingCoverMediaId, command.planSelection, loadFinding,
    )

    private fun matches(command: InventoryUploadCommand, finding: InventoryFindingDto) =
        matchesSavedInventoryInspection(
            command, command.existingMedia, command.existingCoverMediaId, command.planSelection, finding,
        )

    private fun command(plan: InventoryPlanSelectionDto? = null) = InventoryUploadCommand(
        inventoryId = "inventory-1",
        findingId = "finding-1",
        expectedFindingRevision = 2,
        inspection = if (plan == null) "READY" else "WORK_STAGED",
        comment = " Проверено ",
        passportObservation = ObservationInput("PRESENT", mapOf("width" to 6)),
        equipmentObservation = ObservationInput(
            "PRESENT", listOf(mapOf("id" to "chair", "quantity" to 2)),
        ),
        existingMedia = listOf(MediaReferenceDto("photo-1", 1), MediaReferenceDto("photo-2", 1)),
        existingCoverMediaId = "photo-1",
        planSelection = plan,
    )

    private fun finding(command: InventoryUploadCommand) = InventoryFindingDto(
        id = command.findingId,
        inventoryId = command.inventoryId,
        findingRevision = 3,
        origin = "EXPECTED",
        inspection = command.inspection,
        reconciliation = "MATCHED",
        displayCanonicalNumber = "171003",
        identityMatchKey = "171003",
        passportObservation = ObservationDto("PRESENT", mapOf("width" to 6.0)),
        equipmentObservation = ObservationDto(
            "PRESENT", listOf(mapOf("quantity" to 2.0, "id" to "chair")),
        ),
        mutationState = "IDLE",
        comment = "Проверено",
        media = command.existingMedia,
        coverMediaId = command.existingCoverMediaId,
        frozenPlan = command.planSelection?.let(::frozenPlan),
    )

    private fun planSelection() = InventoryPlanSelectionDto(
        mode = "MANUAL",
        priority = 3,
        coverMediaId = "photo-1",
        movementToRepair = false,
        forceCapitalRepair = true,
        logisticsPlanningMode = null,
        lines = List(12) { index ->
            InventoryPlanLineInputDto(
                aggregationKind = "CATALOG",
                catalogNodeId = "node-$index",
                routingCatalogNodeId = null,
                description = null,
                type = null,
                unit = null,
                quantity = "6",
                unitPriceMinor = null,
                normativeMinutes = null,
                groupComment = " Работы\tпо полу ",
                mediaReferences = if (index == 0) listOf(MediaReferenceDto("line-photo", 1)) else emptyList(),
            )
        },
        stages = List(2) { index -> InventoryPlanStageSelectionDto("node-$index", "REPAIR_WORK", index) },
    )

    private fun frozenPlan(selection: InventoryPlanSelectionDto) = InventoryFrozenPlanDto(
        mode = selection.mode,
        catalogVersionId = "version-1",
        fingerprintSha256 = "fingerprint",
        priority = selection.priority,
        coverMediaId = selection.coverMediaId,
        movementToRepair = selection.movementToRepair,
        forceCapitalRepair = selection.forceCapitalRepair,
        logisticsPlanningMode = selection.logisticsPlanningMode,
        logisticsScheduledDate = selection.logisticsScheduledDate,
        lines = selection.lines.mapIndexed { index, line ->
            InventoryFrozenPlanLineDto(
                id = "line-$index", sourceKind = "CATALOG", lineType = "WORK",
                catalogVersionId = "version-1", catalogNodeId = line.catalogNodeId,
                routingQueueId = "queue-${index % 2}", routingQueueType = "REPAIR",
                description = "Работа из каталога", unit = "шт", quantity = "6.000000",
                unitPriceMinor = 500, normativeMinutes = "3.000", groupComment = "Работы по полу",
                mediaReferences = line.mediaReferences,
            )
        },
        stages = selection.stages.map { stage ->
            InventoryFrozenPlanStageDto(
                id = UUID.nameUUIDFromBytes(
                    "version-1:${stage.catalogNodeId}:${stage.kind}:${stage.order}"
                        .toByteArray(StandardCharsets.UTF_8),
                ).toString(),
                order = stage.order, catalogNodeId = stage.catalogNodeId,
                catalogNodeName = "Маршрут", kind = stage.kind,
                routingQueueId = "queue-${stage.catalogNodeId.substringAfterLast('-').toInt() % 2}",
                routingQueueName = "Ремонт", routingQueueType = "REPAIR", photoRequired = false,
                normativeDurationMinutes = 3,
            )
        },
    )

    private fun mergedStageSelection() = planSelection().copy(
        stages = listOf(0, 2, 4, 1).mapIndexed { index, node ->
            InventoryPlanStageSelectionDto("node-$node", "REPAIR_WORK", index)
        },
    )

    private fun mergedStageFinding(command: InventoryUploadCommand): InventoryFindingDto {
        val saved = finding(command)
        val plan = requireNotNull(saved.frozenPlan)
        return saved.copy(frozenPlan = plan.copy(stages = listOf(
            plan.stages[3].copy(order = 0),
            plan.stages[0].copy(order = 1),
        )))
    }

    private fun manualCommand(): InventoryUploadCommand {
        val plan = planSelection()
        val line = plan.lines.first().copy(
            aggregationKind = "MANUAL", catalogNodeId = null, routingCatalogNodeId = "node-0",
            description = " Моя\tработа ", type = "WORK", unit = " шт ",
            unitPriceMinor = 123, normativeMinutes = "3",
        )
        return command(plan.copy(lines = listOf(line), stages = plan.stages.take(1)))
    }

    private fun manualFinding(command: InventoryUploadCommand): InventoryFindingDto {
        val saved = finding(command)
        val plan = requireNotNull(saved.frozenPlan)
        val line = plan.lines.single().copy(
            sourceKind = "MANUAL", catalogVersionId = null, catalogNodeId = null,
            description = "Моя работа", unitPriceMinor = 123,
        )
        return saved.copy(frozenPlan = plan.copy(lines = listOf(line)))
    }
}
