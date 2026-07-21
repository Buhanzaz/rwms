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
  listMaintenanceAcceptance,
  listMaintenanceEstimates,
  listMaintenanceRepairs,
  listMaintenanceWriteOffs,
  queueMaintenanceRepair,
  replaceMaintenanceEstimate,
  replaceMaintenanceRepairPlan,
  writeOffMaintenanceRepair,
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

const stage: MaintenancePlanStageInput = {
  id: stageId,
  kind: "REPAIR_WORK",
  order: 0,
  routing: { queueId, queueCode: "REPAIR", queueKind: "REPAIR" },
  taskDeadline: null,
}

const estimateWrite: MaintenanceEstimateWrite = {
  dispatchDate: "2026-07-18",
  sourceParty: "Арендатор",
  lines: [
    {
      id: lineId,
      catalogSnapshot: null,
      description: "Проверка",
      quantity: "1.5",
      unitPrice: "10.00",
      comment: null,
      mediaReferences: [],
    },
  ],
  plan: [stage],
  mediaReferences: [],
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

    await listMaintenanceEstimates("token", warehouseId, "DRAFT")
    await getMaintenanceEstimate("token", warehouseId, estimateId)
    await listMaintenanceRepairs("token", warehouseId, {
      executionState: "QUEUED",
      acceptanceState: "NOT_READY",
      rentalItemId,
    })
    await getMaintenanceRepair("token", warehouseId, repairId)
    await listMaintenanceAcceptance("token", warehouseId)
    await listMaintenanceWriteOffs("token", warehouseId)

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
    expect(String(fetchMock.mock.calls[2]![0])).toContain(
      "executionState=QUEUED"
    )
    expect(String(fetchMock.mock.calls[4]![0])).toContain("state=PENDING")
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
      idempotencyKey
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
    })
    expect(JSON.parse(String(createInit.body))).not.toHaveProperty("id")

    const [, replaceInit] = requestAt(fetchMock, 1)
    expect(replaceInit.method).toBe("PUT")
    expect(JSON.parse(String(replaceInit.body)).expectedVersion).toBe(3)

    const [completeUrl, completeInit] = requestAt(fetchMock, 2)
    expect(String(completeUrl)).toContain(`/${estimateId}/complete`)
    expect(JSON.parse(String(completeInit.body))).toEqual({
      expectedVersion: 4,
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
      plan: [stage],
      mediaReferences: [],
    })
    await replaceMaintenanceRepairPlan(
      "token",
      warehouseId,
      repairId,
      2,
      [stage],
      [{ mediaId: rentalItemId, generation: 7 }]
    )
    await queueMaintenanceRepair(
      "token",
      warehouseId,
      repairId,
      3,
      idempotencyKey
    )
    await createMaintenanceRework(
      "token",
      warehouseId,
      repairId,
      idempotencyKey,
      {
        expectedVersion: 4,
        reason: "Переделать",
        plan: [stage],
        mediaReferences: [],
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
    await writeOffMaintenanceRepair(
      "token",
      warehouseId,
      repairId,
      6,
      "Неремонтопригодна",
      null,
      idempotencyKey
    )

    expect(requestAt(fetchMock, 0)[0]).toMatch(/\/v1\/repairs\/direct$/)
    expect(JSON.parse(String(requestAt(fetchMock, 1)[1].body))).toEqual({
      expectedVersion: 2,
      stages: [stage],
      mediaReferences: [{ mediaId: rentalItemId, generation: 7 }],
    })
    expect(JSON.parse(String(requestAt(fetchMock, 2)[1].body))).toEqual({
      expectedVersion: 3,
    })
    expect(String(requestAt(fetchMock, 3)[0])).toContain(`/${repairId}/reworks`)
    expect(JSON.parse(String(requestAt(fetchMock, 3)[1].body))).toMatchObject({
      expectedVersion: 4,
      reason: "Переделать",
    })
    expect(JSON.parse(String(requestAt(fetchMock, 4)[1].body))).toEqual({
      expectedVersion: 5,
      comment: "Принято",
      mediaReferences: [],
    })
    expect(JSON.parse(String(requestAt(fetchMock, 5)[1].body))).toEqual({
      expectedVersion: 6,
      reason: "Неремонтопригодна",
      comment: null,
    })
    for (const index of [0, 2, 3, 4, 5]) {
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
