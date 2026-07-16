import type { ContentsTransferTaskClient } from "@/features/rental-items/contents-transfer/ports/contents-transfer-task-client"
import { taskBoardMockClient } from "@/features/task-board/mock"

export class BrowserContentsTransferTaskClient implements ContentsTransferTaskClient {
  async dispatch(
    _accessToken: string,
    command: Parameters<ContentsTransferTaskClient["dispatch"]>[1]
  ) {
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
        "Нет активной очереди перемещения для разнорабочих. Проверьте настройки доски задач"
      )
    }
    const items = command.items
      .map((item) => `${item.name} — ${item.quantity} шт.`)
      .join(", ")
    const title = `${command.source.number} → ${command.target.number}`
    const taskText = `Переместить из ${command.source.number} в ${command.target.number}: ${items}`
    const task = await taskBoardMockClient.registerTask({
      externalTaskId: command.externalTaskId,
      warehouseId: command.serviceWarehouseId,
      queueCode: queue.code,
      title,
      description: taskText,
      cabinNumber: command.target.number,
      route: [{ queueCode: queue.code, title: taskText }],
    })
    return {
      boardTaskId: task.id,
      queueId: queue.id,
      queueCode: queue.code,
    }
  }
}
