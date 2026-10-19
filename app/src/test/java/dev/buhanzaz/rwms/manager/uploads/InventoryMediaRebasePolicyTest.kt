package dev.buhanzaz.rwms.manager.uploads

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.media.MediaOwner
import dev.buhanzaz.rwms.manager.network.InventoryPlanLineInputDto
import dev.buhanzaz.rwms.manager.network.InventoryPlanSelectionDto
import dev.buhanzaz.rwms.manager.network.MediaAssetDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.ObservationInput
import org.junit.Test

class InventoryMediaRebasePolicyTest {
    @Test
    fun `rebase retains aggregate and work line assignments by media id`() {
        val command = inventoryCommand(
            existingMedia = listOf(MediaReferenceDto("aggregate", 1)),
            planSelection = plan(
                workMedia = listOf(MediaReferenceDto("work-photo", 1)),
                materialMedia = listOf(MediaReferenceDto("material-photo", 1)),
            ),
            planLineIds = listOf("work-line", "material-line"),
            planWorkLineIds = listOf("work-line"),
            existingCoverMediaId = "aggregate",
        )
        val operation = operation(command).copy(
            photos = listOf(
                photo("new-aggregate", 1),
                photo("new-work", 1, lineId = "work-line"),
            ),
        )
        val current = readyOwnerMediaReferencesById(
            listOf(
                asset("aggregate", 3),
                asset("work-photo", 4),
                asset("new-aggregate", 5),
                asset("new-work", 6),
            ),
        )

        val rebased = operation.rebaseRetainedInventoryMedia(command, current)
        val rebasedCommand = checkNotNull(rebased.inventory)

        assertThat(rebasedCommand.existingMedia)
            .containsExactly(MediaReferenceDto("aggregate", 3))
        assertThat(rebasedCommand.existingCoverMediaId).isEqualTo("aggregate")
        assertThat(rebasedCommand.planSelection?.lines?.get(0)?.mediaReferences)
            .containsExactly(MediaReferenceDto("work-photo", 4))
        assertThat(rebasedCommand.planSelection?.lines?.get(1)?.mediaReferences).isEmpty()
        assertThat(rebased.photos.map { it.reference })
            .containsExactly(
                MediaReferenceDto("new-aggregate", 5),
                MediaReferenceDto("new-work", 6),
            )
            .inOrder()
        assertThat(rebased.photos.map(BackgroundUploadPhoto::lineId))
            .containsExactly(null, "work-line")
            .inOrder()
    }

    @Test
    fun `missing or non ready retained media is rejected instead of being dropped`() {
        val command = inventoryCommand(
            existingMedia = listOf(MediaReferenceDto("deleted-photo", 1)),
        )

        val failure = runCatching {
            operation(command).rebaseRetainedInventoryMedia(
                command = command,
                currentReadyByMediaId = readyOwnerMediaReferencesById(
                    listOf(asset("deleted-photo", 2, status = "DELETED")),
                ),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure?.message).isEqualTo(INVENTORY_RETAINED_MEDIA_UNAVAILABLE_MESSAGE)
    }

    @Test
    fun `legacy outbox without work line ids preserves and rebases line evidence`() {
        val command = inventoryCommand(
            planSelection = plan(workMedia = listOf(MediaReferenceDto("line-photo", 1))),
            planLineIds = emptyList(),
            planWorkLineIds = emptyList(),
        )

        val rebased = operation(command).rebaseRetainedInventoryMedia(
            command = command,
            currentReadyByMediaId = mapOf("line-photo" to MediaReferenceDto("line-photo", 7)),
        )

        assertThat(rebased.inventory?.planSelection?.lines?.first()?.mediaReferences)
            .containsExactly(MediaReferenceDto("line-photo", 7))
    }

    @Test
    fun `known material-only mapping removes impossible media without requiring owner reference`() {
        val materialLine = plan(
            workMedia = emptyList(),
            materialMedia = listOf(MediaReferenceDto("invalid-material-photo", 1)),
        ).lines.last()
        val command = inventoryCommand(
            planSelection = plan(workMedia = emptyList()).copy(lines = listOf(materialLine)),
            planLineIds = listOf("material-line"),
            planWorkLineIds = emptyList(),
        )

        val rebased = operation(command).rebaseRetainedInventoryMedia(
            command = command,
            currentReadyByMediaId = emptyMap(),
        )

        assertThat(rebased.inventory?.planSelection?.lines?.single()?.mediaReferences).isEmpty()
    }

    @Test
    fun `owner projection uses current ready generation for a logical media id`() {
        val current = readyOwnerMediaReferencesById(
            listOf(
                asset("reprocessed", 1, status = "PROCESSING"),
                asset("reprocessed", 3),
            ),
        )

        assertThat(current).containsExactly(
            "reprocessed",
            MediaReferenceDto("reprocessed", 3),
        )
    }

    private fun operation(command: InventoryUploadCommand) = BackgroundUploadOperation(
        id = "operation-1",
        area = BackgroundUploadArea.INVENTORY,
        title = "Проверка бытовки БЫТ-001",
        createdAtEpochMillis = 100,
        updatedAtEpochMillis = 100,
        inventory = command,
    )

    private fun inventoryCommand(
        existingMedia: List<MediaReferenceDto> = emptyList(),
        existingCoverMediaId: String? = null,
        planSelection: InventoryPlanSelectionDto? = null,
        planLineIds: List<String> = emptyList(),
        planWorkLineIds: List<String> = emptyList(),
    ) = InventoryUploadCommand(
        inventoryId = "inventory-1",
        findingId = "finding-1",
        expectedFindingRevision = 1,
        inspection = "WORK_STAGED",
        comment = "",
        passportObservation = ObservationInput("ABSENT", null),
        equipmentObservation = ObservationInput("ABSENT", null),
        existingMedia = existingMedia,
        existingCoverMediaId = existingCoverMediaId,
        planSelection = planSelection,
        planLineIds = planLineIds,
        planWorkLineIds = planWorkLineIds,
    )

    private fun plan(
        workMedia: List<MediaReferenceDto>,
        materialMedia: List<MediaReferenceDto> = emptyList(),
    ) = InventoryPlanSelectionDto(
        mode = "MANUAL",
        movementToRepair = false,
        logisticsPlanningMode = null,
        lines = listOf(
            line("WORK", workMedia),
            line("MATERIAL", materialMedia),
        ),
        stages = emptyList(),
    )

    private fun line(type: String, media: List<MediaReferenceDto>) = InventoryPlanLineInputDto(
        aggregationKind = "MANUAL",
        catalogNodeId = null,
        routingCatalogNodeId = "routing-node",
        description = "Строка $type",
        type = type,
        unit = "шт.",
        quantity = "1",
        unitPriceMinor = 100,
        normativeMinutes = "30",
        groupComment = null,
        mediaReferences = media,
    )

    private fun photo(mediaId: String, generation: Long, lineId: String? = null) =
        BackgroundUploadPhoto(
            id = "$mediaId-photo",
            sourceName = "$mediaId.jpg",
            durableUri = "file:///$mediaId.jpg",
            owner = MediaOwner(
                ownerType = "INVENTORY_FINDING",
                ownerId = "finding-1",
                warehouseId = "warehouse-1",
                context = "INSPECTION",
            ),
            sortOrder = 0,
            lineId = lineId,
            status = BackgroundPhotoStatus.READY,
            reference = MediaReferenceDto(mediaId, generation),
        )

    private fun asset(mediaId: String, generation: Long, status: String = "READY") = MediaAssetDto(
        id = mediaId,
        folderId = "folder-1",
        clientReferenceId = null,
        fileName = "$mediaId.jpg",
        contentType = "image/jpeg",
        kind = "IMAGE",
        status = status,
        version = generation,
        generation = generation,
        rotationDegrees = 0,
        sortOrder = 0,
        sizeBytes = 10,
        createdAt = "2026-08-06T00:00:00Z",
    )
}
