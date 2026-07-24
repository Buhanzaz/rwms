import type {
  InventoryCompletionPreview,
  InventoryFinding,
  InventoryFrozenPlanLine,
  InventoryFrozenStatistics,
  InventorySessionSummary,
} from "@/features/inventory/model/inventory-service"
import type {
  InventoryConflictCode,
  InventoryConflictDto,
  InventoryFindingDto,
  InventoryFindingPublicationStatus,
  InventoryPublicationStatus,
  InventoryRepairPlanSnapshotDto,
  InventorySessionDto,
  InventoryStatisticsDto,
  InventoryWarehouseSnapshot,
} from "@/features/inventory/model/inventory"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"
import type { RentalItemStatus } from "@/features/rental-items/model/rental-item"

const rentalItemStatuses = new Set<RentalItemStatus>([
  "NEW",
  "RENTED",
  "BOOKED",
  "REPAIR",
  "WAITING_REPAIR_CHECK",
  "WRITTEN_OFF",
  "CAPITAL_REPAIR",
  "AFTER_RENT",
  "WAITING_ESTIMATE_CONFIRMATION",
  "SALE",
  "USED_SALE",
  "RESERVED",
  "FREE",
  "WAREHOUSE",
  "OWN_NEEDS",
  "IN_TRANSFER",
])

const riskMessage: Record<
  InventoryCompletionPreview["risks"][number]["code"],
  string
> = {
  MISSING: "Бытовка не найдена при сверке",
  CONFLICT: "Сервис обнаружил конфликт актуального реестра",
  ASSET_CHANGED: "Бытовка изменилась после начала инвентаризации",
  MEDIA_NOT_READY: "Медиа осмотра ещё не готово",
  PLAN_STALE: "План работ требует повторной проверки",
  MUTATION_IN_FLIGHT: "Операция с бытовкой ещё выполняется",
}

function moneyFromMinor(value: number) {
  return (value / 100).toFixed(2)
}

function quantity(value: string) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function rentalItemStatus(value: string): RentalItemStatus | null {
  return rentalItemStatuses.has(value as RentalItemStatus)
    ? (value as RentalItemStatus)
    : null
}

function publicationStatus(
  value: InventoryFinding["publication"]
): InventoryFindingPublicationStatus {
  if (!value) return "NOT_REQUIRED"
  if (value.state === "READY") return "READY"
  if (value.state === "PENDING") return "PUBLISHING"
  if (value.state === "SUCCEEDED") return "PUBLISHED"
  if (value.state === "TRANSIENT_FAILED") return "FAILED"
  if (value.state === "BLOCKED" || value.state === "CLOSED_BLOCKED") {
    return "BLOCKED"
  }
  return "NOT_REQUIRED"
}

function aggregatePublicationStatus(
  value: InventorySessionSummary["publicationState"]
): InventoryPublicationStatus {
  if (value === "SUCCEEDED") return "PUBLISHED"
  if (value === "BLOCKED") return "FAILED"
  return value
}

function viewLine(
  line: InventoryFrozenPlanLine,
  stageCodeByCatalogNode: Map<string, string>
): RepairEstimateLineDto {
  const itemQuantity = quantity(line.quantity)
  const unitPrice = moneyFromMinor(line.unitPriceMinor)
  return {
    id: line.id,
    sourceLineKey: line.id,
    lineType: line.lineType,
    description: line.description,
    lineComment: line.groupComment ?? "",
    unit: line.unit,
    quantity: itemQuantity,
    unitPrice,
    lineTotal: moneyFromMinor(line.unitPriceMinor * itemQuantity),
    catalogSnapshot: line.catalogNodeId
      ? {
          nodeId: line.catalogNodeId,
          code:
            stageCodeByCatalogNode.get(line.catalogNodeId) ??
            line.catalogNodeId,
          name: line.description,
          nodeType: line.lineType,
          furnitureEquipment: null,
        }
      : null,
  }
}

function viewPlans(
  finding: InventoryFinding,
  lines: RepairEstimateLineDto[]
): InventoryRepairPlanSnapshotDto[] {
  if (!finding.frozenPlan) return []
  const repairStages = finding.frozenPlan.stages.filter(
    (stage) => stage.kind === "REPAIR_WORK"
  )
  return finding.frozenPlan.stages.map((stage, index) => {
    const matchedLines = lines.filter(
      (line) => line.catalogSnapshot?.nodeId === stage.catalogNodeId
    )
    const includedLines =
      stage.kind !== "REPAIR_WORK"
        ? []
        : matchedLines.length > 0
          ? matchedLines
          : repairStages[0]?.id === stage.id
            ? lines
            : []
    return {
      id: stage.id,
      kind: stage.kind,
      includedLineIds: includedLines.map((line) => line.id),
      primaryLineId:
        includedLines.find((line) => line.lineType === "WORK")?.id ?? null,
      groupComment: Array.from(
        new Set(
          includedLines.map((line) => line.lineComment.trim()).filter(Boolean)
        )
      ).join("; "),
      queueCode: stage.routingQueueCode,
      routeQueueKind:
        stage.routingQueueKind === "REPAIR" ||
        stage.routingQueueKind === "MOVEMENT" ||
        stage.routingQueueKind === "HOLDING"
          ? stage.routingQueueKind
          : null,
      sortOrder: (stage.order + 1) * 10 || (index + 1) * 10,
      plannedDurationMinutes: stage.normativeDurationMinutes,
      photoRequired: stage.photoRequired,
    }
  })
}

function viewSnapshot(
  snapshot:
    InventoryFinding["expectedSnapshot"] | InventoryFinding["currentSnapshot"]
) {
  if (!snapshot) return null
  const status = rentalItemStatus(snapshot.status)
  if (!status) return null
  return {
    rentalItemId: snapshot.assetId,
    number: snapshot.displayCanonicalNumber,
    canonicalNumber: snapshot.displayCanonicalNumber,
    warehouseId: snapshot.warehouseId,
    status,
    tenant: snapshot.tenantSnapshot,
  }
}

export function toInventoryFindingView(
  finding: InventoryFinding
): InventoryFindingDto {
  const expectedSnapshot = viewSnapshot(finding.expectedSnapshot)
  const currentSnapshot = viewSnapshot(finding.currentSnapshot)
  const stageCodeByCatalogNode = new Map(
    (finding.frozenPlan?.stages ?? []).map((stage) => [
      stage.catalogNodeId,
      stage.catalogNodeCode,
    ])
  )
  const lines =
    finding.frozenPlan?.lines.map((line) =>
      viewLine(line, stageCodeByCatalogNode)
    ) ?? []
  return {
    id: finding.id,
    version: finding.findingRevision,
    rentalItemId: finding.assetId,
    canonicalNumber: finding.displayCanonicalNumber,
    cabinNumber: finding.displayCanonicalNumber,
    origin: finding.origin,
    inspectionStatus: finding.inspection,
    reconciliationStatus: finding.reconciliation,
    expectedSnapshot,
    currentSnapshot,
    conflicts: finding.conflicts.map((conflict) => ({ ...conflict })),
    comment: finding.comment,
    media: finding.media.map((reference) => ({ ...reference })),
    lines,
    repairCompletionMode: finding.frozenPlan?.mode ?? null,
    movementRequired:
      finding.frozenPlan?.stages.some((stage) => stage.movementRequired) ??
      false,
    repairPlans: viewPlans(finding, lines),
    publicationStatus: publicationStatus(finding.publication),
    publicationOperationKey: finding.publication?.id ?? null,
    publishedRepairTaskId: finding.publication?.maintenanceRepairId ?? null,
    publicationError: finding.publication?.failureCode ?? null,
  }
}

export function toInventoryStatisticsView(
  statistics: InventoryFrozenStatistics
): InventoryStatisticsDto {
  return {
    durationSeconds: statistics.durationSeconds,
    expectedCount: statistics.expectedCount,
    inspectedCount: statistics.inspectedCount,
    missingCount: statistics.missingCount,
    readyCount: statistics.readyCount,
    withWorkCount: statistics.withWorkCount,
    addedCount: statistics.addedCount,
    conflictCount: statistics.conflictCount,
    workLineCount: statistics.workLineCount,
    materialLineCount: statistics.materialLineCount,
    plannedDurationMinutes: quantity(statistics.normativeMinutes),
    workTotal: moneyFromMinor(statistics.workTotalMinor),
    materialTotal: moneyFromMinor(statistics.materialTotalMinor),
    grandTotal: moneyFromMinor(statistics.grandTotalMinor),
    aggregates: statistics.aggregateLines.map((line, index) => ({
      key: `${line.catalogNodeId ?? line.normalizedDescription ?? "manual"}:${index}`,
      lineType: line.type,
      description: line.normalizedDescription ?? "Позиция без наименования",
      catalogNodeId: line.catalogNodeId,
      unit: line.unit,
      unitPrice: moneyFromMinor(line.unitPriceMinor),
      quantity: quantity(line.quantity),
      total: moneyFromMinor(line.rowTotalMinor),
    })),
  }
}

export function toInventorySessionView(input: {
  session: InventorySessionSummary
  warehouse?: InventoryWarehouseSnapshot | null
  findings?: InventoryFinding[]
  statistics?: InventoryFrozenStatistics | null
}): InventorySessionDto {
  const findings = (input.findings ?? []).map((finding) =>
    toInventoryFindingView(finding)
  )
  const warehouse =
    input.warehouse?.id === input.session.warehouseId
      ? input.warehouse
      : {
          id: input.session.warehouseId,
          code: "—",
          name: "Склад",
          timeZone: input.session.warehouseTimeZone,
        }
  return {
    id: input.session.id,
    version: input.session.sessionRevision,
    warehouseId: input.session.warehouseId,
    status: input.session.lifecycle,
    warehouse,
    author: {
      id: input.session.author.id,
      displayName: input.session.author.displayName,
      permissions: [],
      authorizedWarehouseIds: null,
    },
    businessDate: input.session.businessDate,
    startedAt: input.session.startedAt,
    completedAt: input.session.terminalAt,
    findingCount: input.session.findingCount,
    inspectedCount: input.session.inspectedCount,
    findings,
    statistics: input.statistics
      ? toInventoryStatisticsView(input.statistics)
      : null,
    publicationStatus: aggregatePublicationStatus(
      input.session.publicationState
    ),
  }
}

function riskConflict(
  risk: InventoryCompletionPreview["risks"][number]
): InventoryConflictDto | null {
  if (risk.code === "MISSING") return null
  const code: InventoryConflictCode =
    risk.code === "CONFLICT" ? "SERVER_CONFLICT" : risk.code
  return {
    code,
    message: riskMessage[risk.code],
    expected: null,
    actual: null,
  }
}

export function applyInventoryCompletionPreview(
  session: InventorySessionDto,
  preview: InventoryCompletionPreview
): InventorySessionDto {
  const risks = new Map<string, InventoryConflictDto[]>()
  for (const risk of preview.risks) {
    const conflict = riskConflict(risk)
    if (!conflict) continue
    const current = risks.get(risk.findingId) ?? []
    current.push(conflict)
    risks.set(risk.findingId, current)
  }
  const validatedByFinding = new Map(
    preview.validatedFindings.map((finding) => [finding.findingId, finding])
  )
  return {
    ...session,
    version: preview.sessionRevision,
    findings: session.findings.map((finding) => {
      const validated = validatedByFinding.get(finding.id)
      const currentSnapshot = validated
        ? viewSnapshot(validated.currentSnapshot)
        : finding.currentSnapshot
      const registryConflicts = validated?.conflicts ?? finding.conflicts
      const conflicts = [
        ...registryConflicts,
        ...(risks.get(finding.id) ?? []).filter(
          (candidate) =>
            !registryConflicts.some(
              (existing) => existing.code === candidate.code
            )
        ),
      ]
      const missing = conflicts.some((conflict) =>
        ["RENTAL_ITEM_MISSING", "OTHER_WAREHOUSE", "WRITTEN_OFF"].includes(
          conflict.code
        )
      )
      return {
        ...finding,
        currentSnapshot,
        reconciliationStatus: missing
          ? ("MISSING" as const)
          : conflicts.length > 0
            ? ("CONFLICT" as const)
            : finding.reconciliationStatus,
        conflicts,
      }
    }),
    statistics: toInventoryStatisticsView(preview.statistics),
  }
}
