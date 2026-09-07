import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type ReturnEstimateInspection = {
  state: "CONFIRMED" | "PENDING" | "NOT_REQUIRED"
  inventoryId: string | null
  findingId: string | null
  cabinNumber: string | null
}

export function getReturnEstimateInspection(
  accessToken: string,
  warehouseId: string,
  estimateId: string
) {
  const baseUrl = getGatewayRuntimeConfig().inventoryApiBaseUrl
  return bearerRequest<ReturnEstimateInspection>(
    accessToken,
    `${baseUrl}/v1/return-estimates/${encodeURIComponent(estimateId)}/inspection?warehouseId=${encodeURIComponent(warehouseId)}`
  )
}

export async function awaitReturnEstimateInspection(
  accessToken: string,
  warehouseId: string,
  estimateId: string,
  options: {
    delaysMs?: readonly number[]
    wait?: (delayMs: number) => Promise<void>
  } = {}
) {
  const delaysMs = options.delaysMs ?? [
    250, 500, 1_000, 1_500, 2_000, 2_500, 3_000,
  ]
  const wait =
    options.wait ??
    ((delayMs: number) =>
      new Promise<void>((resolve) => window.setTimeout(resolve, delayMs)))

  let inspection = await getReturnEstimateInspection(
    accessToken,
    warehouseId,
    estimateId
  )
  for (const delayMs of delaysMs) {
    if (inspection.state !== "PENDING") return inspection
    await wait(delayMs)
    inspection = await getReturnEstimateInspection(
      accessToken,
      warehouseId,
      estimateId
    )
  }
  return inspection
}
