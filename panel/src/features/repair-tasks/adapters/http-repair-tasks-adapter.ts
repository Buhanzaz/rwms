import {
  acceptMaintenanceRepair,
  createDirectMaintenanceRepair,
  createMaintenanceRework,
  getMaintenanceRepair,
  listMaintenanceAcceptanceProjection,
  listMaintenanceRepairs,
  listMaintenanceWriteOffProjection,
  queueMaintenanceRepair,
  replaceMaintenanceRepairPlan,
  writeOffMaintenanceRepair,
} from "@/features/maintenance/api/maintenance-api"
import {
  canonicalClientUuid,
  withIdempotencyKey,
} from "@/features/maintenance/maintenance-runtime"
import {
  toCanonicalMedia,
  toViewRepair,
} from "@/features/maintenance/adapters/maintenance-view-mappers"
import type {
  RepairTaskSubtaskDto,
  RepairTaskWriteCommand,
} from "@/features/repair-tasks/model/repair-task"
import type { RepairTasksClient } from "@/features/repair-tasks/ports/repair-tasks-client"

function dispatchDate(value: string | null) {
  if (!value) throw new Error("Укажите дату прибытия.")
  return value
}

async function planStage(subtask: RepairTaskSubtaskDto, order: number) {
  if (!subtask.queueId || !subtask.queueCode || !subtask.routeQueueKind) {
    throw new Error(
      "Для этапа ремонта не подтверждена каноническая очередь. Обновите каталог."
    )
  }
  return {
    id: await canonicalClientUuid(subtask.id),
    kind: subtask.kind,
    order,
    routing: {
      queueId: subtask.queueId,
      queueCode: subtask.queueCode,
      queueKind: subtask.routeQueueKind,
    },
    taskDeadline: null,
  }
}

async function directBody(command: RepairTaskWriteCommand) {
  return {
    warehouseId: command.warehouseId,
    rentalItemId: command.rentalItemId,
    dispatchDate: dispatchDate(command.dispatchDate),
    sourceParty: command.reason.trim() || null,
    plan: await Promise.all(
      command.subtasks.map((subtask, index) => planStage(subtask, index))
    ),
    mediaReferences: toCanonicalMedia(command.media),
  }
}

async function save(command: RepairTaskWriteCommand) {
  if (command.taskId) {
    if (command.expectedVersion === null) {
      throw new Error("Для изменения ремонта нужна его актуальная версия.")
    }
    return replaceMaintenanceRepairPlan(command.warehouseId, command.taskId, {
      expectedVersion: command.expectedVersion,
      stages: await Promise.all(
        command.subtasks.map((subtask, index) => planStage(subtask, index))
      ),
    })
  }

  if (command.kind === "REWORK") {
    if (
      !command.sourceRepairTaskId ||
      command.sourceRepairTaskVersion === null
    ) {
      throw new Error("Для переделки не подтверждён исходный ремонт.")
    }
    const body = {
      expectedVersion: command.sourceRepairTaskVersion,
      reason: command.reason.trim(),
      plan: await Promise.all(
        command.subtasks.map((subtask, index) => planStage(subtask, index))
      ),
      mediaReferences: toCanonicalMedia(command.media),
    }
    const intent = {
      sourceRepairId: command.sourceRepairTaskId,
      ...body,
    }
    return withIdempotencyKey(
      "maintenance.repair.rework.create",
      intent,
      (key) =>
        createMaintenanceRework(
          command.warehouseId,
          command.sourceRepairTaskId!,
          key,
          body
        )
    )
  }

  const body = await directBody(command)
  return withIdempotencyKey("maintenance.repair.direct.create", body, (key) =>
    createDirectMaintenanceRepair(key, body)
  )
}

async function bySourceEstimate(sourceEstimateId: string, warehouseId: string) {
  const page = await listMaintenanceRepairs(warehouseId)
  return (
    page.items.find((repair) => repair.estimateId === sourceEstimateId) ?? null
  )
}

function taskBoardOwnedEffect(): never {
  throw new Error(
    "Операторское действие должно выполняться через HTTP API доски заданий."
  )
}

export const httpRepairTasksAdapter: RepairTasksClient = {
  async list(warehouseId) {
    const page = await listMaintenanceRepairs(warehouseId)
    return page.items.map(toViewRepair)
  },

  async listPendingAcceptance(warehouseId) {
    const projections = await listMaintenanceAcceptanceProjection(
      warehouseId,
      "PENDING"
    )
    return Promise.all(
      projections.map((projection) =>
        getMaintenanceRepair(warehouseId, projection.repairId).then(
          toViewRepair
        )
      )
    )
  },

  async listWriteOffs(warehouseId) {
    const projections = await listMaintenanceWriteOffProjection(warehouseId)
    return Promise.all(
      projections.map((projection) =>
        getMaintenanceRepair(warehouseId, projection.repairId).then(
          toViewRepair
        )
      )
    )
  },

  async getById(taskId, warehouseId) {
    return toViewRepair(await getMaintenanceRepair(warehouseId, taskId))
  },

  async getBySourceEstimateId(sourceEstimateId, warehouseId) {
    const repair = await bySourceEstimate(sourceEstimateId, warehouseId)
    return repair ? toViewRepair(repair) : null
  },

  async getByInventoryFinding() {
    throw new Error("Инвентаризация не входит в Stage 6 maintenance cutover.")
  },

  async saveDraft(command) {
    return toViewRepair(await save(command))
  },

  async queue(command) {
    const repair = await save(command)
    const intent = {
      repairId: repair.id,
      expectedVersion: repair.version,
    }
    const result = await withIdempotencyKey(
      "maintenance.repair.queue",
      intent,
      (key) =>
        queueMaintenanceRepair(
          repair.warehouseId,
          repair.id,
          key,
          repair.version
        )
    )
    return toViewRepair(result.repair)
  },

  async updateSubtasks(command) {
    const repair = await getMaintenanceRepair(
      command.warehouseId,
      command.taskId
    )
    if (repair.version !== command.expectedVersion) {
      throw new Error(
        "Ремонт был изменён. Обновите данные и повторите действие."
      )
    }
    const byId = new Map(repair.plan.stages.map((stage) => [stage.id, stage]))
    const stages = command.orderedSubtaskIds.map((id, index) => {
      const stage = byId.get(id)
      if (!stage) throw new Error("Этап ремонта не найден.")
      return {
        id: stage.id,
        kind: stage.kind,
        order: index,
        routing: stage.routing,
        taskDeadline: stage.taskDeadline,
      }
    })
    return toViewRepair(
      await replaceMaintenanceRepairPlan(command.warehouseId, command.taskId, {
        expectedVersion: command.expectedVersion,
        stages,
      })
    )
  },

  async moveEntry() {
    return taskBoardOwnedEffect()
  },
  async takeEntry() {
    return taskBoardOwnedEffect()
  },
  async pauseEntry() {
    return taskBoardOwnedEffect()
  },
  async resumeEntry() {
    return taskBoardOwnedEffect()
  },
  async completeEntry() {
    return taskBoardOwnedEffect()
  },

  async accept(command) {
    const body = {
      expectedVersion: command.expectedVersion,
      comment: command.comment.trim() || null,
    }
    const intent = {
      repairId: command.taskId,
      ...body,
    }
    const result = await withIdempotencyKey(
      "maintenance.repair.accept",
      intent,
      (key) =>
        acceptMaintenanceRepair(command.warehouseId, command.taskId, key, body)
    )
    return toViewRepair(result.repair)
  },

  async writeOff(command) {
    const body = {
      expectedVersion: command.expectedVersion,
      reason: command.reason.trim(),
      comment: null,
    }
    const intent = {
      repairId: command.taskId,
      ...body,
    }
    const result = await withIdempotencyKey(
      "maintenance.repair.write-off",
      intent,
      (key) =>
        writeOffMaintenanceRepair(
          command.warehouseId,
          command.taskId,
          key,
          body
        )
    )
    return toViewRepair(result.repair)
  },

  async earlyWriteOff(command) {
    const repair = await save(command)
    const body = {
      expectedVersion: repair.version,
      reason: command.writeOffReason.trim(),
      comment: command.comment.trim() || null,
    }
    const intent = {
      repairId: repair.id,
      ...body,
    }
    const result = await withIdempotencyKey(
      "maintenance.repair.write-off",
      intent,
      (key) =>
        writeOffMaintenanceRepair(repair.warehouseId, repair.id, key, body)
    )
    return toViewRepair(result.repair)
  },

  async upsertFromEstimate(command) {
    const repair = await bySourceEstimate(
      command.sourceEstimateId,
      command.warehouseId
    )
    if (!repair) {
      throw new Error("Связанный ремонт ещё не создан maintenance-service.")
    }
    return toViewRepair(repair)
  },

  async upsertByInventoryFinding() {
    throw new Error("Инвентаризация не входит в Stage 6 maintenance cutover.")
  },

  async syncFromEstimate(command) {
    const repair = await bySourceEstimate(
      command.sourceEstimateId,
      command.warehouseId
    )
    if (!repair) {
      throw new Error("Связанный ремонт ещё не создан maintenance-service.")
    }
    return toViewRepair(repair)
  },
}
