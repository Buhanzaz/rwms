package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.InventoryCurrentSnapshotDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.ObservationDto
import org.junit.Test

class InventoryReinspectionPolicyTest {
    @Test
    fun `only a saved inspection requires an explicit repeat mode`() {
        assertThat(finding(inspection = "NOT_INSPECTED").requiresInventoryReinspectionChoice())
            .isFalse()
        assertThat(finding(inspection = "READY").requiresInventoryReinspectionChoice()).isTrue()
        assertThat(finding(inspection = "WORK_STAGED").requiresInventoryReinspectionChoice())
            .isTrue()
    }

    @Test
    fun `supplement preserves previous inspection facts and evidence`() {
        val first = MediaReferenceDto("photo-1", 3)
        val second = MediaReferenceDto("photo-2", 4)
        val finding = finding(media = listOf(first, second))

        val seed = finding.inventoryReinspectionSeed(InventoryReinspectionMode.SUPPLEMENT)

        assertThat(seed.retainPreviousInspection).isTrue()
        assertThat(seed.comment).isEqualTo("Старый комментарий")
        assertThat(seed.passport["rentalType"]).isEqualTo("Из осмотра")
        assertThat(
            finding.inventoryReinspectionRemovedMediaIds(InventoryReinspectionMode.SUPPLEMENT),
        ).isEmpty()
    }

    @Test
    fun `replace starts from registry and excludes every previous active photo`() {
        val first = MediaReferenceDto("photo-1", 3)
        val second = MediaReferenceDto("photo-2", 4)
        val finding = finding(media = listOf(first, second))

        val seed = finding.inventoryReinspectionSeed(InventoryReinspectionMode.REPLACE)
        val editor = InventoryEditorState(
            findingId = finding.id,
            number = finding.displayCanonicalNumber,
            outcome = "MATCHED",
            finding = finding,
            removedPersistedMediaIds = finding.inventoryReinspectionRemovedMediaIds(
                InventoryReinspectionMode.REPLACE,
            ),
        )

        assertThat(seed.retainPreviousInspection).isFalse()
        assertThat(seed.comment).isEmpty()
        assertThat(seed.passport["rentalType"]).isEqualTo("Из реестра")
        assertThat(editor.persistedInventoryMediaReferences()).isEmpty()
        assertThat(editor.inventoryPhotoValidationError())
            .isEqualTo("Добавьте хотя бы одну фотографию")
    }

    private fun finding(
        inspection: String = "READY",
        media: List<MediaReferenceDto> = emptyList(),
    ) = InventoryFindingDto(
        id = "finding-1",
        inventoryId = "inventory-1",
        findingRevision = 8,
        origin = "REGISTRY",
        inspection = inspection,
        reconciliation = "MATCHED",
        displayCanonicalNumber = "БЫТ-001",
        identityMatchKey = "быт-001",
        passportObservation = ObservationDto(
            "PRESENT",
            mapOf("rentalType" to "Из осмотра"),
        ),
        equipmentObservation = ObservationDto("EXPLICIT_EMPTY", emptyList<Any?>()),
        mutationState = "UNCHANGED",
        comment = "Старый комментарий",
        currentSnapshot = InventoryCurrentSnapshotDto(
            assetId = "asset-1",
            assetVersion = 11,
            warehouseId = "warehouse-1",
            status = "FREE",
            displayCanonicalNumber = "БЫТ-001",
            passportSnapshot = mapOf("rentalType" to "Из реестра"),
        ),
        media = media,
        coverMediaId = media.firstOrNull()?.mediaId,
    )
}
