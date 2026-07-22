import { afterEach, describe, expect, it, vi } from "vitest"

import {
  listOrders,
  listOrderClients,
  parseOrderDetail,
  saveOrder,
  updateOrder,
} from "@/features/orders/api/orders-api"

const ORDER_ID = "50ac5b00-2378-457b-82fc-14d43daa5c5c"
const CLIENT_ID = "8a14d50d-4b0b-4a4d-9aaf-b29cd877fcd3"
const IDEMPOTENCY_KEY = "ad4f4e00-2378-457b-82fc-14d43daa5c5c"

const orderDetailResponse = {
  id: ORDER_ID,
  version: 5,
  number: "ORD-000003",
  status: "DRAFT",
  client: {
    id: CLIENT_ID,
    type: "LEGAL_ENTITY",
    displayName: "Диагностический клиент",
  },
  managerId: "00000000-0000-0000-0000-0000000000d8",
  managerDisplayName: "development-admin",
  createdBy: "00000000-0000-0000-0000-0000000000d8",
  createdByDisplayName: "development-admin",
  warehouseId: null,
  unitCount: 0,
  createdAt: "2026-07-19T19:49:56.046806Z",
  updatedAt: "2026-07-19T20:49:56.046806Z",
  units: [],
  permissions: { canEdit: true, canViewOtherManagers: true },
}

afterEach(() => vi.unstubAllGlobals())

describe("parseOrderDetail", () => {
  it("accepts technical UUIDs used by the development authorization service", () => {
    const order = parseOrderDetail({
      id: "50ac5b00-2378-457b-82fc-14d43daa5c5c",
      version: 0,
      number: "ORD-000003",
      status: "DRAFT",
      client: {
        id: "8a14d50d-4b0b-4a4d-9aaf-b29cd877fcd3",
        version: 0,
        type: "LEGAL_ENTITY",
        displayName: "Диагностический клиент",
        createdAt: "2026-07-19T19:49:56.021463Z",
        updatedAt: "2026-07-19T19:49:56.021463Z",
      },
      managerId: "00000000-0000-0000-0000-0000000000d8",
      managerDisplayName: "development-admin",
      createdBy: "00000000-0000-0000-0000-0000000000d8",
      createdByDisplayName: "development-admin",
      warehouseId: null,
      unitCount: 0,
      createdAt: "2026-07-19T19:49:56.046806Z",
      updatedAt: "2026-07-19T19:49:56.046806Z",
      units: [],
      permissions: { canEdit: true, canViewOtherManagers: true },
    })

    expect(order.managerId).toBe("00000000-0000-0000-0000-0000000000d8")
    expect(order.createdBy).toBe("00000000-0000-0000-0000-0000000000d8")
  })

  it("updates an order client through the same-origin gateway with CAS and idempotency", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(orderDetailResponse), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      updateOrder({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 4,
        clientId: CLIENT_ID,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toMatchObject({
      id: ORDER_ID,
      version: 5,
      client: { id: CLIENT_ID },
    })

    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(input).pathname).toBe(`/api/logistics/v1/orders/${ORDER_ID}`)
    expect(init.method).toBe("PUT")
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer orders-token"
    )
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init.body))).toEqual({
      expectedVersion: 4,
      clientId: CLIENT_ID,
    })
  })

  it("searches all counterparties when a type is not supplied", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          content: [
            {
              id: CLIENT_ID,
              type: "LEGAL_ENTITY",
              displayName: "Диагностический клиент",
            },
          ],
          page: 0,
          size: 50,
          totalElements: 1,
          totalPages: 1,
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      listOrderClients({
        accessToken: "orders-token",
        search: "Диагностический",
        page: 0,
        size: 50,
      })
    ).resolves.toMatchObject({
      content: [{ id: CLIENT_ID }],
    })

    const [input] = fetchMock.mock.calls[0] as [string]
    const url = new URL(input)
    expect(url.pathname).toBe("/api/logistics/v1/clients")
    expect(url.searchParams.get("search")).toBe("Диагностический")
    expect(url.searchParams.has("type")).toBe(false)
  })

  it("saves an order through the versioned idempotent command", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ ...orderDetailResponse, status: "SAVED" }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      saveOrder({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 5,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toMatchObject({ id: ORDER_ID, status: "SAVED" })

    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const url = new URL(input)
    expect(url.pathname).toBe(`/api/logistics/v1/orders/${ORDER_ID}/save`)
    expect(url.searchParams.get("expectedVersion")).toBe("5")
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
  })

  it("sends status, counterparty, warehouse and creation-date filters", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await listOrders({
      accessToken: "orders-token",
      page: 0,
      size: 20,
      sort: "updatedAt",
      direction: "desc",
      statuses: ["SAVED", "FULFILLED"],
      clientTypes: ["LEGAL_ENTITY"],
      warehouseIds: ["00000000-0000-0000-0000-000000000201"],
      createdFrom: "2026-07-01T00:00:00Z",
      createdTo: "2026-07-31T23:59:59Z",
    })

    const [input] = fetchMock.mock.calls[0] as [string]
    const url = new URL(input)
    expect(url.searchParams.getAll("status")).toEqual(["SAVED", "FULFILLED"])
    expect(url.searchParams.getAll("clientType")).toEqual(["LEGAL_ENTITY"])
    expect(url.searchParams.getAll("warehouseId")).toEqual([
      "00000000-0000-0000-0000-000000000201",
    ])
    expect(url.searchParams.get("createdFrom")).toBe("2026-07-01T00:00:00Z")
    expect(url.searchParams.get("createdTo")).toBe("2026-07-31T23:59:59Z")
  })
})
