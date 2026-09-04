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

function endpoint() {
  return `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1/settings/repair-complexity-colors`
}

export function getRepairComplexityColors(accessToken: string) {
  return bearerRequest<RepairComplexityColorsDto>(
    accessToken,
    endpoint()
  )
}

export function saveRepairComplexityColors(
  accessToken: string,
  command: RepairComplexityColorsCommand
) {
  return bearerRequest<RepairComplexityColorsDto>(
    accessToken,
    endpoint(),
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
