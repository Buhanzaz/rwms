import { afterEach, describe, expect, it, vi } from "vitest"

import { moveEquipmentUsageToStock } from "@/features/equipment/api/equipment-usage-movements-api"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const WAREHOUSE_ID = "00000000-0000-0000-0000-000000000001"
const EQUIPMENT_ID = "00000000-0000-0000-0000-000000000101"
const RENTAL_ITEM_ID = "00000000-0000-0000-0000-000000000201"
const SOURCE_BALANCE_ID = "00000000-0000-0000-0000-000000000301"
const TARGET_BALANCE_ID = "00000000-0000-0000-0000-000000000401"
const IDEMPOTENCY_KEY = "00000000-0000-0000-0000-000000000901"

function jsonResponse(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json" },
  })
}

const input = {
  equipmentId: EQUIPMENT_ID,
  warehouseId: WAREHOUSE_ID,
  rentalItemId: RENTAL_ITEM_ID,
  sourceLocationKind: "CABIN_NON_RENTED" as const,
  sourceExpectedVersion: 7,
  targetExpectedVersion: 3,
  quantity: 2,
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("equipment usage move-to-stock API", () => {
  it("sends the versioned, idempotent cabin-to-stock command through the gateway", async () => {
    const movement = {
      id: "00000000-0000-0000-0000-000000000501",
      version: 0,
      equipmentId: EQUIPMENT_ID,
      sourceBalanceId: SOURCE_BALANCE_ID,
      targetBalanceId: TARGET_BALANCE_ID,
      quantity: 2,
      kind: "CABIN_TO_STOCK",
      occurredAt: "2026-07-22T10:00:00Z",
    }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(movement, 201))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      moveEquipmentUsageToStock({
        accessToken: "access-token",
        idempotencyKey: IDEMPOTENCY_KEY,
        input,
      })
    ).resolves.toEqual(movement)

    const endpoint = new URL(String(fetchMock.mock.calls[0]?.[0]))
    expect(`${endpoint.origin}${endpoint.pathname}`).toBe(
      `${getGatewayRuntimeConfig().assetApiBaseUrl}/v1/equipment/transfers`
    )
    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(request.method).toBe("POST")
    expect(new Headers(request.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
    expect(new Headers(request.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(request.body))).toEqual({
      equipmentId: EQUIPMENT_ID,
      sourceWarehouseId: WAREHOUSE_ID,
      sourceRentalItemId: RENTAL_ITEM_ID,
      sourceLocationKind: "CABIN_NON_RENTED",
      sourceExpectedVersion: 7,
      targetWarehouseId: WAREHOUSE_ID,
      targetRentalItemId: null,
      targetLocationKind: "STOCK",
      targetExpectedVersion: 3,
      quantity: 2,
    })
  })

  it("preserves a stale balance conflict for the panel to refresh", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ detail: "Версия остатка устарела" }, 409)
        )
    )

    await expect(
      moveEquipmentUsageToStock({
        accessToken: "access-token",
        idempotencyKey: IDEMPOTENCY_KEY,
        input,
      })
    ).rejects.toMatchObject({
      status: 409,
      message: "Версия остатка устарела",
    })
  })
})
