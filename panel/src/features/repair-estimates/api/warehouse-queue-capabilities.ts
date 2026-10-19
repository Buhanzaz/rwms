import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type MovementQueueCapabilityDto = {
  queueDefinitionId: string
  workQueueId: string
}

export type WarehouseQueueCapabilitiesDto = {
  warehouseId: string
  movementQueueDefinitions: MovementQueueCapabilityDto[]
}

export function warehouseQueueCapabilitiesQueryKey(warehouseId: string) {
  return ["task-board", warehouseId, "queue-capabilities"] as const
}

export function getWarehouseQueueCapabilities(
  accessToken: string,
  warehouseId: string
) {
  return bearerRequest<WarehouseQueueCapabilitiesDto>(
    accessToken,
    `${getGatewayRuntimeConfig().taskBoardApiBaseUrl}/warehouses/${encodeURIComponent(warehouseId)}/queue-capabilities`
  )
}
