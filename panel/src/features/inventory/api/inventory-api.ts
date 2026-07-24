import { getUserManager } from "@/features/auth/oidc-client"
import * as inventoryHttp from "@/features/inventory/adapters/http-inventory-adapter"
import {
  inventoryCompletionRiskSignature,
  toInventoryRepairPlanSnapshot,
} from "@/features/inventory/domain/inventory-domain"
import { buildInventoryPlanSelection } from "@/features/inventory/domain/inventory-plan-mapper"
import {
  applyInventoryCompletionPreview,
  toInventoryFindingView,
  toInventorySessionView,
} from "@/features/inventory/domain/inventory-view-mapper"
import type {
  InventoryActorSnapshot,
  InventoryCreateRentalItem,
  InventoryFindingDto,
  InventorySessionDto,
  InventoryWarehouseSnapshot,
} from "@/features/inventory/model/inventory"
import type { InventorySessionView } from "@/features/inventory/model/inventory-service"
import type {
  RepairEstimateCompletionMode,
  RepairEstimateLineDto,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"

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
    code: "—",
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
    return {
      kind: "NOT_FOUND" as const,
      canonicalNumber: resolution.displayCanonicalNumber,
      lookup: { kind: "NOT_FOUND" as const },
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
      rentalType: input.rentalItem.type,
      dimensions: input.rentalItem.dimensions,
      finishing: input.rentalItem.finishing,
      category: input.rentalItem.category,
      characteristics: input.rentalItem.characteristics.join(", "),
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
  expectedVersion: number
  actor: InventoryActorSnapshot
  findingId: string
  comment: string
  media: Array<{ mediaId: string; generation: number }>
  lines: RepairEstimateLineDto[]
  repairPlans: ReturnType<typeof toInventoryRepairPlanSnapshot>[]
  repairCompletionMode?: RepairEstimateCompletionMode | null
  movementRequired?: boolean
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
    const taskPlans: RepairEstimateTaskPlanDto[] = input.repairPlans.map(
      (plan) => ({
        ...plan,
        generationStatus: "PENDING_GENERATION",
        workflowRequestRef: null,
      })
    )
    planSelection = buildInventoryPlanSelection({
      completionMode: input.repairCompletionMode ?? "MANUAL",
      movementRequired: input.movementRequired === true,
      taskPlans,
      lines: input.lines,
      media: input.media,
    })
  }
  const saved = await inventoryHttp.saveInventoryInspection({
    accessToken: token,
    inventoryId: input.inventoryId,
    findingId: input.findingId,
    expectedSessionRevision: input.expectedVersion,
    expectedFindingRevision: finding.findingRevision,
    inspection,
    comment: input.comment,
    media: input.media,
    planSelection,
  })
  return toInventoryFindingView(saved)
}

async function previewWithSession(inventoryId: string) {
  const token = await accessToken()
  const rawSession = await inventoryHttp.getInventorySession(token, inventoryId)
  const preview = await inventoryHttp.previewInventoryCompletion({
    accessToken: token,
    session: rawSession,
    idempotencyKey: commandKey(),
  })
  return {
    token,
    rawSession,
    preview,
    reviewed: applyInventoryCompletionPreview(sessionView(rawSession), preview),
  }
}

export async function previewInventoryCompletion(input: {
  inventoryId: string
  expectedVersion: number
  actor: InventoryActorSnapshot
}) {
  const result = await previewWithSession(input.inventoryId)
  if (result.rawSession.sessionRevision !== input.expectedVersion) {
    throw new Error("Инвентаризация была изменена. Обновите данные")
  }
  return result.reviewed
}

export async function completeInventory(input: {
  inventoryId: string
  expectedVersion: number
  actor: InventoryActorSnapshot
  acknowledgedRiskSignature: string | null
}) {
  const result = await previewWithSession(input.inventoryId)
  if (result.rawSession.sessionRevision !== input.expectedVersion) {
    throw new Error("Инвентаризация была изменена. Обновите данные")
  }
  const currentRiskSignature = inventoryCompletionRiskSignature(
    result.reviewed.findings
  )
  if (
    currentRiskSignature &&
    currentRiskSignature !== input.acknowledgedRiskSignature
  ) {
    throw new Error(
      "Сверка изменилась. Проверьте ненайденные бытовки и конфликты повторно"
    )
  }
  await inventoryHttp.completeInventorySession({
    accessToken: result.token,
    preview: result.preview,
    idempotencyKey: commandKey(),
  })
  return sessionView(await refreshedSession(input.inventoryId))
}

export async function publishInventoryWorks(input: {
  inventoryId: string
  actor: InventoryActorSnapshot
}) {
  const token = await accessToken()
  const session = await inventoryHttp.getInventorySession(
    token,
    input.inventoryId
  )
  await inventoryHttp.publishInventoryFindings({
    accessToken: token,
    inventoryId: input.inventoryId,
    expectedSessionRevision: session.sessionRevision,
    idempotencyKey: commandKey(),
  })
  return sessionView(
    await inventoryHttp.getInventorySession(token, input.inventoryId)
  )
}
