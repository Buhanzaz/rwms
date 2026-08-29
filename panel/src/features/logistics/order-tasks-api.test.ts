import { afterEach, describe, expect, it, vi } from "vitest"

import { createOrderShipment } from "@/features/logistics/order-tasks-api"

const ORDER_ID = "11111111-1111-4111-8111-111111111111"
const SERVICE_WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const SOURCE_WAREHOUSE_ID = "33333333-3333-4333-8333-333333333333"
const SHIPMENT_ID = "44444444-4444-4444-8444-444444444444"
const LINE_ID = "55555555-5555-4555-8555-555555555555"
const UNIT_ID = "66666666-6666-4666-8666-666666666666"
const DRIVER_ID = "77777777-7777-4777-8777-777777777777"
const IDEMPOTENCY_KEY = "88888888-8888-4888-8888-888888888888"

function response() {
  return new Response(
    JSON.stringify({
      id: SHIPMENT_ID,
      version: 0,
      documentType: "SHIPMENT",
      state: "DRAFT",
      warehouseId: SERVICE_WAREHOUSE_ID,
      destinationWarehouseId: null,
      partySnapshot: "ООО Клиент",
      driverSnapshot: "Иванов Иван",
      driverWorkerId: DRIVER_ID,
      clientId: null,
      historicalRentalImport: false,
      equipmentMovementTaskId: null,
      scheduledDate: "2026-09-01",
      rentalOrderId: ORDER_ID,
      rentalShipmentId: null,
      lines: [
        {
          id: LINE_ID,
          version: 0,
          lineNumber: 1,
          assetId: UNIT_ID,
          assetVersion: 3,
          state: "PENDING",
          tenantSnapshot: "ООО Клиент",
          rentalOrderId: ORDER_ID,
          inventorySourceWarehouseId: SOURCE_WAREHOUSE_ID,
          inventoryShipmentFurniture: null,
        },
      ],
      createdAt: "2026-08-29T08:00:00Z",
      updatedAt: "2026-08-29T08:00:00Z",
    }),
    { status: 201, headers: { "Content-Type": "application/json" } }
  )
}

afterEach(() => vi.unstubAllGlobals())

describe("createOrderShipment", () => {
  it("sends one physical source with the idempotent order shipment command", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation(() => Promise.resolve(response()))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      createOrderShipment({
        accessToken: "token",
        orderId: ORDER_ID,
        expectedVersion: 7,
        inventorySourceWarehouseId: SOURCE_WAREHOUSE_ID,
        driverSnapshot: "Иванов Иван",
        driverWorkerId: DRIVER_ID,
        scheduledDate: "2026-09-01",
        unitIds: [UNIT_ID],
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toMatchObject({
      warehouseId: SERVICE_WAREHOUSE_ID,
      lines: [{ inventorySourceWarehouseId: SOURCE_WAREHOUSE_ID }],
    })

    const [rawUrl, init] = fetchMock.mock.calls[0]
    expect(new URL(rawUrl).pathname).toBe(
      `/api/logistics/v1/orders/${ORDER_ID}/shipments`
    )
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(init.body)).toEqual({
      expectedVersion: 7,
      inventorySourceWarehouseId: SOURCE_WAREHOUSE_ID,
      driverSnapshot: "Иванов Иван",
      driverWorkerId: DRIVER_ID,
      scheduledDate: "2026-09-01",
      unitIds: [UNIT_ID],
    })
  })
})
