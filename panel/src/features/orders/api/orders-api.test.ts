import { afterEach, describe, expect, it, vi } from "vitest"

import {
  extendOrderRentalTerms,
  listClientOrders,
  listOrders,
  listOrderClients,
  parseOrderDetail,
  saveOrder,
  setOrderRentalTerms,
  updateOrder,
} from "@/features/orders/api/orders-api"

const ORDER_ID = "50ac5b00-2378-457b-82fc-14d43daa5c5c"
const CLIENT_ID = "8a14d50d-4b0b-4a4d-9aaf-b29cd877fcd3"
const UNIT_ID = "9b14d50d-4b0b-4a4d-9aaf-b29cd877fcd3"
const WAREHOUSE_ID = "4b14d50d-4b0b-4a4d-9aaf-b29cd877fcd3"
const IDEMPOTENCY_KEY = "ad4f4e00-2378-457b-82fc-14d43daa5c5c"
const MANAGER_ID = "00000000-0000-0000-0000-0000000000d8"
const delivery = {
  deliveryAddress: "Москва, Складская, 1",
  latitude: 55.75,
  longitude: 37.62,
  contactPhone: "+79990000000",
  comment: "Позвонить заранее",
  acceptableDeliveryDates: ["2026-07-21"],
}

const clientResponse = {
  id: CLIENT_ID,
  version: 0,
  type: "LEGAL_ENTITY",
  displayName: "Диагностический клиент",
  phone: "+79990000000",
  contactPerson: "Иван Петров",
  email: null,
  responsibleManagerId: MANAGER_ID,
  responsibleManagerDisplayName: "development-admin",
  comment: null,
  source: null,
  createdAt: "2026-07-19T19:49:56.021463Z",
  updatedAt: "2026-07-19T19:49:56.021463Z",
}

const orderDetailResponse = {
  id: ORDER_ID,
  version: 5,
  number: "ORD-000003",
  status: "DRAFT",
  client: clientResponse,
  managerId: MANAGER_ID,
  managerDisplayName: "development-admin",
  createdBy: "00000000-0000-0000-0000-0000000000d8",
  createdByDisplayName: "development-admin",
  warehouseId: null,
  ...delivery,
  unitCount: 0,
  createdAt: "2026-07-19T19:49:56.046806Z",
  updatedAt: "2026-07-19T20:49:56.046806Z",
  units: [],
  movements: [],
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
      client: clientResponse,
      managerId: "00000000-0000-0000-0000-0000000000d8",
      managerDisplayName: "development-admin",
      createdBy: "00000000-0000-0000-0000-0000000000d8",
      createdByDisplayName: "development-admin",
      warehouseId: null,
      ...delivery,
      unitCount: 0,
      createdAt: "2026-07-19T19:49:56.046806Z",
      updatedAt: "2026-07-19T19:49:56.046806Z",
      units: [],
      movements: [],
      permissions: { canEdit: true, canViewOtherManagers: true },
    })

    expect(order.managerId).toBe("00000000-0000-0000-0000-0000000000d8")
    expect(order.createdBy).toBe("00000000-0000-0000-0000-0000000000d8")
  })

  it("parses the rental term and shipment dates attached to an order cabin", () => {
    const order = parseOrderDetail({
      ...orderDetailResponse,
      warehouseId: WAREHOUSE_ID,
      unitCount: 1,
      units: [
        {
          reservationId: "2b14d50d-4b0b-4a4d-9aaf-b29cd877fcd3",
          added: true,
          unit: {
            id: UNIT_ID,
            version: 2,
            warehouseId: WAREHOUSE_ID,
            number: "БЫТ-001",
            status: "RENTED",
            rentalType: null,
            dimensions: null,
            finishing: null,
            category: null,
            characteristics: null,
            linoleum: null,
            tags: [],
            contents: [],
            createdAt: "2026-07-19T19:49:56.046806Z",
            updatedAt: "2026-07-19T20:49:56.046806Z",
          },
          desiredContents: [],
          rentalTerm: {
            rentalMonths: 6,
            shipmentDate: "2026-07-20",
            returnDate: "2027-01-20",
          },
        },
      ],
    })

    expect(order.units[0]?.rentalTerm).toEqual({
      rentalMonths: 6,
      shipmentDate: "2026-07-20",
      returnDate: "2027-01-20",
    })
  })

  it("accepts an unscheduled automatically created return movement", () => {
    const order = parseOrderDetail({
      ...orderDetailResponse,
      movements: [
        {
          documentId: "1b14d50d-4b0b-4a4d-9aaf-b29cd877fcd3",
          documentType: "RETURN",
          state: "DRAFT",
          scheduledDate: null,
          actualAt: null,
          rentalShipmentId: null,
          createdAt: "2026-07-19T19:49:56.046806Z",
          updatedAt: "2026-07-19T20:49:56.046806Z",
          cabins: [],
        },
      ],
    })

    expect(order.movements[0]?.scheduledDate).toBeNull()
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
        delivery,
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
      ...delivery,
    })
  })

  it("searches all counterparties when a type is not supplied", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          content: [clientResponse],
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

  it("loads a server-paginated order page owned by one client", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          content: [orderDetailResponse],
          page: 1,
          size: 30,
          totalElements: 31,
          totalPages: 2,
        }),
        { headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      listClientOrders({
        accessToken: "orders-token",
        clientId: CLIENT_ID,
        page: 1,
        size: 30,
        statuses: ["SAVED"],
      })
    ).resolves.toMatchObject({ page: 1, totalElements: 31 })

    const [input] = fetchMock.mock.calls[0] as [string]
    const endpoint = new URL(input)
    expect(endpoint.pathname).toBe(
      `/api/logistics/v1/clients/${CLIENT_ID}/orders`
    )
    expect(endpoint.searchParams.get("page")).toBe("1")
    expect(endpoint.searchParams.get("size")).toBe("30")
    expect(endpoint.searchParams.getAll("status")).toEqual(["SAVED"])
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

  it("sets a complete rental-term vector through the gateway", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(orderDetailResponse), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      setOrderRentalTerms({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 5,
        terms: [{ unitId: UNIT_ID, rentalMonths: 12 }],
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toMatchObject({ id: ORDER_ID })

    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(input).pathname).toBe(
      `/api/logistics/v1/orders/${ORDER_ID}/rental-terms`
    )
    expect(init.method).toBe("PUT")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init.body))).toEqual({
      expectedVersion: 5,
      terms: [{ unitId: UNIT_ID, rentalMonths: 12 }],
    })
  })

  it("extends selected shipped cabins through the gateway", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(orderDetailResponse), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      extendOrderRentalTerms({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 5,
        terms: [{ unitId: UNIT_ID, additionalMonths: 3 }],
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toMatchObject({ id: ORDER_ID })

    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(input).pathname).toBe(
      `/api/logistics/v1/orders/${ORDER_ID}/rental-terms/extend`
    )
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init.body))).toEqual({
      expectedVersion: 5,
      terms: [{ unitId: UNIT_ID, additionalMonths: 3 }],
    })
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
