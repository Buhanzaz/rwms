import {
  readRentalItems,
  runRentalItemMutation,
  writeRentalItems,
} from "@/features/rental-items/api/rental-items-api"
import type {
  RentalItemContentsItemDto,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"
import type {
  CreateWarehouseTransferCommand,
  WarehouseAccountingCorrection,
  WarehouseTransferActorSnapshot,
  WarehouseTransferDocument,
  WarehouseTransferEvent,
  WarehouseTransferEventType,
  WarehouseTransferLine,
  WarehouseTransferPhoto,
  WarehouseTransferWarehouseSnapshot,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import { WAREHOUSE_TRANSFER_ALLOWED_STATUSES } from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import type { WarehouseTransferStore } from "@/features/logistics/warehouse-transfers/ports/warehouse-transfer-store"
import type { WarehouseTransferTaskClient } from "@/features/logistics/warehouse-transfers/ports/warehouse-transfer-task-client"
import { hasActiveShipmentForRentalItemLowLevel } from "@/features/logistics/shipments/active-shipment-guard"

let transferCommandQueue: Promise<void> = Promise.resolve()
const WAREHOUSE_TRANSFER_MUTATION_LOCK = "rwms:warehouse-transfer:mutation"
const BROWSER_STORE_WAREHOUSE_IDS: Readonly<Record<string, string>> = {
  "00000000-0000-0000-0000-000000000001": "spb",
  "00000000-0000-0000-0000-000000000002": "msk",
}

function browserStoreWarehouseId(warehouseId: string) {
  return BROWSER_STORE_WAREHOUSE_IDS[warehouseId] ?? warehouseId
}

function isStoredAtWarehouse(
  storedWarehouseId: string,
  warehouse: WarehouseTransferWarehouseSnapshot
) {
  return storedWarehouseId === browserStoreWarehouseId(warehouse.id)
}

function runTransferCommand<T>(operation: () => Promise<T>) {
  const withOriginLock = () =>
    typeof navigator !== "undefined" && navigator.locks
      ? navigator.locks.request(WAREHOUSE_TRANSFER_MUTATION_LOCK, operation)
      : operation()
  const result = transferCommandQueue.then(withOriginLock, withOriginLock)
  transferCommandQueue = result.then(
    () => undefined,
    () => undefined
  )
  return result
}

function now() {
  return new Date().toISOString()
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Неизвестная ошибка"
}

function normalizeContents(items: RentalItemContentsItemDto[]) {
  const quantities = new Map<string, { name: string; quantity: number }>()
  items.forEach((item) => {
    const name = item.name.trim()
    const key = name.toLocaleLowerCase("ru-RU")
    if (!name || !Number.isInteger(item.quantity) || item.quantity <= 0) return
    const current = quantities.get(key)
    quantities.set(key, {
      name: current?.name ?? name,
      quantity: (current?.quantity ?? 0) + item.quantity,
    })
  })
  return Array.from(quantities.values()).sort((left, right) =>
    left.name.localeCompare(right.name, "ru")
  )
}

function contentsEqual(
  left: RentalItemContentsItemDto[],
  right: RentalItemContentsItemDto[]
) {
  const normalizedLeft = normalizeContents(left)
  const normalizedRight = normalizeContents(right)
  return (
    normalizedLeft.length === normalizedRight.length &&
    normalizedLeft.every(
      (item, index) =>
        item.name.toLocaleLowerCase("ru-RU") ===
          normalizedRight[index].name.toLocaleLowerCase("ru-RU") &&
        item.quantity === normalizedRight[index].quantity
    )
  )
}

function warehouseSnapshot(
  value: WarehouseTransferWarehouseSnapshot
): WarehouseTransferWarehouseSnapshot {
  return structuredClone(value)
}

function taskCommand(
  document: WarehouseTransferDocument,
  line: WarehouseTransferLine,
  direction: "SOURCE" | "DESTINATION"
) {
  return {
    externalTaskId:
      direction === "SOURCE"
        ? line.sourceExternalTaskId
        : line.destinationExternalTaskId,
    serviceWarehouseId:
      direction === "SOURCE"
        ? document.sourceWarehouse.id
        : document.destinationWarehouse.id,
    direction,
    sourceWarehouse: document.sourceWarehouse,
    destinationWarehouse: document.destinationWarehouse,
    cabin: {
      rentalItemId: line.rentalItemId,
      cabinNumber: line.cabinNumber,
      contentsSnapshot: line.contentsSnapshot,
    },
    plannedDate: document.plannedDate,
    driverName: document.driverName,
  } as const
}

export class BrowserWarehouseTransferClient {
  private readonly store: WarehouseTransferStore
  private readonly taskClient: WarehouseTransferTaskClient

  constructor(
    store: WarehouseTransferStore,
    taskClient: WarehouseTransferTaskClient
  ) {
    this.store = store
    this.taskClient = taskClient
  }

  list() {
    return Promise.resolve(this.store.list())
  }

  get(documentId: string) {
    return Promise.resolve(this.store.findById(documentId))
  }

  listEventsForRentalItem(rentalItemId: string) {
    return Promise.resolve(this.store.listEventsForRentalItem(rentalItemId))
  }

  listCorrections() {
    return Promise.resolve(this.store.listCorrections())
  }

  hasActiveTransfer(rentalItemId: string) {
    return Promise.resolve(
      this.store.findActiveByRentalItemId(rentalItemId) !== null
    )
  }

  listActiveRentalItemIds() {
    return Promise.resolve(
      Array.from(
        new Set(
          this.store
            .list()
            .flatMap((document) =>
              document.lines
                .filter((line) =>
                  [
                    "PREPARING",
                    "READY_TO_DEPART",
                    "IN_TRANSIT",
                    "CONFLICT",
                  ].includes(line.status)
                )
                .map((line) => line.rentalItemId)
            )
        )
      )
    )
  }

  create(command: CreateWarehouseTransferCommand) {
    return runTransferCommand(async () => {
      const existing = this.store.findByRequestId(command.requestId)
      if (existing) return existing
      this.validateWarehouses(
        command.sourceWarehouse,
        command.destinationWarehouse
      )
      if (!command.plannedDate || !command.driverName.trim()) {
        throw new Error("Укажите дату перемещения и водителя")
      }
      if (command.cabins.length === 0) {
        throw new Error("Выберите хотя бы одну бытовку")
      }
      const selectedIds = new Set(
        command.cabins.map((item) => item.rentalItemId)
      )
      if (selectedIds.size !== command.cabins.length) {
        throw new Error("Одна бытовка выбрана несколько раз")
      }
      const rentalItems = readRentalItems()
      command.cabins.forEach((selected) => {
        if (this.store.findActiveByRentalItemId(selected.rentalItemId)) {
          const cabin = rentalItems.find(
            (item) => item.id === selected.rentalItemId
          )
          throw new Error(
            `Для бытовки ${cabin?.number ?? selected.rentalItemId} уже есть активное межскладское перемещение`
          )
        }
        if (hasActiveShipmentForRentalItemLowLevel(selected.rentalItemId)) {
          const cabin = rentalItems.find(
            (item) => item.id === selected.rentalItemId
          )
          throw new Error(
            `Бытовка ${cabin?.number ?? selected.rentalItemId} участвует в активной отгрузке. Сначала завершите или отмените отгрузку`
          )
        }
        if (this.hasActiveCorrection(selected.rentalItemId)) {
          const cabin = rentalItems.find(
            (item) => item.id === selected.rentalItemId
          )
          throw new Error(
            `Для бытовки ${cabin?.number ?? selected.rentalItemId} уже создана активная коррекция учёта`
          )
        }
      })
      const createdAt = now()
      const lines = command.cabins.map<WarehouseTransferLine>((selected) => {
        const cabin = rentalItems.find(
          (item) =>
            item.id === selected.rentalItemId &&
            isStoredAtWarehouse(item.warehouseId, command.sourceWarehouse)
        )
        if (!cabin) throw new Error("Выбранная бытовка не найдена на складе")
        if (cabin.version !== selected.expectedVersion) {
          throw new Error(
            `Бытовка ${cabin.number} изменена. Обновите список перед созданием перемещения`
          )
        }
        if (!WAREHOUSE_TRANSFER_ALLOWED_STATUSES.includes(cabin.status)) {
          throw new Error(
            `Бытовку ${cabin.number} нельзя переместить в статусе «${cabin.status}»`
          )
        }
        return {
          id: crypto.randomUUID(),
          version: 0,
          rentalItemId: cabin.id,
          cabinNumber: cabin.number,
          sourceStatus: cabin.status as WarehouseTransferLine["sourceStatus"],
          rentalItemVersion: cabin.version,
          contentsSnapshot: normalizeContents(cabin.contentsItems),
          status: "PREPARING",
          sourceExternalTaskId: crypto.randomUUID(),
          destinationExternalTaskId: crypto.randomUUID(),
          sourceTask: null,
          destinationTask: null,
          photos: [],
          departedAt: null,
          receivedAt: null,
          conflictReason: null,
          lastError: null,
          applicationAttempt: null,
          returnConflict: selected.returnConflict
            ? structuredClone(selected.returnConflict)
            : null,
        }
      })
      let document: WarehouseTransferDocument = {
        id: crypto.randomUUID(),
        requestId: command.requestId,
        version: 0,
        sourceWarehouse: warehouseSnapshot(command.sourceWarehouse),
        destinationWarehouse: warehouseSnapshot(command.destinationWarehouse),
        plannedDate: command.plannedDate,
        driverName: command.driverName.trim(),
        vehicle: null,
        comment: command.comment?.trim() || null,
        actor: structuredClone(command.actor),
        createdAt,
        updatedAt: createdAt,
        lines,
      }
      document = this.store.save(document, null)
      this.event(document, null, "TRANSFER_CREATED", command.actor, null)
      for (const line of document.lines) {
        document = await this.dispatchSourceTask(
          document,
          line.id,
          command.accessToken,
          command.actor
        )
      }
      return document
    })
  }

  retrySourceTask(params: {
    documentId: string
    lineId: string
    expectedDocumentVersion: number
    accessToken: string
    actor: WarehouseTransferActorSnapshot
  }) {
    return runTransferCommand(async () => {
      const document = this.requireDocument(
        params.documentId,
        params.expectedDocumentVersion
      )
      return this.dispatchSourceTask(
        document,
        params.lineId,
        params.accessToken,
        params.actor
      )
    })
  }

  confirmDeparture(params: {
    documentId: string
    lineId: string
    expectedDocumentVersion: number
    expectedLineVersion: number
    accessToken: string
    actor: WarehouseTransferActorSnapshot
  }) {
    return runTransferCommand(async () => {
      let document = this.requireDocument(
        params.documentId,
        params.expectedDocumentVersion
      )
      const line = this.requireLine(document, params.lineId)
      if (
        line.status === "IN_TRANSIT" ||
        line.status === "RECEIVED" ||
        line.status === "CONFLICT"
      ) {
        return document
      }
      this.assertLineVersion(line, params.expectedLineVersion)
      if (line.applicationAttempt?.kind === "DEPARTURE") {
        return this.applyDepartureIntent(
          document,
          line,
          params.accessToken,
          params.actor
        )
      }
      if (line.status !== "READY_TO_DEPART") {
        throw new Error("Бытовка ещё не готова к подтверждению убытия")
      }
      document = this.updateLine(document, line.id, (current) => ({
        ...current,
        version: current.version + 1,
        applicationAttempt: {
          kind: "DEPARTURE",
          startedAt: now(),
          expectedRentalItemVersion: current.rentalItemVersion,
        },
      }))
      return this.applyDepartureIntent(
        document,
        this.requireLine(document, line.id),
        params.accessToken,
        params.actor
      )
    })
  }

  retryDestinationTask(params: {
    documentId: string
    lineId: string
    expectedDocumentVersion: number
    accessToken: string
    actor: WarehouseTransferActorSnapshot
  }) {
    return runTransferCommand(async () => {
      const document = this.requireDocument(
        params.documentId,
        params.expectedDocumentVersion
      )
      return this.dispatchDestinationTask(
        document,
        params.lineId,
        params.accessToken,
        params.actor
      )
    })
  }

  acceptArrival(params: {
    documentId: string
    lineId: string
    expectedDocumentVersion: number
    expectedLineVersion: number
    actualContents: RentalItemContentsItemDto[]
    photos: WarehouseTransferPhoto[]
    actor: WarehouseTransferActorSnapshot
  }) {
    return runTransferCommand(async () => {
      let document = this.requireDocument(
        params.documentId,
        params.expectedDocumentVersion
      )
      const line = this.requireLine(document, params.lineId)
      if (line.status === "RECEIVED") return document
      this.assertLineVersion(line, params.expectedLineVersion)
      if (line.status !== "IN_TRANSIT" && line.status !== "CONFLICT") {
        throw new Error("Приёмка доступна только после подтверждения убытия")
      }
      if (!line.destinationTask) {
        throw new Error(
          "Сначала зарегистрируйте задание приёмки на складе назначения"
        )
      }
      if (line.applicationAttempt?.kind === "ARRIVAL") {
        return this.applyArrivalIntent(document, line, params.actor)
      }
      if (params.photos.length === 0) {
        throw new Error("Для приёмки добавьте хотя бы одну фотографию")
      }
      if (!contentsEqual(line.contentsSnapshot, params.actualContents)) {
        const conflict = this.updateLine(document, line.id, (current) => ({
          ...current,
          version: current.version + 1,
          status: "CONFLICT",
          photos: structuredClone(params.photos),
          conflictReason:
            "Фактическое наполнение не совпадает со снимком отправления",
          lastError: null,
        }))
        this.event(
          conflict,
          line.id,
          "ARRIVAL_CONFLICT",
          params.actor,
          "Фактическое наполнение не совпадает со снимком отправления"
        )
        return conflict
      }

      document = this.updateLine(document, line.id, (current) => ({
        ...current,
        version: current.version + 1,
        applicationAttempt: {
          kind: "ARRIVAL",
          startedAt: now(),
          expectedRentalItemVersion: current.rentalItemVersion,
          photos: structuredClone(params.photos),
        },
      }))
      return this.applyArrivalIntent(
        document,
        this.requireLine(document, line.id),
        params.actor
      )
    })
  }

  cancelLine(params: {
    documentId: string
    lineId: string
    expectedDocumentVersion: number
    expectedLineVersion: number
    accessToken: string
    actor: WarehouseTransferActorSnapshot
    reason: string
  }) {
    return runTransferCommand(async () => {
      let document = this.requireDocument(
        params.documentId,
        params.expectedDocumentVersion
      )
      const line = this.requireLine(document, params.lineId)
      if (line.status === "CANCELLED") return document
      this.assertLineVersion(line, params.expectedLineVersion)
      if (line.status !== "PREPARING" && line.status !== "READY_TO_DEPART") {
        throw new Error(
          "После подтверждения убытия перемещение отменить нельзя"
        )
      }
      if (line.applicationAttempt) {
        throw new Error(
          "Сначала завершите начатое подтверждение убытия бытовки"
        )
      }
      const reason = params.reason.trim()
      if (!reason) throw new Error("Укажите причину отмены")
      let sourceTask = line.sourceTask
      if (!sourceTask) {
        sourceTask = await this.taskClient.recover(params.accessToken, {
          serviceWarehouseId: document.sourceWarehouse.id,
          externalTaskId: line.sourceExternalTaskId,
        })
        if (sourceTask) {
          document = this.updateLine(document, line.id, (current) => ({
            ...current,
            version: current.version + 1,
            sourceTask,
            lastError: null,
          }))
        }
      }
      if (sourceTask) {
        await this.taskClient.cancel(params.accessToken, {
          serviceWarehouseId: document.sourceWarehouse.id,
          externalTaskId: line.sourceExternalTaskId,
          expectedTaskVersion: sourceTask.taskVersion,
          reason,
        })
      }
      document = this.updateLine(document, line.id, (current) => ({
        ...current,
        version: current.version + 1,
        status: "CANCELLED",
        lastError: null,
      }))
      this.event(document, line.id, "TRANSFER_CANCELLED", params.actor, reason)
      return document
    })
  }

  createCorrection(params: {
    rentalItemId: string
    expectedRentalItemVersion: number
    sourceWarehouse: WarehouseTransferWarehouseSnapshot
    destinationWarehouse: WarehouseTransferWarehouseSnapshot
    reason: string
    actor: WarehouseTransferActorSnapshot
    returnConflict?: WarehouseTransferLine["returnConflict"]
  }) {
    return runTransferCommand(async () => {
      this.validateWarehouses(
        params.sourceWarehouse,
        params.destinationWarehouse
      )
      const reason = params.reason.trim()
      if (!reason) throw new Error("Укажите причину коррекции")
      const cabin = readRentalItems().find(
        (item) =>
          item.id === params.rentalItemId &&
          isStoredAtWarehouse(item.warehouseId, params.sourceWarehouse)
      )
      if (!cabin || cabin.version !== params.expectedRentalItemVersion) {
        throw new Error("Бытовка изменилась. Обновите данные конфликта")
      }
      if (this.store.findActiveByRentalItemId(cabin.id)) {
        throw new Error(
          "Для бытовки уже есть активное межскладское перемещение"
        )
      }
      if (hasActiveShipmentForRentalItemLowLevel(cabin.id)) {
        throw new Error(
          "Бытовка участвует в активной отгрузке. Сначала завершите или отмените отгрузку"
        )
      }
      if (this.hasActiveCorrection(cabin.id)) {
        throw new Error("Для бытовки уже создана активная коррекция учёта")
      }
      const timestamp = now()
      const correction: WarehouseAccountingCorrection = {
        id: crypto.randomUUID(),
        version: 0,
        rentalItemId: cabin.id,
        cabinNumber: cabin.number,
        expectedRentalItemVersion: cabin.version,
        sourceWarehouse: warehouseSnapshot(params.sourceWarehouse),
        destinationWarehouse: warehouseSnapshot(params.destinationWarehouse),
        reason,
        status: "PENDING_APPROVALS",
        approvals: [],
        rejectedReason: null,
        createdAt: timestamp,
        updatedAt: timestamp,
        returnConflict: params.returnConflict
          ? structuredClone(params.returnConflict)
          : null,
      }
      const saved = this.store.saveCorrection(correction, null)
      this.correctionEvent(
        saved,
        "ACCOUNTING_CORRECTION_CREATED",
        params.actor,
        reason
      )
      return saved
    })
  }

  approveCorrection(params: {
    correctionId: string
    expectedVersion: number
    approvingWarehouseId: string
    actor: WarehouseTransferActorSnapshot
  }) {
    return runTransferCommand(async () => {
      const current = this.store.findCorrection(params.correctionId)
      if (!current || current.version !== params.expectedVersion) {
        throw new Error("Коррекция изменена. Обновите данные")
      }
      const permitted = [
        current.sourceWarehouse.id,
        current.destinationWarehouse.id,
      ]
      if (!permitted.includes(params.approvingWarehouseId)) {
        throw new Error(
          "Подтвердить коррекцию может только один из двух складов"
        )
      }
      if (current.status === "APPLYING") {
        return this.applyCorrectionIntent(current, params.actor)
      }
      if (current.status !== "PENDING_APPROVALS") return current
      if (
        current.approvals.some(
          (approval) => approval.warehouseId === params.approvingWarehouseId
        )
      ) {
        return current
      }
      const approvals = [
        ...current.approvals,
        {
          warehouseId: params.approvingWarehouseId,
          approvedAt: now(),
          actor: structuredClone(params.actor),
        },
      ]
      const bothApproved = permitted.every((warehouseId) =>
        approvals.some((approval) => approval.warehouseId === warehouseId)
      )
      const next = this.store.saveCorrection(
        {
          ...current,
          version: current.version + 1,
          approvals,
          status: bothApproved ? "APPLYING" : "PENDING_APPROVALS",
          updatedAt: now(),
        },
        current.version
      )
      this.correctionEvent(
        next,
        "ACCOUNTING_CORRECTION_APPROVED",
        params.actor,
        null
      )
      return bothApproved
        ? this.applyCorrectionIntent(next, params.actor)
        : next
    })
  }

  rejectCorrection(params: {
    correctionId: string
    expectedVersion: number
    rejectingWarehouseId: string
    actor: WarehouseTransferActorSnapshot
    reason: string
  }) {
    return runTransferCommand(async () => {
      const current = this.store.findCorrection(params.correctionId)
      if (!current || current.version !== params.expectedVersion) {
        throw new Error("Коррекция изменена. Обновите данные")
      }
      if (current.status !== "PENDING_APPROVALS") return current
      if (
        ![current.sourceWarehouse.id, current.destinationWarehouse.id].includes(
          params.rejectingWarehouseId
        )
      ) {
        throw new Error("Отклонить коррекцию может только один из двух складов")
      }
      const reason = params.reason.trim()
      if (!reason) throw new Error("Укажите причину отклонения")
      const next = this.store.saveCorrection(
        {
          ...current,
          version: current.version + 1,
          status: "REJECTED",
          rejectedReason: reason,
          updatedAt: now(),
        },
        current.version
      )
      this.correctionEvent(
        next,
        "ACCOUNTING_CORRECTION_REJECTED",
        params.actor,
        reason
      )
      return next
    })
  }

  private async applyDepartureIntent(
    document: WarehouseTransferDocument,
    line: WarehouseTransferLine,
    accessToken: string,
    actor: WarehouseTransferActorSnapshot
  ) {
    const attempt = line.applicationAttempt
    if (attempt?.kind !== "DEPARTURE") {
      throw new Error("Намерение подтвердить убытие не найдено")
    }
    const updatedCabin = await runRentalItemMutation(async () => {
      const items = readRentalItems()
      const cabin = items.find((item) => item.id === line.rentalItemId)
      if (!cabin) throw new Error("Бытовка перемещения не найдена")
      const alreadyApplied =
        isStoredAtWarehouse(cabin.warehouseId, document.sourceWarehouse) &&
        cabin.status === "IN_TRANSFER" &&
        cabin.version === attempt.expectedRentalItemVersion + 1
      if (alreadyApplied) return cabin
      if (
        !isStoredAtWarehouse(cabin.warehouseId, document.sourceWarehouse) ||
        cabin.version !== attempt.expectedRentalItemVersion ||
        cabin.status !== line.sourceStatus
      ) {
        throw new Error(
          `Бытовка ${line.cabinNumber} изменилась после создания перемещения`
        )
      }
      const next: RentalItemDto = {
        ...cabin,
        version: cabin.version + 1,
        status: "IN_TRANSFER",
        locationNodeId: null,
      }
      writeRentalItems(items.map((item) => (item.id === next.id ? next : item)))
      return next
    })
    const finalized = this.updateLine(document, line.id, (current) => ({
      ...current,
      version: current.version + 1,
      status: "IN_TRANSIT",
      rentalItemVersion: updatedCabin.version,
      departedAt: current.departedAt ?? now(),
      conflictReason: null,
      lastError: null,
      applicationAttempt: null,
    }))
    this.event(finalized, line.id, "DEPARTURE_CONFIRMED", actor, null)
    return this.dispatchDestinationTask(finalized, line.id, accessToken, actor)
  }

  private async applyArrivalIntent(
    document: WarehouseTransferDocument,
    line: WarehouseTransferLine,
    actor: WarehouseTransferActorSnapshot
  ) {
    const attempt = line.applicationAttempt
    if (attempt?.kind !== "ARRIVAL") {
      throw new Error("Намерение подтвердить приёмку не найдено")
    }
    const receivedCabin = await runRentalItemMutation(async () => {
      const items = readRentalItems()
      const cabin = items.find((item) => item.id === line.rentalItemId)
      if (!cabin) throw new Error("Бытовка перемещения не найдена")
      const alreadyApplied =
        isStoredAtWarehouse(cabin.warehouseId, document.destinationWarehouse) &&
        cabin.status === line.sourceStatus &&
        cabin.version === attempt.expectedRentalItemVersion + 1
      if (alreadyApplied) return cabin
      if (
        !isStoredAtWarehouse(cabin.warehouseId, document.sourceWarehouse) ||
        cabin.status !== "IN_TRANSFER" ||
        cabin.version !== attempt.expectedRentalItemVersion
      ) {
        throw new Error(
          `Версия бытовки ${line.cabinNumber} изменилась после отправления`
        )
      }
      const next: RentalItemDto = {
        ...cabin,
        version: cabin.version + 1,
        warehouseId: browserStoreWarehouseId(document.destinationWarehouse.id),
        status: line.sourceStatus,
        locationNodeId: null,
        contentsItems: normalizeContents(line.contentsSnapshot),
      }
      writeRentalItems(items.map((item) => (item.id === next.id ? next : item)))
      return next
    })
    const finalized = this.updateLine(document, line.id, (current) => ({
      ...current,
      version: current.version + 1,
      status: "RECEIVED",
      rentalItemVersion: receivedCabin.version,
      photos: structuredClone(attempt.photos),
      receivedAt: current.receivedAt ?? now(),
      conflictReason: null,
      lastError: null,
      applicationAttempt: null,
    }))
    this.event(finalized, line.id, "ARRIVAL_CONFIRMED", actor, null)
    return finalized
  }

  private async applyCorrectionIntent(
    intent: WarehouseAccountingCorrection,
    actor: WarehouseTransferActorSnapshot
  ) {
    if (intent.status !== "APPLYING") return intent
    await runRentalItemMutation(async () => {
      const items = readRentalItems()
      const cabin = items.find((item) => item.id === intent.rentalItemId)
      if (!cabin) throw new Error("Бытовка для коррекции не найдена")
      const alreadyApplied =
        cabin.version === intent.expectedRentalItemVersion + 1 &&
        isStoredAtWarehouse(cabin.warehouseId, intent.destinationWarehouse)
      if (alreadyApplied) return
      if (
        cabin.version !== intent.expectedRentalItemVersion ||
        !isStoredAtWarehouse(cabin.warehouseId, intent.sourceWarehouse)
      ) {
        throw new Error("Бытовка изменилась до применения коррекции")
      }
      writeRentalItems(
        items.map((item) =>
          item.id === cabin.id
            ? {
                ...cabin,
                version: cabin.version + 1,
                warehouseId: browserStoreWarehouseId(
                  intent.destinationWarehouse.id
                ),
                locationNodeId: null,
              }
            : item
        )
      )
    })
    const applied = this.store.saveCorrection(
      {
        ...intent,
        version: intent.version + 1,
        status: "APPLIED",
        updatedAt: now(),
      },
      intent.version
    )
    this.correctionEvent(applied, "ACCOUNTING_CORRECTION_APPLIED", actor, null)
    return applied
  }

  private async dispatchSourceTask(
    document: WarehouseTransferDocument,
    lineId: string,
    accessToken: string,
    actor: WarehouseTransferActorSnapshot
  ) {
    const line = this.requireLine(document, lineId)
    if (line.sourceTask) return document
    if (line.status !== "PREPARING") {
      throw new Error("Задание отправления уже не может быть создано")
    }
    try {
      const sourceTask = await this.taskClient.dispatch(
        accessToken,
        taskCommand(document, line, "SOURCE")
      )
      const saved = this.updateLine(document, line.id, (current) => ({
        ...current,
        version: current.version + 1,
        status: "READY_TO_DEPART",
        sourceTask,
        lastError: null,
      }))
      this.event(saved, line.id, "SOURCE_TASK_REGISTERED", actor, null)
      return saved
    } catch (error) {
      return this.updateLine(document, line.id, (current) => ({
        ...current,
        version: current.version + 1,
        lastError: errorMessage(error),
      }))
    }
  }

  private async dispatchDestinationTask(
    document: WarehouseTransferDocument,
    lineId: string,
    accessToken: string,
    actor: WarehouseTransferActorSnapshot
  ) {
    const line = this.requireLine(document, lineId)
    if (line.destinationTask) return document
    if (line.status !== "IN_TRANSIT" && line.status !== "CONFLICT") {
      throw new Error("Задание приёмки создаётся только после убытия")
    }
    try {
      const destinationTask = await this.taskClient.dispatch(
        accessToken,
        taskCommand(document, line, "DESTINATION")
      )
      const saved = this.updateLine(document, line.id, (current) => ({
        ...current,
        version: current.version + 1,
        destinationTask,
        lastError: null,
      }))
      this.event(saved, line.id, "DESTINATION_TASK_REGISTERED", actor, null)
      return saved
    } catch (error) {
      return this.updateLine(document, line.id, (current) => ({
        ...current,
        version: current.version + 1,
        lastError: errorMessage(error),
      }))
    }
  }

  private updateLine(
    document: WarehouseTransferDocument,
    lineId: string,
    updater: (line: WarehouseTransferLine) => WarehouseTransferLine
  ) {
    const current = this.store.findById(document.id)
    if (!current || current.version !== document.version) {
      throw new Error("Перемещение изменено в другой вкладке. Обновите данные")
    }
    const timestamp = now()
    return this.store.save(
      {
        ...current,
        version: current.version + 1,
        updatedAt: timestamp,
        lines: current.lines.map((line) =>
          line.id === lineId ? updater(structuredClone(line)) : line
        ),
      },
      current.version
    )
  }

  private requireDocument(documentId: string, expectedVersion: number) {
    const document = this.store.findById(documentId)
    if (!document) throw new Error("Перемещение не найдено")
    if (document.version !== expectedVersion) {
      throw new Error("Перемещение изменено. Обновите данные")
    }
    return document
  }

  private requireLine(document: WarehouseTransferDocument, lineId: string) {
    const line = document.lines.find((item) => item.id === lineId)
    if (!line) throw new Error("Строка перемещения не найдена")
    return line
  }

  private assertLineVersion(line: WarehouseTransferLine, version: number) {
    if (line.version !== version) {
      throw new Error("Строка перемещения изменена. Обновите данные")
    }
  }

  private hasActiveCorrection(rentalItemId: string) {
    return this.store
      .listCorrections()
      .some(
        (correction) =>
          correction.rentalItemId === rentalItemId &&
          (correction.status === "PENDING_APPROVALS" ||
            correction.status === "APPLYING")
      )
  }

  private validateWarehouses(
    source: WarehouseTransferWarehouseSnapshot,
    destination: WarehouseTransferWarehouseSnapshot
  ) {
    if (source.id === destination.id) {
      throw new Error(
        "Склад назначения должен отличаться от склада отправления"
      )
    }
  }

  private event(
    document: WarehouseTransferDocument,
    lineId: string | null,
    type: WarehouseTransferEventType,
    actor: WarehouseTransferActorSnapshot,
    comment: string | null
  ) {
    const line = lineId
      ? document.lines.find((item) => item.id === lineId)
      : null
    const event: WarehouseTransferEvent = {
      id: crypto.randomUUID(),
      documentId: document.id,
      lineId,
      rentalItemId: line?.rentalItemId ?? null,
      type,
      occurredAt: now(),
      actor: structuredClone(actor),
      comment,
    }
    this.store.appendEvent(event)
  }

  private correctionEvent(
    correction: WarehouseAccountingCorrection,
    type: WarehouseTransferEventType,
    actor: WarehouseTransferActorSnapshot,
    comment: string | null
  ) {
    this.store.appendEvent({
      id: crypto.randomUUID(),
      documentId: correction.id,
      lineId: null,
      rentalItemId: correction.rentalItemId,
      type,
      occurredAt: now(),
      actor: structuredClone(actor),
      comment,
    })
  }
}
