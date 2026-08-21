import { afterEach, describe, expect, it, vi } from "vitest"

import {
  acceptMaintenanceRepair,
  amendMaintenanceEstimate,
  completeMaintenanceEstimate,
  createDirectMaintenanceRepair,
  createMaintenanceEstimate,
  createMaintenanceRework,
  getMaintenanceEstimate,
  getMaintenanceRepair,
  listMaintenanceReturnEstimateSources,
  listMaintenanceAcceptance,
  listMaintenanceEstimates,
  listMaintenanceRepairs,
  queueMaintenanceRepair,
  replaceMaintenanceEstimate,
  replaceMaintenanceRepairPlan,
  type MaintenanceEstimateWrite,
  type MaintenancePlanStageInput,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import { ApiError } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const rentalItemId = "00000000-0000-4000-8000-000000000002"
const estimateId = "00000000-0000-4000-8000-000000000003"
const repairId = "00000000-0000-4000-8000-000000000004"
const stageId = "00000000-0000-4000-8000-000000000005"
const queueId = "00000000-0000-4000-8000-000000000006"
const lineId = "00000000-0000-4000-8000-000000000007"
const idempotencyKey = "00000000-0000-4000-8000-000000000008"
const entryId = "00000000-0000-4000-8000-000000000009"
const evidenceId = "00000000-0000-4000-8000-000000000010"
const workerId = "00000000-0000-4000-8000-000000000011"
const workerGroupId = "00000000-0000-4000-8000-000000000012"
const mediaId = "00000000-0000-4000-8000-000000000013"
const secondRepairId = "00000000-0000-4000-8000-000000000014"

const stage: MaintenancePlanStageInput = {
  id: stageId,
  kind: "REPAIR_WORK",
  order: 0,
  routing: { queueId, queueName: "Ремонт", queueType: "REPAIR" },
  includedLineIds: [lineId],
  primaryLineId: lineId,
  groupComment: "Сначала проверить крепления",
  taskDeadline: null,
}

const estimateWrite: MaintenanceEstimateWrite = {
  dispatchDate: "2026-07-18",
  sourceParty: "Арендатор",
  lines: [
    {
      id: lineId,
      catalogSnapshot: null,
      lineType: "WORK",
      description: "Проверка",
      unit: "ед",
      quantity: "1.5",
      normativeMinutes: 45,
      unitPrice: "10.00",
      comment: null,
      mediaReferences: [],
    },
  ],
  plan: [stage],
  mediaReferences: [],
  forceCapitalRepair: true,
}

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

function requestAt(fetchMock: ReturnType<typeof vi.fn>, index: number) {
  return fetchMock.mock.calls[index] as [string, RequestInit]
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("maintenance lifecycle HTTP client", () => {
  it("uses only the same-origin gateway and bearer token for lifecycle reads", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation(() => Promise.resolve(json({ items: [] })))
    vi.stubGlobal("fetch", fetchMock)

    await listMaintenanceEstimates("token", warehouseId, "DRAFT", rentalItemId)
    await listMaintenanceReturnEstimateSources("token", warehouseId, repairId)
    await getMaintenanceEstimate("token", warehouseId, estimateId)
    await listMaintenanceRepairs("token", warehouseId, {
      executionState: "QUEUED",
      acceptanceState: "NOT_READY",
      rentalItemId,
      repairIds: [repairId, secondRepairId],
    })
    await getMaintenanceRepair("token", warehouseId, repairId)
    await listMaintenanceAcceptance("token", warehouseId)

    for (const [url, init] of fetchMock.mock.calls as Array<
      [string, RequestInit]
    >) {
      expect(String(url)).toContain(
        getGatewayRuntimeConfig().maintenanceApiBaseUrl
      )
      expect(new URL(String(url)).origin).toBe(window.location.origin)
      expect(new Headers(init.headers).get("Authorization")).toBe(
        "Bearer token"
      )
    }
    expect(String(fetchMock.mock.calls[0]![0])).toContain("lifecycle=DRAFT")
    expect(String(fetchMock.mock.calls[0]![0])).toContain(
      `rentalItemId=${rentalItemId}`
    )
    expect(String(fetchMock.mock.calls[1]![0])).toContain(
      "/v1/estimates/return-sources"
    )
    expect(String(fetchMock.mock.calls[1]![0])).toContain(
      `returnId=${repairId}`
    )
    expect(String(fetchMock.mock.calls[3]![0])).toContain(
      "executionState=QUEUED"
    )
    expect(
      new URL(String(fetchMock.mock.calls[3]![0])).searchParams.get("repairIds")
    ).toBe(`${repairId},${secondRepairId}`)
    expect(String(fetchMock.mock.calls[5]![0])).toContain("state=PENDING")
  })

  it("preserves projected worker evidence in a repair stage", async () => {
    const evidence = {
      evidenceId,
      entryId,
      workerId,
      workerGroupId,
      mediaId,
      mediaGeneration: 4,
      capturedAt: "2026-07-18T09:45:00Z",
      recordedAt: "2026-07-18T10:00:00Z",
      state: "READY",
    }
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        json({
          plan: {
            stages: [
              {
                id: stageId,
                evidence: [evidence],
              },
            ],
          },
        })
      )
    )

    const response = await getMaintenanceRepair("token", warehouseId, repairId)

    expect(response.plan.stages[0]?.evidence).toEqual([evidence])
  })

  it("sends canonical estimate create, replace, complete and amend semantics", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation(() => Promise.resolve(json({})))
    vi.stubGlobal("fetch", fetchMock)

    await createMaintenanceEstimate("token", idempotencyKey, {
      warehouseId,
      rentalItemId,
      ...estimateWrite,
    })
    await replaceMaintenanceEstimate(
      "token",
      warehouseId,
      estimateId,
      3,
      estimateWrite
    )
    await completeMaintenanceEstimate(
      "token",
      warehouseId,
      estimateId,
      4,
      1,
      idempotencyKey,
      true,
      "FIXED_DATE",
      "2026-08-12"
    )
    await amendMaintenanceEstimate(
      "token",
      warehouseId,
      estimateId,
      idempotencyKey,
      {
        expectedVersion: 5,
        expectedLinkedRepairVersion: 8,
        reason: "Уточнён объём",
        ...estimateWrite,
      }
    )

    const [createUrl, createInit] = requestAt(fetchMock, 0)
    expect(createUrl).toMatch(/\/v1\/estimates$/)
    expect(createInit.method).toBe("POST")
    expect(new Headers(createInit.headers).get("Idempotency-Key")).toBe(
      idempotencyKey
    )
    expect(JSON.parse(String(createInit.body))).toMatchObject({
      warehouseId,
      rentalItemId,
      dispatchDate: "2026-07-18",
      forceCapitalRepair: true,
      lines: [
        expect.objectContaining({
          lineType: "WORK",
          unit: "ед",
          normativeMinutes: 45,
        }),
      ],
    })
    expect(JSON.parse(String(createInit.body))).not.toHaveProperty("id")

    const [, replaceInit] = requestAt(fetchMock, 1)
    expect(replaceInit.method).toBe("PUT")
    expect(JSON.parse(String(replaceInit.body)).expectedVersion).toBe(3)
    expect(JSON.parse(String(replaceInit.body)).forceCapitalRepair).toBe(true)

    const [completeUrl, completeInit] = requestAt(fetchMock, 2)
    expect(String(completeUrl)).toContain(`/${estimateId}/complete`)
    expect(JSON.parse(String(completeInit.body))).toEqual({
      expectedVersion: 4,
      priority: 1,
      movementToRepair: true,
      allowUnaccountedFurniture: false,
      logisticsPlanningMode: "FIXED_DATE",
      logisticsScheduledDate: "2026-08-12",
    })
    expect(new Headers(completeInit.headers).get("Idempotency-Key")).toBe(
      idempotencyKey
    )

    const [amendUrl, amendInit] = requestAt(fetchMock, 3)
    expect(String(amendUrl)).toContain(`/${estimateId}/amendments`)
    expect(JSON.parse(String(amendInit.body))).toMatchObject({
      expectedVersion: 5,
      expectedLinkedRepairVersion: 8,
      reason: "Уточнён объём",
      forceCapitalRepair: true,
    })
  })

  it("sends CAS and idempotency for repair plan, queue, rework and decisions", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation(() => Promise.resolve(json({})))
    vi.stubGlobal("fetch", fetchMock)

    await createDirectMaintenanceRepair("token", idempotencyKey, {
      warehouseId,
      rentalItemId,
      dispatchDate: "2026-07-18",
      sourceParty: null,
      lines: estimateWrite.lines,
      plan: [stage],
      mediaReferences: [],
      coverMediaId: null,
      forceCapitalRepair: true,
    })
    await replaceMaintenanceRepairPlan(
      "token",
      warehouseId,
      repairId,
      2,
      estimateWrite.lines,
      [stage],
      [{ mediaId: rentalItemId, generation: 7 }],
      rentalItemId
    )
    await queueMaintenanceRepair(
      "token",
      warehouseId,
      repairId,
      3,
      2,
      idempotencyKey,
      false,
      "AUTO",
      null
    )
    await createMaintenanceRework(
      "token",
      warehouseId,
      repairId,
      idempotencyKey,
      {
        expectedVersion: 4,
        reason: "Переделать",
        lines: estimateWrite.lines.map((line) => ({
          id: line.id,
          disposition: "ADDED" as const,
          line,
        })),
        plan: [stage],
        mediaReferences: [],
        coverMediaId: null,
      }
    )
    await acceptMaintenanceRepair(
      "token",
      warehouseId,
      repairId,
      5,
      "Принято",
      [],
      idempotencyKey
    )
    expect(requestAt(fetchMock, 0)[0]).toMatch(/\/v1\/repairs\/direct$/)
    expect(JSON.parse(String(requestAt(fetchMock, 0)[1].body))).toMatchObject({
      lines: estimateWrite.lines,
      plan: [stage],
      forceCapitalRepair: true,
    })
    expect(JSON.parse(String(requestAt(fetchMock, 1)[1].body))).toEqual({
      expectedVersion: 2,
      lines: estimateWrite.lines,
      stages: [stage],
      mediaReferences: [{ mediaId: rentalItemId, generation: 7 }],
      coverMediaId: rentalItemId,
      forceCapitalRepair: false,
    })
    expect(JSON.parse(String(requestAt(fetchMock, 2)[1].body))).toEqual({
      expectedVersion: 3,
      priority: 2,
      movementToRepair: false,
      logisticsPlanningMode: null,
      logisticsScheduledDate: null,
    })
    expect(String(requestAt(fetchMock, 3)[0])).toContain(`/${repairId}/reworks`)
    expect(JSON.parse(String(requestAt(fetchMock, 3)[1].body))).toMatchObject({
      expectedVersion: 4,
      reason: "Переделать",
      lines: estimateWrite.lines.map((line) => ({
        id: line.id,
        disposition: "ADDED",
        line,
      })),
      plan: [stage],
      coverMediaId: null,
    })
    expect(
      JSON.parse(String(requestAt(fetchMock, 3)[1].body))
    ).not.toHaveProperty("forceCapitalRepair")
    expect(JSON.parse(String(requestAt(fetchMock, 4)[1].body))).toEqual({
      expectedVersion: 5,
      comment: "Принято",
      mediaReferences: [],
    })
    for (const index of [0, 2, 3, 4]) {
      expect(
        new Headers(requestAt(fetchMock, index)[1].headers).get(
          "Idempotency-Key"
        )
      ).toBe(idempotencyKey)
    }
  })

  it.each([
    [401, "Требуется вход"],
    [403, "Недостаточно прав"],
    [404, "Ремонт не найден"],
    [409, "Версия устарела"],
  ])("preserves Problem Details for HTTP %s", async (status, detail) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ detail }, status)))

    await expect(
      getMaintenanceRepair("token", warehouseId, repairId)
    ).rejects.toMatchObject({
      name: "ApiError",
      status,
      message: detail,
    } satisfies Partial<ApiError>)
  })
})
