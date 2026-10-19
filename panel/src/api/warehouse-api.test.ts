import { afterEach, describe, expect, it, vi } from "vitest"

import {
  completeWarehouseInactivation,
  createWarehouse,
  listWarehouseSupportLinks,
  listWarehouses,
  replaceWarehouse,
  replaceWarehouseSupportLinks,
  scheduleWarehouseTimeZone,
  startWarehouseDraining,
  type WarehouseSupportLinkInput,
  type WarehouseWriteInput,
} from "@/api/warehouse-api"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000002"
const PRODUCTION_WAREHOUSE_ID = "00000000-0000-4000-8000-000000000004"

const warehouseResponse = {
  id: WAREHOUSE_ID,
  version: 3,
  name: "Северный склад",
  city: "Санкт-Петербург",
  address: null,
  latitude: 59.9343,
  longitude: 30.3351,
  timeZone: "Europe/Moscow",
  active: true,
  lifecycleState: "ACTIVE",
  sortOrder: 2,
  representative: false,
  production: true,
  mainWarehouse: false,
  representativeParentWarehouseId: null,
}

const representativeWarehouseResponse = {
  ...warehouseResponse,
  id: "00000000-0000-4000-8000-000000000005",
  production: false,
  mainWarehouse: false,
  representativeParentWarehouseId: PRODUCTION_WAREHOUSE_ID,
  representative: true,
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("warehouse HTTP API", () => {
  it("requires a token instead of falling back to a browser mock", async () => {
    await expect(listWarehouses(null)).rejects.toThrow("токен")
  })

  it("uses the same-origin gateway route, bearer token and canonical UUID", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse([warehouseResponse]))
    vi.stubGlobal("fetch", fetchMock)

    await expect(listWarehouses("access-token")).resolves.toEqual([
      {
        ...warehouseResponse,
      },
    ])

    expect(String(fetchMock.mock.calls[0]?.[0])).toBe(
      `${getGatewayRuntimeConfig().warehouseApiBaseUrl}/v1/warehouses`
    )
    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(new Headers(request.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
  })

  it("accepts the exact production and representative shapes", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse([
            warehouseResponse,
            representativeWarehouseResponse,
          ])
        )
    )

    await expect(listWarehouses("access-token")).resolves.toEqual([
      warehouseResponse,
      representativeWarehouseResponse,
    ])
  })

  it.each([
    [
      "partial current object shape",
      { ...warehouseResponse, production: undefined },
    ],
    [
      "unexpected classification field",
      { ...warehouseResponse, obsoleteClassification: "PRODUCTION" },
    ],
    [
      "production with a parent",
      {
        ...warehouseResponse,
        representativeParentWarehouseId: PRODUCTION_WAREHOUSE_ID,
      },
    ],
    [
      "production marked representative",
      { ...warehouseResponse, representative: true },
    ],
    [
      "representative without a parent",
      {
        ...representativeWarehouseResponse,
        representativeParentWarehouseId: null,
      },
    ],
    [
      "representative without the current projection",
      { ...representativeWarehouseResponse, representative: false },
    ],
    [
      "extra field in the current object shape",
      { ...warehouseResponse, unknown: true },
    ],
  ])("rejects %s", async (_caseName, response) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse([response])))

    await expect(listWarehouses("access-token")).rejects.toThrow(
      "некорректный ответ"
    )
  })

  it("rejects a malformed warehouse response before it reaches the panel", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse([{ ...warehouseResponse, unknown: "not in OpenAPI" }])
        )
    )

    await expect(listWarehouses("access-token")).rejects.toThrow(
      "некорректный ответ"
    )
  })

  it("sends idempotency and expected-version semantics for mutations", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(warehouseResponse, 201))
      .mockResolvedValueOnce(jsonResponse({ ...warehouseResponse, version: 4 }))
      .mockResolvedValueOnce(
        jsonResponse({
          ...warehouseResponse,
          version: 5,
          active: false,
          lifecycleState: "DRAINING",
        })
      )
      .mockResolvedValueOnce(
        jsonResponse({
          ...warehouseResponse,
          version: 6,
          active: false,
          lifecycleState: "INACTIVE",
        })
      )
      .mockResolvedValueOnce(
        jsonResponse({
          warehouseId: WAREHOUSE_ID,
          warehouseVersion: 7,
          timeZone: "Europe/Samara",
          effectiveFrom: "2099-09-01T00:00:00+04:00",
        })
      )
    vi.stubGlobal("fetch", fetchMock)

    await createWarehouse("access-token", IDEMPOTENCY_KEY, {
      name: "Северный склад",
      city: "Санкт-Петербург",
      address: null,
      latitude: 59.9343,
      longitude: 30.3351,
      timeZone: "Europe/Moscow",
      sortOrder: 2,
      production: false,
      mainWarehouse: false,
      representativeParentWarehouseId: PRODUCTION_WAREHOUSE_ID,
      representative: true,
    })
    await replaceWarehouse("access-token", WAREHOUSE_ID, 3, {
      name: warehouseResponse.name,
      city: warehouseResponse.city,
      address: warehouseResponse.address,
      latitude: warehouseResponse.latitude,
      longitude: warehouseResponse.longitude,
      timeZone: warehouseResponse.timeZone,
      sortOrder: warehouseResponse.sortOrder,
      production: true,
      mainWarehouse: true,
      representativeParentWarehouseId: null,
      representative: false,
    })
    await startWarehouseDraining("access-token", WAREHOUSE_ID, 4)
    await completeWarehouseInactivation("access-token", WAREHOUSE_ID, 5)
    await scheduleWarehouseTimeZone(
      "access-token",
      WAREHOUSE_ID,
      6,
      "Europe/Samara",
      "2099-09-01T00:00:00+04:00"
    )

    const createRequest = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(new Headers(createRequest.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(createRequest.body))).toEqual({
      name: "Северный склад",
      city: "Санкт-Петербург",
      address: null,
      latitude: 59.9343,
      longitude: 30.3351,
      timeZone: "Europe/Moscow",
      sortOrder: 2,
      production: false,
      mainWarehouse: false,
      representativeParentWarehouseId: PRODUCTION_WAREHOUSE_ID,
      representative: true,
    })

    const replaceRequest = fetchMock.mock.calls[1]?.[1] as RequestInit
    expect(JSON.parse(String(replaceRequest.body))).toEqual({
      name: warehouseResponse.name,
      city: warehouseResponse.city,
      address: warehouseResponse.address,
      latitude: warehouseResponse.latitude,
      longitude: warehouseResponse.longitude,
      timeZone: warehouseResponse.timeZone,
      sortOrder: warehouseResponse.sortOrder,
      production: true,
      mainWarehouse: true,
      representativeParentWarehouseId: null,
      representative: false,
      expectedVersion: 3,
    })

    expect(String(fetchMock.mock.calls[2]?.[0])).toContain(
      `/${WAREHOUSE_ID}/draining`
    )
    expect(JSON.parse(String(fetchMock.mock.calls[2]?.[1]?.body))).toEqual({
      expectedVersion: 4,
    })
    expect(String(fetchMock.mock.calls[3]?.[0])).toContain(
      `/${WAREHOUSE_ID}/inactivation`
    )
    expect(JSON.parse(String(fetchMock.mock.calls[3]?.[1]?.body))).toEqual({
      expectedVersion: 5,
    })
    expect(String(fetchMock.mock.calls[4]?.[0])).toContain(
      `/${WAREHOUSE_ID}/time-zone-changes`
    )
    expect(JSON.parse(String(fetchMock.mock.calls[4]?.[1]?.body))).toEqual({
      expectedVersion: 6,
      timeZone: "Europe/Samara",
      effectiveFrom: "2099-09-01T00:00:00+04:00",
    })
  })

  it("rejects incomplete and inconsistent object classifications before sending a command", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse(warehouseResponse, 201))
    vi.stubGlobal("fetch", fetchMock)

    const incompleteInput = {
      name: warehouseResponse.name,
      city: warehouseResponse.city,
      address: warehouseResponse.address,
      latitude: warehouseResponse.latitude,
      longitude: warehouseResponse.longitude,
      timeZone: warehouseResponse.timeZone,
      sortOrder: warehouseResponse.sortOrder,
      representative: false,
    }

    await expect(
      createWarehouse(
        "access-token",
        IDEMPOTENCY_KEY,
        incompleteInput as unknown as WarehouseWriteInput
      )
    ).rejects.toThrow("не соответствуют контракту API")
    await expect(
      createWarehouse("access-token", IDEMPOTENCY_KEY, {
        ...incompleteInput,
        production: true,
        mainWarehouse: false,
        representativeParentWarehouseId: null,
        representative: true,
      })
    ).rejects.toThrow("не соответствуют контракту API")
    await expect(
      createWarehouse("access-token", IDEMPOTENCY_KEY, {
        ...incompleteInput,
        production: false,
        mainWarehouse: false,
        representativeParentWarehouseId: null,
        representative: false,
      })
    ).rejects.toThrow("не соответствуют контракту API")
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it("reads and atomically replaces directed support links", async () => {
    const supportWarehouseId = "00000000-0000-4000-8000-000000000003"
    const supportLinkId = "00000000-0000-4000-8000-000000000004"
    const link = {
      id: supportLinkId,
      version: 1,
      supportWarehouseId,
      servedWarehouseId: WAREHOUSE_ID,
      active: true,
      priority: 1,
      allowDrivers: true,
      allowVehicles: true,
      allowInventory: true,
      allowDirectFulfillment: true,
      allowInterwarehouseTransfer: true,
      allowContractorFallback: false,
      allowedWeekdays: ["TUESDAY", "THURSDAY"],
      allowedDates: ["2026-09-14"],
      excludedDates: ["2026-09-15"],
      serviceStart: "08:00:00",
      serviceEnd: "18:00:00",
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({
          servedWarehouseId: WAREHOUSE_ID,
          warehouseVersion: 3,
          links: [link],
        })
      )
      .mockResolvedValueOnce(
        jsonResponse({
          servedWarehouseId: WAREHOUSE_ID,
          warehouseVersion: 4,
          links: [{ ...link, version: 2 }],
        })
      )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      listWarehouseSupportLinks("access-token", WAREHOUSE_ID)
    ).resolves.toMatchObject({ warehouseVersion: 3, links: [link] })

    const input: WarehouseSupportLinkInput = {
      supportWarehouseId,
      active: true,
      priority: 1,
      allowDrivers: true,
      allowVehicles: true,
      allowInventory: true,
      allowDirectFulfillment: true,
      allowInterwarehouseTransfer: true,
      allowContractorFallback: false,
      allowedWeekdays: ["TUESDAY", "THURSDAY"],
      allowedDates: ["2026-09-14"],
      excludedDates: ["2026-09-15"],
      serviceStart: "08:00",
      serviceEnd: "18:00",
    }
    await replaceWarehouseSupportLinks("access-token", WAREHOUSE_ID, 3, [input])

    expect(String(fetchMock.mock.calls[1]?.[0])).toContain(
      `/${WAREHOUSE_ID}/support-links`
    )
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).toEqual({
      expectedVersion: 3,
      links: [input],
    })
  })
})
