import { apiErrorFromResponse, bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type CabinPhotoPresentation = {
  id: string
  version: number
  cabinId: string
  cabinNumber: string
  photoCount: number
  createdAt: string
  publicPath: string
}

export type CabinPhotoPresentationPhoto = {
  mediaId: string
  generation: number
  sortOrder: number
  thumbnailUrl: string
  contentUrl: string
}

export type PublicCabinPhotoPresentation = {
  id: string
  cabinNumber: string
  createdAt: string
  photos: CabinPhotoPresentationPhoto[]
}

function logisticsV1(path: string) {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1${path}`
}

function publicPhotoPresentationEndpoint(token: string) {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/public/v1/cabin-photo-presentations/${encodeURIComponent(token)}`
}

export function createCabinPhotoPresentation(params: {
  accessToken: string
  cabinId: string
  warehouseId: string
  expectedRentalItemVersion: number
  idempotencyKey: string
}) {
  return bearerRequest<CabinPhotoPresentation>(
    params.accessToken,
    logisticsV1(
      `/cabins/${encodeURIComponent(params.cabinId)}/photo-presentations`
    ),
    {
      method: "POST",
      headers: { "Idempotency-Key": params.idempotencyKey },
      body: JSON.stringify({
        warehouseId: params.warehouseId,
        expectedRentalItemVersion: params.expectedRentalItemVersion,
      }),
    }
  )
}

export async function getPublicCabinPhotoPresentation(token: string) {
  const response = await fetch(publicPhotoPresentationEndpoint(token), {
    headers: { Accept: "application/json" },
  })
  if (!response.ok) throw await apiErrorFromResponse(response)
  return (await response.json()) as PublicCabinPhotoPresentation
}
