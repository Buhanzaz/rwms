import type {
  InventoryActorSnapshot,
  InventoryAggregateLineDto,
  InventoryConflictDto,
  InventoryFindingDto,
  InventoryPermission,
  InventoryPublicationStatus,
  InventoryRepairPlanSnapshotDto,
  InventoryRentalItemSnapshot,
  InventoryStatisticsDto,
} from "@/features/inventory/model/inventory"
import type {
  MoneyDecimal,
  RepairEstimateLineDto,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"

export function canonicalizeInventoryNumber(value: string) {
  return value.trim().replace(/\s+/g, " ").toLocaleUpperCase("ru-RU")
}

export function assertInventoryPermission(
  actor: InventoryActorSnapshot,
  permission: InventoryPermission
) {
  const levels: InventoryPermission[] = ["VIEW", "EDIT", "MANAGE"]
  const required = levels.indexOf(permission)
  const granted = Math.max(
    -1,
    ...actor.permissions.map((item) => levels.indexOf(item))
  )
  if (granted < required) {
    throw new Error("Недостаточно прав для инвентаризации")
  }
}

export function assertInventoryWarehouseAccess(
  actor: InventoryActorSnapshot,
  warehouseId: string
) {
  if (
    actor.authorizedWarehouseIds !== null &&
    !actor.authorizedWarehouseIds.includes(warehouseId)
  ) {
    throw new Error("Нет доступа к складу инвентаризации")
  }
}

export function toInventoryRentalItemSnapshot(item: {
  id: string
  number: string
  warehouseId: string
  status: InventoryRentalItemSnapshot["status"]
  tenant: string | null
}): InventoryRentalItemSnapshot {
  return {
    rentalItemId: item.id,
    number: item.number,
    canonicalNumber: canonicalizeInventoryNumber(item.number),
    warehouseId: item.warehouseId,
    status: item.status,
    tenant: item.tenant,
  }
}

export function deriveInventoryConflicts(
  expected: InventoryRentalItemSnapshot | null,
  current: InventoryRentalItemSnapshot | null,
  inventoryWarehouseId: string
): InventoryConflictDto[] {
  if (!current) {
    return [
      {
        code: "RENTAL_ITEM_MISSING",
        message: "Бытовка отсутствует в актуальном реестре",
        expected: expected?.rentalItemId ?? null,
        actual: null,
      },
    ]
  }
  const conflicts: InventoryConflictDto[] = []
  if (current.warehouseId !== inventoryWarehouseId) {
    conflicts.push({
      code: "OTHER_WAREHOUSE",
      message: "Бытовка относится к другому складу",
      expected: inventoryWarehouseId,
      actual: current.warehouseId,
    })
  }
  if (current.status === "RENTED") {
    conflicts.push({
      code: "RENTED",
      message: "Бытовка числится в аренде",
      expected: expected?.status ?? null,
      actual: current.status,
    })
  }
  if (current.status === "WRITTEN_OFF") {
    conflicts.push({
      code: "WRITTEN_OFF",
      message: "Бытовка списана",
      expected: expected?.status ?? null,
      actual: current.status,
    })
  }
  if (expected && expected.warehouseId !== current.warehouseId) {
    conflicts.push({
      code: "WAREHOUSE_CHANGED",
      message: "Склад бытовки изменился после начала инвентаризации",
      expected: expected.warehouseId,
      actual: current.warehouseId,
    })
  }
  if (expected && expected.status !== current.status) {
    conflicts.push({
      code: "STATUS_CHANGED",
      message: "Статус бытовки изменился после начала инвентаризации",
      expected: expected.status,
      actual: current.status,
    })
  }
  if (expected && expected.tenant !== current.tenant) {
    conflicts.push({
      code: "TENANT_CHANGED",
      message: "Арендатор бытовки изменился после начала инвентаризации",
      expected: expected.tenant,
      actual: current.tenant,
    })
  }
  return conflicts.filter(
    (conflict, index, all) =>
      all.findIndex((candidate) => candidate.code === conflict.code) === index
  )
}

export function deriveInventoryReconciliationStatus(
  current: InventoryRentalItemSnapshot | null,
  conflicts: InventoryConflictDto[]
): InventoryFindingDto["reconciliationStatus"] {
  if (!current) return "MISSING"
  return conflicts.some((conflict) =>
    ["RENTAL_ITEM_MISSING", "OTHER_WAREHOUSE", "WRITTEN_OFF"].includes(
      conflict.code
    )
  )
    ? "MISSING"
    : "MATCHED"
}

export function inventoryCompletionRiskSignature(
  findings: InventoryFindingDto[]
) {
  const risks = findings
    .filter(
      (finding) =>
        finding.reconciliationStatus === "MISSING" ||
        finding.conflicts.length > 0
    )
    .map((finding) => ({
      findingId: finding.id,
      reconciliationStatus: finding.reconciliationStatus,
      conflicts: finding.conflicts.map((conflict) => ({
        code: conflict.code,
        expected: conflict.expected,
        actual: conflict.actual,
      })),
    }))
  return risks.length > 0 ? JSON.stringify(risks) : ""
}

function parseMinor(value: MoneyDecimal) {
  const normalized = value.trim().replace(",", ".")
  if (!/^-?\d+(?:\.\d{1,2})?$/.test(normalized)) {
    throw new Error(`Некорректная денежная сумма: ${value}`)
  }
  const [whole, fraction = ""] = normalized.split(".")
  const sign = whole.startsWith("-") ? -1n : 1n
  const absoluteWhole = whole.replace("-", "")
  return sign * BigInt(`${absoluteWhole}${fraction.padEnd(2, "0")}`)
}

function formatMinor(value: bigint): MoneyDecimal {
  const sign = value < 0n ? "-" : ""
  const digits = (value < 0n ? -value : value).toString().padStart(3, "0")
  return `${sign}${digits.slice(0, -2)}.${digits.slice(-2)}`
}

function normalizedDescription(value: string) {
  return value.trim().replace(/\s+/g, " ").toLocaleLowerCase("ru-RU")
}

function aggregateLines(lines: RepairEstimateLineDto[]) {
  const groups = new Map<
    string,
    { line: RepairEstimateLineDto; quantity: number; total: bigint }
  >()
  lines.forEach((line) => {
    const catalogNodeId = line.catalogSnapshot?.nodeId ?? null
    const key = catalogNodeId
      ? `catalog:${catalogNodeId}:${line.lineType}:${line.unit}:${line.unitPrice}`
      : `manual:${line.lineType}:${normalizedDescription(line.description)}:${line.unit}:${line.unitPrice}`
    const existing = groups.get(key)
    groups.set(key, {
      line,
      quantity: (existing?.quantity ?? 0) + line.quantity,
      total: (existing?.total ?? 0n) + parseMinor(line.lineTotal),
    })
  })
  return Array.from(groups, ([key, group]): InventoryAggregateLineDto => ({
    key,
    lineType: group.line.lineType,
    description: group.line.description,
    catalogNodeId: group.line.catalogSnapshot?.nodeId ?? null,
    unit: group.line.unit,
    unitPrice: group.line.unitPrice,
    quantity: group.quantity,
    total: formatMinor(group.total),
  }))
}

export function calculateInventoryStatistics(params: {
  startedAt: string
  completedAt: string
  findings: InventoryFindingDto[]
}): InventoryStatisticsDto {
  const lines = params.findings.flatMap((finding) => finding.lines)
  const workLines = lines.filter((line) => line.lineType === "WORK")
  const materialLines = lines.filter((line) => line.lineType === "MATERIAL")
  const sum = (values: RepairEstimateLineDto[]) =>
    values.reduce((total, line) => total + parseMinor(line.lineTotal), 0n)
  const workTotal = sum(workLines)
  const materialTotal = sum(materialLines)
  return {
    durationSeconds: Math.max(
      0,
      Math.floor(
        (Date.parse(params.completedAt) - Date.parse(params.startedAt)) / 1000
      )
    ),
    expectedCount: params.findings.filter(
      (finding) => finding.origin === "EXPECTED"
    ).length,
    inspectedCount: params.findings.filter(
      (finding) => finding.inspectionStatus !== "NOT_INSPECTED"
    ).length,
    missingCount: params.findings.filter(
      (finding) => finding.reconciliationStatus === "MISSING"
    ).length,
    readyCount: params.findings.filter(
      (finding) => finding.inspectionStatus === "READY"
    ).length,
    withWorkCount: params.findings.filter(
      (finding) => finding.inspectionStatus === "WORK_STAGED"
    ).length,
    addedCount: params.findings.filter(
      (finding) =>
        finding.origin === "ADDED_NEW" || finding.origin === "ADDED_USED"
    ).length,
    conflictCount: params.findings.filter(
      (finding) => finding.conflicts.length > 0
    ).length,
    workLineCount: workLines.length,
    materialLineCount: materialLines.length,
    plannedDurationMinutes: params.findings.reduce(
      (total, finding) =>
        total +
        finding.repairPlans.reduce(
          (planTotal, plan) => planTotal + (plan.plannedDurationMinutes ?? 0),
          0
        ),
      0
    ),
    workTotal: formatMinor(workTotal),
    materialTotal: formatMinor(materialTotal),
    grandTotal: formatMinor(workTotal + materialTotal),
    aggregates: aggregateLines(lines),
  }
}

export function deriveInventoryPublicationStatus(
  findings: InventoryFindingDto[]
): InventoryPublicationStatus {
  const statuses = findings
    .filter((finding) => finding.lines.length > 0)
    .map((finding) => finding.publicationStatus)
  if (statuses.length === 0) return "NOT_REQUESTED"
  if (statuses.every((status) => status === "PUBLISHED")) return "PUBLISHED"
  if (statuses.some((status) => status === "PUBLISHED")) return "PARTIAL"
  if (statuses.some((status) => status === "PUBLISHING" || status === "READY"))
    return "PENDING"
  return "FAILED"
}

/**
 * Inventory keeps an immutable copy of the selected repair route. The
 * estimate-only generation fields deliberately do not cross this boundary.
 */
export function toInventoryRepairPlanSnapshot(
  plan: RepairEstimateTaskPlanDto
): InventoryRepairPlanSnapshotDto {
  return {
    id: plan.id,
    kind: plan.kind,
    includedLineIds: [...plan.includedLineIds],
    primaryLineId: plan.primaryLineId,
    groupComment: plan.groupComment,
    queueCode: plan.queueCode,
    routeQueueKind: plan.routeQueueKind,
    sortOrder: plan.sortOrder,
    plannedDurationMinutes: null,
    photoRequired: false,
  }
}

export function toRepairEstimateTaskPlan(
  plan: InventoryRepairPlanSnapshotDto
): RepairEstimateTaskPlanDto {
  return {
    id: plan.id,
    kind: plan.kind,
    includedLineIds: [...plan.includedLineIds],
    primaryLineId: plan.primaryLineId,
    groupComment: plan.groupComment,
    queueCode: plan.queueCode,
    routeQueueKind: plan.routeQueueKind,
    sortOrder: plan.sortOrder,
    generationStatus: "PENDING_GENERATION",
    workflowRequestRef: null,
  }
}

/**
 * Reopening a saved inspection retains its route while making the plan valid
 * for edited lines. New unassigned lines receive a freshly prepared plan;
 * deleted lines are removed from their old plan.
 */
export function reconcileInventoryRepairTaskPlans(params: {
  lines: RepairEstimateLineDto[]
  stored: InventoryRepairPlanSnapshotDto[]
  prepared: RepairEstimateTaskPlanDto[]
}): RepairEstimateTaskPlanDto[] {
  const lineIds = new Set(params.lines.map((line) => line.id))
  const lineById = new Map(params.lines.map((line) => [line.id, line]))
  const existing = params.stored
    .map(toRepairEstimateTaskPlan)
    .slice()
    .sort((left, right) => left.sortOrder - right.sortOrder)
    .flatMap((plan) => {
      if (plan.kind !== "REPAIR_WORK") return [plan]
      const includedLineIds = plan.includedLineIds.filter((lineId) =>
        lineIds.has(lineId)
      )
      if (includedLineIds.length === 0) return []
      const primary = plan.primaryLineId
        ? lineById.get(plan.primaryLineId)
        : null
      return [
        {
          ...plan,
          includedLineIds,
          primaryLineId:
            primary?.lineType === "WORK" && includedLineIds.includes(primary.id)
              ? primary.id
              : (includedLineIds.find(
                  (lineId) => lineById.get(lineId)?.lineType === "WORK"
                ) ?? null),
        },
      ]
    })
  const coveredLineIds = new Set(
    existing.flatMap((plan) => plan.includedLineIds)
  )
  const additions = params.prepared.flatMap((plan) => {
    if (plan.kind !== "REPAIR_WORK") return []
    const includedLineIds = plan.includedLineIds.filter(
      (lineId) => !coveredLineIds.has(lineId)
    )
    if (includedLineIds.length === 0) return []
    includedLineIds.forEach((lineId) => coveredLineIds.add(lineId))
    return [
      {
        ...plan,
        id: `inventory-plan-unassigned-${includedLineIds.join("-")}`,
        includedLineIds,
        primaryLineId:
          includedLineIds.find(
            (lineId) => lineById.get(lineId)?.lineType === "WORK"
          ) ?? null,
      },
    ]
  })

  const combined = [...existing]
  const moveFromIndex = combined.findIndex(
    (plan) => plan.kind === "MOVE_FROM_REPAIR"
  )
  combined.splice(
    moveFromIndex >= 0 ? moveFromIndex : combined.length,
    0,
    ...additions
  )
  return combined.map((plan, index) => ({
    ...plan,
    sortOrder: (index + 1) * 10,
  }))
}
