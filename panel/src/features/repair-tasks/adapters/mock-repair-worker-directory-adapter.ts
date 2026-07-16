import { DEV_AUTH_BYPASS_TOKEN } from "@/features/auth/auth-config"
import { taskBoardSettingsClient } from "@/features/settings/task-board/api/task-board-settings-api"
import type { TaskBoardSettingsClient } from "@/features/settings/task-board/api/task-board-settings-client"
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

/**
 * Compatibility projection used by repair/logistics forms while the browser
 * task-board mock owns the actual workforce directory. It intentionally does
 * not carry a second hard-coded list of workers.
 */
export class MockRepairWorkerDirectoryAdapter implements RepairWorkerDirectoryClient {
  private readonly settingsClient: TaskBoardSettingsClient

  constructor(
    settingsClient: TaskBoardSettingsClient = taskBoardSettingsClient
  ) {
    this.settingsClient = settingsClient
  }

  async listGroups(
    query: Parameters<RepairWorkerDirectoryClient["listGroups"]>[0],
    accessToken = DEV_AUTH_BYPASS_TOKEN
  ) {
    const settingsWarehouseId = query.warehouseId
    const [groups, workers, queues, classes] = await Promise.all([
      this.settingsClient.listGroups(accessToken, settingsWarehouseId),
      this.settingsClient.listWorkers(accessToken, settingsWarehouseId),
      this.settingsClient.listQueues(accessToken, settingsWarehouseId),
      this.settingsClient.listClasses(accessToken),
    ])
    const workersById = new Map(workers.map((worker) => [worker.id, worker]))
    const classCodeById = new Map(
      classes.map((workerClass) => [workerClass.id, workerClass.code])
    )
    const matchingQueues = queues.filter((queue) => {
      if (!queue.active || queue.hidden) return false
      if (query.queueCode) return queue.code === query.queueCode
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
    const driverDirectory = query.purpose === "DRIVER_DIRECTORY"

    return groups
      .filter((group) => {
        if (!group.active) return false
        if (driverDirectory) {
          return classCodeById.get(group.workerClass.id) === "DRIVER"
        }
        if (eligibleClassIds.has(group.workerClass.id)) return true
        return false
      })
      .map<RepairWorkerDirectoryGroupDto>((group) => ({
        id: group.id,
        warehouseId: query.warehouseId,
        name: group.name,
        active: group.active,
        queueCodes: queues
          .filter(
            (queue) =>
              queue.active &&
              !queue.hidden &&
              queue.bindings.some(
                (binding) => binding.workerClass.id === group.workerClass.id
              )
          )
          .map((queue) => queue.code),
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
