import { IndexedDbRepairEstimateMediaAdapter } from "@/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter"
import {
  createRepairEstimateCatalogIndex,
  getOperationalRepairEstimateCatalog,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import type {
  PendingEstimateMediaUpload,
  RepairEstimateCompletionMode,
  RepairEstimateLineDto,
  RepairEstimateMediaId,
  RepairEstimateMediaRefDto,
  RepairEstimateMediaRotationDegrees,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"
import type { RepairTaskSubtaskDto } from "@/features/repair-tasks/model/repair-task"
import { buildRepairTaskSubtasks } from "@/features/repair-tasks/domain/repair-task-domain"
import {
  assertTaskPlansValid,
  buildRepairEstimateTaskPlans,
  finalizeTaskPlans,
  validateAutoCompletion,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import {
  getRepairTaskSnapshotByInventoryFinding,
  upsertRepairTaskByInventoryFinding,
} from "@/features/repair-tasks/api/repair-tasks-api"
import {
  createRentalItemForInventory,
  getRentalItem,
  getRentalItemsForInventoryStart,
  lookupRentalItemForInventory,
  type InventoryRentalItemLookupResult,
} from "@/features/rental-items/api/rental-items-api"
import type { CreateInventoryRentalItemPayload } from "@/features/rental-items/model/rental-item-create"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { LocalStorageInventoryAdapter } from "@/features/inventory/adapters/local-storage-inventory-adapter"
import {
  assertInventoryPermission,
  assertInventoryWarehouseAccess,
  calculateInventoryStatistics,
  canonicalizeInventoryNumber,
  deriveInventoryConflicts,
  deriveInventoryReconciliationStatus,
  inventoryCompletionRiskSignature,
  toInventoryRepairPlanSnapshot,
  toRepairEstimateTaskPlan,
  toInventoryRentalItemSnapshot,
} from "@/features/inventory/domain/inventory-domain"
import type {
  InventoryActorSnapshot,
  InventoryConflictDto,
  InventoryFindingDto,
  InventoryFindingOrigin,
  InventoryRepairPlanSnapshotDto,
  InventorySessionDto,
  InventoryWarehouseSnapshot,
} from "@/features/inventory/model/inventory"
import type { InventoryClient } from "@/features/inventory/ports/inventory-client"
import type { InventoryMediaClient } from "@/features/inventory/ports/inventory-media-client"

export const INVENTORY_QUERY_KEY = ["inventory"] as const

const inventoryClient: InventoryClient = new LocalStorageInventoryAdapter()
const inventoryMediaClient: InventoryMediaClient =
  new IndexedDbRepairEstimateMediaAdapter()

export function inventoryListQueryKey(warehouseId: string) {
  return [...INVENTORY_QUERY_KEY, "list", warehouseId] as const
}

export function inventoryActiveQueryKey(warehouseId: string) {
  return [...INVENTORY_QUERY_KEY, "active", warehouseId] as const
}

export function inventoryDetailQueryKey(inventoryId: string | null) {
  return [...INVENTORY_QUERY_KEY, "detail", inventoryId] as const
}

export function inventoryFinishPreviewQueryKey(
  inventoryId: string | null,
  version: number | null
) {
  return [
    ...INVENTORY_QUERY_KEY,
    "finish-preview",
    inventoryId,
    version,
  ] as const
}

export function createInventoryFindingId() {
  const suffix =
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `inventory-finding-${suffix}`
}

async function hydrateSession(session: InventorySessionDto) {
  return {
    ...session,
    findings: await Promise.all(
      session.findings.map(async (finding) => ({
        ...finding,
        media: await inventoryMediaClient.hydrate(finding.media),
      }))
    ),
  }
}

export function listInventories(
  warehouseId: string,
  actor: InventoryActorSnapshot
) {
  assertInventoryPermission(actor, "VIEW")
  assertInventoryWarehouseAccess(actor, warehouseId)
  return inventoryClient.list(warehouseId)
}

export async function getInventory(
  inventoryId: string,
  actor: InventoryActorSnapshot
) {
  assertInventoryPermission(actor, "VIEW")
  const session = await inventoryClient.get(inventoryId)
  if (session) assertInventoryWarehouseAccess(actor, session.warehouseId)
  return session ? hydrateSession(session) : null
}

export function getActiveInventory(params: {
  warehouseId: string
  actor: InventoryActorSnapshot
}) {
  assertInventoryPermission(params.actor, "VIEW")
  assertInventoryWarehouseAccess(params.actor, params.warehouseId)
  return inventoryClient.getActive(params.warehouseId)
}

export function subscribeInventory(listener: () => void) {
  return inventoryClient.subscribe(listener)
}

export async function startInventory(params: {
  warehouse: InventoryWarehouseSnapshot
  actor: InventoryActorSnapshot
  businessDate: string
}) {
  assertInventoryPermission(params.actor, "EDIT")
  const expectedItems = (
    await getRentalItemsForInventoryStart(params.warehouse.id)
  ).map(toInventoryRentalItemSnapshot)
  const uniqueExpectedItems = Array.from(
    new Map(
      expectedItems.map((item) => [item.canonicalNumber, item] as const)
    ).values()
  )
  if (!/^\d{4}-\d{2}-\d{2}$/.test(params.businessDate)) {
    throw new Error("Дата инвентаризации должна быть в формате ГГГГ-ММ-ДД")
  }
  return inventoryClient.start({
    ...params,
    expectedItems: uniqueExpectedItems,
  })
}

export type InventoryNumberResolution =
  | {
      kind: "EXISTING_FINDING" | "OPEN_INSPECTION"
      finding: InventoryFindingDto
      session: InventorySessionDto
    }
  | {
      kind: "NOT_FOUND"
      canonicalNumber: string
      lookup: Extract<InventoryRentalItemLookupResult, { kind: "NOT_FOUND" }>
    }
  | {
      kind: "CONFLICT"
      conflict: "RENTAL_ITEM_MISSING" | "OTHER_WAREHOUSE" | "WRITTEN_OFF"
      item: RentalItemDto | null
      finding: InventoryFindingDto
      session: InventorySessionDto
    }

function newFinding(params: {
  id: string
  item: RentalItemDto
  origin: InventoryFindingOrigin
  conflicts?: InventoryConflictDto[]
}): InventoryFindingDto {
  const current = toInventoryRentalItemSnapshot(params.item)
  return {
    id: params.id,
    rentalItemId: params.item.id,
    canonicalNumber: current.canonicalNumber,
    cabinNumber: params.item.number,
    origin: params.origin,
    inspectionStatus: "NOT_INSPECTED",
    reconciliationStatus: "MATCHED",
    expectedSnapshot: null,
    currentSnapshot: current,
    conflicts: params.conflicts ?? [],
    comment: "",
    media: [],
    lines: [],
    repairCompletionMode: null,
    movementRequired: false,
    repairPlans: [],
    publicationStatus: "NOT_REQUIRED",
    publicationOperationKey: null,
    publishedRepairTaskId: null,
    publicationError: null,
    inspectedAt: null,
    inspectedBy: null,
  }
}

function findingCurrentState(
  session: InventorySessionDto,
  finding: InventoryFindingDto,
  item: RentalItemDto | null
) {
  const current = item ? toInventoryRentalItemSnapshot(item) : null
  const liveConflicts = deriveInventoryConflicts(
    finding.expectedSnapshot,
    current,
    session.warehouseId
  )
  const addedAfterStart =
    finding.origin === "UNEXPECTED_EXISTING" &&
    finding.expectedSnapshot === null
      ? [
          {
            code: "ADDED_AFTER_START" as const,
            message: "Бытовка отсутствовала в снимке на начало инвентаризации",
            expected: null,
            actual: current?.rentalItemId ?? null,
          },
        ]
      : []
  const conflicts = [...addedAfterStart, ...liveConflicts]
  const reconciliationStatus =
    finding.origin === "EXPECTED" &&
    finding.inspectionStatus === "NOT_INSPECTED"
      ? "MISSING"
      : deriveInventoryReconciliationStatus(current, conflicts)
  return {
    ...finding,
    currentSnapshot: current,
    conflicts,
    reconciliationStatus,
  }
}

function itemFromLookup(lookup: InventoryRentalItemLookupResult) {
  return lookup.kind === "NOT_FOUND" ? null : lookup.item
}

function blockingConflictCode(finding: InventoryFindingDto) {
  return finding.conflicts.find((conflict) =>
    ["RENTAL_ITEM_MISSING", "OTHER_WAREHOUSE", "WRITTEN_OFF"].includes(
      conflict.code
    )
  )?.code as
    "RENTAL_ITEM_MISSING" | "OTHER_WAREHOUSE" | "WRITTEN_OFF" | undefined
}

export async function resolveInventoryNumber(params: {
  inventoryId: string
  expectedVersion: number
  actor: InventoryActorSnapshot
  number: string
}): Promise<InventoryNumberResolution> {
  assertInventoryPermission(params.actor, "EDIT")
  const session = await inventoryClient.get(params.inventoryId)
  if (!session || session.status !== "ACTIVE")
    throw new Error("Активная инвентаризация не найдена")
  assertInventoryWarehouseAccess(params.actor, session.warehouseId)
  if (session.version !== params.expectedVersion)
    throw new Error("Инвентаризация была изменена. Обновите данные")
  const canonicalNumber = canonicalizeInventoryNumber(params.number)
  const existing = session.findings.find(
    (finding) => finding.canonicalNumber === canonicalNumber
  )
  if (existing) {
    const lookup = await lookupRentalItemForInventory({
      warehouseId: session.warehouseId,
      number: canonicalNumber,
    })
    const refreshed = findingCurrentState(
      session,
      existing,
      itemFromLookup(lookup)
    )
    const saved = await inventoryClient.replaceFindings({
      inventoryId: session.id,
      expectedVersion: session.version,
      actor: params.actor,
      findings: session.findings.map((finding) =>
        finding.id === existing.id ? refreshed : finding
      ),
    })
    const savedFinding = saved.findings.find(
      (finding) => finding.id === existing.id
    )!
    const blockingCode = blockingConflictCode(savedFinding)
    if (blockingCode) {
      return {
        kind: "CONFLICT",
        conflict: blockingCode,
        item: itemFromLookup(lookup),
        finding: savedFinding,
        session: saved,
      }
    }
    return {
      kind:
        savedFinding.inspectionStatus === "NOT_INSPECTED"
          ? "OPEN_INSPECTION"
          : "EXISTING_FINDING",
      finding: savedFinding,
      session: saved,
    }
  }
  const lookup = await lookupRentalItemForInventory({
    warehouseId: session.warehouseId,
    number: params.number,
  })
  if (lookup.kind === "NOT_FOUND") {
    return { kind: "NOT_FOUND", canonicalNumber, lookup }
  }
  if (lookup.kind === "OTHER_WAREHOUSE" || lookup.kind === "WRITTEN_OFF") {
    const current = toInventoryRentalItemSnapshot(lookup.item)
    const finding = newFinding({
      id: createInventoryFindingId(),
      item: lookup.item,
      origin: "UNEXPECTED_EXISTING",
      conflicts: [
        {
          code: "ADDED_AFTER_START",
          message: "Бытовка отсутствовала в снимке на начало инвентаризации",
          expected: null,
          actual: current.rentalItemId,
        },
        ...deriveInventoryConflicts(null, current, session.warehouseId),
      ],
    })
    const saved = await inventoryClient.addFinding({
      inventoryId: session.id,
      expectedVersion: session.version,
      actor: params.actor,
      finding,
    })
    return {
      kind: "CONFLICT",
      conflict: lookup.kind,
      item: lookup.item,
      finding,
      session: saved,
    }
  }
  const current = toInventoryRentalItemSnapshot(lookup.item)
  const finding = newFinding({
    id: createInventoryFindingId(),
    item: lookup.item,
    origin: "UNEXPECTED_EXISTING",
    conflicts: deriveInventoryConflicts(null, current, session.warehouseId),
  })
  finding.conflicts = [
    {
      code: "ADDED_AFTER_START",
      message: "Бытовка отсутствовала в снимке на начало инвентаризации",
      expected: null,
      actual: current.rentalItemId,
    },
    ...finding.conflicts,
  ]
  const saved = await inventoryClient.addFinding({
    inventoryId: session.id,
    expectedVersion: session.version,
    actor: params.actor,
    finding,
  })
  return { kind: "OPEN_INSPECTION", finding, session: saved }
}

export async function addInventoryRentalItem(params: {
  inventoryId: string
  expectedVersion: number
  actor: InventoryActorSnapshot
  findingId: string
  condition: "NEW" | "USED"
  rentalItem: Omit<
    CreateInventoryRentalItemPayload,
    "warehouseId" | "inventoryId" | "findingId" | "condition"
  >
}) {
  assertInventoryPermission(params.actor, "EDIT")
  const before = await inventoryClient.get(params.inventoryId)
  if (!before || before.status !== "ACTIVE")
    throw new Error("Активная инвентаризация не найдена")
  assertInventoryWarehouseAccess(params.actor, before.warehouseId)
  if (before.version !== params.expectedVersion)
    throw new Error("Инвентаризация была изменена. Обновите данные")
  const item = await createRentalItemForInventory({
    ...params.rentalItem,
    warehouseId: before.warehouseId,
    inventoryId: before.id,
    findingId: params.findingId,
    condition: params.condition,
  })
  const finding = newFinding({
    id: params.findingId,
    item,
    origin: params.condition === "NEW" ? "ADDED_NEW" : "ADDED_USED",
  })
  const resumed = await inventoryClient.get(before.id)
  if (!resumed) throw new Error("Инвентаризация не найдена")
  assertInventoryWarehouseAccess(params.actor, resumed.warehouseId)
  const saved = await inventoryClient.addFinding({
    inventoryId: resumed.id,
    expectedVersion: resumed.version,
    actor: params.actor,
    finding,
  })
  return { session: saved, finding, rentalItem: item }
}

async function currentFindingState(
  session: InventorySessionDto,
  finding: InventoryFindingDto
) {
  const item = finding.rentalItemId
    ? await getRentalItem(finding.rentalItemId)
    : null
  return findingCurrentState(session, finding, item)
}

function hasSameRepairPlanInputs(
  before: RepairEstimateLineDto[],
  after: RepairEstimateLineDto[]
) {
  if (before.length !== after.length) return false
  const afterById = new Map(after.map((line) => [line.id, line]))
  return before.every((line) => {
    const next = afterById.get(line.id)
    return (
      next?.lineType === line.lineType &&
      next.quantity === line.quantity &&
      next.lineComment === line.lineComment &&
      next.catalogSnapshot?.nodeId === line.catalogSnapshot?.nodeId
    )
  })
}

function ensureInventoryRepairPlanCoverage(
  plans: RepairEstimateTaskPlanDto[],
  lines: RepairEstimateLineDto[]
) {
  const orderedPlans = plans
    .slice()
    .sort((left, right) => left.sortOrder - right.sortOrder)
  const coveredLineIds = new Set<string>()
  orderedPlans.forEach((plan) => {
    if (plan.kind !== "REPAIR_WORK") return
    plan.includedLineIds.forEach((lineId) => {
      if (!coveredLineIds.has(lineId)) coveredLineIds.add(lineId)
    })
  })
  const unassigned = lines.filter((line) => !coveredLineIds.has(line.id))
  if (unassigned.length === 0) return orderedPlans
  const comments = Array.from(
    new Set(unassigned.map((line) => line.lineComment.trim()).filter(Boolean))
  ).join("; ")
  const fallback: RepairEstimateTaskPlanDto = {
    id: `inventory-plan-fallback-${unassigned.map((line) => line.id).join("-")}`,
    kind: "REPAIR_WORK",
    includedLineIds: unassigned.map((line) => line.id),
    primaryLineId:
      unassigned.find((line) => line.lineType === "WORK")?.id ?? null,
    groupComment: comments,
    queueCode: null,
    routeQueueKind: null,
    sortOrder: 0,
    generationStatus: "PENDING_GENERATION",
    workflowRequestRef: null,
  }
  const moveFromIndex = orderedPlans.findIndex(
    (plan) => plan.kind === "MOVE_FROM_REPAIR"
  )
  const combined = [...orderedPlans]
  combined.splice(
    moveFromIndex >= 0 ? moveFromIndex : combined.length,
    0,
    fallback
  )
  return combined.map((plan, index) => ({
    ...plan,
    sortOrder: (index + 1) * 10,
  }))
}

async function freezeInventoryRepairPlans(params: {
  beforeLines: RepairEstimateLineDto[]
  lines: RepairEstimateLineDto[]
  existingPlans: InventoryRepairPlanSnapshotDto[]
  existingCompletionMode: RepairEstimateCompletionMode | null
  existingMovementRequired: boolean
  requestedPlans: InventoryRepairPlanSnapshotDto[]
  requestedCompletionMode?: RepairEstimateCompletionMode | null
  requestedMovementRequired?: boolean
  comment: string
}) {
  if (params.lines.length === 0) {
    return {
      repairCompletionMode: null,
      movementRequired: false,
      repairPlans: [],
    }
  }

  const hasRequestedCompletionMode =
    params.requestedCompletionMode === "AUTO" ||
    params.requestedCompletionMode === "MANUAL"
  if (
    !hasRequestedCompletionMode &&
    params.existingPlans.length > 0 &&
    hasSameRepairPlanInputs(params.beforeLines, params.lines)
  ) {
    return {
      repairCompletionMode: params.existingCompletionMode,
      movementRequired: params.existingMovementRequired,
      repairPlans: params.existingPlans,
    }
  }
  const snapshot = await getOperationalRepairEstimateCatalog()
  const catalog = createRepairEstimateCatalogIndex(snapshot)
  const completionMode: RepairEstimateCompletionMode | null =
    params.requestedCompletionMode === "AUTO" ||
    params.requestedCompletionMode === "MANUAL"
      ? params.requestedCompletionMode
      : null
  const movementRequired = hasRequestedCompletionMode
    ? params.requestedMovementRequired === true
    : false
  if (completionMode === "AUTO") {
    const issues = validateAutoCompletion(params.lines, catalog)
    if (issues.length > 0) {
      throw new Error(
        `Автоматическое распределение недоступно: ${issues.join("; ")}`
      )
    }
  }
  const preparedPlans =
    completionMode === "AUTO"
      ? buildRepairEstimateTaskPlans(params.lines, catalog)
      : hasRequestedCompletionMode
        ? params.requestedPlans.map(toRepairEstimateTaskPlan)
        : buildRepairEstimateTaskPlans(params.lines, catalog)
  const taskPlans = ensureInventoryRepairPlanCoverage(
    finalizeTaskPlans({
      plans: preparedPlans,
      completionMode: completionMode ?? "MANUAL",
      movementRequired,
    }),
    params.lines
  )
  assertTaskPlansValid(taskPlans, params.lines)
  const subtasks = buildRepairTaskSubtasks({
    lines: params.lines,
    plans: taskPlans,
    catalog,
  })
  const subtaskByPlanId = new Map(
    subtasks.map((subtask) => [subtask.id, subtask] as const)
  )
  return {
    repairCompletionMode: completionMode,
    movementRequired,
    repairPlans: taskPlans.flatMap((plan) => {
      const subtask = subtaskByPlanId.get(plan.id)
      if (!subtask && plan.kind === "REPAIR_WORK") return []
      return [
        {
          ...toInventoryRepairPlanSnapshot(plan),
          groupComment: plan.groupComment.trim() || params.comment.trim(),
          plannedDurationMinutes: subtask?.plannedDurationMinutes ?? null,
          photoRequired: subtask?.photoRequired ?? false,
        },
      ]
    }),
  }
}

export async function saveInventoryFinding(params: {
  inventoryId: string
  expectedVersion: number
  actor: InventoryActorSnapshot
  findingId: string
  comment: string
  media: RepairEstimateMediaRefDto[]
  lines: RepairEstimateLineDto[]
  repairPlans: InventoryRepairPlanSnapshotDto[]
  repairCompletionMode?: RepairEstimateCompletionMode | null
  movementRequired?: boolean
}) {
  assertInventoryPermission(params.actor, "EDIT")
  const session = await inventoryClient.get(params.inventoryId)
  if (!session || session.status !== "ACTIVE")
    throw new Error("Активная инвентаризация не найдена")
  assertInventoryWarehouseAccess(params.actor, session.warehouseId)
  if (session.version !== params.expectedVersion)
    throw new Error("Инвентаризация была изменена. Обновите данные")
  const finding = session.findings.find((item) => item.id === params.findingId)
  if (!finding) throw new Error("Бытовка не найдена в инвентаризации")
  const lookup = await lookupRentalItemForInventory({
    warehouseId: session.warehouseId,
    number: finding.canonicalNumber,
  })
  const refreshed = findingCurrentState(
    session,
    finding,
    itemFromLookup(lookup)
  )
  const blockingCode = blockingConflictCode(refreshed)
  if (blockingCode) {
    throw new Error(
      `Нельзя сохранить осмотр: ${refreshed.conflicts
        .filter((conflict) => conflict.code === blockingCode)
        .map((conflict) => conflict.message)
        .join("; ")}`
    )
  }
  const repairWorkflow = await freezeInventoryRepairPlans({
    beforeLines: finding.lines,
    lines: params.lines,
    existingPlans: finding.repairPlans,
    existingCompletionMode: finding.repairCompletionMode,
    existingMovementRequired: finding.movementRequired,
    requestedPlans: params.repairPlans,
    requestedCompletionMode: params.repairCompletionMode,
    requestedMovementRequired: params.movementRequired,
    comment: params.comment,
  })
  return inventoryClient.saveFinding({
    ...params,
    media: inventoryMediaClient.dehydrate(params.media),
    repairCompletionMode: repairWorkflow.repairCompletionMode,
    movementRequired: repairWorkflow.movementRequired,
    repairPlans: repairWorkflow.repairPlans,
    currentSnapshot: refreshed.currentSnapshot,
    conflicts: refreshed.conflicts,
  })
}

export async function previewInventoryCompletion(params: {
  inventoryId: string
  expectedVersion: number
  actor: InventoryActorSnapshot
}) {
  assertInventoryPermission(params.actor, "MANAGE")
  const session = await inventoryClient.get(params.inventoryId)
  if (!session || session.status !== "ACTIVE")
    throw new Error("Активная инвентаризация не найдена")
  assertInventoryWarehouseAccess(params.actor, session.warehouseId)
  if (session.version !== params.expectedVersion)
    throw new Error("Инвентаризация была изменена. Обновите данные")
  const completedAt = new Date().toISOString()
  const findings = await Promise.all(
    session.findings.map((finding) => currentFindingState(session, finding))
  )
  return hydrateSession({
    ...session,
    findings,
    statistics: calculateInventoryStatistics({
      startedAt: session.startedAt,
      completedAt,
      findings,
    }),
  })
}

export async function completeInventory(params: {
  inventoryId: string
  expectedVersion: number
  actor: InventoryActorSnapshot
  acknowledgedRiskSignature: string | null
}) {
  assertInventoryPermission(params.actor, "MANAGE")
  const session = await inventoryClient.get(params.inventoryId)
  if (!session) throw new Error("Инвентаризация не найдена")
  assertInventoryWarehouseAccess(params.actor, session.warehouseId)
  if (session.version !== params.expectedVersion)
    throw new Error("Инвентаризация была изменена. Обновите данные")
  const completedAt = new Date().toISOString()
  const findings = await Promise.all(
    session.findings.map((finding) => currentFindingState(session, finding))
  )
  const riskSignature = inventoryCompletionRiskSignature(findings)
  if (riskSignature && params.acknowledgedRiskSignature !== riskSignature) {
    throw new Error(
      "Сверка изменилась. Проверьте ненайденные бытовки и конфликты повторно"
    )
  }
  return inventoryClient.complete({
    inventoryId: session.id,
    expectedVersion: session.version,
    actor: params.actor,
    findings,
    statistics: calculateInventoryStatistics({
      startedAt: session.startedAt,
      completedAt,
      findings,
    }),
  })
}

function buildPublicationSubtasks(
  finding: InventoryFindingDto
): RepairTaskSubtaskDto[] {
  const plans = finding.repairPlans.length
    ? finding.repairPlans
    : [
        {
          id: `${finding.id}:repair-plan`,
          kind: "REPAIR_WORK" as const,
          includedLineIds: finding.lines.map((line) => line.id),
          primaryLineId:
            finding.lines.find((line) => line.lineType === "WORK")?.id ?? null,
          groupComment: finding.comment,
          queueCode: null,
          routeQueueKind: null,
          sortOrder: 10,
          plannedDurationMinutes: null,
          photoRequired: false,
        },
      ]
  const planById = new Map(plans.map((plan) => [plan.id, plan] as const))
  return buildRepairTaskSubtasks({
    lines: finding.lines,
    plans: plans.map(toRepairEstimateTaskPlan),
  }).map((subtask) => {
    const plan = planById.get(subtask.id)
    return {
      ...subtask,
      id: `${finding.id}:subtask:${subtask.id}`,
      plannedDurationMinutes:
        plan?.plannedDurationMinutes ?? subtask.plannedDurationMinutes,
      photoRequired: plan?.photoRequired ?? subtask.photoRequired,
    }
  })
}

export async function publishInventoryWorks(params: {
  inventoryId: string
  actor: InventoryActorSnapshot
}) {
  assertInventoryPermission(params.actor, "MANAGE")
  let session = await inventoryClient.get(params.inventoryId)
  if (!session || session.status !== "COMPLETED")
    throw new Error("Завершённая инвентаризация не найдена")
  assertInventoryWarehouseAccess(params.actor, session.warehouseId)
  for (const candidate of session.findings) {
    if (
      candidate.lines.length === 0 ||
      candidate.publicationStatus === "PUBLISHED"
    )
      continue
    const operationKey = `${session.id}:${candidate.id}`
    if (
      candidate.publicationStatus === "PUBLISHING" ||
      candidate.publicationStatus === "FAILED"
    ) {
      const existingTask = await getRepairTaskSnapshotByInventoryFinding(
        session.id,
        candidate.id,
        session.warehouseId
      )
      if (existingTask) {
        session = await inventoryClient.setFindingPublication({
          inventoryId: session.id,
          expectedVersion: session.version,
          actor: params.actor,
          findingId: candidate.id,
          status: "PUBLISHED",
          operationKey,
          repairTaskId: existingTask.id,
          error: null,
        })
        continue
      }
    }
    const current = await currentFindingState(session, candidate)
    if (current.conflicts.length > 0) {
      session = await inventoryClient.setFindingPublication({
        inventoryId: session.id,
        expectedVersion: session.version,
        actor: params.actor,
        findingId: candidate.id,
        status: "BLOCKED",
        operationKey,
        repairTaskId: candidate.publishedRepairTaskId,
        error: `Устраните конфликт реестра перед передачей работ: ${current.conflicts.map((conflict) => conflict.message).join("; ")}`,
      })
      continue
    }
    session = await inventoryClient.setFindingPublication({
      inventoryId: session.id,
      expectedVersion: session.version,
      actor: params.actor,
      findingId: candidate.id,
      status: "PUBLISHING",
      operationKey,
      repairTaskId: candidate.publishedRepairTaskId,
      error: null,
    })
    try {
      const task = await upsertRepairTaskByInventoryFinding({
        warehouseId: session.warehouseId,
        rentalItemId: candidate.rentalItemId!,
        cabinNumber: candidate.cabinNumber,
        authorName: params.actor.displayName,
        sourceInventoryId: session.id,
        sourceInventoryFindingId: candidate.id,
        reason: "Инвентаризация",
        dispatchDate: session.businessDate,
        comment: candidate.comment,
        media: candidate.media,
        subtasks: buildPublicationSubtasks(candidate),
      })
      session = await inventoryClient.setFindingPublication({
        inventoryId: session.id,
        expectedVersion: session.version,
        actor: params.actor,
        findingId: candidate.id,
        status: "PUBLISHED",
        operationKey,
        repairTaskId: task.id,
        error: null,
      })
    } catch (error) {
      const current = await inventoryClient.get(session.id)
      if (!current) throw error
      assertInventoryWarehouseAccess(params.actor, current.warehouseId)
      session = await inventoryClient.setFindingPublication({
        inventoryId: current.id,
        expectedVersion: current.version,
        actor: params.actor,
        findingId: candidate.id,
        status: "FAILED",
        operationKey,
        repairTaskId: candidate.publishedRepairTaskId,
        error:
          error instanceof Error ? error.message : "Передача работ не удалась",
      })
    }
  }
  return session
}

export function uploadInventoryMedia(uploads: PendingEstimateMediaUpload[]) {
  return inventoryMediaClient.upload(uploads)
}

export function discardInventoryMedia(mediaIds: RepairEstimateMediaId[]) {
  return inventoryMediaClient.discard(mediaIds)
}

export function updateInventoryMediaRotation(
  mediaId: RepairEstimateMediaId,
  rotation: RepairEstimateMediaRotationDegrees
) {
  return inventoryMediaClient.updateRotation(mediaId, rotation)
}
