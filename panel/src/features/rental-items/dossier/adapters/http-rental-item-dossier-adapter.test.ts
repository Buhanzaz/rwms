import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

vi.mock("@/lib/gateway-config", () => ({
  getGatewayRuntimeConfig: () => ({
    dossierApiBaseUrl: "https://gateway.example.test/api/dossier",
  }),
}))

import { getRentalItemDossierPage } from "@/features/rental-items/dossier/api/rental-item-dossier-api"
import { ApiError } from "@/lib/api-client"

const CABIN_ID = "10000000-0000-0000-0000-000000000001"
const WAREHOUSE_ID = "20000000-0000-0000-0000-000000000001"
const ACTIVITY_ID = "30000000-0000-0000-0000-000000000001"
const ACTOR_ID = "40000000-0000-0000-0000-000000000001"
const SOURCE_ID = "50000000-0000-0000-0000-000000000001"
const SECONDARY_ID = "60000000-0000-0000-0000-000000000001"
const MEDIA_ID = "70000000-0000-0000-0000-000000000001"
const FINDING_ID = "80000000-0000-0000-0000-000000000001"

function dossierResponse(overrides: Record<string, unknown> = {}) {
  return {
    cabinId: CABIN_ID,
    activities: [
      {
        activityId: ACTIVITY_ID,
        cabinId: CABIN_ID,
        warehouseId: WAREHOUSE_ID,
        activityCode: "INVENTORY_INSPECTION_SAVED",
        occurredAt: null,
        recordedAt: "2026-07-18T12:00:00Z",
        actorRef: {
          subjectId: ACTOR_ID,
          principalType: "USER",
          profileRevision: null,
        },
        sourceRef: {
          producer: "inventory-service",
          aggregateType: "INVENTORY_SESSION",
          aggregateId: SOURCE_ID,
          secondaryId: SECONDARY_ID,
        },
        media: [
          {
            mediaId: MEDIA_ID,
            folderId: MEDIA_ID,
            findingId: FINDING_ID,
            generation: 2,
            state: "READY",
          },
        ],
      },
    ],
    nextCursor: "cursor-page-2",
    visibility: "COMPLETE",
    ...overrides,
  }
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

describe("dossier-service HTTP adapter", () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal("fetch", fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it("maps the canonical activity, opaque actor and source references", async () => {
    fetchMock.mockResolvedValue(jsonResponse(dossierResponse()))

    await expect(
      getRentalItemDossierPage("access-token", CABIN_ID, { limit: 25 })
    ).resolves.toEqual(dossierResponse())

    const [requestUrl, request] = fetchMock.mock.calls[0] as [
      string,
      RequestInit,
    ]
    expect(requestUrl).toBe(
      `https://gateway.example.test/api/dossier/v1/cabins/${CABIN_ID}?limit=25`
    )
    expect(new Headers(request.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
  })

  it.each(["REPAIR_TRANSFER_PREPARED", "REPAIR_TRANSFERRED"] as const)(
    "accepts the canonical repair transfer activity %s",
    async (activityCode) => {
      const response = dossierResponse()
      response.activities[0]!.activityCode = activityCode
      fetchMock.mockResolvedValue(jsonResponse(response))

      await expect(
        getRentalItemDossierPage("access-token", CABIN_ID)
      ).resolves.toEqual(response)
    }
  )

  it("preserves PARTIAL coverage even when the visible filtered page is empty", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        dossierResponse({
          activities: [],
          nextCursor: null,
          visibility: "PARTIAL",
        })
      )
    )

    await expect(
      getRentalItemDossierPage("access-token", CABIN_ID)
    ).resolves.toEqual({
      cabinId: CABIN_ID,
      activities: [],
      nextCursor: null,
      visibility: "PARTIAL",
    })
  })

  it("sends repeated allowlist filters and the opaque cursor unchanged", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(dossierResponse({ activities: [], nextCursor: null }))
    )

    await getRentalItemDossierPage("access-token", CABIN_ID, {
      limit: 10,
      after: "opaque+/cursor==",
      occurredFrom: "2026-07-01T00:00:00Z",
      occurredBefore: "2026-08-01T00:00:00Z",
      activityCodes: ["CABIN_CREATED", "MEDIA_READY"],
      sourceTypes: ["ASSET", "MEDIA"],
      actorSubjectId: ACTOR_ID,
    })

    const url = new URL(String(fetchMock.mock.calls[0]?.[0]))
    expect(url.origin + url.pathname).toBe(
      `https://gateway.example.test/api/dossier/v1/cabins/${CABIN_ID}`
    )
    expect(url.searchParams.get("limit")).toBe("10")
    expect(url.searchParams.get("after")).toBe("opaque+/cursor==")
    expect(url.searchParams.get("occurredFrom")).toBe("2026-07-01T00:00:00Z")
    expect(url.searchParams.get("occurredBefore")).toBe("2026-08-01T00:00:00Z")
    expect(url.searchParams.getAll("activityCode")).toEqual([
      "CABIN_CREATED",
      "MEDIA_READY",
    ])
    expect(url.searchParams.getAll("sourceType")).toEqual(["ASSET", "MEDIA"])
    expect(url.searchParams.get("actorSubjectId")).toBe(ACTOR_ID)
  })

  it.each([
    [404, "DOSSIER_NOT_FOUND"],
    [403, "DOSSIER_FORBIDDEN"],
  ])(
    "fails closed on HTTP %s without an asset fallback",
    async (status, code) => {
      fetchMock.mockResolvedValue(
        jsonResponse(
          {
            detail: `Dossier error: ${code}`,
            status,
            code,
          },
          status
        )
      )

      const promise = getRentalItemDossierPage("access-token", CABIN_ID)
      await expect(promise).rejects.toBeInstanceOf(ApiError)
      await expect(promise).rejects.toMatchObject({ status })
      expect(fetchMock).toHaveBeenCalledTimes(1)
    }
  )

  it("rejects a missing token before making a request", async () => {
    await expect(getRentalItemDossierPage(null, CABIN_ID)).rejects.toThrow(
      "Не получен токен доступа к сервису досье."
    )
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it("rejects malformed rows instead of inventing actor display data", async () => {
    const malformed = dossierResponse()
    malformed.activities[0]!.actorRef = {
      ...malformed.activities[0]!.actorRef,
      displayName: "Имя, которого нет в контракте",
    } as (typeof malformed.activities)[0]["actorRef"]
    fetchMock.mockResolvedValue(jsonResponse(malformed))

    await expect(
      getRentalItemDossierPage("access-token", CABIN_ID)
    ).rejects.toThrow(
      "Сервис досье вернул некорректный ответ об истории бытовки."
    )
  })
})
