import { HttpTaskBoardSettingsClient } from "@/features/settings/task-board/api/http-task-board-settings-client"

export const taskBoardSettingsClient = new HttpTaskBoardSettingsClient()

export const taskBoardSettingsKeys = {
  queueDefinitions: ["task-board-settings", "queue-definitions"] as const,
  classes: ["task-board-settings", "classes"] as const,
  queues: (warehouseId: string) =>
    ["task-board-settings", warehouseId, "queues"] as const,
  workers: (warehouseId: string) =>
    ["task-board-settings", warehouseId, "workers"] as const,
  groups: (warehouseId: string) =>
    ["task-board-settings", warehouseId, "groups"] as const,
}
