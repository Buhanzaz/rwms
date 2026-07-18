import { afterEach, describe, expect, it, vi } from "vitest"

import { HttpWarehouseTransferClient } from "@/features/logistics/warehouse-transfers/adapters/http-warehouse-transfer-client"
import type { TransferDocument } from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"

const SOURCE_WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DESTINATION_WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const DOCUMENT_ID = "33333333-3333-4333-8333-333333333333"
const LINE_ID = "44444444-4444-4444-8444-444444444444"
const ASSET_ID = "55555555-5555-4555-8555-555555555555"
const MEDIA_ID = "66666666-6666-4666-8666-666666666666"
const IDEMPOTENCY_KEY = "77777777-7777-4777-8777-777777777777"

const document: TransferDocument = {
  id: DOCUMENT_ID,
  version: 4,
  documentType: "TRANSFER",
  state: "DRAFT",
  warehouseId: SOURCE_WAREHOUSE_ID,
  destinationWarehouseId: DESTINATION_WAREHOUSE_ID,
  partySnapshot: null,
  driverSnapshot: null,
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

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

function commandInit(call: unknown[]) {
  return call[1] as RequestInit
}

afterEach(() => vi.unstubAllGlobals())

describe("HttpWarehouseTransferClient", () => {
  it("lists and gets canonical transfers through the same-origin gateway", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json([document]))
      .mockResolvedValueOnce(json(document))
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpWarehouseTransferClient()

    await expect(
      client.list("transfer-token", SOURCE_WAREHOUSE_ID)
    ).resolves.toEqual([document])
    await expect(client.get("transfer-token", DOCUMENT_ID)).resolves.toEqual(
      document
    )

    const listUrl = new URL(fetchMock.mock.calls[0][0])
    const detailUrl = new URL(fetchMock.mock.calls[1][0])
    expect(listUrl.origin).toBe(window.location.origin)
    expect(listUrl.pathname).toBe("/api/logistics/v1/transfers")
    expect(listUrl.searchParams.get("warehouseId")).toBe(SOURCE_WAREHOUSE_ID)
    expect(detailUrl.pathname).toBe(
      `/api/logistics/v1/transfers/${DOCUMENT_ID}`
    )
    for (const call of fetchMock.mock.calls) {
      expect(new Headers(commandInit(call).headers).get("Authorization")).toBe(
        "Bearer transfer-token"
      )
    }
  })

  it("keeps the caller-owned create identity stable for duplicate submission", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(document, 201))
      .mockResolvedValueOnce(json(document, 201))
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpWarehouseTransferClient()
    const command = {
      accessToken: "transfer-token",
      warehouseId: SOURCE_WAREHOUSE_ID,
      destinationWarehouseId: DESTINATION_WAREHOUSE_ID,
      idempotencyKey: IDEMPOTENCY_KEY,
      lines: [{ assetId: ASSET_ID, assetVersion: 8 }],
    }

    await client.create(command)
    await client.create(command)

    for (const call of fetchMock.mock.calls) {
      const [rawUrl] = call
      const init = commandInit(call)
      expect(rawUrl).toBe(
        `${window.location.origin}/api/logistics/v1/transfers`
      )
      expect(init.method).toBe("POST")
      expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
        IDEMPOTENCY_KEY
      )
      expect(JSON.parse(init.body as string)).toEqual({
        warehouseId: SOURCE_WAREHOUSE_ID,
        destinationWarehouseId: DESTINATION_WAREHOUSE_ID,
        lines: command.lines,
      })
    }
  })

  it("departs a line with document and line CAS versions", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(json({ ...document, state: "DEPARTING" }, 202))
    vi.stubGlobal("fetch", fetchMock)

    await new HttpWarehouseTransferClient().depart({
      accessToken: "transfer-token",
      documentId: DOCUMENT_ID,
      lineId: LINE_ID,
      expectedVersion: 4,
      expectedLineVersion: 2,
      idempotencyKey: IDEMPOTENCY_KEY,
    })

    const [rawUrl] = fetchMock.mock.calls[0]
    const init = commandInit(fetchMock.mock.calls[0])
    const url = new URL(rawUrl)
    expect(url.pathname).toBe(
      `/api/logistics/v1/transfers/${DOCUMENT_ID}/lines/${LINE_ID}/depart`
    )
    expect(url.searchParams.get("expectedVersion")).toBe("4")
    expect(url.searchParams.get("expectedLineVersion")).toBe("2")
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
  })

  it("arrives a line only with proven media references and both versions", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(json({ ...document, state: "ARRIVING" }, 202))
    vi.stubGlobal("fetch", fetchMock)

    await new HttpWarehouseTransferClient().arrive({
      accessToken: "transfer-token",
      documentId: DOCUMENT_ID,
      lineId: LINE_ID,
      expectedVersion: 7,
      expectedLineVersion: 5,
      idempotencyKey: IDEMPOTENCY_KEY,
      references: [{ mediaId: MEDIA_ID, generation: 3 }],
    })

    const [rawUrl] = fetchMock.mock.calls[0]
    const init = commandInit(fetchMock.mock.calls[0])
    const url = new URL(rawUrl)
    expect(url.pathname).toBe(
      `/api/logistics/v1/transfers/${DOCUMENT_ID}/lines/${LINE_ID}/arrive`
    )
    expect(url.searchParams.get("expectedVersion")).toBe("7")
    expect(url.searchParams.get("expectedLineVersion")).toBe("5")
    expect(JSON.parse(init.body as string)).toEqual({
      references: [{ mediaId: MEDIA_ID, generation: 3 }],
    })
  })

  it("cancels and reconciles the whole document with server CAS", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json({ ...document, state: "CANCELLED" }, 202))
      .mockResolvedValueOnce(json({ ...document, state: "DEPARTING" }, 202))
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpWarehouseTransferClient()

    await client.cancel({
      accessToken: "transfer-token",
      documentId: DOCUMENT_ID,
      expectedVersion: 4,
      idempotencyKey: IDEMPOTENCY_KEY,
    })
    await client.reconcile({
      accessToken: "transfer-token",
      documentId: DOCUMENT_ID,
      expectedVersion: 6,
      idempotencyKey: IDEMPOTENCY_KEY,
      reason: "Повторная проверка зависшего эффекта",
    })

    const cancelUrl = new URL(fetchMock.mock.calls[0][0])
    const reconcileUrl = new URL(fetchMock.mock.calls[1][0])
    expect(cancelUrl.pathname).toBe(
      `/api/logistics/v1/transfers/${DOCUMENT_ID}/cancel`
    )
    expect(cancelUrl.searchParams.get("expectedVersion")).toBe("4")
    expect(reconcileUrl.pathname).toBe(
      `/api/logistics/v1/transfers/${DOCUMENT_ID}/reconcile`
    )
    expect(reconcileUrl.searchParams.get("expectedVersion")).toBe("6")
    expect(
      JSON.parse(commandInit(fetchMock.mock.calls[1]).body as string)
    ).toEqual({ reason: "Повторная проверка зависшего эффекта" })
  })

  it("preserves 401, 403, 404 and 409 Problem Details", async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpWarehouseTransferClient()

    for (const status of [401, 403]) {
      fetchMock.mockResolvedValueOnce(
        json(
          { status, code: `TRANSFER_${status}`, detail: `Ошибка ${status}` },
          status
        )
      )
      await expect(
        client.list("transfer-token", SOURCE_WAREHOUSE_ID)
      ).rejects.toMatchObject({ status, message: `Ошибка ${status}` })
    }

    fetchMock.mockResolvedValueOnce(
      json(
        { status: 404, code: "TRANSFER_NOT_FOUND", detail: "Не найден" },
        404
      )
    )
    await expect(
      client.get("transfer-token", DOCUMENT_ID)
    ).rejects.toMatchObject({ status: 404, message: "Не найден" })

    fetchMock.mockResolvedValueOnce(
      json(
        { status: 409, code: "TRANSFER_VERSION", detail: "Версия устарела" },
        409
      )
    )
    await expect(
      client.depart({
        accessToken: "transfer-token",
        documentId: DOCUMENT_ID,
        lineId: LINE_ID,
        expectedVersion: 4,
        expectedLineVersion: 2,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).rejects.toMatchObject({ status: 409, message: "Версия устарела" })
  })

  it("rejects malformed transfer projections instead of synthesizing state", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json([{ ...document, documentType: "SHIPMENT" }]))
      .mockResolvedValueOnce(json([{ ...document, version: "4" }]))
      .mockResolvedValueOnce(json([{ ...document, lines: [] }]))
      .mockResolvedValueOnce(json([{ ...document, unexpected: true }]))
      .mockResolvedValueOnce(
        json([
          {
            ...document,
            lines: [{ ...document.lines[0], lineNumber: 0 }],
          },
        ])
      )
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpWarehouseTransferClient()

    for (let index = 0; index < 5; index += 1) {
      await expect(
        client.list("transfer-token", SOURCE_WAREHOUSE_ID)
      ).rejects.toThrow("Сервис логистики вернул некорректный ответ")
    }
  })
})
