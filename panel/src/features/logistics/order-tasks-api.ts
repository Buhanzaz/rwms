import { parseShipmentDocument } from "@/features/logistics/shipments/adapters/http-shipment-client"
import type { ShipmentDocument } from "@/features/logistics/shipments/model"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type CreateOrderShipmentCommand = {
  accessToken: string
  orderId: string
  expectedVersion: number
  inventorySourceWarehouseId: string | null
  driverSnapshot: string
  driverWorkerId: string
  scheduledDate: string
  unitIds: string[]
  idempotencyKey: string
}

function orderShipmentsEndpoint(orderId: string) {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/orders/${encodeURIComponent(orderId)}/shipments`
}

/**
 * Creates one shipment for a selected subset of an order's cabins.
 *
 * The command is deliberately kept separate from the generic shipment client:
 * the order endpoint owns the invariant that a cabin cannot belong to two
 * active shipments.  It also lets the task grid remain honest when a booking
 * is delivered in several trips.
 */
export async function createOrderShipment(
  input: CreateOrderShipmentCommand
): Promise<ShipmentDocument> {
  const response = await bearerRequest<unknown>(
    input.accessToken,
    orderShipmentsEndpoint(input.orderId),
    {
      method: "POST",
      headers: { "Idempotency-Key": input.idempotencyKey },
      body: JSON.stringify({
        expectedVersion: input.expectedVersion,
        inventorySourceWarehouseId: input.inventorySourceWarehouseId,
        driverSnapshot: input.driverSnapshot,
        driverWorkerId: input.driverWorkerId,
        scheduledDate: input.scheduledDate,
        unitIds: input.unitIds,
      }),
    }
  )
  return parseShipmentDocument(response)
}
