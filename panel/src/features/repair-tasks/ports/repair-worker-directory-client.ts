import type {
  RepairWorkerDirectoryGroupDto,
  RepairWorkerGroupsQuery,
} from "@/features/repair-tasks/model/repair-worker-directory"

export interface RepairWorkerDirectoryClient {
  listGroups(
    query: RepairWorkerGroupsQuery,
    accessToken: string
  ): Promise<RepairWorkerDirectoryGroupDto[]>
}
