import { afterEach, describe, expect, it, vi } from "vitest"

import { HttpShipmentClient } from "@/features/logistics/shipments/adapters/http-shipment-client"
import type { ShipmentDocument } from "@/features/logistics/shipments/model"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DOCUMENT_ID = "22222222-2222-4222-8222-222222222222"
const LINE_ID = "33333333-3333-4333-8333-333333333333"
const ASSET_ID = "44444444-4444-4444-8444-444444444444"
const EQUIPMENT_ID = "55555555-5555-4555-8555-555555555555"
const IDEMPOTENCY_KEY = "66666666-6666-4666-8666-666666666666"

const document: ShipmentDocument = {
  id: DOCUMENT_ID,
  version: 4,
  documentType: "SHIPMENT",
  state: "DRAFT",
  warehouseId: WAREHOUSE_ID,
  destinationWarehouseId: null,
  partySnapshot: "ООО Тест",
  driverSnapshot: "Иванов Иван",
  lines: [
    {
      id: LINE_ID,
      version: 2,
      lineNumber: 1,
      assetId: ASSET_ID,
      assetVersion: 8,
      state: "PENDING",
      tenantSnapshot: null,
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
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe("HttpShipmentClient", () => {
  it("lists and gets canonical shipments through the same-origin gateway", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json([document]))
      .mockResolvedValueOnce(json(document))
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpShipmentClient()

    await expect(client.list("shipment-token", WAREHOUSE_ID)).resolves.toEqual([
      document,
    ])
    await expect(client.get("shipment-token", DOCUMENT_ID)).resolves.toEqual(
      document
    )

    const listUrl = new URL(fetchMock.mock.calls[0][0])
    const detailUrl = new URL(fetchMock.mock.calls[1][0])
    expect(listUrl.origin).toBe(window.location.origin)
    expect(listUrl.pathname).toBe("/api/logistics/v1/shipments")
    expect(listUrl.searchParams.get("warehouseId")).toBe(WAREHOUSE_ID)
    expect(detailUrl.pathname).toBe(
      `/api/logistics/v1/shipments/${DOCUMENT_ID}`
    )
    for (const call of fetchMock.mock.calls) {
      expect(new Headers(call[1].headers).get("Authorization")).toBe(
        "Bearer shipment-token"
      )
    }
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
      partySnapshot: document.partySnapshot,
      driverSnapshot: document.driverSnapshot,
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
        partySnapshot: document.partySnapshot,
        driverSnapshot: document.driverSnapshot,
        lines: planLines,
      })
    }
  })

  it("repeats the immutable plan with CAS and idempotency", async () => {
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
      partySnapshot: document.partySnapshot,
      driverSnapshot: document.driverSnapshot,
      lines: planLines,
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
      partySnapshot: document.partySnapshot,
      driverSnapshot: document.driverSnapshot,
      lines: planLines,
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
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
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
      .mockResolvedValueOnce(json([{ ...document, lines: [] }]))
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpShipmentClient()

    for (let index = 0; index < 4; index += 1) {
      await expect(client.list("shipment-token", WAREHOUSE_ID)).rejects.toThrow(
        "Сервис логистики вернул некорректную отгрузку"
      )
    }
  })
})
