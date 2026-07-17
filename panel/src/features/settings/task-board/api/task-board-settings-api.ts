import { HttpTaskBoardSettingsClient } from "@/features/settings/task-board/api/http-task-board-settings-client"
import { DEV_MAINTENANCE_FIXTURES_ENABLED } from "@/features/maintenance/maintenance-runtime"
import { taskBoardMockClient } from "@/features/task-board/mock"

export const taskBoardSettingsClient = DEV_MAINTENANCE_FIXTURES_ENABLED
  ? taskBoardMockClient
  : new HttpTaskBoardSettingsClient()

export const taskBoardSettingsKeys = {
  classes: ["task-board-settings", "classes"] as const,
  queues: (warehouseId: string) =>
    ["task-board-settings", warehouseId, "queues"] as const,
  workers: (warehouseId: string) =>
    ["task-board-settings", warehouseId, "workers"] as const,
  groups: (warehouseId: string) =>
    ["task-board-settings", warehouseId, "groups"] as const,
}
