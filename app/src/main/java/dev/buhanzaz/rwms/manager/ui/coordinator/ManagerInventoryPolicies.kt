package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.CabinCatalogValueDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RentalItemDto
import dev.buhanzaz.rwms.manager.network.RentalItemCreationOptionsDto

/**
 * Pure inventory editor reducers and validation policies. They preserve screen-facing package
 * names while moving non-Android state semantics out of the view-model facade.
 */

internal fun InventoryEditorState.passport(): Map<String, Any?> =
    buildMap {
        rentalType.trim().takeIf(String::isNotEmpty)?.let { put("rentalType", it) }
        dimensions.trim().takeIf(String::isNotEmpty)?.let { put("dimensions", it) }
        finishing.trim().takeIf(String::isNotEmpty)?.let { put("finishing", it) }
        category.trim().takeIf(String::isNotEmpty)?.let { put("category", it) }
        inventoryCharacteristics().takeIf(List<String>::isNotEmpty)?.let {
            put("characteristics", it.joinToString(", "))
        }
        linoleum?.let { put("linoleum", it) }
        put("passport", emptyMap<String, Any?>())
        put("tags", emptyList<String>())
    }

internal fun InventoryEditorState.observationPassport(): Map<String, Any?> =
    buildMap {
        rentalType.trim().takeIf(String::isNotEmpty)?.let { put("rentalType", it) }
        dimensions.trim().takeIf(String::isNotEmpty)?.let { put("dimensions", it) }
        finishing.trim().takeIf(String::isNotEmpty)?.let { put("finishing", it) }
        category.trim().takeIf(String::isNotEmpty)?.let { put("category", it) }
        inventoryCharacteristics().takeIf(List<String>::isNotEmpty)?.let {
            put("characteristics", it.joinToString(", "))
        }
        linoleum?.let { put("linoleum", it) }
    }

internal fun InventoryFindingDto.inspectionPassport(): Map<String, Any?> =
    when (passportObservation.presence) {
        "PRESENT" -> passportObservation.value.asStringMap()
        "EXPLICIT_EMPTY" -> emptyMap()
        else -> inspectionBaseline?.passportSnapshot
            ?: expectedSnapshot?.passportSnapshot
            ?: currentSnapshot?.passportSnapshot
            ?: emptyMap()
    }

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
internal data class InventoryPassportFacts(
    val rentalType: String?,
    val dimensions: String?,
    val finishing: String?,
    val category: String?,
    val linoleum: Boolean?,
)

internal fun InventoryFindingDto.inventoryPassportFacts(): InventoryPassportFacts {
    val passport = inspectionPassport()
    return InventoryPassportFacts(
        rentalType = passport.text("rentalType").takeIf(String::isNotBlank),
        dimensions = passport.text("dimensions").takeIf(String::isNotBlank),
        finishing = passport.text("finishing").takeIf(String::isNotBlank),
        category = passport.text("category").takeIf(String::isNotBlank),
        linoleum = passport["linoleum"] as? Boolean,
    )
}

private fun Any?.asStringMap(): Map<String, Any?> {
    val raw = this as? Map<*, *> ?: return emptyMap()
    return buildMap {
        raw.forEach { (key, value) ->
            if (key is String) put(key, value)
        }
    }
}

internal fun Map<String, Any?>.text(key: String): String =
    this[key]?.toString().orEmpty()

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
internal data class ParsedInventoryCharacteristics(
    val selected: List<String>,
    val toilets: Int,
    val sinks: Int,
    val showers: Int,
)

private val sanitaryCharacteristicPattern =
    Regex("""^(Туалеты|Раковины|Душевые):\s*(\d+)$""", RegexOption.IGNORE_CASE)

/**
 * The asset projection may contain characteristics as a JSON array, a comma-separated string,
 * or no value at all. In particular, an empty JSON array must remain an empty selection rather
 * than becoming the literal characteristic `[]` in the editor.
 */
internal fun parseInventoryCharacteristics(value: Any?): ParsedInventoryCharacteristics {
    var toilets = 0
    var sinks = 0
    var showers = 0
    val rawValues = when (value) {
        null -> emptyList()
        is Iterable<*> -> value.mapNotNull { item -> item?.toString() }
        is Array<*> -> value.mapNotNull { item -> item?.toString() }
        else -> listOf(value.toString())
    }
    val selected = buildList {
        rawValues.forEach { rawValue ->
            rawValue.split(',').forEach tokenLoop@ { token ->
                val characteristic = token.trim()
                if (characteristic.isEmpty() || characteristic == "[]") return@tokenLoop
                val match = sanitaryCharacteristicPattern.matchEntire(characteristic)
                if (match == null) {
                    add(characteristic)
                } else {
                    val count = match.groupValues[2].toIntOrNull() ?: 0
                    when (match.groupValues[1].lowercase()) {
                        "туалеты" -> toilets = count
                        "раковины" -> sinks = count
                        "душевые" -> showers = count
                    }
                }
            }
        }
    }
    return ParsedInventoryCharacteristics(
        selected = selected.distinct(),
        toilets = toilets,
        sinks = sinks,
        showers = showers,
    )
}

internal fun InventoryEditorState.inventoryCharacteristics(): List<String> =
    buildList {
        addAll(characteristics.distinct())
        if (isSanitary) {
            add("Туалеты: ${sanitaryToilets.coerceAtLeast(0)}")
            add("Раковины: ${sanitarySinks.coerceAtLeast(0)}")
            add("Душевые: ${sanitaryShowers.coerceAtLeast(0)}")
        }
    }

/** Text shown in the selector. An empty selection deliberately renders as an empty field. */
internal fun inventoryCharacteristicsDisplayValue(characteristics: List<String>): String =
    characteristics.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filterNot { it == "[]" }
        .distinct()
        .joinToString()

internal fun InventoryEditorState.withInventoryCreationOrigin(
    origin: String,
): InventoryEditorState {
    require(origin == "ADDED_NEW" || origin == "ADDED_USED")
    val options = creationOptions
    val selectedCategory = when (origin) {
        "ADDED_NEW" -> options?.newCategory.orEmpty()
        else -> category.takeIf { it in options?.usedCategories.orEmpty() }.orEmpty()
    }
    return copy(
        creationOrigin = origin,
        category = selectedCategory,
    )
}

internal fun InventoryEditorState.withInventoryRentalType(
    value: String,
): InventoryEditorState {
    val firstDimension = creationOptions
        ?.dimensionOptionsForRentalType(value)
        ?.firstOrNull()
        ?.name
        .orEmpty()
    return copy(
        rentalType = value,
        dimensions = firstDimension,
    )
}

internal fun InventoryEditorState.inventoryCategoryOptions(): List<String> {
    val serviceValues = when {
        !isCreation -> listOfNotNull(creationOptions?.newCategory) +
            creationOptions?.usedCategories.orEmpty()
        creationOrigin == "ADDED_NEW" -> listOfNotNull(creationOptions?.newCategory)
        creationOrigin == "ADDED_USED" -> creationOptions?.usedCategories.orEmpty()
        else -> emptyList()
    }
    return (serviceValues + category.takeIf(String::isNotBlank)).filterNotNull().distinct()
}

internal fun InventoryEditorState.inventoryRentalTypeOptions(): List<String> =
    (creationOptions?.rentalTypes.orEmpty().map(CabinCatalogValueDto::name) +
        rentalType.takeIf(String::isNotBlank))
        .filterNotNull()
        .distinct()

internal fun InventoryEditorState.inventoryDimensionOptions(): List<String> =
    (creationOptions?.dimensionOptionsForRentalType(rentalType).orEmpty().map(CabinCatalogValueDto::name) +
        dimensions.takeIf(String::isNotBlank))
        .filterNotNull()
        .distinct()

internal fun InventoryEditorState.inventoryFinishingOptions(): List<String> =
    (creationOptions?.finishings.orEmpty().map(CabinCatalogValueDto::name) +
        finishing.takeIf(String::isNotBlank))
        .filterNotNull()
        .distinct()

internal fun InventoryEditorState.inventoryCharacteristicOptions(): List<String> =
    (creationOptions?.characteristics.orEmpty().map(CabinCatalogValueDto::name) + characteristics)
        .distinct()

internal fun InventoryEditorState.inventoryCreationValidationError(
    hasCreationPhoto: Boolean,
    hasCoverPhoto: Boolean = false,
): String? {
    if (!isCreation) return null
    val options = creationOptions ?: return "Варианты паспорта бытовки не загружены"
    val origin = creationOrigin ?: return "Выберите: новая или б/у"
    if (category.isBlank()) return "Выберите категорию бытовки"
    if (origin == "ADDED_NEW" && category != options.newCategory) {
        return "Для новой бытовки доступна только категория ${options.newCategory}"
    }
    if (origin == "ADDED_USED" && category !in options.usedCategories) {
        return "Выберите категорию б/у бытовки"
    }
    val type = options.rentalTypes.firstOrNull { it.name == rentalType }
        ?: return "Выберите тип бытовки"
    if (dimensions !in options.dimensionOptionsForTypeId(type.id).map(CabinCatalogValueDto::name)) {
        return "Выберите габариты бытовки"
    }
    if (finishing !in options.finishings.map(CabinCatalogValueDto::name)) {
        return "Выберите отделку бытовки"
    }
    if (linoleum == null) return "Укажите, есть ли линолеум"
    if (characteristics.any { it !in options.characteristics.map(CabinCatalogValueDto::name) }) {
        return "Выберите характеристики из списка"
    }
    if (sanitaryToilets < 0 || sanitarySinks < 0 || sanitaryShowers < 0) {
        return "Количество оборудования санблока не может быть отрицательным"
    }
    if (!hasCreationPhoto) return "Для добавляемой бытовки обязательна фотография"
    if (!hasCoverPhoto) return "Выберите титульную фотографию"
    return null
}

private fun RentalItemCreationOptionsDto.dimensionOptionsForRentalType(
    rentalTypeName: String,
): List<CabinCatalogValueDto> =
    rentalTypes
        .firstOrNull { it.name == rentalTypeName }
        ?.let { type -> dimensionOptionsForTypeId(type.id) }
        .orEmpty()

private fun RentalItemCreationOptionsDto.dimensionOptionsForTypeId(
    typeId: String,
): List<CabinCatalogValueDto> {
    val dimensionsById = dimensions.associateBy(CabinCatalogValueDto::id)
    return typeDimensions
        .asSequence()
        .filter { it.typeId == typeId }
        .sortedBy { it.sortOrder }
        .mapNotNull { dimensionsById[it.dimensionId] }
        .toList()
}

internal fun InventoryEditorState.inventoryPhotoUrisForUpload(): List<String> {
    if (photoUris.isEmpty()) {
        if (persistedInventoryMediaReferences().isNotEmpty()) return emptyList()
        throw IllegalArgumentException("Добавьте хотя бы одну фотографию")
    }
    val cover = coverPhotoUri?.takeIf { it in photoUris }
    if (cover != null) return listOf(cover) + photoUris.filterNot { it == cover }
    if (persistedInventoryMediaReferences().isNotEmpty()) return photoUris
    throw IllegalArgumentException("Выберите титульную фотографию")
}

internal fun InventoryEditorState.pendingInventoryPhotoUris(): List<String> =
    inventoryPhotoUrisForUpload().filterNot { uri ->
        uri in uploadedPhotoMedia || uri in persistedPhotoMedia
    }

/**
 * Removes a visible cached server photo from the next inventory save without deleting the media
 * asset itself. Unavailable old references are deliberately retained, because a download failure
 * must never be interpreted as an operator decision to remove evidence.
 */
internal fun InventoryEditorState.removeInventoryPhoto(uri: String): InventoryEditorState {
    if (uri !in photoUris) return this
    val removedReference = persistedPhotoMedia[uri]
    return copy(
        photoUris = photoUris - uri,
        coverPhotoUri = coverPhotoUri.takeUnless { selected -> selected == uri },
        persistedPhotoMedia = persistedPhotoMedia - uri,
        removedPersistedMediaIds = removedReference?.mediaId?.let { mediaId ->
            removedPersistedMediaIds + mediaId
        } ?: removedPersistedMediaIds,
        uploadedPhotoMedia = uploadedPhotoMedia - uri,
    )
}

/** Retains one server-confirmed original without changing the user-selected URI order. */
internal fun InventoryEditorState.retainUploadedInventoryPhoto(
    uri: String,
    reference: MediaReferenceDto,
): InventoryEditorState = if (uri in photoUris) {
    copy(uploadedPhotoMedia = uploadedPhotoMedia + (uri to reference))
} else {
    this
}

internal fun inventoryMediaReferencesForEditor(
    editor: InventoryEditorState,
    uploadedByUri: Map<String, MediaReferenceDto>,
    persisted: List<MediaReferenceDto>,
): List<MediaReferenceDto> =
    buildList {
        editor.inventoryPhotoUrisForUpload()
            .map { uri ->
                editor.persistedPhotoMedia[uri] ?: requireNotNull(uploadedByUri[uri]) {
                    "Выбранная фотография ещё не готова к сохранению"
                }
            }
            .forEach(::add)
        addAll(persisted)
    }
        .distinctBy(MediaReferenceDto::mediaId)

internal fun InventoryEditorState.persistedInventoryMediaReferences(): List<MediaReferenceDto> =
    finding?.media.orEmpty()
        .filterNot { reference -> reference.mediaId in removedPersistedMediaIds }
        .distinctBy(MediaReferenceDto::mediaId)

internal fun inventoryExistingMediaReferences(
    persisted: List<MediaReferenceDto>,
    uploadedByUri: Map<String, MediaReferenceDto>,
): List<MediaReferenceDto> =
    (persisted + uploadedByUri.values).distinctBy(MediaReferenceDto::mediaId)

internal fun InventoryEditorState.inventoryPhotoValidationError(): String? =
    when {
        photoUris.isEmpty() && persistedInventoryMediaReferences().isEmpty() ->
            "Добавьте хотя бы одну фотографию"
        photoUris.isNotEmpty() &&
            coverPhotoUri !in photoUris &&
            persistedInventoryMediaReferences().isEmpty() ->
            "Выберите титульную фотографию"
        else -> null
    }

internal fun inventoryRentalItemSuggestions(
    items: List<RentalItemDto>,
    query: String,
    limit: Int = 8,
): List<RentalItemDto> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return items.take(limit)
    return items
        .asSequence()
        .filter { it.number.contains(normalized, ignoreCase = true) }
        .take(limit)
        .toList()
}

internal fun hasExactInventoryRentalItemNumber(
    items: List<RentalItemDto>,
    query: String,
): Boolean {
    val normalized = query.trim()
    return normalized.isNotEmpty() &&
        items.any { it.number.equals(normalized, ignoreCase = true) }
}

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
internal data class InventorySemanticChange(
    val label: String,
    val before: String,
    val after: String,
)

internal fun InventoryFindingDto.inventorySemanticChanges(): List<InventorySemanticChange> {
    val before = inspectionBaseline ?: return emptyList()
    val after = currentSnapshot ?: return emptyList()
    return buildList {
        addInventorySemanticChange(
            label = "Статус",
            before = inventoryBusinessStatusLabel(before.status),
            after = inventoryBusinessStatusLabel(after.status),
        )
        addInventorySemanticChange(
            label = "Номер",
            before = before.displayCanonicalNumber,
            after = after.displayCanonicalNumber,
        )
        addInventorySemanticChange(
            label = "Склад",
            before = before.warehouseId,
            after = after.warehouseId,
        )
        addInventorySemanticChange(
            label = "Контрагент",
            before = before.tenantSnapshot.orEmpty().ifBlank { "Не указан" },
            after = after.tenantSnapshot.orEmpty().ifBlank { "Не указан" },
        )
        addInventorySemanticChange(
            label = "Паспорт",
            before = formatInventorySnapshotValue(before.passportSnapshot),
            after = formatInventorySnapshotValue(after.passportSnapshot),
        )
        addInventorySemanticChange(
            label = "Комплектация",
            before = formatInventorySnapshotValue(before.contentsSnapshot),
            after = formatInventorySnapshotValue(after.contentsSnapshot),
        )
        addInventorySemanticChange(
            label = "Ремонты",
            before = formatInventoryRepairs(before.repairsSnapshot),
            after = formatInventoryRepairs(after.repairsSnapshot),
        )
    }
}

private fun MutableList<InventorySemanticChange>.addInventorySemanticChange(
    label: String,
    before: String,
    after: String,
) {
    if (before != after) add(InventorySemanticChange(label, before, after))
}

private fun formatInventorySnapshotValue(value: Any?): String =
    when (value) {
        null -> "Нет"
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .joinToString("; ") { (key, item) ->
                "$key: ${formatInventorySnapshotValue(item)}"
            }
            .ifBlank { "Нет" }
        is Iterable<*> -> value
            .joinToString("; ") { formatInventorySnapshotValue(it) }
            .ifBlank { "Нет" }
        else -> value.toString().ifBlank { "Нет" }
    }

private fun formatInventoryRepairs(
    repairs: List<dev.buhanzaz.rwms.manager.network.InventoryRepairRegistryFactDto>,
): String = repairs.joinToString("; ") {
    listOf(
        "Ремонт",
        it.kind,
        it.executionState,
        it.acceptanceState,
        "план зафиксирован",
    ).joinToString(" · ")
}.ifBlank { "Нет" }

internal fun InventoryFindingDto.inventoryBusinessStatus(): String? =
    currentSnapshot?.status
        ?: inspectionBaseline?.status
        ?: expectedSnapshot?.status

internal fun inventoryBusinessStatusLabel(value: String): String = when (value) {
    "BOOKED" -> "Забронирована"
    "RENTED" -> "В аренде"
    "REPAIR" -> "Ремонт"
    "WAITING_REPAIR_CHECK" -> "Ожидает проверки ремонта"
    "CAPITAL_REPAIR" -> "Капитальный ремонт"
    "AFTER_RENT" -> "После аренды"
    "WAITING_ESTIMATE_CONFIRMATION" -> "Ожидает подтверждения сметы"
    "SALE" -> "Продажа"
    "USED_SALE" -> "Продажа б/у"
    "RESERVED" -> "Резерв"
    "FREE" -> "Свободная"
    "WAREHOUSE" -> "На складе"
    "OWN_NEEDS" -> "Собственные нужды"
    "IN_TRANSFER" -> "Перемещение"
    "WRITTEN_OFF" -> "Списана"
    else -> value
}

internal fun inventoryInspectionLabel(value: String): String = when (value) {
    "NOT_INSPECTED" -> "Непроверена"
    "READY", "WORK_STAGED" -> "Проверена"
    else -> value
}
