import type {
  CreateManualMovement,
  DriverBoard,
  DriverBoardCard,
  MoveDriverBoardTask,
} from "@/features/logistics/driver-board/driver-board-model"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const DRIVER_BOARD_API = `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/driver-board`
const DRIVER_TASKS_API = `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/driver-tasks`
const TASK_BOARD_API = getGatewayRuntimeConfig().taskBoardApiBaseUrl

export const DRIVER_BOARD_QUERY_KEY = ["logistics", "driver-board"] as const

export function driverBoardQueryKey(warehouseId: string) {
  return [...DRIVER_BOARD_QUERY_KEY, warehouseId] as const
}

export function getDriverBoard(accessToken: string, warehouseId: string) {
  const query = new URLSearchParams({ warehouseId })
  return bearerRequest<DriverBoard>(
    accessToken,
    `${DRIVER_BOARD_API}?${query.toString()}`
  )
}

export function moveDriverBoardTask(params: {
  accessToken: string
  externalTaskId: string
  command: MoveDriverBoardTask
}) {
  return bearerRequest<DriverBoardCard>(
    params.accessToken,
    `${DRIVER_BOARD_API}/tasks/${encodeURIComponent(params.externalTaskId)}/move`,
    {
      method: "POST",
      body: JSON.stringify(params.command),
    }
  )
}

export function promoteCapitalRepair(params: {
  accessToken: string
  repairId: string
  warehouseId: string
  idempotencyKey: string
}) {
  return bearerRequest<unknown>(
    params.accessToken,
    `${DRIVER_BOARD_API}/capital-repairs/${encodeURIComponent(params.repairId)}/promote`,
    {
      method: "POST",
      headers: {
        "Idempotency-Key": params.idempotencyKey,
      },
      body: JSON.stringify({ warehouseId: params.warehouseId }),
    }
  )
}

export function scheduleCapitalRepair(params: {
  accessToken: string
  repairId: string
  warehouseId: string
  targetDate: string
  targetIndex: number
  idempotencyKey: string
}) {
  return bearerRequest<DriverBoardCard>(
    params.accessToken,
    `${DRIVER_BOARD_API}/capital-repairs/${encodeURIComponent(params.repairId)}/schedule`,
    {
      method: "POST",
      headers: {
        "Idempotency-Key": params.idempotencyKey,
      },
      body: JSON.stringify({
        warehouseId: params.warehouseId,
        targetDate: params.targetDate,
        targetIndex: params.targetIndex,
      }),
    }
  )
}

export function returnCapitalRepair(params: {
  accessToken: string
  externalTaskId: string
  warehouseId: string
  expectedTaskVersion: number
}) {
  return bearerRequest<void>(
    params.accessToken,
    `${DRIVER_BOARD_API}/tasks/${encodeURIComponent(params.externalTaskId)}/return-to-capital-repairs`,
    {
      method: "POST",
      body: JSON.stringify({
        warehouseId: params.warehouseId,
        expectedTaskVersion: params.expectedTaskVersion,
      }),
    }
  )
}

export function createManualMovement(params: {
  accessToken: string
  command: CreateManualMovement
}) {
  const { idempotencyKey, ...command } = params.command

  return bearerRequest<unknown>(params.accessToken, DRIVER_TASKS_API, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({
      warehouseId: command.warehouseId,
      cabinId: command.cabinId,
      repairId: null,
      sourceType: "MANUAL",
      sourceId: idempotencyKey,
      kind: "GENERAL_MOVEMENT",
      planningMode: "AUTO",
      scheduledDate: null,
      priority: command.priority,
      activateNow: true,
      comment: command.comment,
    }),
  })
}

export function pinDriverBoardTask(params: {
  accessToken: string
  warehouseId: string
  taskId: string
  expectedTaskVersion: number
  pinned: boolean
}) {
  return bearerRequest<unknown>(
    params.accessToken,
    `${TASK_BOARD_API}/warehouses/${encodeURIComponent(params.warehouseId)}/task-board/tasks/${encodeURIComponent(params.taskId)}/pin`,
    {
      method: "POST",
      body: JSON.stringify({
        expectedTaskVersion: params.expectedTaskVersion,
        pinned: params.pinned,
      }),
    }
  )
}
