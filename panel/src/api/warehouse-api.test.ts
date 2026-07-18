import { afterEach, describe, expect, it, vi } from "vitest"

import {
  createWarehouse,
  deactivateWarehouse,
  listWarehouses,
  replaceWarehouse,
} from "@/api/warehouse-api"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000001"
const IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000002"

const warehouseResponse = {
  id: WAREHOUSE_ID,
  version: 3,
  code: "WH_NORTH",
  name: "Северный склад",
  city: "Санкт-Петербург",
  address: null,
  timeZone: "Europe/Moscow",
  active: true,
  sortOrder: 2,
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
      { ...warehouseResponse, serviceId: WAREHOUSE_ID },
    ])

    expect(String(fetchMock.mock.calls[0]?.[0])).toBe(
      `${getGatewayRuntimeConfig().warehouseApiBaseUrl}/v1/warehouses`
    )
    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(new Headers(request.headers).get("Authorization")).toBe(
      "Bearer access-token"
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
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal("fetch", fetchMock)

    await createWarehouse("access-token", IDEMPOTENCY_KEY, {
      code: "WH_NORTH",
      name: "Северный склад",
      city: "Санкт-Петербург",
      address: null,
      timeZone: "Europe/Moscow",
      sortOrder: 2,
    })
    await replaceWarehouse("access-token", WAREHOUSE_ID, 3, warehouseResponse)
    await deactivateWarehouse("access-token", WAREHOUSE_ID, 4)

    const createRequest = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(new Headers(createRequest.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(createRequest.body))).toEqual({
      code: "WH_NORTH",
      name: "Северный склад",
      city: "Санкт-Петербург",
      address: null,
      timeZone: "Europe/Moscow",
      sortOrder: 2,
    })

    const replaceRequest = fetchMock.mock.calls[1]?.[1] as RequestInit
    expect(JSON.parse(String(replaceRequest.body))).toEqual({
      ...warehouseResponse,
      expectedVersion: 3,
    })
    expect(String(fetchMock.mock.calls[2]?.[0])).toContain(
      `/${WAREHOUSE_ID}?expectedVersion=4`
    )
  })
})
