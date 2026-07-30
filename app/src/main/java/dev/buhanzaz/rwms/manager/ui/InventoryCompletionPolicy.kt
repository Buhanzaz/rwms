package dev.buhanzaz.rwms.manager.ui

import dev.buhanzaz.rwms.manager.network.InventoryCompletionPreviewDto
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.InventoryRevisionExpectationDto

internal val inventoryBlockingCompletionRiskCodes = setOf(
    "CONFLICT",
    "ASSET_CHANGED",
    "MEDIA_NOT_READY",
    "PLAN_STALE",
    "MUTATION_IN_FLIGHT",
)

internal fun inventoryCompletionRevisionExpectations(
    findings: List<InventoryFindingDto>,
): List<InventoryRevisionExpectationDto> =
    findings
        .sortedBy(InventoryFindingDto::id)
        .map { finding ->
            InventoryRevisionExpectationDto(
                findingId = finding.id,
                expectedFindingRevision = finding.findingRevision,
            )
        }

internal fun InventoryCompletionPreviewDto.inventoryCompletionRiskCounts(): Map<String, Int> =
    risks.groupingBy { it.code }.eachCount()

internal fun InventoryCompletionPreviewDto.inventoryBlockingCompletionRisks() =
    risks.filter { it.code in inventoryBlockingCompletionRiskCodes }

internal fun mergeInventoryCompletionValidation(
    findings: List<InventoryFindingDto>,
    preview: InventoryCompletionPreviewDto,
): List<InventoryFindingDto> {
    val validatedByFindingId = preview.validatedFindings.associateBy { it.findingId }
    return findings.map { finding ->
        val validated = validatedByFindingId[finding.id] ?: return@map finding
        finding.copy(
            currentSnapshot = validated.currentSnapshot,
            conflicts = if (finding.inspection == "NOT_INSPECTED") {
                emptyList()
            } else {
                validated.conflicts
            },
        )
    }
}

internal fun inventoryCompletionValidationError(
    preview: InventoryCompletionPreviewDto,
    confirmNotInspected: Boolean,
    confirmMissing: Boolean,
): String? {
    val riskCounts = preview.inventoryCompletionRiskCounts()
    if (preview.inventoryBlockingCompletionRisks().isNotEmpty()) {
        return "Сначала устраните блокирующие риски инвентаризации"
    }
    if (riskCounts.getOrDefault("NOT_INSPECTED", 0) > 0 && !confirmNotInspected) {
        return "Подтвердите завершение с непроверенными бытовками"
    }
    if (riskCounts.getOrDefault("MISSING", 0) > 0 && !confirmMissing) {
        return "Подтвердите отсутствующие бытовки"
    }
    if (!preview.acknowledgementSha256.isSha256() || !preview.validationSha256.isSha256()) {
        return "Проверка завершения повреждена. Обновите её"
    }
    return null
}

internal fun InventoryCompletionPreviewDto.canCompleteInventory(
    confirmNotInspected: Boolean,
    confirmMissing: Boolean,
): Boolean = inventoryCompletionValidationError(
    preview = this,
    confirmNotInspected = confirmNotInspected,
    confirmMissing = confirmMissing,
) == null

internal fun inventoryCompletionRiskLabel(code: String): String =
    when (code) {
        "NOT_INSPECTED" -> "Не проверена"
        "MISSING" -> "Не найдена"
        "CONFLICT" -> "Не разрешён конфликт"
        "ASSET_CHANGED" -> "Данные бытовки изменились"
        "MEDIA_NOT_READY" -> "Фото ещё обрабатывается"
        "PLAN_STALE" -> "План ремонта устарел"
        "MUTATION_IN_FLIGHT" -> "Изменение ещё выполняется"
        else -> code
    }

private fun String.isSha256(): Boolean =
    length == 64 && all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }
