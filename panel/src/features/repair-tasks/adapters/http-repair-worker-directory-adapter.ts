import { taskBoardSettingsClient } from "@/features/settings/task-board/api/task-board-settings-api"
import type { RepairEstimateCatalogRouteQueueKind } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import type { QueueType } from "@/features/settings/task-board/model/task-board-settings"
import type { RepairWorkerDirectoryGroupDto } from "@/features/repair-tasks/model/repair-worker-directory"
import type { RepairWorkerDirectoryClient } from "@/features/repair-tasks/ports/repair-worker-directory-client"

const ROUTE_QUEUE_TYPE: Record<
  NonNullable<
    Parameters<RepairWorkerDirectoryClient["listGroups"]>[0]["routeQueueKind"]
  >,
  QueueType
> = {
  MOVEMENT: "MOVEMENT",
  REPAIR: "REPAIR",
  HOLDING: "HOLDING",
}

function isRepairRouteQueueKind(
  value: QueueType
): value is RepairEstimateCatalogRouteQueueKind {
  return value === "MOVEMENT" || value === "REPAIR" || value === "HOLDING"
}

export class HttpRepairWorkerDirectoryAdapter implements RepairWorkerDirectoryClient {
  async listGroups(
    query: Parameters<RepairWorkerDirectoryClient["listGroups"]>[0],
    accessToken: string
  ) {
    const [groups, workers, queues] = await Promise.all([
      taskBoardSettingsClient.listGroups(accessToken, query.warehouseId),
      taskBoardSettingsClient.listWorkers(accessToken, query.warehouseId),
      taskBoardSettingsClient.listQueues(accessToken, query.warehouseId),
    ])
    const workersById = new Map(workers.map((worker) => [worker.id, worker]))
    const matchingQueues = queues.filter((queue) => {
      if (!queue.active || queue.hidden) return false
      if (query.queueId) return queue.id === query.queueId
      if (query.routeQueueKind) {
        return queue.type === ROUTE_QUEUE_TYPE[query.routeQueueKind]
      }
      return true
    })
    const eligibleClassIds = new Set(
      matchingQueues.flatMap((queue) =>
        queue.bindings.map((binding) => binding.workerClass.id)
      )
    )

    return groups
      .filter((group) => {
        if (!group.active) return false
        return eligibleClassIds.has(group.workerClass.id)
      })
      .map<RepairWorkerDirectoryGroupDto>((group) => ({
        id: group.id,
        warehouseId: query.warehouseId,
        name: group.name,
        active: group.active,
        queueIds: queues
          .filter(
            (queue) =>
              queue.active &&
              !queue.hidden &&
              queue.bindings.some(
                (binding) => binding.workerClass.id === group.workerClass.id
              )
          )
          .map((queue) => queue.id),
        routeQueueKinds: Array.from(
          new Set(
            queues
              .filter(
                (queue) =>
                  queue.active &&
                  !queue.hidden &&
                  queue.bindings.some(
                    (binding) => binding.workerClass.id === group.workerClass.id
                  )
              )
              .map((queue) => queue.type)
              .filter(isRepairRouteQueueKind)
          )
        ),
        members: group.members
          .filter((member) => member.active)
          .map((member) => workersById.get(member.workerId))
          .filter((worker): worker is NonNullable<typeof worker> =>
            Boolean(worker?.active)
          )
          .map((worker) => ({ id: worker.id, name: worker.displayName })),
      }))
      .filter((group) => group.members.length > 0)
      .sort((left, right) => left.name.localeCompare(right.name, "ru"))
  }
}
