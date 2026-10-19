import { afterEach, describe, expect, it, vi } from "vitest"

import {
  createOrder,
  extendOrderRentalTerms,
  listClientOrders,
  listOrderReplacementCandidates,
  listOrders,
  listOrderClients,
  parseOrderDetail,
  replaceOrderUnit,
  saveOrder,
  updateOrder,
} from "@/features/orders/api/orders-api"

const ORDER_ID = "50ac5b00-2378-457b-82fc-14d43daa5c5c"
const CLIENT_ID = "8a14d50d-4b0b-4a4d-9aaf-b29cd877fcd3"
const UNIT_ID = "9b14d50d-4b0b-4a4d-9aaf-b29cd877fcd3"
const REPLACEMENT_UNIT_ID = "ab14d50d-4b0b-4a4d-9aaf-b29cd877fcd3"
const WAREHOUSE_ID = "4b14d50d-4b0b-4a4d-9aaf-b29cd877fcd3"
const IDEMPOTENCY_KEY = "ad4f4e00-2378-457b-82fc-14d43daa5c5c"
const MANAGER_ID = "00000000-0000-0000-0000-0000000000d8"
const delivery = {
  deliveryAddress: "Москва, Складская, 1",
  latitude: 55.75,
  longitude: 37.62,
  contactPhone: "+79990000000",
  comment: "Позвонить заранее",
  additionalContacts: [{ name: "Анна Петрова", phone: "+79990000001" }],
}
const managerOrderInput = {
  contactPhone: delivery.contactPhone,
  comment: delivery.comment,
}

const desiredDeliveryWindows = [
  {
    startDate: "2026-07-21",
    endDate: "2026-07-21",
  },
]

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
  additionalContacts: [],
  createdAt: "2026-07-19T19:49:56.021463Z",
  updatedAt: "2026-07-19T19:49:56.021463Z",
}

const orderDetailResponse = {
  id: ORDER_ID,
  version: 5,
  number: "ORD-000003",
  status: "DRAFT",
  customerDeliveryPurpose: "RENTAL_DELIVERY",
  client: clientResponse,
  managerId: MANAGER_ID,
  managerDisplayName: "development-admin",
  createdBy: "00000000-0000-0000-0000-0000000000d8",
  createdByDisplayName: "development-admin",
  warehouseId: null,
  ...delivery,
  desiredDeliveryWindows,
  unitCount: 0,
  createdAt: "2026-07-19T19:49:56.046806Z",
  updatedAt: "2026-07-19T20:49:56.046806Z",
  units: [],
  movements: [],
  permissions: {
    canEdit: true,
    canReplaceUnits: false,
    canExtendRentalTerms: true,
    canViewOtherManagers: true,
  },
}

afterEach(() => vi.unstubAllGlobals())

describe("parseOrderDetail", () => {
  it("accepts technical UUIDs used by the development authorization service", () => {
    const order = parseOrderDetail({
      id: "50ac5b00-2378-457b-82fc-14d43daa5c5c",
      version: 0,
      number: "ORD-000003",
      status: "DRAFT",
      customerDeliveryPurpose: "RENTAL_DELIVERY",
      client: clientResponse,
      managerId: "00000000-0000-0000-0000-0000000000d8",
      managerDisplayName: "development-admin",
      createdBy: "00000000-0000-0000-0000-0000000000d8",
      createdByDisplayName: "development-admin",
      warehouseId: null,
      ...delivery,
      desiredDeliveryWindows,
      unitCount: 0,
      createdAt: "2026-07-19T19:49:56.046806Z",
      updatedAt: "2026-07-19T19:49:56.046806Z",
      units: [],
      movements: [],
      permissions: {
        canEdit: true,
        canReplaceUnits: false,
        canExtendRentalTerms: true,
        canViewOtherManagers: true,
      },
    })

    expect(order.managerId).toBe("00000000-0000-0000-0000-0000000000d8")
    expect(order.createdBy).toBe("00000000-0000-0000-0000-0000000000d8")
    expect(order.permissions.canExtendRentalTerms).toBe(true)
  })

  it("requires the server extension permission in an order detail", () => {
    const response = {
      ...orderDetailResponse,
      permissions: { ...orderDetailResponse.permissions },
    }
    delete (response.permissions as { canExtendRentalTerms?: boolean })
      .canExtendRentalTerms

    expect(() => parseOrderDetail(response)).toThrow(
      "Сервис логистики вернул некорректный ответ модуля бронирований."
    )
  })

  it("requires the current rental delivery purpose in order projections", () => {
    const missingPurpose = { ...orderDetailResponse }
    Reflect.deleteProperty(missingPurpose, "customerDeliveryPurpose")

    expect(() => parseOrderDetail(missingPurpose)).toThrow(
      "Сервис логистики вернул некорректный ответ модуля бронирований."
    )
    expect(() =>
      parseOrderDetail({
        ...orderDetailResponse,
        customerDeliveryPurpose: "SALE_DELIVERY",
      })
    ).toThrow(
      "Сервис логистики вернул некорректный ответ модуля бронирований."
    )
  })

  it("drops legacy desired-window times from the panel model", () => {
    const order = parseOrderDetail({
      ...orderDetailResponse,
      desiredDeliveryWindows: [
        {
          startDate: "2026-07-21",
          endDate: "2026-07-21",
          timeFrom: "10:00:00",
          timeTo: "13:00:00",
        },
      ],
    })

    expect(order.desiredDeliveryWindows).toEqual([
      { startDate: "2026-07-21", endDate: "2026-07-21" },
    ])
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
          reservationState: "ACTIVE",
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
          customerDeliveryPurpose: null,
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
    expect(order.movements[0]?.customerDeliveryPurpose).toBeNull()
  })

  it("requires a customer delivery purpose only for shipment movements", () => {
    const shipmentMovement = {
      documentId: "1b14d50d-4b0b-4a4d-9aaf-b29cd877fcd3",
      documentType: "SHIPMENT",
      customerDeliveryPurpose: "CUSTOMER_RELOCATION",
      state: "DRAFT",
      scheduledDate: null,
      actualAt: null,
      rentalShipmentId: null,
      createdAt: "2026-07-19T19:49:56.046806Z",
      updatedAt: "2026-07-19T20:49:56.046806Z",
      cabins: [],
    }

    expect(
      parseOrderDetail({
        ...orderDetailResponse,
        movements: [shipmentMovement],
      }).movements[0]?.customerDeliveryPurpose
    ).toBe("CUSTOMER_RELOCATION")
    expect(() =>
      parseOrderDetail({
        ...orderDetailResponse,
        movements: [{ ...shipmentMovement, customerDeliveryPurpose: null }],
      })
    ).toThrow(
      "Сервис логистики вернул некорректный ответ модуля бронирований."
    )
    const missingPurposeMovement = { ...shipmentMovement }
    Reflect.deleteProperty(missingPurposeMovement, "customerDeliveryPurpose")
    expect(() =>
      parseOrderDetail({
        ...orderDetailResponse,
        movements: [missingPurposeMovement],
      })
    ).toThrow(
      "Сервис логистики вернул некорректный ответ модуля бронирований."
    )
    expect(() =>
      parseOrderDetail({
        ...orderDetailResponse,
        movements: [
          {
            ...shipmentMovement,
            documentType: "RETURN",
            customerDeliveryPurpose: "RENTAL_DELIVERY",
          },
        ],
      })
    ).toThrow(
      "Сервис логистики вернул некорректный ответ модуля бронирований."
    )
  })

  it("creates only manager-owned order fields", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(orderDetailResponse), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await createOrder({
      accessToken: "orders-token",
      idempotencyKey: IDEMPOTENCY_KEY,
      input: { clientId: CLIENT_ID, ...managerOrderInput },
    })

    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(input).pathname).toBe("/api/logistics/v1/orders")
    expect(init.method).toBe("POST")
    const body = JSON.parse(String(init.body))
    expect(body).toEqual({ clientId: CLIENT_ID, ...managerOrderInput })
    expect(body).not.toHaveProperty("deliveryAddress")
    expect(body).not.toHaveProperty("latitude")
    expect(body).not.toHaveProperty("longitude")
    expect(body).not.toHaveProperty("additionalContacts")
    expect(body).not.toHaveProperty("desiredDeliveryWindows")
  })

  it("updates only manager-owned order fields", async () => {
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
        delivery: managerOrderInput,
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
    const body = JSON.parse(String(init.body))
    expect(body).toEqual({
      expectedVersion: 4,
      clientId: CLIENT_ID,
      ...managerOrderInput,
    })
    expect(body).not.toHaveProperty("deliveryAddress")
    expect(body).not.toHaveProperty("latitude")
    expect(body).not.toHaveProperty("longitude")
    expect(body).not.toHaveProperty("additionalContacts")
    expect(body).not.toHaveProperty("desiredDeliveryWindows")
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

  it("loads replacement candidates from the current order boundary", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          content: [
            {
              reservationId: null,
              added: false,
              reservationState: null,
              desiredContents: [],
              rentalTerm: null,
              unit: {
                id: REPLACEMENT_UNIT_ID,
                version: 1,
                warehouseId: WAREHOUSE_ID,
                number: "БЫТ-002",
                status: "FREE",
                rentalType: "БК-1",
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
            },
          ],
          page: 1,
          size: 50,
          totalElements: 51,
          totalPages: 2,
        }),
        { headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      listOrderReplacementCandidates({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        page: 1,
        size: 50,
        search: " БЫТ-002 ",
        inventorySourceWarehouseId: WAREHOUSE_ID,
      })
    ).resolves.toMatchObject({
      content: [{ added: false, unit: { id: REPLACEMENT_UNIT_ID } }],
      page: 1,
      totalPages: 2,
    })

    const [input] = fetchMock.mock.calls[0] as [string]
    const endpoint = new URL(input)
    expect(endpoint.pathname).toBe(
      `/api/logistics/v1/orders/${ORDER_ID}/available-units`
    )
    expect(endpoint.searchParams.get("page")).toBe("1")
    expect(endpoint.searchParams.get("size")).toBe("50")
    expect(endpoint.searchParams.get("search")).toBe("БЫТ-002")
    expect(endpoint.searchParams.get("inventorySourceWarehouseId")).toBe(
      WAREHOUSE_ID
    )
  })

  it("replaces one order cabin through the exact idempotent command", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(orderDetailResponse), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      replaceOrderUnit({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 5,
        unitId: UNIT_ID,
        replacementRentalItemId: REPLACEMENT_UNIT_ID,
        reason: "  Протечка  ",
        inventorySourceWarehouseId: WAREHOUSE_ID,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toMatchObject({ id: ORDER_ID, version: 5 })

    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(input).pathname).toBe(
      `/api/logistics/v1/orders/${ORDER_ID}/units/${UNIT_ID}/replace`
    )
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init.body))).toEqual({
      expectedVersion: 5,
      replacementRentalItemId: REPLACEMENT_UNIT_ID,
      reason: "Протечка",
      inventorySourceWarehouseId: WAREHOUSE_ID,
    })
  })

  it("keeps same-warehouse replacement compatible with omitted or null source", async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify(orderDetailResponse), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    const base = {
      accessToken: "orders-token",
      orderId: ORDER_ID,
      expectedVersion: 5,
      unitId: UNIT_ID,
      replacementRentalItemId: REPLACEMENT_UNIT_ID,
      reason: "Протечка",
      idempotencyKey: IDEMPOTENCY_KEY,
    }
    await replaceOrderUnit(base)
    await replaceOrderUnit({ ...base, inventorySourceWarehouseId: null })

    const omittedBody = JSON.parse(String(fetchMock.mock.calls[0][1].body))
    const nullBody = JSON.parse(String(fetchMock.mock.calls[1][1].body))
    expect(omittedBody).not.toHaveProperty("inventorySourceWarehouseId")
    expect(nullBody.inventorySourceWarehouseId).toBeNull()
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
