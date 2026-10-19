import { afterEach, describe, expect, it, vi } from "vitest"

vi.mock("@/lib/gateway-config", () => ({
  getGatewayRuntimeConfig: () => ({
    logisticsApiBaseUrl: "https://rwms.example/api/logistics",
  }),
}))

import {
  listCustomerCabinProblems,
  parseCustomerCabinProblemClaim,
  resolveCustomerCabinProblem,
  startCustomerCabinProblem,
} from "@/features/claims/api/claims-api"

const IDS = {
  problemId: "00000000-0000-4000-8000-000000000001",
  orderId: "00000000-0000-4000-8000-000000000002",
  warehouseId: "00000000-0000-4000-8000-000000000003",
  bookingId: "00000000-0000-4000-8000-000000000004",
  cabinUnitId: "00000000-0000-4000-8000-000000000005",
  actionId: "00000000-0000-4000-8000-000000000006",
}

const claimResponse = {
  ...IDS,
  category: "OTHER",
  phase: "BEFORE_ACCEPTANCE",
  description: "На стене обнаружен дефект",
  reportedAt: "2026-09-02T08:00:00Z",
  orderNumber: "ORD-000012",
  clientDisplayName: "ООО «СтройМонтаж»",
  clientType: "LEGAL_ENTITY",
  clientPhone: "+79990000000",
  orderContactPhone: "+79990000001",
  deliveryAddress: "Санкт-Петербург, Тестовая улица, 1",
  status: "IN_PROGRESS",
  resolutionDeadline: "2026-09-05T08:00:00Z",
  version: 2,
  resolutionKind: null,
  resolutionComment: null,
  resolvedAt: null,
  actions: [
    {
      actionId: IDS.actionId,
      actionKind: "STATUS_TRANSITION",
      previousStatus: "OPEN",
      lifecycleStatus: "IN_PROGRESS",
      resolutionKind: null,
      commentText: null,
      occurredAt: "2026-09-02T09:00:00Z",
    },
  ],
}

afterEach(() => vi.unstubAllGlobals())

describe("customer cabin problem claims API", () => {
  it("decodes the claim context without exposing transport IDs in the UI model", () => {
    const claim = parseCustomerCabinProblemClaim(claimResponse)

    expect(claim).toMatchObject({
      id: IDS.problemId,
      orderNumber: "ORD-000012",
      clientDisplayName: "ООО «СтройМонтаж»",
      clientType: "LEGAL_ENTITY",
      category: "OTHER",
      phase: "BEFORE_ACCEPTANCE",
      clientPhone: "+79990000000",
      status: "IN_PROGRESS",
      actions: [
        {
          actionKind: "STATUS_TRANSITION",
          lifecycleStatus: "IN_PROGRESS",
        },
      ],
    })
    expect(claim.actions[0]).not.toHaveProperty("actorSubjectId")
  })

  it("rejects a lifecycle response without a valid version fence", () => {
    expect(() =>
      parseCustomerCabinProblemClaim({ ...claimResponse, version: -1 })
    ).toThrow("Сервис логистики вернул некорректный ответ по претензиям.")
  })

  it.each([
    ["category", "NOT_A_CATEGORY"],
    ["phase", "NOT_A_PHASE"],
  ] as const)("rejects an unknown claim %s", (field, value) => {
    expect(() =>
      parseCustomerCabinProblemClaim({ ...claimResponse, [field]: value })
    ).toThrow("Сервис логистики вернул некорректный ответ по претензиям.")
  })

  it("sends a status-filtered list request through the public gateway", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify([claimResponse]), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      listCustomerCabinProblems("access-token", "OPEN")
    ).resolves.toHaveLength(1)

    expect(fetchMock).toHaveBeenCalledWith(
      "https://rwms.example/api/logistics/v1/customer-cabin-problems?status=OPEN",
      expect.objectContaining({
        headers: expect.any(Headers),
      })
    )
    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Headers
    expect(headers.get("Authorization")).toBe("Bearer access-token")
  })

  it("keeps start and resolve commands behind their expected version fences", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(claimResponse), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(claimResponse), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
    vi.stubGlobal("fetch", fetchMock)

    await startCustomerCabinProblem("access-token", IDS.problemId, 2)
    await resolveCustomerCabinProblem("access-token", IDS.problemId, {
      expectedVersion: 3,
      resolutionKind: "DISCOUNT",
      resolutionComment: "Согласована скидка",
    })

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      `https://rwms.example/api/logistics/v1/customer-cabin-problems/${IDS.problemId}/start-progress`
    )
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1]?.body as string)).toEqual({
      expectedVersion: 2,
    })
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      `https://rwms.example/api/logistics/v1/customer-cabin-problems/${IDS.problemId}/resolve`
    )
    expect(JSON.parse(fetchMock.mock.calls[1]?.[1]?.body as string)).toEqual({
      expectedVersion: 3,
      resolutionKind: "DISCOUNT",
      resolutionComment: "Согласована скидка",
    })
  })
})
