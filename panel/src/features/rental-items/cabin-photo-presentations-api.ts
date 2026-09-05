import {
  apiErrorFromRequestFailure,
  apiErrorFromResponse,
  bearerRequest,
  invalidApiResponseError,
} from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import { assertPresentationRentalPrice } from "@/features/assistant/api/rental-pricing-api"

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
  pricingVersion: number | null
  monthlyPriceRubles: string | null
  dimensions: string | null
  finishing: string | null
  category: string | null
  characteristics: string[]
  linoleum: boolean | null
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
  let response: Response
  try {
    response = await fetch(publicPhotoPresentationEndpoint(token), {
      headers: { Accept: "application/json" },
    })
  } catch (error) {
    throw apiErrorFromRequestFailure(error)
  }
  if (!response.ok) throw await apiErrorFromResponse(response)
  try {
    const presentation = (await response.json()) as PublicCabinPhotoPresentation
    assertPresentationRentalPrice(presentation)
    return presentation
  } catch (error) {
    throw invalidApiResponseError(error)
  }
}
