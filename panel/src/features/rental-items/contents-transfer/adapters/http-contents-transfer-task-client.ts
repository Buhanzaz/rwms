import type { WorkQueueDto } from "@/features/settings/task-board/model/task-board-settings"
import type { ContentsTransferTaskRef } from "@/features/rental-items/contents-transfer/model/contents-transfer"
import type {
  ContentsTransferTaskClient,
  ContentsTransferTaskCommand,
} from "@/features/rental-items/contents-transfer/ports/contents-transfer-task-client"
import { ApiError, bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const TASK_BOARD_API = getGatewayRuntimeConfig().taskBoardApiBaseUrl

type BoardSnapshot = {
  columns: Array<{
    entries: Array<{
      taskId: string
      externalTaskId: string | null
      queueId: string
      queueCode: string
    }>
  }>
}

function warehouseEndpoint(warehouseId: string) {
  return `${TASK_BOARD_API}/warehouses/${encodeURIComponent(warehouseId)}`
}

function movementQueue(queues: WorkQueueDto[]) {
  return queues
    .filter(
      (queue) => queue.type === "MOVEMENT" && queue.active && !queue.hidden
    )
    .sort(
      (left, right) =>
        left.sortOrder - right.sortOrder || left.code.localeCompare(right.code)
    )[0]
}

function findTask(
  snapshot: BoardSnapshot,
  externalTaskId: string
): ContentsTransferTaskRef | null {
  const entry = snapshot.columns
    .flatMap((column) => column.entries)
    .find((candidate) => candidate.externalTaskId === externalTaskId)
  return entry
    ? {
        boardTaskId: entry.taskId,
        queueId: entry.queueId,
        queueCode: entry.queueCode,
      }
    : null
}

export class HttpContentsTransferTaskClient implements ContentsTransferTaskClient {
  async dispatch(accessToken: string, command: ContentsTransferTaskCommand) {
    const warehouseUrl = warehouseEndpoint(command.serviceWarehouseId)
    const queues = await bearerRequest<WorkQueueDto[]>(
      accessToken,
      `${warehouseUrl}/work-queues`
    )
    const queue = movementQueue(queues)
    if (!queue) throw new Error("Нет активной очереди перемещения")

    const taskText = command.items
      .map((item) => `${item.name} — ${item.quantity} шт.`)
      .join(", ")
    let snapshot: BoardSnapshot
    try {
      snapshot = await bearerRequest<BoardSnapshot>(
        accessToken,
        `${warehouseUrl}/task-board/tasks`,
        {
          method: "POST",
          body: JSON.stringify({
            externalTaskId: command.externalTaskId,
            title: `${command.source.number} → ${command.target.number}`,
            unitNumber: command.target.number,
            description: "Перемещение наполнения между бытовками",
            plannedDurationMinutes: null,
            deadlineAt: null,
            route: [
              {
                queueId: queue.id,
                queueCode: queue.code,
                taskText: `Переместить из ${command.source.number} в ${command.target.number}: ${taskText}`,
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
}
