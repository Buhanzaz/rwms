import { afterEach, describe, expect, it, vi } from "vitest"

import { transferEquipment } from "@/api/equipment-api"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const warehouseId = "00000000-0000-0000-0000-000000000001"
const equipmentId = "00000000-0000-0000-0000-000000000101"
const sourceRentalItemId = "00000000-0000-0000-0000-000000000111"
const targetRentalItemId = "00000000-0000-0000-0000-000000000112"
const sourceBalanceId = "00000000-0000-0000-0000-000000000201"
const targetBalanceId = "00000000-0000-0000-0000-000000000202"
const movementId = "00000000-0000-0000-0000-000000000301"
const idempotencyKey = "00000000-0000-0000-0000-000000000901"

function jsonResponse(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json" },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("asset-service equipment transfer HTTP adapter", () => {
  it("sends both balance versions through the same-origin asset route", async () => {
    const movement = {
      id: movementId,
      version: 0,
      equipmentId,
      sourceBalanceId,
      targetBalanceId,
      quantity: 2,
      kind: "CABIN_TO_CABIN",
      occurredAt: "2026-07-20T10:00:00Z",
    }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(movement, 201))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      transferEquipment("access-token", idempotencyKey, {
        equipmentId,
        sourceWarehouseId: warehouseId,
        sourceRentalItemId,
        sourceLocationKind: "CABIN_NON_RENTED",
        sourceExpectedVersion: 7,
        targetWarehouseId: warehouseId,
        targetRentalItemId,
        targetLocationKind: "CABIN_NON_RENTED",
        targetExpectedVersion: 4,
        quantity: 2,
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
      idempotencyKey
    )
    expect(JSON.parse(String(request.body))).toEqual({
      equipmentId,
      sourceWarehouseId: warehouseId,
      sourceRentalItemId,
      sourceLocationKind: "CABIN_NON_RENTED",
      sourceExpectedVersion: 7,
      targetWarehouseId: warehouseId,
      targetRentalItemId,
      targetLocationKind: "CABIN_NON_RENTED",
      targetExpectedVersion: 4,
      quantity: 2,
    })
  })

  it("preserves a stale-balance conflict for UI reconciliation", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ detail: "Версия остатка устарела" }, 409)
        )
    )

    await expect(
      transferEquipment("access-token", idempotencyKey, {
        equipmentId,
        sourceWarehouseId: warehouseId,
        sourceRentalItemId: null,
        sourceLocationKind: "STOCK",
        sourceExpectedVersion: 7,
        targetWarehouseId: warehouseId,
        targetRentalItemId,
        targetLocationKind: "CABIN_NON_RENTED",
        targetExpectedVersion: 4,
        quantity: 1,
      })
    ).rejects.toMatchObject({
      status: 409,
      message: "Версия остатка устарела",
    })
  })
})
