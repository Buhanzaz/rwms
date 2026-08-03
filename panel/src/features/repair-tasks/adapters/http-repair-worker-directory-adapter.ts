import { taskBoardSettingsClient } from "@/features/settings/task-board/api/task-board-settings-api"
import type { RepairEstimateCatalogRouteQueueKind } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import type {
  QueueType,
  WorkerDto,
  WorkQueueDto,
} from "@/features/settings/task-board/model/task-board-settings"
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

function isPrimaryBinding(binding: WorkQueueDto["bindings"][number]) {
  return binding.primary || binding.participationPolicy === "PRIMARY"
}

function isQualifiedForAny(
  worker: WorkerDto,
  warehouseId: string,
  workerClassIds: ReadonlySet<string>
) {
  return (
    worker.warehouseId === warehouseId &&
    worker.active &&
    worker.qualifications.some(
      (qualification) =>
        qualification.active && workerClassIds.has(qualification.workerClass.id)
    )
  )
}

export class HttpRepairWorkerDirectoryAdapter implements RepairWorkerDirectoryClient {
  async listGroups(
    query: Parameters<RepairWorkerDirectoryClient["listGroups"]>[0],
    accessToken: string
  ) {
    const groupsPromise =
      query.purpose === "DRIVER_DIRECTORY"
        ? Promise.resolve([])
        : taskBoardSettingsClient.listGroups(accessToken, query.warehouseId)
    const [groups, workers, queues] = await Promise.all([
      groupsPromise,
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

    // A logistics driver is an independently registered warehouse worker.
    // Unlike repair staff, it is not required to belong to a worker group.
    // The warehouse's LOGISTICS_DRIVER queue and its primary class are the
    // authoritative directory, which also keeps drivers separated by city.
    if (query.purpose === "DRIVER_DIRECTORY") {
      return matchingQueues
        .filter((queue) => queue.purpose === "LOGISTICS_DRIVER")
        .map<RepairWorkerDirectoryGroupDto>((queue) => {
          const primaryClassIds = new Set(
            queue.bindings
              .filter(isPrimaryBinding)
              .map((binding) => binding.workerClass.id)
          )
          return {
            id: queue.id,
            warehouseId: query.warehouseId,
            name: queue.name,
            active: queue.active,
            queueIds: [queue.id],
            routeQueueKinds: isRepairRouteQueueKind(queue.type)
              ? [queue.type]
              : [],
            members: workers
              .filter((worker) =>
                isQualifiedForAny(worker, query.warehouseId, primaryClassIds)
              )
              .map((worker) => ({ id: worker.id, name: worker.displayName })),
          }
        })
        .filter((group) => group.members.length > 0)
        .sort((left, right) => left.name.localeCompare(right.name, "ru"))
    }

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
