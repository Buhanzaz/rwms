import type { WorkQueueDto } from "@/features/settings/task-board/model/task-board-settings"
import type {
  WarehouseTransferTaskCommand,
  WarehouseTransferTaskRef,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import type { WarehouseTransferTaskClient } from "@/features/logistics/warehouse-transfers/ports/warehouse-transfer-task-client"
import { ApiError, bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const TASK_BOARD_API = getGatewayRuntimeConfig().taskBoardApiBaseUrl

type BoardSnapshot = {
  columns: Array<{
    entries: Array<{
      taskId: string
      taskVersion: number
      externalTaskId: string | null
      queueId: string
      queueCode: string
    }>
  }>
}

function warehouseEndpoint(warehouseId: string) {
  return `${TASK_BOARD_API}/warehouses/${encodeURIComponent(warehouseId)}`
}

function findMovementQueue(queues: WorkQueueDto[]) {
  return queues
    .filter(
      (queue) =>
        queue.type === "MOVEMENT" &&
        queue.active &&
        !queue.hidden &&
        queue.bindings.length > 0 &&
        queue.bindings.some(
          (binding) =>
            binding.workerClass.active &&
            binding.workerClass.code === "GENERAL_WORKER"
        )
    )
    .sort(
      (left, right) =>
        left.sortOrder - right.sortOrder || left.code.localeCompare(right.code)
    )[0]
}

function findTask(
  snapshot: BoardSnapshot,
  externalTaskId: string
): WarehouseTransferTaskRef | null {
  const entry = snapshot.columns
    .flatMap((column) => column.entries)
    .find((candidate) => candidate.externalTaskId === externalTaskId)
  return entry
    ? {
        boardTaskId: entry.taskId,
        taskVersion: entry.taskVersion,
        queueId: entry.queueId,
        queueCode: entry.queueCode,
      }
    : null
}

function taskText(command: WarehouseTransferTaskCommand) {
  const contents = command.cabin.contentsSnapshot
    .map((item) => `${item.name} — ${item.quantity} шт.`)
    .join(", ")
  const route = `${command.sourceWarehouse.code} → ${command.destinationWarehouse.code}`
  if (command.direction === "SOURCE") {
    return `Подготовить ${command.cabin.cabinNumber} к межскладскому перемещению ${route}.${contents ? ` Наполнение: ${contents}.` : ""}`
  }
  return `Принять ${command.cabin.cabinNumber} после межскладского перемещения ${route}, сверить наполнение и сделать фотографии.${contents ? ` Ожидается: ${contents}.` : ""}`
}

export class HttpWarehouseTransferTaskClient implements WarehouseTransferTaskClient {
  async dispatch(accessToken: string, command: WarehouseTransferTaskCommand) {
    const warehouseUrl = warehouseEndpoint(command.serviceWarehouseId)
    const queues = await bearerRequest<WorkQueueDto[]>(
      accessToken,
      `${warehouseUrl}/work-queues`
    )
    const queue = findMovementQueue(queues)
    if (!queue) {
      throw new Error(
        "Нет активной видимой очереди типа «Перемещение», связанной с классом GENERAL_WORKER. Проверьте /settings/task-board"
      )
    }

    let snapshot: BoardSnapshot
    try {
      snapshot = await bearerRequest<BoardSnapshot>(
        accessToken,
        `${warehouseUrl}/task-board/tasks`,
        {
          method: "POST",
          body: JSON.stringify({
            externalTaskId: command.externalTaskId,
            title:
              command.direction === "SOURCE"
                ? `Отправить ${command.cabin.cabinNumber} в ${command.destinationWarehouse.code}`
                : `Принять ${command.cabin.cabinNumber} из ${command.sourceWarehouse.code}`,
            unitNumber: command.cabin.cabinNumber,
            description: `Межскладское перемещение. Водитель: ${command.driverName}`,
            plannedDurationMinutes: null,
            deadlineAt: null,
            route: [
              {
                queueId: queue.id,
                queueCode: queue.code,
                taskText: taskText(command),
                plannedDurationMinutes: null,
              },
            ],
          }),
        }
      )
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 409) throw error
      snapshot = await bearerRequest<BoardSnapshot>(
        accessToken,
        `${warehouseUrl}/task-board?includeShadow=true`
      )
    }

    const task = findTask(snapshot, command.externalTaskId)
    if (!task) {
      throw new Error(
        "Task-board не подтвердил регистрацию задачи по externalTaskId"
      )
    }
    return task
  }

  async cancel(
    accessToken: string,
    command: {
      serviceWarehouseId: string
      externalTaskId: string
      expectedTaskVersion: number
      reason: string
    }
  ) {
    await bearerRequest(
      accessToken,
      `${warehouseEndpoint(command.serviceWarehouseId)}/task-board/tasks/by-external-id/${encodeURIComponent(command.externalTaskId)}/cancel`,
      {
        method: "POST",
        body: JSON.stringify({
          expectedTaskVersion: command.expectedTaskVersion,
          reason: command.reason,
        }),
      }
    )
  }

  async recover(
    accessToken: string,
    command: { serviceWarehouseId: string; externalTaskId: string }
  ) {
    const snapshot = await bearerRequest<BoardSnapshot>(
      accessToken,
      `${warehouseEndpoint(command.serviceWarehouseId)}/task-board?includeShadow=true`
    )
    return findTask(snapshot, command.externalTaskId)
  }
}
