import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import {
  getLogisticsDocumentHistory,
  type LogisticsDocumentHistory,
  type LogisticsHistoryReference,
} from "./document-history-api"

const id = (value: number) =>
  `00000000-0000-0000-0000-${String(value).padStart(12, "0")}`
const reference: LogisticsHistoryReference = {
  documentId: id(1),
  warehouseId: id(2),
  documentType: "RETURN",
}
function page(): LogisticsDocumentHistory {
  return {
    ...reference,
    documentVersion: 4,
    nextAfterVersion: 0,
    lines: [
      {
        lineId: id(3),
        assetId: id(4),
        contentsBeforeOperation: null,
        contentsAfterRegistration: { contents: [] },
        returnAcceptance: {
          equipmentConfirmed: true,
          additionalEquipment: [{ equipmentId: id(8), quantity: 2 }],
        },
        inventoryShipmentFurniture: null,
      },
    ],
    events: [
      {
        eventId: id(5),
        aggregateVersion: 0,
        eventType: "logistics.return.created.v1",
        occurredAt: null,
        recordedAt: "2026-09-05T10:00:00Z",
        baseline: true,
        recordedActor: { subjectId: id(6), principalType: "USER" },
        state: "DRAFT",
        resultCode: null,
      },
    ],
  }
}
const fetchMock = vi.fn()
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal("fetch", fetchMock)
})
afterEach(() => vi.unstubAllGlobals())

describe("document history boundary", () => {
  it("uses the same-origin authorized owner endpoint and retains unknown versus recorded empty evidence", async () => {
    const payload = page()
    fetchMock.mockResolvedValue(Response.json(payload))
    await expect(
      getLogisticsDocumentHistory("token", reference)
    ).resolves.toEqual(payload)
    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit]
    expect(url.origin).toBe(window.location.origin)
    expect(url.pathname).toBe(`/api/logistics/v1/returns/${id(1)}/history`)
    expect(url.searchParams.get("afterVersion")).toBe("-1")
    expect(url.searchParams.get("size")).toBe("50")
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer token")
  })
  it("supports shipment history with its own identity and a strict exclusive cursor", async () => {
    const payload = page()
    const shipment = { ...reference, documentType: "SHIPMENT" as const }
    fetchMock.mockResolvedValue(
      Response.json({
        ...payload,
        ...shipment,
        nextAfterVersion: null,
        events: [
          {
            ...payload.events[0],
            aggregateVersion: 4,
            eventType: "logistics.shipment.preparation-confirmed.v1",
            baseline: false,
            occurredAt: "2026-09-05T10:00:00Z",
            state: "SHIPPED",
          },
        ],
      })
    )
    const signal = new AbortController().signal
    const result = await getLogisticsDocumentHistory(
      "token",
      shipment,
      2,
      signal
    )
    expect(result.events[0].aggregateVersion).toBe(4)
    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit]
    expect(url.pathname).toBe(`/api/logistics/v1/shipments/${id(1)}/history`)
    expect(url.searchParams.get("afterVersion")).toBe("2")
    expect(init.signal).toBe(signal)
  })
  it.each([
    (payload: LogisticsDocumentHistory) => ({
      ...payload,
      warehouseId: id(20),
    }),
    (payload: LogisticsDocumentHistory) => ({ ...payload, documentId: id(20) }),
    (payload: LogisticsDocumentHistory) => ({
      ...payload,
      documentType: "SHIPMENT",
    }),
    (payload: LogisticsDocumentHistory) => ({
      ...payload,
      nextAfterVersion: 2,
    }),
    (payload: LogisticsDocumentHistory) => ({
      ...payload,
      documentVersion: -1,
    }),
    (payload: LogisticsDocumentHistory) => ({
      ...payload,
      events: [payload.events[0], payload.events[0]],
    }),
    (payload: LogisticsDocumentHistory) => ({
      ...payload,
      events: [{ ...payload.events[0], aggregateVersion: 5 }],
    }),
    (payload: LogisticsDocumentHistory) => ({
      ...payload,
      events: [
        { ...payload.events[0], eventType: "logistics.shipment.created.v1" },
      ],
    }),
    (payload: LogisticsDocumentHistory) => ({
      ...payload,
      events: [{ ...payload.events[0], occurredAt: "2026-09-05T10:00:00Z" }],
    }),
    (payload: LogisticsDocumentHistory) => ({
      ...payload,
      lines: [{ ...payload.lines[0], contentsBeforeOperation: {} }],
    }),
    (payload: LogisticsDocumentHistory) => ({
      ...payload,
      lines: [
        {
          ...payload.lines[0],
          returnAcceptance: {
            equipmentConfirmed: false,
            additionalEquipment: [],
          },
        },
      ],
    }),
    (payload: LogisticsDocumentHistory) => ({
      ...payload,
      lines: [
        {
          ...payload.lines[0],
          contentsBeforeOperation: {
            contents: [{ equipmentId: id(8), quantity: -1 }],
          },
        },
      ],
    }),
  ])(
    "rejects inconsistent identities, cursors and evidence",
    async (change) => {
      fetchMock.mockResolvedValue(Response.json(change(page())))
      await expect(
        getLogisticsDocumentHistory("token", reference)
      ).rejects.toMatchObject({ code: "INVALID_API_RESPONSE" })
    }
  )
  it("does not accept the current cursor as a later event", async () => {
    fetchMock.mockResolvedValue(Response.json(page()))
    await expect(
      getLogisticsDocumentHistory("token", reference, 0)
    ).rejects.toMatchObject({ code: "INVALID_API_RESPONSE" })
  })
  it("preserves access failures and never returns fake empty history", async () => {
    fetchMock.mockResolvedValue(
      Response.json({ detail: "Нет доступа к складу" }, { status: 403 })
    )
    await expect(
      getLogisticsDocumentHistory("token", reference)
    ).rejects.toMatchObject({ status: 403 })
  })
  it("does not request history without a token", async () => {
    await expect(getLogisticsDocumentHistory("", reference)).rejects.toThrow(
      "токен"
    )
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
