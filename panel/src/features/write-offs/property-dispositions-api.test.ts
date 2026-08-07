import { afterEach, describe, expect, it, vi } from "vitest"

import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

import {
  createPropertyDisposition,
  listPropertyDispositions,
  writeOffRepairDisposition,
} from "./property-dispositions-api"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DECISION_ID = "22222222-2222-4222-8222-222222222222"
const ASSET_ID = "33333333-3333-4333-8333-333333333333"
const REPAIR_ID = "44444444-4444-4444-8444-444444444444"
const EQUIPMENT_ID = "55555555-5555-4555-8555-555555555555"
const IDEMPOTENCY_KEY = "66666666-6666-4666-8666-666666666666"

function response(overrides: Record<string, unknown> = {}) {
  return {
    id: DECISION_ID,
    version: 1,
    recoveryVersion: 0,
    warehouseId: WAREHOUSE_ID,
    assetKind: "CABIN",
    assetId: ASSET_ID,
    assetDisplayName: "БЫТ-1",
    disposition: "WRITE_OFF",
    source: "MANUAL",
    state: "PENDING_APPROVAL",
    reason: "Причина",
    evidenceLink: null,
    quantity: null,
    expectedAssetVersion: 3,
    expectedSourceBalanceVersion: null,
    contentsPlan: null,
    rootRepairId: null,
    repairChain: [],
    inventorySessionId: null,
    findingId: null,
    assetEffectState: "NOT_STARTED",
    movementTaskId: null,
    failureCode: null,
    failureDetail: null,
    requestedBy: { actorId: ASSET_ID, actorType: "USER" },
    requestedAt: "2026-08-05T10:00:00Z",
    reviewedBy: null,
    reviewedAt: null,
    reviewComment: null,
    effectiveAt: null,
    updatedAt: "2026-08-05T10:00:00Z",
    ...overrides,
  }
}

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe("property dispositions HTTP client", () => {
  it("sends a complete cabin contents plan to the maintenance owner", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(response(), 201))
    vi.stubGlobal("fetch", fetchMock)
    await createPropertyDisposition({
      accessToken: "token",
      idempotencyKey: IDEMPOTENCY_KEY,
      request: {
        warehouseId: WAREHOUSE_ID,
        assetKind: "CABIN",
        assetId: ASSET_ID,
        expectedAssetVersion: 3,
        disposition: "WRITE_OFF",
        reason: "Причина",
        evidenceLink: null,
        contentsPlan: {
          mode: "MOVE_SELECTED_TO_STOCK",
          lines: [
            {
              equipmentId: EQUIPMENT_ID,
              expectedBalanceVersion: 7,
              moveToStockQuantity: 2,
            },
          ],
        },
      },
    })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(
      `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1/dispositions`
    )
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init.body))).toEqual(
      expect.objectContaining({
        assetKind: "CABIN",
        expectedAssetVersion: 3,
        contentsPlan: {
          mode: "MOVE_SELECTED_TO_STOCK",
          lines: [
            {
              equipmentId: EQUIPMENT_ID,
              expectedBalanceVersion: 7,
              moveToStockQuantity: 2,
            },
          ],
        },
      })
    )
  })

  it("sends null contentsPlan from a repair when the cabin is empty", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        json(response({ source: "REPAIR", rootRepairId: REPAIR_ID }), 201)
      )
    vi.stubGlobal("fetch", fetchMock)
    await writeOffRepairDisposition({
      accessToken: "token",
      warehouseId: WAREHOUSE_ID,
      repairId: REPAIR_ID,
      expectedVersion: 4,
      reason: "Не ремонтируется",
      comment: null,
      contentsPlan: null,
      idempotencyKey: IDEMPOTENCY_KEY,
    })
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(String(init.body))).toEqual({
      expectedVersion: 4,
      reason: "Не ремонтируется",
      comment: null,
      contentsPlan: null,
    })
  })

  it("uses server paging and state filtering for loss decisions", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      json({
        items: [response({ disposition: "LOSS", state: "QUARANTINED" })],
        page: 2,
        size: 25,
        totalElements: 70,
      })
    )
    vi.stubGlobal("fetch", fetchMock)
    const page = await listPropertyDispositions({
      accessToken: "token",
      warehouseId: WAREHOUSE_ID,
      disposition: "LOSS",
      page: 2,
      size: 25,
      state: "QUARANTINED",
    })
    const [url] = fetchMock.mock.calls[0] as [string]
    expect(String(url)).toContain("/v1/losses?")
    expect(String(url)).toContain("page=2")
    expect(String(url)).toContain("size=25")
    expect(String(url)).toContain("state=QUARANTINED")
    expect(page).toMatchObject({ page: 2, size: 25, totalElements: 70 })
  })

  it("accepts an unaccounted loss without a warehouse stock effect", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      json({
        items: [
          response({
            disposition: "LOSS",
            source: "UNACCOUNTED",
            state: "EFFECTIVE",
            assetEffectState: "NOT_REQUIRED",
          }),
        ],
        page: 0,
        size: 25,
        totalElements: 1,
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    const page = await listPropertyDispositions({
      accessToken: "token",
      warehouseId: WAREHOUSE_ID,
      disposition: "LOSS",
      page: 0,
      size: 25,
      state: null,
    })

    expect(page.items[0]).toMatchObject({
      source: "UNACCOUNTED",
      assetEffectState: "NOT_REQUIRED",
    })
  })
})
