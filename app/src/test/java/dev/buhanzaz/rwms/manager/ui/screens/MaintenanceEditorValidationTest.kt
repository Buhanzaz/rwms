package dev.buhanzaz.rwms.manager.ui.screens

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorMode
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorState
import dev.buhanzaz.rwms.manager.ui.MaintenanceLineEditorState
import dev.buhanzaz.rwms.manager.ui.LOGISTICS_PLANNING_MODE_AUTO
import dev.buhanzaz.rwms.manager.ui.isEmptyMaintenanceEstimate
import dev.buhanzaz.rwms.manager.ui.maintenanceLocalPhotoKey
import dev.buhanzaz.rwms.manager.ui.maintenanceRequiresPhotos
import java.math.BigDecimal
import org.junit.Test

class MaintenanceEditorValidationTest {
    @Test
    fun `initial details and submission require a repair priority`() {
        val photoUri = "content://rwms/maintenance/movement-priority.jpg"
        val editor = MaintenanceEditorState(
            mode = MaintenanceEditorMode.REPAIR,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = RentalItemDto(
                id = "asset-1",
                version = 1,
                warehouseId = "warehouse-1",
                number = "БК-001",
                status = "AFTER_RENT",
            ),
            dispatchDate = "2026-08-03",
            sourceParty = "",
            lines = listOf(line(id = "one", quantity = "1", unitPrice = "100.00")),
            photoUris = listOf(photoUri),
            readyMedia = emptyList(),
            priority = 0,
            movementToRepair = true,
            logisticsPlanningMode = LOGISTICS_PLANNING_MODE_AUTO,
            step = 5,
            coverPhotoKey = maintenanceLocalPhotoKey(photoUri),
        )

        assertThat(maintenanceCanSubmit(editor)).isFalse()
        assertThat(maintenanceCanAdvance(editor, step = 1)).isFalse()
        assertThat(maintenanceCanSubmit(editor.copy(priority = 4))).isTrue()
        assertThat(maintenanceCanAdvance(editor.copy(priority = 4), step = 1)).isTrue()
    }

    @Test
    fun totalUsesExactDecimalArithmetic() {
        val total = maintenanceTotal(
            listOf(
                line(id = "one", quantity = "0.1", unitPrice = "0.20"),
                line(id = "two", quantity = "0.1", unitPrice = "0.10"),
            ),
        )

        assertThat(total?.compareTo(BigDecimal("0.03"))).isEqualTo(0)
    }

    @Test
    fun directRepairCanBeSubmittedWithoutSourceParty() {
        val photoUri = "content://rwms/maintenance/repair-1.jpg"
        val editor = MaintenanceEditorState(
            mode = MaintenanceEditorMode.REPAIR,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = RentalItemDto(
                id = "asset-1",
                version = 1,
                warehouseId = "warehouse-1",
                number = "БК-001",
                status = "AVAILABLE",
            ),
            dispatchDate = "2026-07-27",
            sourceParty = "",
            lines = listOf(line(id = "one", quantity = "1", unitPrice = "100.00")),
            photoUris = listOf(photoUri),
            readyMedia = emptyList(),
            priority = 3,
            step = 1,
            coverPhotoKey = maintenanceLocalPhotoKey(photoUri),
        )

        assertThat(maintenanceCanSubmit(editor)).isTrue()
    }

    @Test
    fun `photo step requires both a photo and a title selection`() {
        val editor = MaintenanceEditorState(
            mode = MaintenanceEditorMode.REPAIR,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = RentalItemDto(
                id = "asset-1",
                version = 1,
                warehouseId = "warehouse-1",
                number = "БК-001",
                status = "AVAILABLE",
            ),
            dispatchDate = "2026-07-27",
            sourceParty = "",
            lines = listOf(line(id = "one", quantity = "1", unitPrice = "100.00")),
            photoUris = emptyList(),
            readyMedia = emptyList(),
            priority = 3,
            step = 2,
        )

        assertThat(maintenanceCanAdvance(editor, step = 2)).isFalse()
        val photoUri = "content://rwms/maintenance/one.jpg"
        assertThat(
            maintenanceCanAdvance(
                editor.copy(photoUris = listOf(photoUri)),
                step = 2,
            ),
        ).isFalse()
        assertThat(
            maintenanceCanAdvance(
                editor.copy(
                    photoUris = listOf(photoUri),
                    coverPhotoKey = maintenanceLocalPhotoKey(photoUri),
                ),
                step = 2,
            ),
        ).isTrue()
    }

    @Test
    fun `empty estimate still requires a titled photo before it can release the cabin`() {
        val photoUri = "content://rwms/maintenance/empty-estimate.jpg"
        val editorWithoutPhoto = MaintenanceEditorState(
            mode = MaintenanceEditorMode.ESTIMATE,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = RentalItemDto(
                id = "asset-1",
                version = 1,
                warehouseId = "warehouse-1",
                number = "БК-001",
                status = "AFTER_RENT",
            ),
            dispatchDate = "2026-07-27",
            sourceParty = "",
            lines = emptyList(),
            photoUris = emptyList(),
            readyMedia = emptyList(),
            priority = 3,
            step = 2,
        )

        assertThat(isEmptyMaintenanceEstimate(editorWithoutPhoto)).isTrue()
        assertThat(maintenanceRequiresPhotos(editorWithoutPhoto)).isTrue()
        assertThat(maintenanceCanAdvance(editorWithoutPhoto, step = 2)).isFalse()
        assertThat(maintenanceCanSubmit(editorWithoutPhoto)).isFalse()

        val editor = editorWithoutPhoto.copy(
            photoUris = listOf(photoUri),
            coverPhotoKey = maintenanceLocalPhotoKey(photoUri),
        )
        assertThat(maintenanceCanAdvance(editor, step = 2)).isTrue()
        assertThat(maintenanceCanAdvance(editor, step = 3)).isTrue()
        assertThat(maintenanceCanAdvance(editor, step = 4)).isTrue()
        assertThat(maintenanceCanSubmit(editor)).isTrue()
    }

    @Test
    fun `empty direct repair also requires a titled photo`() {
        val photoUri = "content://rwms/maintenance/empty-repair.jpg"
        val editorWithoutPhoto = MaintenanceEditorState(
            mode = MaintenanceEditorMode.REPAIR,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = RentalItemDto(
                id = "asset-1",
                version = 1,
                warehouseId = "warehouse-1",
                number = "БК-001",
                status = "FREE",
            ),
            dispatchDate = "2026-07-27",
            sourceParty = "",
            lines = emptyList(),
            photoUris = emptyList(),
            readyMedia = emptyList(),
            priority = 3,
            step = 2,
        )

        assertThat(isEmptyMaintenanceEstimate(editorWithoutPhoto)).isFalse()
        assertThat(maintenanceRequiresPhotos(editorWithoutPhoto)).isTrue()
        assertThat(maintenanceCanAdvance(editorWithoutPhoto, step = 2)).isFalse()
        assertThat(maintenanceCanSubmit(editorWithoutPhoto)).isFalse()

        val editor = editorWithoutPhoto.copy(
            photoUris = listOf(photoUri),
            coverPhotoKey = maintenanceLocalPhotoKey(photoUri),
        )
        assertThat(maintenanceCanAdvance(editor, step = 2)).isTrue()
        assertThat(maintenanceCanAdvance(editor, step = 3)).isTrue()
        assertThat(maintenanceCanSubmit(editor)).isTrue()
    }

    @Test
    fun `rework photo step allows inherited photos and requires a title only for newly added photos`() {
        val photoUri = "content://rwms/maintenance/rework.jpg"
        val editor = MaintenanceEditorState(
            mode = MaintenanceEditorMode.REPAIR,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = RentalItemDto(
                id = "asset-1",
                version = 1,
                warehouseId = "warehouse-1",
                number = "БК-001",
                status = "REPAIR",
            ),
            dispatchDate = "2026-07-27",
            sourceParty = "",
            lines = listOf(line(id = "one", quantity = "1", unitPrice = "100.00")),
            photoUris = emptyList(),
            readyMedia = emptyList(),
            priority = 3,
            step = 2,
            repairKind = "REWORK",
            sourceRepairId = "source-repair-1",
            sourceRepairExpectedVersion = 4,
            reworkReason = "Исправить отделку",
        )

        assertThat(maintenancePhotoStepPolicy(editor).showNext).isTrue()
        assertThat(maintenancePhotoStepPolicy(editor).targetStep).isEqualTo(3)
        assertThat(maintenancePhotoStepPolicy(editor).enabled).isTrue()
        assertThat(
            maintenancePhotoStepPolicy(
                editor.copy(
                    photoUris = listOf(photoUri),
                    coverPhotoKey = maintenanceLocalPhotoKey(photoUri),
                ),
            ).enabled,
        ).isTrue()
        assertThat(editor.lines).hasSize(1)
    }

    @Test
    fun `selected asset never keeps stale choose cabin validation`() {
        val selected = MaintenanceEditorState(
            mode = MaintenanceEditorMode.ESTIMATE,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = RentalItemDto(
                id = "asset-1",
                version = 1,
                warehouseId = "warehouse-1",
                number = "БК-001",
                status = "AFTER_RENT",
            ),
            dispatchDate = "invalid-date",
            sourceParty = "",
            lines = emptyList(),
            photoUris = emptyList(),
            readyMedia = emptyList(),
            priority = 3,
            step = 1,
        )

        assertThat(maintenanceDetailsValidationMessage(selected))
            .isEqualTo("Укажите дату осмотра в формате ГГГГ-ММ-ДД.")
    }

    @Test
    fun `legacy fourth wizard step is normalized and headers omit step labels`() {
        assertThat(maintenanceWizardStep(4)).isEqualTo(3)
        assertThat(maintenanceWizardStep(5)).isEqualTo(5)
        assertThat(maintenanceWizardTitle(1))
            .isEqualTo("Бытовка и основные данные")
        assertThat(maintenanceWizardTitle(3)).isEqualTo("Каталог работ")
        assertThat(maintenanceWizardTitle(5)).isEqualTo("Проверка и действия")
    }

    @Test
    fun totalIsPresentedWithTwoDecimalPlaces() {
        assertThat(formatMaintenanceMoney(BigDecimal("10"))).isEqualTo("10,00 ₽")
    }

    @Test
    fun `custom material keeps explicit material type and zero duration`() {
        val material = MaintenanceLineEditorState(
            id = "material-1",
            catalogNodeId = null,
            description = "Крепёж",
            lineType = "MATERIAL",
            unit = "шт.",
            quantity = "2",
            unitPrice = "15.00",
            normativeMinutes = 0,
            comment = "Для текущего этапа",
            customRouting = RoutingSnapshotDto("queue-1", "Ремонт", "REPAIR"),
        )

        assertThat(maintenanceLineIsValid(material)).isTrue()
        assertThat(maintenanceLineIsValid(material.copy(normativeMinutes = 10))).isFalse()
    }

    @Test
    fun `material-only document passes catalog and submit validation`() {
        val photoUri = "content://rwms/maintenance/material-only.jpg"
        val material = MaintenanceLineEditorState(
            id = "material-1",
            catalogNodeId = null,
            description = "Герметик",
            lineType = "MATERIAL",
            unit = "шт.",
            quantity = "1",
            unitPrice = "250.00",
            normativeMinutes = 0,
            comment = "",
            customRouting = RoutingSnapshotDto("queue-1", "Ремонт", "REPAIR"),
        )
        val editor = MaintenanceEditorState(
            mode = MaintenanceEditorMode.REPAIR,
            entityId = null,
            expectedVersion = null,
            readOnly = false,
            selectedAsset = RentalItemDto(
                id = "asset-1",
                version = 1,
                warehouseId = "warehouse-1",
                number = "БК-001",
                status = "FREE",
            ),
            dispatchDate = "2026-07-27",
            sourceParty = "",
            lines = listOf(material),
            photoUris = listOf(photoUri),
            readyMedia = emptyList(),
            priority = 3,
            step = 3,
            coverPhotoKey = maintenanceLocalPhotoKey(photoUri),
        )

        assertThat(maintenanceCanAdvance(editor, step = 3)).isTrue()
        assertThat(maintenanceCanSubmit(editor)).isTrue()
    }

    private fun line(
        id: String,
        quantity: String,
        unitPrice: String,
    ) = MaintenanceLineEditorState(
        id = id,
        catalogNodeId = "catalog-$id",
        description = "Работа $id",
        lineType = "WORK",
        unit = "шт.",
        quantity = quantity,
        unitPrice = unitPrice,
        normativeMinutes = 10,
        comment = "",
    )
}
