import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type TaskRequirementState =
  "AVAILABLE" | "MISSING" | "RESTORED" | "COMPLETED"
export type TaskRequirement = {
  itemId: string
  kind: "WORK" | "MATERIAL"
  name: string
  state: TaskRequirementState
  linkedItemIds: string[]
}
export type TaskRequirements = {
  taskId: string
  taskVersion: number
  hasProblem: boolean
  incomplete: boolean
  completedWorkPercent: number
  items: TaskRequirement[]
}
const base = getGatewayRuntimeConfig().taskBoardApiBaseUrl
export const taskRegistrationQueryKey = (
  warehouseId: string,
  externalTaskId: string
) => ["task-board", warehouseId, "registration", externalTaskId] as const
export function getTaskRegistration(
  accessToken: string,
  warehouseId: string,
  externalTaskId: string
) {
  return bearerRequest<{ taskId: string }>(
    accessToken,
    `${base}/warehouses/${encodeURIComponent(warehouseId)}/task-board/tasks/by-external-id/${encodeURIComponent(externalTaskId)}`
  )
}
export const taskRequirementsQueryKey = (warehouseId: string, taskId: string) =>
  ["task-board", warehouseId, "requirements", taskId] as const
export function getTaskRequirements(
  accessToken: string,
  warehouseId: string,
  taskId: string
) {
  return bearerRequest<TaskRequirements>(
    accessToken,
    `${base}/warehouses/${encodeURIComponent(warehouseId)}/task-board/tasks/${encodeURIComponent(taskId)}/requirements`
  )
}
