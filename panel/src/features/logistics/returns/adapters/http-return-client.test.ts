import { afterEach, describe, expect, it, vi } from "vitest"

import { HttpReturnClient } from "@/features/logistics/returns/adapters/http-return-client"
import type { ReturnDocument } from "@/features/logistics/returns/model"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DOCUMENT_ID = "22222222-2222-4222-8222-222222222222"
const LINE_ID = "33333333-3333-4333-8333-333333333333"
const ASSET_ID = "44444444-4444-4444-8444-444444444444"
const MEDIA_ID = "55555555-5555-4555-8555-555555555555"
const EQUIPMENT_ID = "66666666-6666-4666-8666-666666666666"
const IDEMPOTENCY_KEY = "77777777-7777-4777-8777-777777777777"
const CLIENT_ID = "88888888-8888-4888-8888-888888888888"
const RENTAL_ORDER_ID = "99999999-9999-4999-8999-999999999999"

const document: ReturnDocument = {
  id: DOCUMENT_ID,
  version: 4,
  documentType: "RETURN",
  state: "INSPECTION_REQUIRED",
  warehouseId: WAREHOUSE_ID,
  destinationWarehouseId: null,
  partySnapshot: null,
  driverSnapshot: "Иванов Иван",
  clientId: CLIENT_ID,
  equipmentMovementTaskId: null,
  scheduledDate: "2026-07-22",
  rentalOrderId: RENTAL_ORDER_ID,
  lines: [
    {
      id: LINE_ID,
      version: 2,
      lineNumber: 1,
      assetId: ASSET_ID,
      assetVersion: 8,
      state: "PENDING",
      tenantSnapshot: "ООО Тест",
      rentalOrderId: RENTAL_ORDER_ID,
    },
  ],
  createdAt: "2026-07-18T08:00:00Z",
  updatedAt: "2026-07-18T08:10:00Z",
}

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe("HttpReturnClient", () => {
  it("lists and gets canonical returns through the same-origin gateway", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json([document]))
      .mockResolvedValueOnce(json(document))
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpReturnClient()

    await expect(client.list("return-token", WAREHOUSE_ID)).resolves.toEqual([
      document,
    ])
    await expect(client.get("return-token", DOCUMENT_ID)).resolves.toEqual(
      document
    )

    const listUrl = new URL(fetchMock.mock.calls[0][0])
    const detailUrl = new URL(fetchMock.mock.calls[1][0])
    expect(listUrl.origin).toBe(window.location.origin)
    expect(listUrl.pathname).toBe("/api/logistics/v1/returns")
    expect(listUrl.searchParams.get("warehouseId")).toBe(WAREHOUSE_ID)
    expect(detailUrl.pathname).toBe(`/api/logistics/v1/returns/${DOCUMENT_ID}`)
    for (const call of fetchMock.mock.calls) {
      expect(new Headers(call[1].headers).get("Authorization")).toBe(
        "Bearer return-token"
      )
    }
  })

  it("keeps caller-owned create identity stable for duplicate submission", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        json({ ...document, version: 0, state: "DRAFT" }, 201)
      )
      .mockResolvedValueOnce(
        json({ ...document, version: 0, state: "DRAFT" }, 201)
      )
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpReturnClient()
    const command = {
      accessToken: "return-token",
      warehouseId: WAREHOUSE_ID,
      clientId: CLIENT_ID,
      driverSnapshot: "Иванов Иван",
      idempotencyKey: IDEMPOTENCY_KEY,
      lines: [
        {
          assetId: ASSET_ID,
          assetVersion: 8,
          tenantSnapshot: "ООО Тест",
          rentalOrderId: RENTAL_ORDER_ID,
        },
      ],
    }

    await client.create(command)
    await client.create(command)

    for (const call of fetchMock.mock.calls) {
      expect(call[0]).toBe(`${window.location.origin}/api/logistics/v1/returns`)
      expect(call[1].method).toBe("POST")
      expect(new Headers(call[1].headers).get("Idempotency-Key")).toBe(
        IDEMPOTENCY_KEY
      )
      expect(JSON.parse(call[1].body)).toEqual({
        warehouseId: WAREHOUSE_ID,
        clientId: CLIENT_ID,
        driverSnapshot: "Иванов Иван",
        lines: command.lines,
      })
    }
  })

  it("registers with the server version and idempotency key", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(json({ ...document, state: "REGISTERING" }, 202))
    vi.stubGlobal("fetch", fetchMock)

    await new HttpReturnClient().register({
      accessToken: "return-token",
      documentId: DOCUMENT_ID,
      expectedVersion: 4,
      driverSnapshot: "Иванов Иван",
      scheduledDate: "2026-07-22",
      idempotencyKey: IDEMPOTENCY_KEY,
    })

    const [rawUrl, init] = fetchMock.mock.calls[0]
    const url = new URL(rawUrl)
    expect(url.pathname).toBe(
      `/api/logistics/v1/returns/${DOCUMENT_ID}/register`
    )
    expect(url.searchParams.get("expectedVersion")).toBe("4")
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(init.body)).toEqual({
      driverSnapshot: "Иванов Иван",
      scheduledDate: "2026-07-22",
    })
  })

  it("sends proven media references for undamaged acceptance", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(json({ ...document, state: "ACCEPTING" }, 202))
    vi.stubGlobal("fetch", fetchMock)

    await new HttpReturnClient().acceptUndamaged({
      accessToken: "return-token",
      documentId: DOCUMENT_ID,
      expectedVersion: 4,
      idempotencyKey: IDEMPOTENCY_KEY,
      lines: [
        {
          lineId: LINE_ID,
          references: [{ mediaId: MEDIA_ID, generation: 3 }],
          equipmentConfirmed: true as const,
          additionalEquipment: [{ equipmentId: EQUIPMENT_ID, quantity: 2 }],
        },
      ],
    })

    const [rawUrl, init] = fetchMock.mock.calls[0]
    const url = new URL(rawUrl)
    expect(url.pathname).toBe(
      `/api/logistics/v1/returns/${DOCUMENT_ID}/accept-undamaged`
    )
    expect(url.searchParams.get("expectedVersion")).toBe("4")
    expect(JSON.parse(init.body)).toEqual({
      lines: [
        {
          lineId: LINE_ID,
          references: [{ mediaId: MEDIA_ID, generation: 3 }],
          equipmentConfirmed: true,
          additionalEquipment: [{ equipmentId: EQUIPMENT_ID, quantity: 2 }],
        },
      ],
    })
  })

  it("requests an estimate with canonical equipment shortages", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(json({ ...document, state: "ESTIMATE_PENDING" }, 202))
    vi.stubGlobal("fetch", fetchMock)

    await new HttpReturnClient().requestEstimate({
      accessToken: "return-token",
      documentId: DOCUMENT_ID,
      expectedVersion: 4,
      idempotencyKey: IDEMPOTENCY_KEY,
      lines: [
        {
          lineId: LINE_ID,
          references: [{ mediaId: MEDIA_ID, generation: 3 }],
          shortages: [{ equipmentId: EQUIPMENT_ID, missingQuantity: 2 }],
        },
      ],
    })

    const [rawUrl, init] = fetchMock.mock.calls[0]
    const url = new URL(rawUrl)
    expect(url.pathname).toBe(
      `/api/logistics/v1/returns/${DOCUMENT_ID}/request-estimate`
    )
    expect(url.searchParams.get("expectedVersion")).toBe("4")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(init.body)).toEqual({
      lines: [
        {
          lineId: LINE_ID,
          references: [{ mediaId: MEDIA_ID, generation: 3 }],
          shortages: [{ equipmentId: EQUIPMENT_ID, missingQuantity: 2 }],
        },
      ],
    })
  })

  it("preserves 401, 403, 404 and 409 Problem Details", async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpReturnClient()

    for (const status of [401, 403]) {
      fetchMock.mockResolvedValueOnce(
        json(
          { status, code: `RETURN_${status}`, detail: `Ошибка ${status}` },
          status
        )
      )
      await expect(
        client.list("return-token", WAREHOUSE_ID)
      ).rejects.toMatchObject({
        status,
        message: `Ошибка ${status}`,
      })
    }

    fetchMock.mockResolvedValueOnce(
      json({ status: 404, code: "RETURN_NOT_FOUND", detail: "Не найден" }, 404)
    )
    await expect(client.get("return-token", DOCUMENT_ID)).rejects.toMatchObject(
      { status: 404, message: "Не найден" }
    )

    fetchMock.mockResolvedValueOnce(
      json(
        { status: 409, code: "RETURN_VERSION", detail: "Версия устарела" },
        409
      )
    )
    await expect(
      client.register({
        accessToken: "return-token",
        documentId: DOCUMENT_ID,
        expectedVersion: 4,
        driverSnapshot: "Иванов Иван",
        scheduledDate: "2026-07-22",
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).rejects.toMatchObject({ status: 409, message: "Версия устарела" })
  })

  it("rejects malformed return projections instead of synthesizing state", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json([{ ...document, documentType: "SHIPMENT" }]))
      .mockResolvedValueOnce(json([{ ...document, version: "4" }]))
      .mockResolvedValueOnce(json([{ ...document, lines: [] }]))
      .mockResolvedValueOnce(
        json([
          {
            ...document,
            lines: [{ ...document.lines[0], lineNumber: 0 }],
          },
        ])
      )
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpReturnClient()

    await expect(client.list("return-token", WAREHOUSE_ID)).rejects.toThrow(
      "Сервис логистики вернул некорректный ответ"
    )
    await expect(client.list("return-token", WAREHOUSE_ID)).rejects.toThrow(
      "Сервис логистики вернул некорректный ответ"
    )
    await expect(client.list("return-token", WAREHOUSE_ID)).rejects.toThrow(
      "Сервис логистики вернул некорректный ответ"
    )
    await expect(client.list("return-token", WAREHOUSE_ID)).rejects.toThrow(
      "Сервис логистики вернул некорректный ответ"
    )
  })
})
