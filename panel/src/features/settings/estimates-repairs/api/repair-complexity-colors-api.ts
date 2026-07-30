import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type RepairComplexityColorsDto = {
  version: number
  lightColor: string
  mediumColor: string
  complexColor: string
  capitalColor: string
  updatedAt: string
}

export type RepairComplexityColorsCommand = Pick<
  RepairComplexityColorsDto,
  "version" | "lightColor" | "mediumColor" | "complexColor" | "capitalColor"
>

function endpoint(warehouseId: string) {
  const value = new URL(
    `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1/settings/repair-complexity-colors`
  )
  value.searchParams.set("warehouseId", warehouseId)
  return value
}

export function getRepairComplexityColors(
  accessToken: string,
  warehouseId: string
) {
  return bearerRequest<RepairComplexityColorsDto>(
    accessToken,
    endpoint(warehouseId)
  )
}

export function saveRepairComplexityColors(
  accessToken: string,
  warehouseId: string,
  command: RepairComplexityColorsCommand
) {
  return bearerRequest<RepairComplexityColorsDto>(
    accessToken,
    endpoint(warehouseId),
    {
      method: "PUT",
      body: JSON.stringify({
        expectedVersion: command.version,
        lightColor: command.lightColor,
        mediumColor: command.mediumColor,
        complexColor: command.complexColor,
        capitalColor: command.capitalColor,
      }),
    }
  )
}
