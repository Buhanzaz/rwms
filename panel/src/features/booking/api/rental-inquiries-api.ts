import type { OrderClient } from "@/features/orders/domain/orders"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export const ORDER_RENTAL_INQUIRIES_QUERY_KEY = [
  "order-rental-inquiries",
] as const

export type RentalInquiry = {
  id: string
  version: number
  conversationId: string | null
  client: OrderClient
  managerId: string
  managerDisplayName: string
  managerRole: string
  rentalOrderId: string | null
  warehouseId: string | null
  state: "ACTIVE" | "BOOKED" | "ARCHIVED"
  bookedOrderId: string | null
  createdAt: string
  updatedAt: string
}

function rentalInquiriesEndpoint(path = "") {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/rental-inquiries${path}`
}

export function listRentalInquiriesForOrder(params: {
  accessToken: string
  rentalOrderId: string
}) {
  const endpoint = new URL(rentalInquiriesEndpoint())
  endpoint.searchParams.set("rentalOrderId", params.rentalOrderId)
  return bearerRequest<RentalInquiry[]>(params.accessToken, endpoint)
}

export function createManualRentalInquiry(params: {
  accessToken: string
  clientId: string
  rentalOrderId: string
  idempotencyKey: string
}) {
  return bearerRequest<RentalInquiry>(
    params.accessToken,
    rentalInquiriesEndpoint(),
    {
      method: "POST",
      headers: { "Idempotency-Key": params.idempotencyKey },
      body: JSON.stringify({
        conversationId: null,
        clientId: params.clientId,
        rentalOrderId: params.rentalOrderId,
      }),
    }
  )
}
