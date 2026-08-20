import { getUserManager } from "@/features/auth/oidc-client"
import * as inventoryHttp from "@/features/inventory/adapters/http-inventory-adapter"
import { toInventoryRepairPlanSnapshot } from "@/features/inventory/domain/inventory-domain"
import { buildInventoryPlanSelection } from "@/features/inventory/domain/inventory-plan-mapper"
import {
  applyInventoryCompletionPreview,
  applyInventoryRegistryReview,
  toInventoryFurnitureReviewView,
  toInventoryFindingView,
  toInventoryStatisticsView,
  toInventorySessionView,
} from "@/features/inventory/domain/inventory-view-mapper"
import type {
  InventoryActorSnapshot,
  InventoryCreateRentalItem,
  InventoryFindingDto,
  InventoryFurnitureReviewDto,
  InventorySessionDto,
  InventoryWarehouseSnapshot,
} from "@/features/inventory/model/inventory"
import type {
  InventoryCompletionPreview,
  InventoryFinalPlan,
  InventoryObservation,
  InventoryPlanningSettings,
  InventorySessionView,
  UpdateInventoryFinalPlanRequest,
  UpdateInventoryPlanningSettingsRequest,
} from "@/features/inventory/model/inventory-service"
import type {
  LogisticsPlanningMode,
  RepairEstimateCompletionMode,
  RepairEstimateLineDto,
  RepairPriority,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"
import { getWarehouseQueueCapabilities } from "@/features/repair-estimates/api/warehouse-queue-capabilities"
import { ApiError } from "@/lib/api-client"

export const INVENTORY_QUERY_KEY = ["inventory-service"] as const

export function inventoryListQueryKey(warehouseId: string) {
  return [...INVENTORY_QUERY_KEY, "list", warehouseId] as const
}

export function inventoryActiveQueryKey(warehouseId: string) {
  return [...INVENTORY_QUERY_KEY, "active", warehouseId] as const
}

export function inventoryDetailQueryKey(inventoryId: string | null) {
  return [...INVENTORY_QUERY_KEY, "detail", inventoryId] as const
}

export function inventoryPublicationQueryKey(inventoryId: string | null) {
  return [...INVENTORY_QUERY_KEY, "publication", inventoryId] as const
}

export function inventoryFurnitureReviewQueryKey(inventoryId: string | null) {
  return [...INVENTORY_QUERY_KEY, "furniture-review", inventoryId] as const
}

export function inventoryPlanningSettingsQueryKey(warehouseId: string | null) {
  return [...INVENTORY_QUERY_KEY, "planning-settings", warehouseId] as const
}

export function inventoryFinalPlanQueryKey(
  inventoryId: string | null,
  sessionRevision: number | null = null
) {
  return [
    ...INVENTORY_QUERY_KEY,
    "final-plan",
    inventoryId,
    sessionRevision,
  ] as const
}

export function inventoryFinishPreviewQueryKey(
  inventoryId: string | null,
  version: number | null,
  finalPlanVersion: number | null = null
) {
  return [
    ...INVENTORY_QUERY_KEY,
    "finish-preview",
    inventoryId,
    version,
    finalPlanVersion,
  ] as const
}

export function inventoryPreliminaryStatisticsQueryKey(
  inventoryId: string | null,
  version: number | null
) {
  return [
    ...INVENTORY_QUERY_KEY,
    "statistics-preview",
    inventoryId,
    version,
  ] as const
}

async function accessToken() {
  const user = await getUserManager().getUser()
  if (!user?.access_token) {
    throw new Error("Для инвентаризации требуется авторизация")
  }
  return user.access_token
}

function commandKey() {
  return crypto.randomUUID()
}

function fallbackWarehouse(session: InventorySessionView) {
  return {
    id: session.warehouseId,
    name: "Склад",
    timeZone: session.warehouseTimeZone,
  } satisfies InventoryWarehouseSnapshot
}

function sessionView(
  session: InventorySessionView,
  warehouse?: InventoryWarehouseSnapshot
) {
  return toInventorySessionView({
    session,
    warehouse: warehouse ?? fallbackWarehouse(session),
    findings: session.findings,
    statistics: session.statistics,
  })
}

export async function listInventories(warehouseId: string) {
  const token = await accessToken()
  const sessions: InventorySessionDto[] = []
  for (let page = 0; ; page += 1) {
    const response = await inventoryHttp.listInventorySessions(
      token,
      warehouseId,
      page
    )
    const details = await Promise.all(
      response.content.map((session) =>
        inventoryHttp.getInventorySession(token, session.id)
      )
    )
    sessions.push(...details.map((session) => sessionView(session)))
    if (page + 1 >= response.page.totalPages) break
  }
  return sessions
}

export async function getInventory(inventoryId: string) {
  const session = await inventoryHttp.getInventorySession(
    await accessToken(),
    inventoryId
  )
  return sessionView(session)
}

export async function refreshInventorySession(input: {
  inventoryId: string
  expectedSessionRevision: number
}) {
  const token = await accessToken()
  await inventoryHttp.refreshInventorySession({
    accessToken: token,
    inventoryId: input.inventoryId,
    request: {
      expectedSessionRevision: input.expectedSessionRevision,
    },
    idempotencyKey: commandKey(),
  })
  return sessionView(
    await inventoryHttp.getInventorySession(token, input.inventoryId)
  )
}

export async function reviewInventoryRegistry(inventoryId: string) {
  const token = await accessToken()
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const rawSession = await inventoryHttp.getInventorySession(
      token,
      inventoryId
    )
    try {
      const review = await inventoryHttp.reviewInventoryRegistry({
        accessToken: token,
        session: rawSession,
      })
      if (
        review.inventoryId !== rawSession.id ||
        review.sessionRevision !== rawSession.sessionRevision
      ) {
        throw new Error(
          "Сверка реестра относится к другой версии инвентаризации"
        )
      }
      return applyInventoryRegistryReview(sessionView(rawSession), review)
    } catch (error) {
      if (!(
        error instanceof ApiError &&
        error.status === 409 &&
        attempt === 0
      )) {
        throw error
      }
    }
  }
  throw new Error("Не удалось получить актуальную сверку реестра")
}

export async function getInventoryPreliminaryStatistics(inventoryId: string) {
  return toInventoryStatisticsView(
    await inventoryHttp.getInventoryPreliminaryStatistics(
      await accessToken(),
      inventoryId
    )
  )
}

export async function getActiveInventory(input: {
  warehouseId: string
  actor: InventoryActorSnapshot
}) {
  const session = await inventoryHttp.getActiveInventorySession(
    await accessToken(),
    input.warehouseId
  )
  return session ? sessionView(session) : null
}

export function subscribeInventory(listener: () => void) {
  const handleVisibility = () => {
    if (document.visibilityState === "visible") listener()
  }
  window.addEventListener("focus", listener)
  document.addEventListener("visibilitychange", handleVisibility)
  return () => {
    window.removeEventListener("focus", listener)
    document.removeEventListener("visibilitychange", handleVisibility)
  }
}

export async function startInventory(input: {
  warehouse: InventoryWarehouseSnapshot
  actor: InventoryActorSnapshot
  businessDate: string
}) {
  const token = await accessToken()
  const created = await inventoryHttp.startInventorySession(
    token,
    input.warehouse.id,
    commandKey()
  )
  return toInventorySessionView({
    session: created,
    warehouse: input.warehouse,
    findings: [],
    statistics: created.statistics,
  })
}

export function createInventoryFindingId() {
  return crypto.randomUUID()
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
      lookup: { kind: "NOT_FOUND" }
      session: InventorySessionDto
    }
  | {
      kind: "CONFLICT"
      conflict: "RENTAL_ITEM_MISSING" | "OTHER_WAREHOUSE" | "WRITTEN_OFF"
      item: { number: string } | null
      finding: InventoryFindingDto
      session: InventorySessionDto
    }

async function refreshedSession(inventoryId: string) {
  return inventoryHttp.getInventorySession(await accessToken(), inventoryId)
}

export async function resolveInventoryNumber(input: {
  inventoryId: string
  expectedVersion: number
  actor: InventoryActorSnapshot
  number: string
}): Promise<InventoryNumberResolution> {
  const token = await accessToken()
  const resolution = await inventoryHttp.resolveInventoryNumber({
    accessToken: token,
    inventoryId: input.inventoryId,
    expectedSessionRevision: input.expectedVersion,
    submittedNumber: input.number,
    idempotencyKey: commandKey(),
  })
  if (resolution.outcome === "NOT_FOUND") {
    const session = sessionView(
      await inventoryHttp.getInventorySession(token, input.inventoryId)
    )
    return {
      kind: "NOT_FOUND" as const,
      canonicalNumber: resolution.displayCanonicalNumber,
      lookup: { kind: "NOT_FOUND" as const },
      session,
    }
  }
  if (!resolution.finding) {
    throw new Error("Сервис не вернул результат сверки номера")
  }
  const rawSession = await inventoryHttp.getInventorySession(
    token,
    input.inventoryId
  )
  const finding = toInventoryFindingView(resolution.finding)
  const mappedSession = sessionView(rawSession)
  const session = {
    ...mappedSession,
    findings: mappedSession.findings.map((candidate) =>
      candidate.id === finding.id ? finding : candidate
    ),
  }
  if (resolution.outcome !== "MATCHED") {
    return {
      kind: "CONFLICT" as const,
      conflict:
        resolution.outcome === "MISSING_CONFLICT"
          ? ("RENTAL_ITEM_MISSING" as const)
          : resolution.outcome === "CROSS_WAREHOUSE_CONFLICT"
            ? ("OTHER_WAREHOUSE" as const)
            : ("WRITTEN_OFF" as const),
      item: resolution.finding.currentSnapshot
        ? { number: resolution.finding.currentSnapshot.displayCanonicalNumber }
        : null,
      finding,
      session,
    }
  }
  return {
    kind:
      finding.inspectionStatus === "NOT_INSPECTED"
        ? ("OPEN_INSPECTION" as const)
        : ("EXISTING_FINDING" as const),
    finding,
    session,
  }
}

export async function addInventoryRentalItem(input: {
  inventoryId: string
  expectedVersion: number
  actor: InventoryActorSnapshot
  findingId: string
  condition: "NEW" | "USED"
  rentalItem: InventoryCreateRentalItem
}) {
  const token = await accessToken()
  const rawFinding = await inventoryHttp.createAndAttachInventoryAsset({
    accessToken: token,
    inventoryId: input.inventoryId,
    findingId: input.findingId,
    expectedSessionRevision: input.expectedVersion,
    expectedFindingRevision: 0,
    origin: input.condition === "NEW" ? "ADDED_NEW" : "ADDED_USED",
    displayCanonicalNumber: input.rentalItem.number,
    safePassport: {
      rentalTypeId: input.rentalItem.rentalTypeId,
      dimensionId: input.rentalItem.dimensionId,
      finishingId: input.rentalItem.finishingId,
      category: input.rentalItem.category,
      characteristicIds: input.rentalItem.characteristicIds,
      linoleum: input.rentalItem.linoleum,
      passport: {},
      tags: [],
    },
    idempotencyKey: commandKey(),
  })
  const rawSession = await inventoryHttp.getInventorySession(
    token,
    input.inventoryId
  )
  return {
    session: sessionView(rawSession),
    finding: toInventoryFindingView(rawFinding),
    rentalItem: rawFinding.currentSnapshot,
  }
}

export async function saveInventoryFinding(input: {
  inventoryId: string
  expectedFindingVersion: number
  actor: InventoryActorSnapshot
  findingId: string
  comment: string
  passportObservation: InventoryObservation
  equipmentObservation: InventoryObservation
  media: Array<{ mediaId: string; generation: number }>
  coverMediaId: string | null
  lines: RepairEstimateLineDto[]
  repairPlans: ReturnType<typeof toInventoryRepairPlanSnapshot>[]
  repairCompletionMode?: RepairEstimateCompletionMode | null
  movementToRepair?: boolean
  forceCapitalRepair?: boolean
  logisticsPlanningMode?: LogisticsPlanningMode
  logisticsScheduledDate?: string | null
  priority?: RepairPriority
}) {
  const token = await accessToken()
  const rawSession = await inventoryHttp.getInventorySession(
    token,
    input.inventoryId
  )
  const finding = rawSession.findings.find(
    (candidate) => candidate.id === input.findingId
  )
  if (!finding) throw new Error("Бытовка не найдена в инвентаризации")
  const inspection = input.lines.length > 0 ? "WORK_STAGED" : "READY"
  let planSelection = null
  if (inspection === "WORK_STAGED") {
    const movementToRepair = input.movementToRepair === true
    if (movementToRepair) {
      const capabilities = await getWarehouseQueueCapabilities(
        token,
        rawSession.warehouseId
      )
      if (capabilities.movementQueueDefinitions.length === 0) {
        throw new Error("На складе не подключена очередь для перемещений.")
      }
    }
    const taskPlans: RepairEstimateTaskPlanDto[] = input.repairPlans.map(
      (plan) => ({
        ...plan,
        generationStatus: "PENDING_GENERATION",
        workflowRequestRef: null,
      })
    )
    planSelection = buildInventoryPlanSelection({
      completionMode: input.repairCompletionMode ?? "MANUAL",
      movementToRepair,
      forceCapitalRepair: input.forceCapitalRepair === true,
      logisticsPlanningMode: movementToRepair
        ? (input.logisticsPlanningMode ?? "AUTO")
        : null,
      logisticsScheduledDate:
        movementToRepair && input.logisticsPlanningMode === "FIXED_DATE"
          ? (input.logisticsScheduledDate ?? null)
          : null,
      priority: input.priority ?? 3,
      coverMediaId: input.coverMediaId,
      taskPlans,
      lines: input.lines,
    })
  }
  const saved = await inventoryHttp.saveInventoryInspection({
    accessToken: token,
    inventoryId: input.inventoryId,
    findingId: input.findingId,
    // The session revision fences the live inventory as a whole and may advance when another
    // cabin changes while this editor is open. The finding revision below remains the narrow
    // conflict fence, so refreshing only the aggregate revision cannot overwrite this cabin.
    expectedSessionRevision: rawSession.sessionRevision,
    expectedFindingRevision: input.expectedFindingVersion,
    inspection,
    comment: input.comment,
    passportObservation: input.passportObservation,
    equipmentObservation: input.equipmentObservation,
    media: input.media,
    coverMediaId: input.coverMediaId,
    planSelection,
  })
  return toInventoryFindingView(saved)
}

export async function resolveInventoryFindingConflict(input: {
  inventoryId: string
  expectedVersion: number
  expectedFindingVersion: number
  actor: InventoryActorSnapshot
  findingId: string
  strategy: "ACCEPT_REGISTRY" | "KEEP_INSPECTION"
  reason: string | null
}) {
  const token = await accessToken()
  await inventoryHttp.resolveInventoryFindingConflict({
    accessToken: token,
    inventoryId: input.inventoryId,
    findingId: input.findingId,
    expectedSessionRevision: input.expectedVersion,
    expectedFindingRevision: input.expectedFindingVersion,
    strategy: input.strategy,
    reason: input.reason,
  })
  return sessionView(
    await inventoryHttp.getInventorySession(token, input.inventoryId)
  )
}

export async function startInventoryFurnitureReview(input: {
  session: InventorySessionDto
  acknowledgeIncomplete: boolean
}) {
  const token = await accessToken()
  const review = await inventoryHttp.startFurnitureReview({
    accessToken: token,
    inventoryId: input.session.id,
    request: {
      expectedSessionRevision: input.session.version,
      findingRevisions: input.session.findings.map((finding) => ({
        findingId: finding.id,
        expectedFindingRevision: finding.version,
      })),
      acknowledgeIncomplete: input.acknowledgeIncomplete,
    },
    idempotencyKey: commandKey(),
  })
  return toInventoryFurnitureReviewView(review)
}

export async function getInventoryFurnitureReview(inventoryId: string) {
  return toInventoryFurnitureReviewView(
    await inventoryHttp.getFurnitureReview(await accessToken(), inventoryId)
  )
}

export async function saveInventoryFurnitureReview(input: {
  review: InventoryFurnitureReviewDto
  session: InventorySessionDto
}) {
  const findingRevisions = new Map(
    input.session.findings.map((finding) => [finding.id, finding.version])
  )
  const expectedFindingRevision = (findingId: string) => {
    const revision = findingRevisions.get(findingId)
    if (revision === undefined) {
      throw new Error(
        "Бытовка отсутствует в актуальных результатах инвентаризации"
      )
    }
    return revision
  }
  const saved = await inventoryHttp.saveFurnitureReview({
    accessToken: await accessToken(),
    inventoryId: input.review.inventoryId,
    request: {
      expectedSessionRevision: input.review.sessionRevision,
      assetSnapshotSha256: input.review.assetSnapshotSha256,
      items: input.review.items.map((item) => ({
        equipmentId: item.equipmentId,
        catalogVersion: item.catalogVersion,
        observedStockQuantity: item.observedStockQuantity,
        cabins: item.cabins.map((cabin) => ({
          findingId: cabin.findingId,
          expectedFindingRevision: expectedFindingRevision(cabin.findingId),
          observedQuantity: cabin.observedQuantity,
        })),
      })),
    },
  })
  return toInventoryFurnitureReviewView(saved)
}

export async function getInventoryPlanningSettings(
  warehouseId: string
): Promise<InventoryPlanningSettings> {
  return inventoryHttp.getInventoryPlanningSettings(
    await accessToken(),
    warehouseId
  )
}

export async function saveInventoryPlanningSettings(
  warehouseId: string,
  request: UpdateInventoryPlanningSettingsRequest
) {
  return inventoryHttp.updateInventoryPlanningSettings({
    accessToken: await accessToken(),
    warehouseId,
    request,
  })
}

export async function getInventoryFinalPlan(inventoryId: string) {
  try {
    return await inventoryHttp.getInventoryFinalPlan(
      await accessToken(),
      inventoryId
    )
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null
    throw error
  }
}

export async function prepareInventoryFinalPlan(input: {
  inventoryId: string
  expectedSessionRevision: number
  expectedSettingsRevision: number
  movementScheduleMode: "AUTO" | "MANUAL"
  repairScheduleMode: "AUTO" | "MANUAL"
}) {
  return inventoryHttp.prepareInventoryFinalPlan({
    accessToken: await accessToken(),
    inventoryId: input.inventoryId,
    request: {
      expectedSessionRevision: input.expectedSessionRevision,
      expectedSettingsRevision: input.expectedSettingsRevision,
      movementScheduleMode: input.movementScheduleMode,
      repairScheduleMode: input.repairScheduleMode,
    },
    idempotencyKey: commandKey(),
  })
}

export async function saveInventoryFinalPlan(input: {
  inventoryId: string
  request: UpdateInventoryFinalPlanRequest
}) {
  return inventoryHttp.updateInventoryFinalPlan({
    accessToken: await accessToken(),
    inventoryId: input.inventoryId,
    request: input.request,
    idempotencyKey: commandKey(),
  })
}

export type InventoryCompletionReview = InventorySessionDto & {
  completionEvidence: InventoryCompletionPreview
}

async function previewWithSession(
  inventoryId: string,
  finalPlan: InventoryFinalPlan
) {
  const token = await accessToken()
  const rawSession = await inventoryHttp.getInventorySession(token, inventoryId)
  const preview = await inventoryHttp.previewInventoryCompletion({
    accessToken: token,
    session: rawSession,
    finalPlanVersion: finalPlan.finalPlanVersion,
    finalPlanSha256: finalPlan.finalPlanSha256,
    idempotencyKey: commandKey(),
  })
  const reviewed = applyInventoryCompletionPreview(
    sessionView(rawSession),
    preview
  )
  return {
    token,
    rawSession,
    preview,
    reviewed: {
      ...reviewed,
      completionEvidence: preview,
    } satisfies InventoryCompletionReview,
  }
}

export async function previewInventoryCompletion(input: {
  inventoryId: string
  expectedVersion: number
  actor: InventoryActorSnapshot
  finalPlan: InventoryFinalPlan
}) {
  const result = await previewWithSession(input.inventoryId, input.finalPlan)
  if (result.rawSession.sessionRevision !== input.expectedVersion) {
    throw new Error("Инвентаризация была изменена. Обновите данные")
  }
  return result.reviewed
}

export async function completeInventory(input: {
  inventoryId: string
  expectedVersion: number
  actor: InventoryActorSnapshot
  completionEvidence: InventoryCompletionPreview
}) {
  if (
    input.completionEvidence.inventoryId !== input.inventoryId ||
    input.completionEvidence.sessionRevision !== input.expectedVersion
  ) {
    throw new Error("Инвентаризация была изменена. Обновите данные")
  }
  await inventoryHttp.completeInventorySession({
    accessToken: await accessToken(),
    preview: input.completionEvidence,
    idempotencyKey: commandKey(),
  })
  return sessionView(await refreshedSession(input.inventoryId))
}

export async function cancelInventory(input: {
  inventoryId: string
  expectedVersion: number
  reason: string
}) {
  const reason = input.reason.trim()
  if (!reason) throw new Error("Укажите причину отмены инвентаризации")
  const cancelled = await inventoryHttp.cancelInventorySession({
    accessToken: await accessToken(),
    inventoryId: input.inventoryId,
    expectedSessionRevision: input.expectedVersion,
    reason,
    idempotencyKey: commandKey(),
  })
  return sessionView({ ...cancelled, findings: [] })
}

export async function recalculateInventoryOutcome(input: {
  inventoryId: string
  expectedSessionRevision: number
  finalPlanVersion: number
  finalPlanSha256: string
}) {
  return inventoryHttp.recalculateInventoryOutcome({
    accessToken: await accessToken(),
    inventoryId: input.inventoryId,
    request: {
      expectedSessionRevision: input.expectedSessionRevision,
      finalPlanVersion: input.finalPlanVersion,
      finalPlanSha256: input.finalPlanSha256,
    },
    idempotencyKey: commandKey(),
  })
}
