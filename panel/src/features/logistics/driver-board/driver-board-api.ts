import type {
  DriverBoard,
  DriverBoardCard,
  MoveDriverBoardTask,
} from "@/features/logistics/driver-board/driver-board-model"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const DRIVER_BOARD_API = `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/driver-board`

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
