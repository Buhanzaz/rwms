import type { WarehouseTransferTaskCommand } from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import type { WarehouseTransferTaskClient } from "@/features/logistics/warehouse-transfers/ports/warehouse-transfer-task-client"
import { taskBoardMockClient } from "@/features/task-board/mock"

function taskText(command: WarehouseTransferTaskCommand) {
  const route = `${command.sourceWarehouse.code} → ${command.destinationWarehouse.code}`
  const contents = command.cabin.contentsSnapshot
    .map((item) => `${item.name} — ${item.quantity} шт.`)
    .join(", ")
  return command.direction === "SOURCE"
    ? `Подготовить ${command.cabin.cabinNumber} к межскладскому перемещению ${route}.${contents ? ` Наполнение: ${contents}.` : ""}`
    : `Принять ${command.cabin.cabinNumber} после межскладского перемещения ${route}, сверить наполнение и сделать фотографии.${contents ? ` Ожидается: ${contents}.` : ""}`
}

export class BrowserWarehouseTransferTaskClient implements WarehouseTransferTaskClient {
  async dispatch(_accessToken: string, command: WarehouseTransferTaskCommand) {
    const snapshot = await taskBoardMockClient.getSnapshot(
      command.serviceWarehouseId
    )
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
        "Нет активной очереди перемещения для разнорабочих. Проверьте /settings/task-board"
      )
    }
    const title =
      command.direction === "SOURCE"
        ? `Отправить ${command.cabin.cabinNumber} в ${command.destinationWarehouse.code}`
        : `Принять ${command.cabin.cabinNumber} из ${command.sourceWarehouse.code}`
    const task = await taskBoardMockClient.registerTask({
      externalTaskId: command.externalTaskId,
      warehouseId: command.serviceWarehouseId,
      queueCode: queue.code,
      title,
      description: taskText(command),
      cabinNumber: command.cabin.cabinNumber,
      route: [{ queueCode: queue.code, title: taskText(command) }],
    })
    return {
      boardTaskId: task.id,
      taskVersion: task.version,
      queueId: queue.id,
      queueCode: queue.code,
    }
  }

  async recover(
    _accessToken: string,
    command: { serviceWarehouseId: string; externalTaskId: string }
  ) {
    const task = await taskBoardMockClient.findByExternalTaskId(
      command.serviceWarehouseId,
      command.externalTaskId
    )
    if (!task) return null
    const snapshot = await taskBoardMockClient.getSnapshot(
      command.serviceWarehouseId
    )
    const queue = snapshot.queues.find(
      (candidate) => candidate.id === task.queueId
    )
    if (!queue) return null
    return {
      boardTaskId: task.id,
      taskVersion: task.version,
      queueId: queue.id,
      queueCode: queue.code,
    }
  }

  async cancel(
    _accessToken: string,
    command: {
      serviceWarehouseId: string
      externalTaskId: string
      expectedTaskVersion: number
      reason: string
    }
  ) {
    const task = await taskBoardMockClient.findByExternalTaskId(
      command.serviceWarehouseId,
      command.externalTaskId
    )
    if (!task) return
    await taskBoardMockClient.cancelTask({
      taskId: task.id,
      expectedVersion: command.expectedTaskVersion,
    })
  }
}
