package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CabinCatalogValueDto
import dev.buhanzaz.rwms.manager.network.CabinTypeDimensionDto
import dev.buhanzaz.rwms.manager.network.EquipmentCatalogItemDto
import dev.buhanzaz.rwms.manager.network.RentalItemCreationOptionsDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import org.junit.Test

class InventoryEditorStatePolicyTest {
    @Test
    fun `new and used origins enforce service categories`() {
        val editor = creationEditor()

        val newCabin = editor.withInventoryCreationOrigin("ADDED_NEW")
        val usedCabin = newCabin.withInventoryCreationOrigin("ADDED_USED")

        assertThat(newCabin.category).isEqualTo("Новая")
        assertThat(usedCabin.category).isEmpty()
        assertThat(usedCabin.inventoryCategoryOptions())
            .containsExactly("ИТР", "Обычная")
            .inOrder()
    }

    @Test
    fun `type selection picks first dimension allowed by canonical type link`() {
        val selected = creationEditor()
            .withInventoryCreationOrigin("ADDED_NEW")
            .withInventoryRentalType("БК-Санблок")

        assertThat(selected.dimensions).isEqualTo("2.4x6")
        assertThat(selected.isSanitary).isTrue()
    }

    @Test
    fun `sanitary counters are stored with selected characteristics`() {
        val editor = creationEditor()
            .withInventoryCreationOrigin("ADDED_NEW")
            .withInventoryRentalType("БК-Санблок")
            .copy(
                characteristics = listOf("Пластиковое окно"),
                sanitaryToilets = 2,
                sanitarySinks = 1,
                sanitaryShowers = 3,
            )

        assertThat(editor.inventoryCharacteristics()).containsExactly(
            "Пластиковое окно",
            "Туалеты: 2",
            "Раковины: 1",
            "Душевые: 3",
        ).inOrder()

        val parsed = parseInventoryCharacteristics(
            "Пластиковое окно, Туалеты: 2, Раковины: 1, Душевые: 3",
        )
        assertThat(parsed.selected).containsExactly("Пластиковое окно")
        assertThat(parsed.toilets).isEqualTo(2)
        assertThat(parsed.sinks).isEqualTo(1)
        assertThat(parsed.showers).isEqualTo(3)
    }

    @Test
    fun `empty serialized characteristics do not become a visible bracket placeholder`() {
        val parsedArray = parseInventoryCharacteristics(emptyList<String>())
        val parsedLegacyText = parseInventoryCharacteristics("[]")

        assertThat(parsedArray.selected).isEmpty()
        assertThat(parsedLegacyText.selected).isEmpty()
        assertThat(inventoryCharacteristicsDisplayValue(parsedArray.selected)).isEmpty()
        assertThat(
            inventoryCharacteristicsDisplayValue(
                listOf("[]", "Пластиковое окно", "Пластиковое окно"),
            ),
        ).isEqualTo("Пластиковое окно")
    }

    @Test
    fun `creation validation requires complete server values and photo`() {
        val incomplete = creationEditor().withInventoryCreationOrigin("ADDED_USED")
        assertThat(incomplete.inventoryCreationValidationError(hasCreationPhoto = false))
            .isEqualTo("Выберите категорию бытовки")

        val complete = incomplete
            .copy(category = "Обычная")
            .withInventoryRentalType("БК-1")
            .copy(
                finishing = "ДВП",
                characteristics = listOf("Пластиковое окно"),
            )

        assertThat(complete.inventoryCreationValidationError(hasCreationPhoto = false))
            .isEqualTo("Для добавляемой бытовки обязательна фотография")
        assertThat(
            complete.inventoryCreationValidationError(
                hasCreationPhoto = true,
                hasCoverPhoto = false,
            ),
        ).isEqualTo("Выберите титульную фотографию")
        assertThat(
            complete.inventoryCreationValidationError(
                hasCreationPhoto = true,
                hasCoverPhoto = true,
            ),
        ).isNull()
    }

    @Test
    fun `creation validation requires explicit linoleum choice`() {
        val editor = creationEditor()
            .withInventoryCreationOrigin("ADDED_USED")
            .copy(category = "Обычная")
            .withInventoryRentalType("БК-1")
            .copy(
                finishing = "ДВП",
                linoleum = null,
            )

        assertThat(
            editor.inventoryCreationValidationError(
                hasCreationPhoto = true,
                hasCoverPhoto = true,
            ),
        ).isEqualTo("Укажите, есть ли линолеум")
    }

    @Test
    fun `title photo is uploaded first without duplicating it`() {
        val editor = creationEditor().copy(
            photoUris = listOf("content://one", "content://two", "content://three"),
            coverPhotoUri = "content://two",
        )

        assertThat(editor.inventoryPhotoUrisForUpload()).containsExactly(
            "content://two",
            "content://one",
            "content://three",
        ).inOrder()
    }

    @Test
    fun `final inspection requires a photograph and explicit title selection`() {
        val withoutPhoto = creationEditor().copy(
            outcome = "MATCHED",
            photoUris = emptyList(),
            coverPhotoUri = null,
        )
        assertThat(withoutPhoto.inventoryPhotoValidationError())
            .isEqualTo("Добавьте хотя бы одну фотографию")
        assertThat(
            runCatching { withoutPhoto.inventoryPhotoUrisForUpload() }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)

        val withoutTitle = withoutPhoto.copy(
            outcome = "MATCHED",
            photoUris = listOf("content://one", "content://two"),
            coverPhotoUri = null,
        )

        assertThat(withoutTitle.inventoryPhotoValidationError())
            .isEqualTo("Выберите титульную фотографию")
        assertThat(
            runCatching { withoutTitle.inventoryPhotoUrisForUpload() }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)

        val selected = withoutTitle.copy(coverPhotoUri = "content://two")
        assertThat(selected.inventoryPhotoValidationError()).isNull()
        assertThat(selected.inventoryPhotoUrisForUpload()).containsExactly(
            "content://two",
            "content://one",
        ).inOrder()
    }

    @Test
    fun `selected ready title retains only ready historical media`() {
        val title = MediaReferenceDto("title", 3)
        val secondNew = MediaReferenceDto("second", 1)
        val readyHistorical = MediaReferenceDto("historical-ready", 7)
        val staleHistorical = MediaReferenceDto("historical-stale", 4)
        val editor = creationEditor().copy(
            outcome = "MATCHED",
            photoUris = listOf("content://second", "content://title"),
            coverPhotoUri = "content://title",
        )
        val persisted = inventoryReadyPersistedMediaReferences(
            persisted = listOf(readyHistorical, staleHistorical),
            readyOwnerReferences = setOf(readyHistorical),
        )

        assertThat(
            inventoryMediaReferencesForEditor(
                editor = editor,
                uploadedByUri = mapOf(
                    "content://title" to title,
                    "content://second" to secondNew,
                ),
                persisted = persisted,
            ),
        ).containsExactly(title, secondNew, readyHistorical).inOrder()
    }

    @Test
    fun `selected title must have a ready media reference before save`() {
        val editor = creationEditor().copy(
            outcome = "MATCHED",
            photoUris = listOf("content://title"),
            coverPhotoUri = "content://title",
        )

        val failure = runCatching {
            inventoryMediaReferencesForEditor(
                editor = editor,
                uploadedByUri = emptyMap(),
                persisted = emptyList(),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure?.message).isEqualTo("Выбранная фотография ещё не готова к сохранению")
    }

    @Test
    fun `completed upload is retained across command failure and is not uploaded twice`() {
        val first = "content://first"
        val title = "content://title"
        val firstReference = MediaReferenceDto("first-media", 2)
        val titleReference = MediaReferenceDto("title-media", 3)
        val editor = creationEditor().copy(
            photoUris = listOf(first, title),
            coverPhotoUri = title,
            uploadedPhotoMedia = mapOf(
                first to firstReference,
                title to titleReference,
            ),
        )

        assertThat(editor.pendingInventoryPhotoUris()).isEmpty()
        assertThat(
            inventoryMediaReferencesForEditor(
                editor = editor,
                uploadedByUri = editor.uploadedPhotoMedia,
                persisted = emptyList(),
            ),
        ).containsExactly(titleReference, firstReference).inOrder()
    }

    @Test
    fun `each ready upload is retained before the rest of the batch completes`() {
        val title = "content://title"
        val remaining = "content://remaining"
        val titleReference = MediaReferenceDto("title-media", 3)
        val editor = creationEditor().copy(
            photoUris = listOf(title, remaining),
            coverPhotoUri = title,
        )

        val afterTitle = editor.retainUploadedInventoryPhoto(title, titleReference)

        assertThat(afterTitle.uploadedPhotoMedia).containsExactly(title, titleReference)
        assertThat(afterTitle.pendingInventoryPhotoUris()).containsExactly(remaining)
    }

    @Test
    fun `photo rotation keeps its original uri and records an absolute media orientation`() {
        val uri = "content://original"
        val editor = creationEditor().copy(photoUris = listOf(uri), coverPhotoUri = uri)

        val once = editor.rotateInventoryPhoto(uri)
        val twice = once.rotateInventoryPhoto(uri)
        val fullTurn = twice.rotateInventoryPhoto(uri).rotateInventoryPhoto(uri)

        assertThat(once.photoUris).containsExactly(uri)
        assertThat(once.coverPhotoUri).isEqualTo(uri)
        assertThat(once.photoRotationDegrees).containsExactly(uri, 90)
        assertThat(twice.photoRotationDegrees).containsExactly(uri, 180)
        assertThat(fullTurn.photoRotationDegrees).isEmpty()
    }

    @Test
    fun `furniture snapshot keeps only positive catalog quantities`() {
        val observation = creationEditor()
            .copy(
                equipmentCatalog = equipmentCatalog(),
                equipmentObservationRequested = true,
                equipmentQuantities = mapOf(
                    "table" to "2",
                    "chair" to "0",
                ),
            )
            .inventoryEquipmentObservation()

        assertThat(observation.presence).isEqualTo("PRESENT")
        @Suppress("UNCHECKED_CAST")
        val observed = observation.value as List<Map<String, Any?>>
        assertThat(observed).hasSize(1)
        assertThat(observed.single()).containsExactly(
            "equipmentId", "table",
            "equipmentName", "Стол",
            "equipmentCategory", "FURNITURE",
            "catalogVersion", 4L,
            "quantity", 2L,
        )
    }

    @Test
    fun `all zero furniture quantities send explicit empty snapshot`() {
        val observation = creationEditor()
            .copy(
                equipmentCatalog = equipmentCatalog(),
                equipmentObservationRequested = true,
            )
            .inventoryEquipmentObservation()

        assertThat(observation.presence).isEqualTo("EXPLICIT_EMPTY")
        assertThat(observation.value).isEqualTo(emptyList<Map<String, Any?>>())
    }

    @Test
    fun `no furniture answer sends an explicit empty snapshot`() {
        val observation = creationEditor()
            .copy(equipmentObservationRequested = false)
            .inventoryEquipmentObservation()

        assertThat(observation.presence).isEqualTo("EXPLICIT_EMPTY")
        assertThat(observation.value).isEqualTo(emptyList<Map<String, Any?>>())
    }

    @Test
    fun `furniture snapshot rejects quantities outside current catalog`() {
        val editor = creationEditor().copy(
            equipmentCatalog = equipmentCatalog(),
            equipmentObservationRequested = true,
            equipmentQuantities = mapOf("removed" to "1"),
        )

        assertThat(editor.inventoryEquipmentObservationValidationError())
            .isEqualTo("Состав оборудования изменился. Откройте проверку заново")
    }

    @Test
    fun `inventory suggestions match cabins and exact comparison ignores case`() {
        val items = listOf(
            rentalItem("1", "БЫТ-001"),
            rentalItem("2", "БЫТ-010"),
            rentalItem("3", "МСК-100"),
        )

        assertThat(inventoryRentalItemSuggestions(items, "быт-0").map { it.number })
            .containsExactly("БЫТ-001", "БЫТ-010")
            .inOrder()
        assertThat(hasExactInventoryRentalItemNumber(items, " быт-001 ")).isTrue()
        assertThat(hasExactInventoryRentalItemNumber(items, "БЫТ")).isFalse()
    }

    @Test
    fun `unknown existing passport values remain selectable`() {
        val editor = creationEditor().copy(
            outcome = "MATCHED",
            rentalType = "Архивный тип",
            dimensions = "9x9",
            finishing = "Архивная отделка",
            category = "Архивная категория",
            characteristics = listOf("Архивная характеристика"),
        )

        assertThat(editor.inventoryRentalTypeOptions()).contains("Архивный тип")
        assertThat(editor.inventoryDimensionOptions()).contains("9x9")
        assertThat(editor.inventoryFinishingOptions()).contains("Архивная отделка")
        assertThat(editor.inventoryCategoryOptions()).contains("Архивная категория")
        assertThat(editor.inventoryCharacteristicOptions())
            .contains("Архивная характеристика")
    }

    private fun creationEditor() = InventoryEditorState(
        findingId = "finding-1",
        number = "БЫТ-001",
        outcome = "NOT_FOUND",
        creationOptions = RentalItemCreationOptionsDto(
            newCategory = "Новая",
            usedCategories = listOf("ИТР", "Обычная"),
            rentalTypes = listOf(
                CabinCatalogValueDto(id = "type-bk-1", name = "БК-1"),
                CabinCatalogValueDto(id = "type-sanitary", name = "БК-Санблок"),
            ),
            dimensions = listOf(
                CabinCatalogValueDto(id = "dimension-2-4x6", name = "2.4x6"),
            ),
            finishings = listOf(
                CabinCatalogValueDto(id = "finishing-dvp", name = "ДВП"),
                CabinCatalogValueDto(id = "finishing-pvh", name = "ПВХ"),
            ),
            characteristics = listOf(
                CabinCatalogValueDto(id = "characteristic-window", name = "Пластиковое окно"),
                CabinCatalogValueDto(id = "characteristic-door", name = "Металлическая дверь"),
            ),
            typeDimensions = listOf(
                CabinTypeDimensionDto(
                    typeId = "type-bk-1",
                    dimensionId = "dimension-2-4x6",
                    sortOrder = 0,
                ),
                CabinTypeDimensionDto(
                    typeId = "type-sanitary",
                    dimensionId = "dimension-2-4x6",
                    sortOrder = 0,
                ),
            ),
        ),
        characteristics = listOf("Пластиковое окно"),
        linoleum = false,
    )

    private fun rentalItem(id: String, number: String) = RentalItemDto(
        id = id,
        version = 1,
        warehouseId = "warehouse-1",
        number = number,
        status = "AVAILABLE",
    )

    private fun equipmentCatalog() = listOf(
        EquipmentCatalogItemDto(
            id = "chair",
            version = 2,
            name = "Стул",
            category = "FURNITURE",
            active = true,
        ),
        EquipmentCatalogItemDto(
            id = "table",
            version = 4,
            name = "Стол",
            category = "FURNITURE",
            active = true,
        ),
        EquipmentCatalogItemDto(
            id = "archived",
            version = 1,
            name = "Архивный шкаф",
            category = "FURNITURE",
            active = false,
        ),
    )
}
