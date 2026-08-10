import { afterEach, describe, expect, it, vi } from "vitest"

import {
  HttpWarehouseTransferClient,
  parseTransferArrivalPreflight,
  parseTransferDocument,
} from "@/features/logistics/warehouse-transfers/adapters/http-warehouse-transfer-client"
import type { TransferDocument } from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"

const SOURCE_WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DESTINATION_WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const DOCUMENT_ID = "33333333-3333-4333-8333-333333333333"
const LINE_ID = "44444444-4444-4444-8444-444444444444"
const ASSET_ID = "55555555-5555-4555-8555-555555555555"
const MEDIA_ID = "66666666-6666-4666-8666-666666666666"
const IDEMPOTENCY_KEY = "77777777-7777-4777-8777-777777777777"
const EQUIPMENT_ID = "88888888-8888-4888-8888-888888888888"
const EQUIPMENT_TASK_ID = "99999999-9999-4999-8999-999999999999"
const FURNITURE_TASK_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const EXTERNAL_FURNITURE_TASK_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
const TASK_BOARD_TASK_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

const document: TransferDocument = {
  id: DOCUMENT_ID,
  version: 4,
  documentType: "TRANSFER",
  state: "DRAFT",
  warehouseId: SOURCE_WAREHOUSE_ID,
  destinationWarehouseId: DESTINATION_WAREHOUSE_ID,
  partySnapshot: null,
  clientId: null,
  equipmentMovementTaskId: EQUIPMENT_TASK_ID,
  scheduledDate: "2026-07-19",
  scheduledAt: null,
  rentalOrderId: null,
  lines: [
    {
      id: LINE_ID,
      version: 2,
      lineNumber: 1,
      assetId: ASSET_ID,
      assetVersion: 8,
      state: "PENDING",
      tenantSnapshot: null,
      rentalOrderId: null,
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
  it("accepts the real transfer shape with document-level rentalOrderId: null", () => {
    expect(
      parseTransferDocument({
        ...document,
        rentalOrderId: null,
      })
    ).toEqual(document)
  })

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

  it("reads strict furniture readiness through the same-origin gateway", async () => {
    const readiness = {
      transferId: DOCUMENT_ID,
      transferVersion: 4,
      state: "AWAITING_TASK_COMPLETION" as const,
      tasks: [
        {
          rentalItemId: ASSET_ID,
          unitNumber: "БЫТ-041",
          taskId: FURNITURE_TASK_ID,
          externalTaskId: EXTERNAL_FURNITURE_TASK_ID,
          taskBoardTaskId: TASK_BOARD_TASK_ID,
          taskState: "AWAITING_WORKER" as const,
          lineCount: 2,
        },
      ],
    }
    const fetchMock = vi.fn().mockResolvedValue(json(readiness))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      new HttpWarehouseTransferClient().getFurnitureReadiness(
        "transfer-token",
        DOCUMENT_ID
      )
    ).resolves.toEqual(readiness)

    const [rawUrl, init] = fetchMock.mock.calls[0]
    expect(new URL(rawUrl).pathname).toBe(
      `/api/logistics/v1/transfers/${DOCUMENT_ID}/furniture-readiness`
    )
    expect(
      new Headers(commandInit([rawUrl, init]).headers).get("Authorization")
    ).toBe("Bearer transfer-token")
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
      scheduledDate: "2026-07-19",
      idempotencyKey: IDEMPOTENCY_KEY,
      lines: [{ assetId: ASSET_ID, assetVersion: 8 }],
      furnitureReplacements: [
        {
          assetId: ASSET_ID,
          contents: [{ equipmentId: EQUIPMENT_ID, quantity: 2 }],
        },
      ],
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
        scheduledDate: command.scheduledDate,
        lines: command.lines,
        furnitureReplacements: command.furnitureReplacements,
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
      priority: 2,
    })

    const [rawUrl] = fetchMock.mock.calls[0]
    const init = commandInit(fetchMock.mock.calls[0])
    const url = new URL(rawUrl)
    expect(url.pathname).toBe(
      `/api/logistics/v1/transfers/${DOCUMENT_ID}/lines/${LINE_ID}/arrive`
    )
    expect(url.searchParams.get("expectedVersion")).toBe("7")
    expect(url.searchParams.get("expectedLineVersion")).toBe("5")
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(init.body as string)).toEqual({
      references: [{ mediaId: MEDIA_ID, generation: 3 }],
      priority: 2,
    })
  })

  it("reads and validates the repair continuation preflight", async () => {
    const activeRepairId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    const response = {
      transferId: DOCUMENT_ID,
      lineId: LINE_ID,
      activeRepairId,
      priorityRequired: true,
      missingQueueDefinitionIds: [],
    }
    const fetchMock = vi.fn().mockResolvedValue(json(response))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      new HttpWarehouseTransferClient().getArrivalPreflight({
        accessToken: "transfer-token",
        documentId: DOCUMENT_ID,
        lineId: LINE_ID,
        expectedVersion: 7,
        expectedLineVersion: 5,
      })
    ).resolves.toEqual(response)
    expect(parseTransferArrivalPreflight(response)).toEqual(response)

    const url = new URL(fetchMock.mock.calls[0][0])
    expect(url.pathname).toBe(
      `/api/logistics/v1/transfers/${DOCUMENT_ID}/lines/${LINE_ID}/arrival-preflight`
    )
    expect(url.searchParams.get("expectedVersion")).toBe("7")
    expect(url.searchParams.get("expectedLineVersion")).toBe("5")
  })

  it("preserves arrival conflicts as 409 Problem Details", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      json(
        {
          status: 409,
          code: "TRANSFER_LINE_VERSION",
          detail: "Версия строки устарела",
        },
        409
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      new HttpWarehouseTransferClient().arrive({
        accessToken: "transfer-token",
        documentId: DOCUMENT_ID,
        lineId: LINE_ID,
        expectedVersion: 7,
        expectedLineVersion: 5,
        idempotencyKey: IDEMPOTENCY_KEY,
        references: [{ mediaId: MEDIA_ID, generation: 3 }],
        priority: null,
      })
    ).rejects.toMatchObject({
      status: 409,
      message: "Версия строки устарела",
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
      .mockResolvedValueOnce(
        json([{ ...document, rentalOrderId: EQUIPMENT_TASK_ID }])
      )
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpWarehouseTransferClient()

    for (let index = 0; index < 6; index += 1) {
      await expect(
        client.list("transfer-token", SOURCE_WAREHOUSE_ID)
      ).rejects.toThrow("Сервис логистики вернул некорректный ответ")
    }
  })

  it("rejects malformed or mismatched furniture readiness", async () => {
    const readiness = {
      transferId: DOCUMENT_ID,
      transferVersion: 4,
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
        },
      ],
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        json({
          ...readiness,
          tasks: [{ ...readiness.tasks[0], lineCount: 0 }],
        })
      )
      .mockResolvedValueOnce(
        json({
          ...readiness,
          transferId: FURNITURE_TASK_ID,
        })
      )
    vi.stubGlobal("fetch", fetchMock)
    const client = new HttpWarehouseTransferClient()

    await expect(
      client.getFurnitureReadiness("transfer-token", DOCUMENT_ID)
    ).rejects.toThrow("Сервис логистики вернул некорректный ответ")
    await expect(
      client.getFurnitureReadiness("transfer-token", DOCUMENT_ID)
    ).rejects.toThrow("Сервис логистики вернул некорректный ответ")
  })
})
