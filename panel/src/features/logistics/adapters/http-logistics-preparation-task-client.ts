import type {
  LogisticsPreparationTaskClient,
  LogisticsPreparationTaskCancelCommand,
  LogisticsPreparationTaskCommand,
  LogisticsPreparationTaskResult,
} from "@/features/logistics/ports/logistics-preparation-task-client"
import type { WorkQueueDto } from "@/features/settings/task-board/model/task-board-settings"
import { ApiError, bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const TASK_BOARD_API = getGatewayRuntimeConfig().taskBoardApiBaseUrl

type BoardEntry = {
  taskId: string
  taskVersion: number
  externalTaskId: string | null
  queueId: string
  queueCode: string
}

type BoardSnapshot = {
  warehouseId: string
  columns: Array<{ entries: BoardEntry[] }>
}

function warehouseEndpoint(warehouseId: string) {
  return `${TASK_BOARD_API}/warehouses/${encodeURIComponent(warehouseId)}`
}

function resolveMovementQueue(queues: WorkQueueDto[]) {
  return queues
    .filter(
      (queue) =>
        queue.active &&
        !queue.hidden &&
        queue.type === "MOVEMENT" &&
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

function findExactTask(
  snapshot: BoardSnapshot,
  externalTaskId: string
): LogisticsPreparationTaskResult | null {
  const entry = snapshot.columns
    .flatMap((column) => column.entries)
    .find((candidate) => candidate.externalTaskId === externalTaskId)
  return entry
    ? {
        boardTaskId: entry.taskId,
        boardTaskVersion: entry.taskVersion,
        queueId: entry.queueId,
        queueCode: entry.queueCode,
      }
    : null
}

export class HttpLogisticsPreparationTaskClient implements LogisticsPreparationTaskClient {
  async dispatch(
    accessToken: string,
    command: LogisticsPreparationTaskCommand
  ) {
    const warehouseUrl = warehouseEndpoint(command.serviceWarehouseId)
    const queues = await bearerRequest<WorkQueueDto[]>(
      accessToken,
      `${warehouseUrl}/work-queues`
    )
    const queue = resolveMovementQueue(queues)
    if (!queue) {
      throw new Error(
        "Не настроена активная очередь перемещения для разнорабочих. Откройте настройки доски задач"
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
            title: `Подготовка к отгрузке: ${command.cabinNumber}`,
            unitNumber: command.cabinNumber,
            description: "Сопутствующие работы перед отгрузкой в аренду",
            plannedDurationMinutes: null,
            deadlineAt: null,
            route: [
              {
                queueId: queue.id,
                queueCode: queue.code,
                taskText: command.taskText,
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

    const registered = findExactTask(snapshot, command.externalTaskId)
    if (!registered) {
      throw new Error(
        "Task-board не подтвердил регистрацию задачи по externalTaskId"
      )
    }
    return registered
  }

  async cancel(
    accessToken: string,
    command: LogisticsPreparationTaskCancelCommand
  ) {
    const response = await bearerRequest<{ taskVersion: number }>(
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
    return { boardTaskVersion: response.taskVersion }
  }
}
