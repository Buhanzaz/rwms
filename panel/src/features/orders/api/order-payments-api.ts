import {
  parseOrderPayment,
  type OrderPayment,
} from "@/features/orders/domain/order-payment"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export const ORDER_PAYMENT_QUERY_KEY = ["orders", "payment"] as const

function endpoint(orderId: string) {
  return (
    getGatewayRuntimeConfig().logisticsApiBaseUrl +
    "/v1/orders/" +
    encodeURIComponent(orderId) +
    "/payment"
  )
}

export async function getOrderPayment(
  accessToken: string,
  orderId: string
): Promise<OrderPayment> {
  return parseOrderPayment(
    await bearerRequest<unknown>(accessToken, endpoint(orderId)),
    orderId
  )
}

/** A manager acknowledgement only: the owner supplies source and identity, not a payment provider. */
export async function confirmOrderPayment(params: {
  accessToken: string
  orderId: string
  expectedVersion: number
  idempotencyKey: string
}): Promise<OrderPayment> {
  return parseOrderPayment(
    await bearerRequest<unknown>(
      params.accessToken,
      endpoint(params.orderId) + "/confirm",
      {
        method: "POST",
        headers: { "Idempotency-Key": params.idempotencyKey },
        body: JSON.stringify({ expectedVersion: params.expectedVersion }),
      }
    ),
    params.orderId
  )
}
