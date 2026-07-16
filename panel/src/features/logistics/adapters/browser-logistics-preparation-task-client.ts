import type {
  LogisticsPreparationTaskClient,
  LogisticsPreparationTaskCancelCommand,
  LogisticsPreparationTaskCommand,
} from "@/features/logistics/ports/logistics-preparation-task-client"
import { taskBoardMockClient } from "@/features/task-board/mock"

async function movementQueue(warehouseId: string) {
  const snapshot = await taskBoardMockClient.getSnapshot(warehouseId)
  const queue = snapshot.queues
    .filter(
      (candidate) =>
        candidate.active &&
        !candidate.hidden &&
        candidate.type === "MOVEMENT" &&
        candidate.bindings.some(
          (binding) => binding.workerClass.code === "GENERAL_WORKER"
        )
    )
    .sort((left, right) => left.sortOrder - right.sortOrder)[0]
  if (!queue) {
    throw new Error(
      "Не настроена активная очередь перемещения для разнорабочих. Откройте настройки доски задач"
    )
  }
  return queue
}

export class BrowserLogisticsPreparationTaskClient implements LogisticsPreparationTaskClient {
  async dispatch(
    _accessToken: string,
    command: LogisticsPreparationTaskCommand
  ) {
    const queue = await movementQueue(command.serviceWarehouseId)
    const task = await taskBoardMockClient.registerTask({
      externalTaskId: command.externalTaskId,
      warehouseId: command.serviceWarehouseId,
      queueCode: queue.code,
      title: `Подготовка к отгрузке: ${command.cabinNumber}`,
      description: command.taskText,
      cabinNumber: command.cabinNumber,
      route: [{ queueCode: queue.code, title: command.taskText }],
    })
    return {
      boardTaskId: task.id,
      boardTaskVersion: task.version,
      queueId: queue.id,
      queueCode: queue.code,
    }
  }

  async cancel(
    _accessToken: string,
    command: LogisticsPreparationTaskCancelCommand
  ) {
    const task = await taskBoardMockClient.findByExternalTaskId(
      command.serviceWarehouseId,
      command.externalTaskId
    )
    if (!task) return { boardTaskVersion: command.expectedTaskVersion }
    const cancelled = await taskBoardMockClient.cancelTask({
      taskId: task.id,
      expectedVersion: command.expectedTaskVersion,
    })
    return { boardTaskVersion: cancelled.version }
  }
}
