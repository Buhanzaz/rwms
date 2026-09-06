import { afterEach, describe, expect, it, vi } from "vitest"

import { HttpShipmentClient } from "@/features/logistics/shipments/adapters/http-shipment-client"
import type { ShipmentDocument } from "@/features/logistics/shipments/model"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DOCUMENT_ID = "22222222-2222-4222-8222-222222222222"
const LINE_ID = "33333333-3333-4333-8333-333333333333"
const ASSET_ID = "44444444-4444-4444-8444-444444444444"
const EQUIPMENT_ID = "55555555-5555-4555-8555-555555555555"
const IDEMPOTENCY_KEY = "66666666-6666-4666-8666-666666666666"
const CLIENT_ID = "77777777-7777-4777-8777-777777777777"
const RENTAL_ORDER_ID = "88888888-8888-4888-8888-888888888888"
const FURNITURE_TASK_ID = "99999999-9999-4999-8999-999999999999"
const EXTERNAL_FURNITURE_TASK_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const DRIVER_WORKER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

const document: ShipmentDocument = {
  id: DOCUMENT_ID,
  version: 4,
  documentType: "SHIPMENT",
  customerDeliveryPurpose: "RENTAL_DELIVERY",
  state: "DRAFT",
  warehouseId: WAREHOUSE_ID,
  destinationWarehouseId: null,
  partySnapshot: "ООО Тест",
  driverSnapshot: "Иванов Иван",
  driverWorkerId: DRIVER_WORKER_ID,
  clientId: CLIENT_ID,
  historicalRentalImport: false,
  equipmentMovementTaskId: null,
  scheduledDate: null,
  rentalOrderId: RENTAL_ORDER_ID,
  lines: [
    {
      id: LINE_ID,
      version: 2,
      lineNumber: 1,
      assetId: ASSET_ID,
      assetVersion: 8,
      state: "PENDING",
      tenantSnapshot: null,
      rentalOrderId: RENTAL_ORDER_ID,
      inventorySourceWarehouseId: WAREHOUSE_ID,
      inventoryShipmentFurniture: null,
    },
  ],
  createdAt: "2026-07-18T08:00:00Z",
  updatedAt: "2026-07-18T08:10:00Z",
}

const planLines = [
  {
    assetId: ASSET_ID,
    assetVersion: 8,
    allocations: [
      {
        equipmentId: EQUIPMENT_ID,
        quantity: 2,
        expectedStockVersion: 5,
      },
    ],
  },
]

function json(value: unknown, status = 200) {
  const paginationHeaders: Record<string, string> = Array.isArray(value)
    ? {
        "X-RWMS-Page": "0",
        "X-RWMS-Page-Size": "100",
        "X-RWMS-Total-Elements": String(value.length),
        "X-RWMS-Total-Pages": value.length === 0 ? "0" : "1",
        "X-RWMS-Has-Next": "false",
      }
    : {}
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json", ...paginationHeaders },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe("HttpShipmentClient", () => {
  it("loads every cabin-history page with the same asset filter and no current-day filter", async () => {
    const firstPage = Array.from({ length: 100 }, (_, index) => ({
      ...document,
      id: `22222222-2222-4222-8222-${String(index).padStart(12, "0")}`,
    }))
    const responses = [json(firstPage), json([document])]
    responses.forEach((response, page) => {
      response.headers.set("X-RWMS-Page", String(page))
      response.headers.set("X-RWMS-Total-Elements", "101")
      response.headers.set("X-RWMS-Total-Pages", "2")
      response.headers.set("X-RWMS-Has-Next", String(page === 0))
    })
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(responses[0])
      .mockResolvedValueOnce(responses[1])
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      new HttpShipmentClient().list("token", WAREHOUSE_ID, undefined, ASSET_ID)
    ).resolves.toHaveLength(101)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    fetchMock.mock.calls.forEach(([input], page) => {
      const url = new URL(input)
      expect(url.searchParams.get("warehouseId")).toBe(WAREHOUSE_ID)
      expect(url.searchParams.get("assetId")).toBe(ASSET_ID)
      expect(url.searchParams.get("page")).toBe(String(page))
      expect(url.searchParams.has("scheduledDate")).toBe(false)
    })
  })

  it("rejects a server response that ignored the cabin-history filter", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json([document])))
    await expect(
      new HttpShipmentClient().list(
        "token",
        WAREHOUSE_ID,
        undefined,
        EQUIPMENT_ID
      )
    ).rejects.toThrow("Сервис логистики вернул некорректную отгрузку.")
  })

  it("lists and gets canonical shipments through the same-origin gateway", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json([document]))
      .mockResolvedValueOnce(json(document))
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpShipmentClient()

    await expect(
      client.list("shipment-token", WAREHOUSE_ID, "2026-07-18")
    ).resolves.toEqual([document])
    await expect(client.get("shipment-token", DOCUMENT_ID)).resolves.toEqual(
      document
    )

    const listUrl = new URL(fetchMock.mock.calls[0][0])
    const detailUrl = new URL(fetchMock.mock.calls[1][0])
    expect(listUrl.origin).toBe(window.location.origin)
    expect(listUrl.pathname).toBe("/api/logistics/v1/shipments")
    expect(listUrl.searchParams.get("warehouseId")).toBe(WAREHOUSE_ID)
    expect(listUrl.searchParams.get("scheduledDate")).toBe("2026-07-18")
    expect(listUrl.searchParams.get("page")).toBe("0")
    expect(listUrl.searchParams.get("size")).toBe("100")
    expect(detailUrl.pathname).toBe(
      `/api/logistics/v1/shipments/${DOCUMENT_ID}`
    )
    for (const call of fetchMock.mock.calls) {
      expect(new Headers(call[1].headers).get("Authorization")).toBe(
        "Bearer shipment-token"
      )
    }
  })

  it("accepts the canonical seed warehouse UUID in shipment projections", async () => {
    const seedWarehouseId = "00000000-0000-0000-0000-000000000001"
    const fetchMock = vi
      .fn()
      .mockResolvedValue(json([{ ...document, warehouseId: seedWarehouseId }]))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      new HttpShipmentClient().list("shipment-token", seedWarehouseId)
    ).resolves.toEqual([{ ...document, warehouseId: seedWarehouseId }])
  })

  it("keeps caller-owned create identity stable for duplicate submission", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(document, 201))
      .mockResolvedValueOnce(json(document, 201))
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpShipmentClient()
    const command = {
      accessToken: "shipment-token",
      warehouseId: WAREHOUSE_ID,
      clientId: CLIENT_ID,
      rentalOrderId: RENTAL_ORDER_ID,
      partySnapshot: document.partySnapshot,
      driverSnapshot: document.driverSnapshot!,
      driverWorkerId: DRIVER_WORKER_ID,
      lines: planLines,
      idempotencyKey: IDEMPOTENCY_KEY,
    }

    await client.create(command)
    await client.create(command)

    for (const call of fetchMock.mock.calls) {
      expect(call[0]).toBe(
        `${window.location.origin}/api/logistics/v1/shipments`
      )
      expect(call[1].method).toBe("POST")
      expect(new Headers(call[1].headers).get("Idempotency-Key")).toBe(
        IDEMPOTENCY_KEY
      )
      expect(JSON.parse(call[1].body)).toEqual({
        warehouseId: WAREHOUSE_ID,
        clientId: CLIENT_ID,
        rentalOrderId: RENTAL_ORDER_ID,
        partySnapshot: document.partySnapshot,
        driverSnapshot: document.driverSnapshot,
        driverWorkerId: DRIVER_WORKER_ID,
        lines: planLines,
      })
    }
  })

  it("assigns the driver and shipment date with CAS and idempotency", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        json({ ...document, version: 5, state: "PREPARING" }, 202)
      )
    vi.stubGlobal("fetch", fetchMock)

    await new HttpShipmentClient().replacePlan({
      accessToken: "shipment-token",
      documentId: DOCUMENT_ID,
      expectedVersion: 4,
      driverSnapshot: document.driverSnapshot!,
      driverWorkerId: DRIVER_WORKER_ID,
      scheduledDate: "2026-07-22",
      idempotencyKey: IDEMPOTENCY_KEY,
    })

    const [rawUrl, init] = fetchMock.mock.calls[0]
    const url = new URL(rawUrl)
    expect(url.pathname).toBe(`/api/logistics/v1/shipments/${DOCUMENT_ID}/plan`)
    expect(url.searchParams.get("expectedVersion")).toBe("4")
    expect(init.method).toBe("PUT")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(init.body)).toEqual({
      driverSnapshot: document.driverSnapshot,
      driverWorkerId: DRIVER_WORKER_ID,
      scheduledDate: "2026-07-22",
    })
  })

  it("reads the service-owned furniture readiness through the gateway", async () => {
    const response = {
      shipmentId: DOCUMENT_ID,
      shipmentVersion: 4,
      state: "AWAITING_TASK_COMPLETION",
      tasks: [
        {
          rentalItemId: ASSET_ID,
          unitNumber: "БЫТ-041",
          taskId: FURNITURE_TASK_ID,
          externalTaskId: EXTERNAL_FURNITURE_TASK_ID,
          taskBoardTaskId: null,
          taskState: "AWAITING_WORKER",
          lineCount: 2,
          movementTaskCreated: true,
          movementTaskCompleted: false,
          contentReady: false,
        },
      ],
    }
    const fetchMock = vi.fn().mockResolvedValue(json(response))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      new HttpShipmentClient().getFurnitureReadiness(
        "shipment-token",
        DOCUMENT_ID
      )
    ).resolves.toEqual(response)

    const [rawUrl, init] = fetchMock.mock.calls[0]
    expect(new URL(rawUrl).pathname).toBe(
      `/api/logistics/v1/shipments/${DOCUMENT_ID}/furniture-readiness`
    )
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer shipment-token"
    )
  })

  it.each([false, true])(
    "accepts readiness before a movement task exists (content ready: %s)",
    async (contentReady) => {
      const response = {
        shipmentId: DOCUMENT_ID,
        shipmentVersion: 4,
        state: contentReady ? "READY" : "REQUIRES_TASK_CREATION",
        tasks: [
          {
            rentalItemId: ASSET_ID,
            unitNumber: "БЫТ-041",
            taskId: null,
            externalTaskId: null,
            taskBoardTaskId: null,
            taskState: null,
            lineCount: 0,
            movementTaskCreated: false,
            movementTaskCompleted: false,
            contentReady,
          },
        ],
      }
      const fetchMock = vi.fn().mockResolvedValue(json(response))
      vi.stubGlobal("fetch", fetchMock)
      await expect(
        new HttpShipmentClient().getFurnitureReadiness(
          "shipment-token",
          DOCUMENT_ID
        )
      ).resolves.toEqual(response)
      for (const invalid of [
        { taskId: undefined },
        { taskState: "UNKNOWN" },
        { lineCount: -1 },
        { movementTaskCreated: undefined },
        { movementTaskCompleted: "false" },
        { contentReady: undefined },
      ]) {
        fetchMock.mockResolvedValue(
          json({ ...response, tasks: [{ ...response.tasks[0], ...invalid }] })
        )
        await expect(
          new HttpShipmentClient().getFurnitureReadiness(
            "shipment-token",
            DOCUMENT_ID
          )
        ).rejects.toThrow()
      }
    }
  )

  it("preserves the exact furniture frozen by an inventory shipment", async () => {
    const inventoryFurniture = [
      {
        equipmentId: EQUIPMENT_ID,
        catalogVersion: 7,
        quantity: 3,
      },
    ]
    const response = {
      ...document,
      rentalOrderId: null,
      lines: [
        {
          ...document.lines[0],
          rentalOrderId: null,
          inventoryShipmentFurniture: inventoryFurniture,
        },
      ],
    }
    const fetchMock = vi.fn().mockResolvedValue(json(response))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      new HttpShipmentClient().get("shipment-token", DOCUMENT_ID)
    ).resolves.toMatchObject({
      lines: [{ inventoryShipmentFurniture: inventoryFurniture }],
    })
  })

  it("confirms preparation with the current server version", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        json({ ...document, version: 6, state: "CONFIRMING_PREPARATION" }, 202)
      )
    vi.stubGlobal("fetch", fetchMock)

    await new HttpShipmentClient().confirmPreparation({
      accessToken: "shipment-token",
      documentId: DOCUMENT_ID,
      expectedVersion: 5,
      idempotencyKey: IDEMPOTENCY_KEY,
    })

    const [rawUrl, init] = fetchMock.mock.calls[0]
    const url = new URL(rawUrl)
    expect(url.pathname).toBe(
      `/api/logistics/v1/shipments/${DOCUMENT_ID}/confirm-preparation`
    )
    expect(url.searchParams.get("expectedVersion")).toBe("5")
    expect(url.searchParams.get("keepScheduledDate")).toBeNull()
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
  })

  it("passes an explicit keep-date decision to the service", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        json({ ...document, version: 6, state: "CONFIRMING_PREPARATION" }, 202)
      )
    vi.stubGlobal("fetch", fetchMock)

    await new HttpShipmentClient().confirmPreparation({
      accessToken: "shipment-token",
      documentId: DOCUMENT_ID,
      expectedVersion: 5,
      idempotencyKey: IDEMPOTENCY_KEY,
      keepScheduledDate: true,
    })

    const [rawUrl] = fetchMock.mock.calls[0]
    expect(new URL(rawUrl).searchParams.get("keepScheduledDate")).toBe("true")
  })

  it("cancels through the service-owned compensation workflow", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(json({ ...document, state: "CANCELLING" }, 202))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      new HttpShipmentClient().cancel({
        accessToken: "shipment-token",
        documentId: DOCUMENT_ID,
        expectedVersion: 4,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toMatchObject({ state: "CANCELLING" })

    const [rawUrl, init] = fetchMock.mock.calls[0]
    const url = new URL(rawUrl)
    expect(url.pathname).toBe(
      `/api/logistics/v1/shipments/${DOCUMENT_ID}/cancel`
    )
    expect(url.searchParams.get("expectedVersion")).toBe("4")
    expect(init.method).toBe("POST")
  })

  it("preserves 401, 403, 404 and 409 Problem Details", async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpShipmentClient()

    for (const status of [401, 403]) {
      fetchMock.mockResolvedValueOnce(
        json(
          {
            status,
            code: `SHIPMENT_${status}`,
            detail: `Ошибка ${status}`,
          },
          status
        )
      )
      await expect(
        client.list("shipment-token", WAREHOUSE_ID)
      ).rejects.toMatchObject({ status, message: `Ошибка ${status}` })
    }

    fetchMock.mockResolvedValueOnce(
      json(
        { status: 404, code: "SHIPMENT_NOT_FOUND", detail: "Не найдено" },
        404
      )
    )
    await expect(
      client.get("shipment-token", DOCUMENT_ID)
    ).rejects.toMatchObject({ status: 404, message: "Не найдено" })

    fetchMock.mockResolvedValueOnce(
      json(
        { status: 409, code: "SHIPMENT_VERSION", detail: "Версия устарела" },
        409
      )
    )
    await expect(
      client.cancel({
        accessToken: "shipment-token",
        documentId: DOCUMENT_ID,
        expectedVersion: 4,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).rejects.toMatchObject({ status: 409, message: "Версия устарела" })
  })

  it("rejects malformed shipment projections instead of synthesizing state", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json([{ ...document, documentType: "RETURN" }]))
      .mockResolvedValueOnce(json([{ ...document, partySnapshot: null }]))
      .mockResolvedValueOnce(json([{ ...document, state: "FINALIZING" }]))
      .mockResolvedValueOnce(
        json([{ ...document, customerDeliveryPurpose: null }])
      )
      .mockResolvedValueOnce(
        json([{ ...document, customerDeliveryPurpose: "TRANSFER" }])
      )
      .mockResolvedValueOnce(
        json([{ ...document, customerDeliveryPurpose: undefined }])
      )
      .mockResolvedValueOnce(json([{ ...document, lines: [] }]))
      .mockResolvedValueOnce(
        json([
          {
            ...document,
            lines: [
              {
                ...document.lines[0],
                inventoryShipmentFurniture: undefined,
              },
            ],
          },
        ])
      )
      .mockResolvedValueOnce(
        json([
          {
            ...document,
            lines: [
              {
                ...document.lines[0],
                inventorySourceWarehouseId: undefined,
              },
            ],
          },
        ])
      )
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpShipmentClient()

    for (let index = 0; index < 9; index += 1) {
      await expect(client.list("shipment-token", WAREHOUSE_ID)).rejects.toThrow(
        "Сервис логистики вернул некорректную отгрузку"
      )
    }
  })
})
