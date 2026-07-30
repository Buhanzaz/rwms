import { HttpRepairWorkerDirectoryAdapter } from "@/features/repair-tasks/adapters/http-repair-worker-directory-adapter"
import { currentMaintenanceAccessToken } from "@/features/repair-estimates/api/maintenance-auth"
import type { RepairWorkerGroupsQuery } from "@/features/repair-tasks/model/repair-worker-directory"
import type { RepairWorkerDirectoryClient } from "@/features/repair-tasks/ports/repair-worker-directory-client"

export const REPAIR_WORKER_GROUPS_QUERY_KEY = ["repair-worker-groups"] as const

const repairWorkerDirectoryClient: RepairWorkerDirectoryClient =
  new HttpRepairWorkerDirectoryAdapter()

export function repairWorkerGroupsQueryKey(query: RepairWorkerGroupsQuery) {
  return [
    ...REPAIR_WORKER_GROUPS_QUERY_KEY,
    query.warehouseId,
    query.queueId,
    query.routeQueueKind,
    query.purpose ?? "TASK_ASSIGNMENT",
  ] as const
}

export async function listRepairWorkerGroups(
  query: RepairWorkerGroupsQuery,
  accessToken?: string
) {
  return repairWorkerDirectoryClient.listGroups(
    query,
    accessToken ?? (await currentMaintenanceAccessToken())
  )
}
