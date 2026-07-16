import {
  assertInventoryPermission,
  assertInventoryWarehouseAccess,
  canonicalizeInventoryNumber,
  deriveInventoryPublicationStatus,
  deriveInventoryReconciliationStatus,
} from "@/features/inventory/domain/inventory-domain"
import type {
  InventoryCompleteCommand,
  InventoryFindingDto,
  InventoryFindingInspectionCommand,
  InventorySessionDto,
  InventoryStartCommand,
} from "@/features/inventory/model/inventory"
import type { InventoryClient } from "@/features/inventory/ports/inventory-client"

export const INVENTORY_STORAGE_KEY = "rwms:inventory:v1"
export const INVENTORY_UPDATED_EVENT = "rwms:inventory-updated"
const INVENTORY_MUTATION_LOCK = "rwms:inventory:mutation"
const INVENTORY_FALLBACK_LOCK_KEY = "rwms:inventory:mutation:lease"

type InventoryEnvelope = {
  service: "inventory"
  schemaVersion: 2
  revision: number
  sessions: InventorySessionDto[]
}

const runtime = globalThis as typeof globalThis & {
  __rwmsInventoryMutationQueue__?: Promise<void>
  __rwmsInventoryMemoryEnvelope__?: InventoryEnvelope
}
runtime.__rwmsInventoryMutationQueue__ ??= Promise.resolve()

function emptyEnvelope(): InventoryEnvelope {
  return { service: "inventory", schemaVersion: 2, revision: 0, sessions: [] }
}

function clone<T>(value: T): T {
  return structuredClone(value)
}

function opaqueId(prefix: string) {
  const id =
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `${prefix}-${id}`
}

function normalizeRepairPlan(
  value: unknown
): InventoryFindingDto["repairPlans"][number] | null {
  if (!value || typeof value !== "object") return null
  const plan = value as Partial<InventoryFindingDto["repairPlans"][number]>
  if (typeof plan.id !== "string" || !plan.id) return null
  return {
    id: plan.id,
    kind:
      plan.kind === "MOVE_TO_REPAIR" || plan.kind === "MOVE_FROM_REPAIR"
        ? plan.kind
        : "REPAIR_WORK",
    includedLineIds: Array.isArray(plan.includedLineIds)
      ? plan.includedLineIds.filter(
          (lineId): lineId is string => typeof lineId === "string"
        )
      : [],
    primaryLineId:
      typeof plan.primaryLineId === "string" ? plan.primaryLineId : null,
    groupComment:
      typeof plan.groupComment === "string" ? plan.groupComment : "",
    queueCode: typeof plan.queueCode === "string" ? plan.queueCode : null,
    routeQueueKind:
      plan.routeQueueKind === "REPAIR" ||
      plan.routeQueueKind === "MOVEMENT" ||
      plan.routeQueueKind === "HOLDING"
        ? plan.routeQueueKind
        : null,
    sortOrder:
      typeof plan.sortOrder === "number" && Number.isFinite(plan.sortOrder)
        ? Math.max(0, Math.floor(plan.sortOrder))
        : 0,
    plannedDurationMinutes:
      typeof plan.plannedDurationMinutes === "number" &&
      Number.isFinite(plan.plannedDurationMinutes) &&
      plan.plannedDurationMinutes >= 0
        ? Math.floor(plan.plannedDurationMinutes)
        : null,
    photoRequired: plan.photoRequired === true,
  }
}

function normalizeFinding(value: unknown): InventoryFindingDto | null {
  if (!value || typeof value !== "object") return null
  const finding = value as Partial<InventoryFindingDto>
  if (typeof finding.id !== "string" || typeof finding.cabinNumber !== "string")
    return null
  const lines = Array.isArray(finding.lines) ? finding.lines : []
  const inspectionStatus =
    finding.inspectionStatus === "READY" ||
    finding.inspectionStatus === "WORK_STAGED"
      ? finding.inspectionStatus
      : "NOT_INSPECTED"
  return {
    id: finding.id,
    rentalItemId:
      typeof finding.rentalItemId === "string" ? finding.rentalItemId : null,
    canonicalNumber: canonicalizeInventoryNumber(
      finding.canonicalNumber || finding.cabinNumber
    ),
    cabinNumber: finding.cabinNumber.trim(),
    origin:
      finding.origin === "ADDED_NEW" ||
      finding.origin === "ADDED_USED" ||
      finding.origin === "UNEXPECTED_EXISTING"
        ? finding.origin
        : "EXPECTED",
    inspectionStatus,
    reconciliationStatus:
      finding.reconciliationStatus === "MATCHED" ? "MATCHED" : "MISSING",
    expectedSnapshot: finding.expectedSnapshot ?? null,
    currentSnapshot: finding.currentSnapshot ?? null,
    conflicts: Array.isArray(finding.conflicts) ? finding.conflicts : [],
    comment: typeof finding.comment === "string" ? finding.comment : "",
    media: Array.isArray(finding.media) ? finding.media : [],
    lines,
    repairCompletionMode:
      finding.repairCompletionMode === "AUTO" ||
      finding.repairCompletionMode === "MANUAL"
        ? finding.repairCompletionMode
        : null,
    movementRequired: finding.movementRequired === true,
    repairPlans: Array.isArray(finding.repairPlans)
      ? finding.repairPlans
          .map(normalizeRepairPlan)
          .filter(
            (
              plan
            ): plan is NonNullable<ReturnType<typeof normalizeRepairPlan>> =>
              plan !== null
          )
      : [],
    publicationStatus:
      finding.publicationStatus === "READY" ||
      finding.publicationStatus === "PUBLISHING" ||
      finding.publicationStatus === "PUBLISHED" ||
      finding.publicationStatus === "BLOCKED" ||
      finding.publicationStatus === "FAILED"
        ? finding.publicationStatus
        : lines.length > 0
          ? "READY"
          : "NOT_REQUIRED",
    publicationOperationKey:
      typeof finding.publicationOperationKey === "string"
        ? finding.publicationOperationKey
        : null,
    publishedRepairTaskId:
      typeof finding.publishedRepairTaskId === "string"
        ? finding.publishedRepairTaskId
        : null,
    publicationError:
      typeof finding.publicationError === "string"
        ? finding.publicationError
        : null,
    inspectedAt:
      typeof finding.inspectedAt === "string" ? finding.inspectedAt : null,
    inspectedBy: finding.inspectedBy ?? null,
  }
}

function normalizeSession(value: unknown): InventorySessionDto | null {
  if (!value || typeof value !== "object") return null
  const session = value as Partial<InventorySessionDto>
  if (
    typeof session.id !== "string" ||
    typeof session.warehouseId !== "string" ||
    !session.warehouse ||
    !session.author ||
    typeof session.startedAt !== "string" ||
    typeof session.businessDate !== "string"
  ) {
    return null
  }
  const findings = (Array.isArray(session.findings) ? session.findings : [])
    .map(normalizeFinding)
    .filter((item): item is InventoryFindingDto => item !== null)
  return {
    id: session.id,
    version:
      typeof session.version === "number" && Number.isFinite(session.version)
        ? Math.max(1, Math.floor(session.version))
        : 1,
    warehouseId: session.warehouseId,
    status: session.status === "COMPLETED" ? "COMPLETED" : "ACTIVE",
    warehouse: session.warehouse,
    author: session.author,
    businessDate: session.businessDate,
    startedAt: session.startedAt,
    completedAt:
      typeof session.completedAt === "string" ? session.completedAt : null,
    expectedItems: Array.isArray(session.expectedItems)
      ? session.expectedItems
      : findings
          .map((finding) => finding.expectedSnapshot)
          .filter((item): item is NonNullable<typeof item> => item !== null),
    findings,
    statistics: session.statistics ?? null,
    publicationStatus:
      session.publicationStatus ?? deriveInventoryPublicationStatus(findings),
  }
}

function parseEnvelope(raw: string | null): InventoryEnvelope {
  if (!raw) return emptyEnvelope()
  try {
    const parsed = JSON.parse(raw) as unknown
    const sessions = Array.isArray(parsed)
      ? parsed
      : parsed && typeof parsed === "object"
        ? ((parsed as { sessions?: unknown }).sessions ?? [])
        : []
    return {
      service: "inventory",
      schemaVersion: 2,
      revision:
        parsed &&
        typeof parsed === "object" &&
        typeof (parsed as { revision?: unknown }).revision === "number"
          ? Math.max(0, Math.floor((parsed as { revision: number }).revision))
          : 0,
      sessions: (Array.isArray(sessions) ? sessions : [])
        .map(normalizeSession)
        .filter((item): item is InventorySessionDto => item !== null),
    }
  } catch {
    return emptyEnvelope()
  }
}

function readEnvelope() {
  if (typeof window === "undefined") {
    return clone(runtime.__rwmsInventoryMemoryEnvelope__ ?? emptyEnvelope())
  }
  return parseEnvelope(window.localStorage.getItem(INVENTORY_STORAGE_KEY))
}

function writeEnvelope(envelope: InventoryEnvelope) {
  runtime.__rwmsInventoryMemoryEnvelope__ = clone(envelope)
  if (typeof window === "undefined") return
  window.localStorage.setItem(INVENTORY_STORAGE_KEY, JSON.stringify(envelope))
  window.dispatchEvent(new Event(INVENTORY_UPDATED_EVENT))
}

function runMutation<T>(operation: () => Promise<T>) {
  const locked = () =>
    typeof navigator !== "undefined" && navigator.locks
      ? navigator.locks.request(INVENTORY_MUTATION_LOCK, operation)
      : runWithFallbackLease(operation)
  const result = runtime.__rwmsInventoryMutationQueue__!.then(locked, locked)
  runtime.__rwmsInventoryMutationQueue__ = result.then(
    () => undefined,
    () => undefined
  )
  return result
}

async function runWithFallbackLease<T>(operation: () => Promise<T>) {
  if (typeof window === "undefined") return operation()
  const token = opaqueId("inventory-lease")
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const now = Date.now()
    let lease: { token: string; expiresAt: number } | null = null
    try {
      lease = JSON.parse(
        window.localStorage.getItem(INVENTORY_FALLBACK_LOCK_KEY) ?? "null"
      ) as { token: string; expiresAt: number } | null
    } catch {
      // A corrupt lease is treated as expired and may be replaced below.
    }
    if (!lease || lease.expiresAt <= now) {
      window.localStorage.setItem(
        INVENTORY_FALLBACK_LOCK_KEY,
        JSON.stringify({ token, expiresAt: now + 5_000 })
      )
      const claimed = window.localStorage.getItem(INVENTORY_FALLBACK_LOCK_KEY)
      if (claimed?.includes(token)) {
        try {
          return await operation()
        } finally {
          if (
            window.localStorage
              .getItem(INVENTORY_FALLBACK_LOCK_KEY)
              ?.includes(token)
          ) {
            window.localStorage.removeItem(INVENTORY_FALLBACK_LOCK_KEY)
          }
        }
      }
    }
    await new Promise((resolve) => window.setTimeout(resolve, 10))
  }
  throw new Error("Инвентаризация занята в другой вкладке")
}

function assertMutable(
  envelope: InventoryEnvelope,
  inventoryId: string,
  expectedVersion: number
) {
  const session = envelope.sessions.find((item) => item.id === inventoryId)
  if (!session) throw new Error("Инвентаризация не найдена")
  if (session.version !== expectedVersion)
    throw new Error("Инвентаризация была изменена. Обновите данные")
  if (session.status !== "ACTIVE")
    throw new Error("Завершённую инвентаризацию нельзя изменить")
  return session
}

function commitSession(
  envelope: InventoryEnvelope,
  session: InventorySessionDto
) {
  writeEnvelope({
    ...envelope,
    revision: envelope.revision + 1,
    sessions: envelope.sessions.map((item) =>
      item.id === session.id ? session : item
    ),
  })
  return clone(session)
}

export class LocalStorageInventoryAdapter implements InventoryClient {
  async list(warehouseId: string) {
    return readEnvelope()
      .sessions.filter((session) => session.warehouseId === warehouseId)
      .sort((left, right) => right.startedAt.localeCompare(left.startedAt))
      .map(clone)
  }

  async get(inventoryId: string) {
    const session = readEnvelope().sessions.find(
      (item) => item.id === inventoryId
    )
    return session ? clone(session) : null
  }

  async getActive(warehouseId: string) {
    const session = readEnvelope().sessions.find(
      (item) => item.warehouseId === warehouseId && item.status === "ACTIVE"
    )
    return session ? clone(session) : null
  }

  async start(command: InventoryStartCommand) {
    assertInventoryPermission(command.actor, "EDIT")
    assertInventoryWarehouseAccess(command.actor, command.warehouse.id)
    return runMutation(async () => {
      const envelope = readEnvelope()
      const existing = envelope.sessions.find(
        (item) =>
          item.warehouseId === command.warehouse.id && item.status === "ACTIVE"
      )
      if (existing) return clone(existing)
      const startedAt = new Date().toISOString()
      const findings: InventoryFindingDto[] = command.expectedItems.map(
        (item) => ({
          id: opaqueId("inventory-finding"),
          rentalItemId: item.rentalItemId,
          canonicalNumber: item.canonicalNumber,
          cabinNumber: item.number,
          origin: "EXPECTED",
          inspectionStatus: "NOT_INSPECTED",
          reconciliationStatus: "MISSING",
          expectedSnapshot: clone(item),
          currentSnapshot: clone(item),
          conflicts: [],
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
        })
      )
      const session: InventorySessionDto = {
        id: opaqueId("inventory"),
        version: 1,
        warehouseId: command.warehouse.id,
        status: "ACTIVE",
        warehouse: clone(command.warehouse),
        author: clone(command.actor),
        businessDate: command.businessDate,
        startedAt,
        completedAt: null,
        expectedItems: clone(command.expectedItems),
        findings,
        statistics: null,
        publicationStatus: "NOT_REQUESTED",
      }
      writeEnvelope({
        ...envelope,
        revision: envelope.revision + 1,
        sessions: [session, ...envelope.sessions],
      })
      return clone(session)
    })
  }

  async addFinding(command: Parameters<InventoryClient["addFinding"]>[0]) {
    assertInventoryPermission(command.actor, "EDIT")
    return runMutation(async () => {
      const envelope = readEnvelope()
      const session = assertMutable(
        envelope,
        command.inventoryId,
        command.expectedVersion
      )
      assertInventoryWarehouseAccess(command.actor, session.warehouseId)
      const byId = session.findings.find(
        (finding) => finding.id === command.finding.id
      )
      const byNumber = session.findings.find(
        (finding) => finding.canonicalNumber === command.finding.canonicalNumber
      )
      if (byId || byNumber) return clone(session)
      return commitSession(envelope, {
        ...session,
        version: session.version + 1,
        findings: [...session.findings, clone(command.finding)],
      })
    })
  }

  async saveFinding(command: InventoryFindingInspectionCommand) {
    assertInventoryPermission(command.actor, "EDIT")
    return runMutation(async () => {
      const envelope = readEnvelope()
      const session = assertMutable(
        envelope,
        command.inventoryId,
        command.expectedVersion
      )
      assertInventoryWarehouseAccess(command.actor, session.warehouseId)
      const finding = session.findings.find(
        (item) => item.id === command.findingId
      )
      if (!finding) throw new Error("Бытовка не найдена в инвентаризации")
      const now = new Date().toISOString()
      const savedFinding: InventoryFindingDto = {
        ...finding,
        comment: command.comment.trim(),
        media: clone(command.media),
        lines: clone(command.lines),
        repairCompletionMode: command.repairCompletionMode ?? null,
        movementRequired: command.movementRequired === true,
        repairPlans: clone(command.repairPlans),
        currentSnapshot: clone(command.currentSnapshot),
        conflicts: clone(command.conflicts),
        inspectionStatus: command.lines.length > 0 ? "WORK_STAGED" : "READY",
        reconciliationStatus: deriveInventoryReconciliationStatus(
          command.currentSnapshot,
          command.conflicts
        ),
        publicationStatus: command.lines.length > 0 ? "READY" : "NOT_REQUIRED",
        publicationOperationKey: null,
        publishedRepairTaskId: null,
        publicationError: null,
        inspectedAt: now,
        inspectedBy: clone(command.actor),
      }
      return commitSession(envelope, {
        ...session,
        version: session.version + 1,
        findings: session.findings.map((item) =>
          item.id === savedFinding.id ? savedFinding : item
        ),
      })
    })
  }

  async replaceFindings(
    command: Parameters<InventoryClient["replaceFindings"]>[0]
  ) {
    assertInventoryPermission(command.actor, "EDIT")
    return runMutation(async () => {
      const envelope = readEnvelope()
      const session = assertMutable(
        envelope,
        command.inventoryId,
        command.expectedVersion
      )
      assertInventoryWarehouseAccess(command.actor, session.warehouseId)
      return commitSession(envelope, {
        ...session,
        version: session.version + 1,
        findings: clone(command.findings),
      })
    })
  }

  async complete(command: InventoryCompleteCommand) {
    assertInventoryPermission(command.actor, "MANAGE")
    return runMutation(async () => {
      const envelope = readEnvelope()
      const session = assertMutable(
        envelope,
        command.inventoryId,
        command.expectedVersion
      )
      assertInventoryWarehouseAccess(command.actor, session.warehouseId)
      const completed: InventorySessionDto = {
        ...session,
        version: session.version + 1,
        status: "COMPLETED",
        completedAt: new Date().toISOString(),
        findings: clone(command.findings),
        statistics: clone(command.statistics),
        publicationStatus: deriveInventoryPublicationStatus(command.findings),
      }
      return commitSession(envelope, completed)
    })
  }

  async setFindingPublication(
    command: Parameters<InventoryClient["setFindingPublication"]>[0]
  ) {
    assertInventoryPermission(command.actor, "MANAGE")
    return runMutation(async () => {
      const envelope = readEnvelope()
      const session = envelope.sessions.find(
        (item) => item.id === command.inventoryId
      )
      if (!session) throw new Error("Инвентаризация не найдена")
      assertInventoryWarehouseAccess(command.actor, session.warehouseId)
      if (session.status !== "COMPLETED")
        throw new Error("Передача работ доступна после завершения")
      if (session.version !== command.expectedVersion)
        throw new Error("Инвентаризация была изменена. Обновите данные")
      const finding = session.findings.find(
        (item) => item.id === command.findingId
      )
      if (!finding) throw new Error("Бытовка не найдена в инвентаризации")
      if (
        finding.publicationOperationKey &&
        command.operationKey !== finding.publicationOperationKey
      ) {
        throw new Error("Ключ операции публикации нельзя изменить")
      }
      if (
        finding.publishedRepairTaskId &&
        command.repairTaskId !== finding.publishedRepairTaskId
      ) {
        throw new Error("Связанное задание публикации нельзя заменить")
      }
      if (
        finding.publicationStatus === "PUBLISHED" &&
        command.status !== "PUBLISHED"
      ) {
        throw new Error("Опубликованную работу нельзя вернуть в обработку")
      }
      if (command.status === "PUBLISHING" && !command.operationKey) {
        throw new Error("Для публикации требуется устойчивый ключ операции")
      }
      if (command.status === "PUBLISHED" && !command.repairTaskId) {
        throw new Error("Опубликованная работа должна ссылаться на задание")
      }
      const findings = session.findings.map((item) =>
        item.id === finding.id
          ? {
              ...item,
              publicationStatus: command.status,
              publicationOperationKey: command.operationKey,
              publishedRepairTaskId: command.repairTaskId,
              publicationError: command.error,
            }
          : item
      )
      return commitSession(envelope, {
        ...session,
        version: session.version + 1,
        findings,
        publicationStatus: deriveInventoryPublicationStatus(findings),
      })
    })
  }

  subscribe(listener: () => void) {
    if (typeof window === "undefined") return () => undefined
    const handleStorage = (event: StorageEvent) => {
      if (event.key === INVENTORY_STORAGE_KEY) listener()
    }
    window.addEventListener(INVENTORY_UPDATED_EVENT, listener)
    window.addEventListener("storage", handleStorage)
    return () => {
      window.removeEventListener(INVENTORY_UPDATED_EVENT, listener)
      window.removeEventListener("storage", handleStorage)
    }
  }
}
