import { useQuery } from "@tanstack/react-query"
import { useAuth } from "@/features/auth/use-auth"
import {
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"
import { bearerRequest, invalidApiResponseError } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type CabinStatusColors = {
  version: number
  colors: Record<RentalItemStatus, string>
  updatedAt: string
}

export const CABIN_STATUS_COLOR_STATUSES = Object.keys(
  RENTAL_ITEM_STATUS_LABEL
) as RentalItemStatus[]
export const CABIN_STATUS_COLOR_PATTERN = /^#[0-9a-fA-F]{6}$/
export const cabinStatusColorsQueryKey = (userId: string | undefined) =>
  ["cabin-status-colors", userId] as const
export const cabinStatusColorVariable = (status: RentalItemStatus) =>
  `--cabin-status-${status.toLowerCase().replaceAll("_", "-")}`

function endpoint() {
  return `${getGatewayRuntimeConfig().assetApiBaseUrl}/v1/cabin-settings/status-colors`
}

function parsePalette(value: unknown): CabinStatusColors {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    throw invalidApiResponseError("Некорректная палитра статусов.")
  const palette = value as Partial<CabinStatusColors>
  if (
    !Number.isSafeInteger(palette.version) ||
    palette.version! < 0 ||
    typeof palette.updatedAt !== "string" ||
    !Number.isFinite(Date.parse(palette.updatedAt)) ||
    typeof palette.colors !== "object" ||
    palette.colors === null ||
    Array.isArray(palette.colors) ||
    Object.keys(palette.colors).length !== CABIN_STATUS_COLOR_STATUSES.length ||
    CABIN_STATUS_COLOR_STATUSES.some(
      (status) =>
        typeof palette.colors![status] !== "string" ||
        !CABIN_STATUS_COLOR_PATTERN.test(palette.colors![status])
    )
  )
    throw invalidApiResponseError(
      "Сервис вернул неполную или некорректную палитру статусов."
    )
  return palette as CabinStatusColors
}

export async function getCabinStatusColors(accessToken: string) {
  return parsePalette(await bearerRequest<unknown>(accessToken, endpoint()))
}

export async function saveCabinStatusColors(
  accessToken: string,
  expectedVersion: number,
  colors: CabinStatusColors["colors"]
) {
  return parsePalette(
    await bearerRequest<unknown>(accessToken, endpoint(), {
      method: "PUT",
      body: JSON.stringify({ expectedVersion, colors }),
    })
  )
}

/** One per-user global query, shared by the editor and every active panel surface. */
export function useCabinStatusColors() {
  const { accessToken, currentUser } = useAuth()
  return useQuery({
    queryKey: cabinStatusColorsQueryKey(currentUser?.id),
    queryFn: () => getCabinStatusColors(accessToken!),
    enabled: Boolean(accessToken && currentUser?.principalType === "USER"),
    staleTime: 10_000,
    refetchInterval: 30_000,
    retry: false,
  })
}
