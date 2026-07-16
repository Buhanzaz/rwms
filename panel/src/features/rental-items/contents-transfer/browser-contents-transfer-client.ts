import {
  getRentalItem,
  moveRentalItemContentsToRentalItem,
  RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES,
  RentalItemVersionConflictError,
} from "@/features/rental-items/api/rental-items-api"
import { HttpContentsTransferTaskClient } from "@/features/rental-items/contents-transfer/adapters/http-contents-transfer-task-client"
import { BrowserContentsTransferTaskClient } from "@/features/rental-items/contents-transfer/adapters/browser-contents-transfer-task-client"
import { DEV_AUTH_BYPASS_ENABLED } from "@/features/auth/auth-config"
import { LocalStorageContentsTransferStore } from "@/features/rental-items/contents-transfer/adapters/local-storage-contents-transfer-store"
import type {
  ContentsTransferAttempt,
  ContentsTransferRecord,
  CreateContentsTransferCommand,
  StoredContentsTransferCommand,
} from "@/features/rental-items/contents-transfer/model/contents-transfer"
import type { ContentsTransferStore } from "@/features/rental-items/contents-transfer/ports/contents-transfer-store"
import type { ContentsTransferTaskClient } from "@/features/rental-items/contents-transfer/ports/contents-transfer-task-client"
import type {
  RentalItemContentsItemDto,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"

type StoredCommandWithSnapshots = StoredContentsTransferCommand & {
  source: StoredContentsTransferCommand["source"] & {
    contentsBefore: RentalItemContentsItemDto[]
  }
  target: StoredContentsTransferCommand["target"] & {
    contentsBefore: RentalItemContentsItemDto[]
  }
}

function normalizeItems(items: CreateContentsTransferCommand["items"]) {
  return items
    .map((item) => ({ name: item.name.trim(), quantity: item.quantity }))
    .filter(
      (item) =>
        item.name && Number.isInteger(item.quantity) && item.quantity > 0
    )
}

function key(name: string) {
  return name.trim().toLocaleLowerCase("ru-RU")
}

function applyItems(
  source: RentalItemContentsItemDto[],
  target: RentalItemContentsItemDto[],
  moved: RentalItemContentsItemDto[]
) {
  const quantity = new Map(moved.map((item) => [key(item.name), item.quantity]))
  const sourceAfter = source
    .map((item) => ({
      ...item,
      quantity: item.quantity - (quantity.get(key(item.name)) ?? 0),
    }))
    .filter((item) => item.quantity > 0)
  const targetAfter = target.map((item) => ({ ...item }))
  moved.forEach((item) => {
    const existing = targetAfter.find(
      (candidate) => key(candidate.name) === key(item.name)
    )
    if (existing) existing.quantity += item.quantity
    else targetAfter.push({ ...item })
  })
  return { sourceAfter, targetAfter }
}

function sameContents(
  left: RentalItemContentsItemDto[],
  right: RentalItemContentsItemDto[]
) {
  const normalized = (items: RentalItemContentsItemDto[]) =>
    items
      .map((item) => ({ name: key(item.name), quantity: item.quantity }))
      .sort((a, b) => a.name.localeCompare(b.name))
  return JSON.stringify(normalized(left)) === JSON.stringify(normalized(right))
}

function matchesCommand(
  attempt: ContentsTransferAttempt,
  command: CreateContentsTransferCommand,
  items: RentalItemContentsItemDto[]
) {
  return (
    attempt.command.source.rentalItemId === command.source.rentalItemId &&
    attempt.command.target.rentalItemId === command.target.rentalItemId &&
    JSON.stringify(attempt.command.items) === JSON.stringify(items)
  )
}

export class BrowserContentsTransferClient {
  private readonly taskClient: ContentsTransferTaskClient
  private readonly store: ContentsTransferStore

  constructor(
    taskClient: ContentsTransferTaskClient = DEV_AUTH_BYPASS_ENABLED
      ? new BrowserContentsTransferTaskClient()
      : new HttpContentsTransferTaskClient(),
    store: ContentsTransferStore = new LocalStorageContentsTransferStore()
  ) {
    this.taskClient = taskClient
    this.store = store
  }

  async transfer(command: CreateContentsTransferCommand) {
    if (command.source.rentalItemId === command.target.rentalItemId) {
      throw new Error("Нельзя переместить наполнение в ту же бытовку")
    }
    const items = normalizeItems(command.items)
    if (items.length === 0)
      throw new Error("Выберите наполнение для перемещения")

    const explicit = this.store.findByExternalTaskId(command.externalTaskId)
    if (explicit && !matchesCommand(explicit, command, items)) {
      throw new Error("externalTaskId уже связан с другим перемещением")
    }
    const matching = this.store.findMatching({
      ...command,
      items,
    })
    let attempt = explicit ?? matching
    if (attempt?.phase === "APPLIED" && attempt.record) {
      return this.appliedResult(attempt.record)
    }

    if (!attempt) {
      const { source, target } = await this.loadAndValidate(command, items)
      const now = new Date().toISOString()
      const storedCommand: StoredCommandWithSnapshots = {
        externalTaskId: command.externalTaskId,
        warehouseId: command.warehouseId,
        serviceWarehouseId: command.serviceWarehouseId,
        actor: command.actor,
        source: { ...command.source, contentsBefore: source.contentsItems },
        target: { ...command.target, contentsBefore: target.contentsItems },
        items,
      }
      attempt = await this.store.saveAttempt({
        externalTaskId: command.externalTaskId,
        phase: "PENDING_DISPATCH",
        command: storedCommand,
        task: null,
        record: null,
        error: null,
        createdAt: now,
        updatedAt: now,
      })
    }

    const effective = attempt.command as StoredCommandWithSnapshots
    let task
    try {
      task = await this.taskClient.dispatch(command.accessToken, {
        externalTaskId: attempt.externalTaskId,
        serviceWarehouseId: effective.serviceWarehouseId,
        source: effective.source,
        target: effective.target,
        items: effective.items,
      })
      attempt = await this.store.saveAttempt({
        ...attempt,
        phase: "TASK_REGISTERED",
        task,
        error: null,
        updatedAt: new Date().toISOString(),
      })
    } catch (error) {
      await this.store.saveAttempt({
        ...attempt,
        phase: "PENDING_DISPATCH",
        error: error instanceof Error ? error.message : "Ошибка task-board",
        updatedAt: new Date().toISOString(),
      })
      throw error
    }

    try {
      const recovered = await this.recoverAlreadyApplied(effective)
      const moved =
        recovered ??
        (await moveRentalItemContentsToRentalItem({
          sourceRentalItemId: effective.source.rentalItemId,
          targetRentalItemId: effective.target.rentalItemId,
          expectedSourceVersion: effective.source.expectedVersion,
          expectedTargetVersion: effective.target.expectedVersion,
          payload: effective.items,
        }))
      if (!moved) throw new Error("Не удалось применить перемещение")
      const record = this.record(attempt.externalTaskId, effective, task, moved)
      await this.store.saveAttempt({
        ...attempt,
        phase: "APPLIED",
        task,
        record,
        error: null,
        updatedAt: record.occurredAt,
      })
      return { record, ...moved }
    } catch (error) {
      await this.store.saveAttempt({
        ...attempt,
        phase:
          error instanceof RentalItemVersionConflictError
            ? "CONFLICT"
            : "TASK_REGISTERED",
        task,
        error: error instanceof Error ? error.message : "Ошибка применения",
        updatedAt: new Date().toISOString(),
      })
      throw error
    }
  }

  private async loadAndValidate(
    command: CreateContentsTransferCommand,
    items: RentalItemContentsItemDto[]
  ) {
    const [source, target] = await Promise.all([
      getRentalItem(command.source.rentalItemId),
      getRentalItem(command.target.rentalItemId),
    ])
    if (!source || !target) throw new Error("Выбранная бытовка не найдена")
    if (
      source.warehouseId !== command.warehouseId ||
      target.warehouseId !== command.warehouseId ||
      source.warehouseId !== target.warehouseId
    ) {
      throw new Error("Перемещение доступно только внутри одного склада")
    }
    if (
      source.version !== command.source.expectedVersion ||
      target.version !== command.target.expectedVersion
    ) {
      throw new RentalItemVersionConflictError()
    }
    if (
      !RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES.includes(source.status) ||
      !RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES.includes(target.status)
    ) {
      throw new Error(
        "Перемещение доступно только для активных бытовок на складе"
      )
    }
    items.forEach((item) => {
      const available = source.contentsItems.find(
        (candidate) => key(candidate.name) === key(item.name)
      )?.quantity
      if (available === undefined || item.quantity > available) {
        throw new Error(`Недоступное количество позиции «${item.name}»`)
      }
    })
    return { source, target }
  }

  private async recoverAlreadyApplied(command: StoredCommandWithSnapshots) {
    const [source, target] = await Promise.all([
      getRentalItem(command.source.rentalItemId),
      getRentalItem(command.target.rentalItemId),
    ])
    if (!source || !target) return null
    const expected = applyItems(
      command.source.contentsBefore,
      command.target.contentsBefore,
      command.items
    )
    return source.version === command.source.expectedVersion + 1 &&
      target.version === command.target.expectedVersion + 1 &&
      sameContents(source.contentsItems, expected.sourceAfter) &&
      sameContents(target.contentsItems, expected.targetAfter)
      ? { sourceItem: source, targetItem: target }
      : null
  }

  private record(
    externalTaskId: string,
    command: StoredCommandWithSnapshots,
    task: ContentsTransferRecord["task"],
    moved: { sourceItem: RentalItemDto; targetItem: RentalItemDto }
  ): ContentsTransferRecord {
    return {
      id: externalTaskId,
      externalTaskId,
      status: "APPLIED",
      warehouseId: command.warehouseId,
      serviceWarehouseId: command.serviceWarehouseId,
      occurredAt: new Date().toISOString(),
      actor: command.actor,
      source: {
        rentalItemId: command.source.rentalItemId,
        number: command.source.number,
        versionBefore: command.source.expectedVersion,
        versionAfter: moved.sourceItem.version,
      },
      target: {
        rentalItemId: command.target.rentalItemId,
        number: command.target.number,
        versionBefore: command.target.expectedVersion,
        versionAfter: moved.targetItem.version,
      },
      items: command.items,
      task,
    }
  }

  private async appliedResult(record: ContentsTransferRecord) {
    const [sourceItem, targetItem] = await Promise.all([
      getRentalItem(record.source.rentalItemId),
      getRentalItem(record.target.rentalItemId),
    ])
    if (!sourceItem || !targetItem) {
      throw new Error("Бытовка из выполненного перемещения не найдена")
    }
    return { record, sourceItem, targetItem }
  }
}
