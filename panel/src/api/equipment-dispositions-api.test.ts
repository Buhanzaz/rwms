import { afterEach, describe, expect, it, vi } from "vitest"

import {
  disposeEquipment,
  getEquipmentItems,
  listEquipmentDispositionItems,
} from "@/api/equipment-api"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const warehouseId = "00000000-0000-0000-0000-000000000001"
const equipmentId = "00000000-0000-0000-0000-000000000101"
const balanceId = "00000000-0000-0000-0000-000000000201"
const targetBalanceId = "00000000-0000-0000-0000-000000000202"
const movementId = "00000000-0000-0000-0000-000000000301"
const idempotencyKey = "00000000-0000-0000-0000-000000000901"

const equipmentWarehouse = {
  equipment: {
    id: equipmentId,
    version: 4,
    code: "CHAIR",
    name: "Стул",
    category: "FURNITURE",
    active: true,
    comment: null,
    createdAt: "2026-07-18T10:00:00Z",
    updatedAt: "2026-07-18T10:00:00Z",
  },
  totals: {
    equipmentId,
    warehouseId,
    totalQuantity: 12,
    stockQuantity: 8,
    nonRentedCabinQuantity: 2,
    rentedCabinQuantity: 1,
    writtenOffQuantity: 1,
    lostQuantity: 0,
    activeHeldQuantity: 1,
    availableStock: 7,
    balances: [
      {
        id: balanceId,
        version: 9,
        equipmentId,
        warehouseId,
        rentalItemId: null,
        locationKind: "STOCK",
        quantity: 8,
        activeHeldQuantity: 1,
        availableStock: 7,
      },
    ],
  },
}

const disposition = {
  movement: {
    id: movementId,
    version: 2,
    equipmentId,
    sourceBalanceId: balanceId,
    targetBalanceId,
    quantity: 2,
    kind: "EQUIPMENT_WRITTEN_OFF",
    occurredAt: "2026-07-18T11:00:00Z",
  },
  equipmentCode: "CHAIR",
  equipmentName: "Стул",
}

function jsonResponse(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json" },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("asset-service equipment HTTP adapter", () => {
  it("requires an actual Bearer token rather than a browser fallback", async () => {
    await expect(
      getEquipmentItems(null, { warehouseId })
    ).rejects.toMatchObject({ status: 401 })
  })

  it("maps canonical warehouse equipment and sends the gateway Bearer request", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse([equipmentWarehouse]))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      getEquipmentItems("access-token", { warehouseId, search: "сту" })
    ).resolves.toEqual([
      expect.objectContaining({
        id: equipmentId,
        warehouseId,
        name: "Стул",
        stockQuantity: 8,
        cabinStockQuantity: 2,
        rentedQuantity: 1,
        writtenOffQuantity: 1,
        availableStock: 7,
        usages: [],
      }),
    ])

    const endpoint = new URL(String(fetchMock.mock.calls[0]?.[0]))
    expect(`${endpoint.origin}${endpoint.pathname}`).toBe(
      `${getGatewayRuntimeConfig().assetApiBaseUrl}/v1/equipment`
    )
    expect(endpoint.searchParams.get("warehouseId")).toBe(warehouseId)
    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(new Headers(request.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
  })

  it("rejects malformed service balances instead of deriving browser values", async () => {
    const malformed = structuredClone(equipmentWarehouse)
    malformed.totals.balances[0].warehouseId =
      "00000000-0000-0000-0000-000000000099"
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse([malformed])))

    await expect(
      getEquipmentItems("access-token", { warehouseId })
    ).rejects.toThrow("несогласованный баланс")
  })

  it("preserves forbidden warehouse access from asset-service", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ detail: "Нет доступа к складу" }, 403)
        )
    )

    await expect(
      getEquipmentItems("access-token", { warehouseId })
    ).rejects.toMatchObject({ status: 403, message: "Нет доступа к складу" })
  })

  it("preserves a missing source balance as not found", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(jsonResponse({ detail: "Остаток не найден" }, 404))
    )

    await expect(
      disposeEquipment("access-token", idempotencyKey, {
        equipmentId,
        warehouseId,
        sourceRentalItemId: null,
        sourceLocationKind: "STOCK",
        sourceExpectedVersion: 9,
        quantity: 2,
        disposition: "WRITE_OFF",
      })
    ).rejects.toMatchObject({ status: 404, message: "Остаток не найден" })
  })

  it("maps disposition history through the canonical endpoint and local search", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([disposition]))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      listEquipmentDispositionItems("access-token", {
        warehouseId,
        search: "стул",
      })
    ).resolves.toEqual([
      expect.objectContaining({
        id: movementId,
        equipmentId,
        equipmentName: "Стул",
        kind: "EQUIPMENT_WRITTEN_OFF",
      }),
    ])

    const endpoint = new URL(String(fetchMock.mock.calls[0]?.[0]))
    expect(`${endpoint.origin}${endpoint.pathname}`).toBe(
      `${getGatewayRuntimeConfig().assetApiBaseUrl}/v1/equipment/dispositions`
    )
    expect(endpoint.searchParams.get("warehouseId")).toBe(warehouseId)
  })

  it("sends a versioned, idempotent asset-service disposition command", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse(disposition.movement, 201))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      disposeEquipment("access-token", idempotencyKey, {
        equipmentId,
        warehouseId,
        sourceRentalItemId: null,
        sourceLocationKind: "STOCK",
        sourceExpectedVersion: 9,
        quantity: 2,
        disposition: "WRITE_OFF",
      })
    ).resolves.toMatchObject({
      id: movementId,
      sourceBalanceId: balanceId,
      targetBalanceId,
    })

    const endpoint = new URL(String(fetchMock.mock.calls[0]?.[0]))
    expect(`${endpoint.origin}${endpoint.pathname}`).toBe(
      `${getGatewayRuntimeConfig().assetApiBaseUrl}/v1/equipment/dispositions`
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
      warehouseId,
      sourceRentalItemId: null,
      sourceLocationKind: "STOCK",
      sourceExpectedVersion: 9,
      quantity: 2,
      disposition: "WRITE_OFF",
    })
  })

  it("preserves canonical conflict failures for the caller to refresh", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ detail: "Версия остатка устарела" }, 409)
        )
    )

    await expect(
      disposeEquipment("access-token", idempotencyKey, {
        equipmentId,
        warehouseId,
        sourceRentalItemId: null,
        sourceLocationKind: "STOCK",
        sourceExpectedVersion: 9,
        quantity: 2,
        disposition: "WRITE_OFF",
      })
    ).rejects.toMatchObject({ status: 409, message: "Версия остатка устарела" })
  })
})
