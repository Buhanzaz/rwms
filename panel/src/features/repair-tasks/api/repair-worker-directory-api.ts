import { MockRepairWorkerDirectoryAdapter } from "@/features/repair-tasks/adapters/mock-repair-worker-directory-adapter"
import { DEV_AUTH_BYPASS_TOKEN } from "@/features/auth/auth-config"
import type { RepairWorkerGroupsQuery } from "@/features/repair-tasks/model/repair-worker-directory"
import type { RepairWorkerDirectoryClient } from "@/features/repair-tasks/ports/repair-worker-directory-client"

export const REPAIR_WORKER_GROUPS_QUERY_KEY = ["repair-worker-groups"] as const

const repairWorkerDirectoryClient: RepairWorkerDirectoryClient =
  new MockRepairWorkerDirectoryAdapter()

export function repairWorkerGroupsQueryKey(query: RepairWorkerGroupsQuery) {
  return [
    ...REPAIR_WORKER_GROUPS_QUERY_KEY,
    query.warehouseId,
    query.queueCode,
    query.routeQueueKind,
    query.purpose ?? "TASK_ASSIGNMENT",
  ] as const
}

export function listRepairWorkerGroups(
  query: RepairWorkerGroupsQuery,
  accessToken = DEV_AUTH_BYPASS_TOKEN
) {
  if (repairWorkerDirectoryClient instanceof MockRepairWorkerDirectoryAdapter) {
    return repairWorkerDirectoryClient.listGroups(query, accessToken)
  }
  return repairWorkerDirectoryClient.listGroups(query)
}
