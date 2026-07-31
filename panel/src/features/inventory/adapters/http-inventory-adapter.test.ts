import { afterEach, describe, expect, it, vi } from "vitest"

import {
  closeBlockedFindingPublication,
  completeInventorySession,
  createAndAttachInventoryAsset,
  getActiveInventorySession,
  getInventorySession,
  getInventoryStatisticsSummary,
  listInventorySessionStatistics,
  listInventorySessions,
  previewInventoryCompletion,
  publishInventoryFindings,
  resolveInventoryFindingConflict,
  retryFindingPublication,
  saveInventoryInspection,
  startInventorySession,
} from "@/features/inventory/adapters/http-inventory-adapter"
import type {
  InventoryCompletionPreview,
  InventorySessionView,
} from "@/features/inventory/model/inventory-service"

const session = {
  id: "00000000-0000-0000-0000-000000000010",
  sessionRevision: 3,
  warehouseId: "00000000-0000-0000-0000-000000000020",
  warehouseVersion: 2,
  warehouseTimeZone: "Europe/Moscow",
  author: {
    id: "00000000-0000-0000-0000-000000000021",
    displayName: "Кладовщик",
  },
  businessDate: "2026-07-17",
  lifecycle: "ACTIVE",
  expectedCount: 1,
  findingCount: 1,
  inspectedCount: 0,
  startedAt: "2026-07-17T10:00:00Z",
  terminalAt: null,
  publicationState: "NOT_REQUESTED",
  statistics: null,
  cancellation: null,
  membershipMovements: [],
} satisfies Omit<InventorySessionView, "findings">

afterEach(() => vi.unstubAllGlobals())

describe("http inventory adapter", () => {
  it("uses the same-origin gateway with Bearer auth for history and start", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            content: [session],
            page: { page: 0, size: 50, totalElements: 1, totalPages: 1 },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        )
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(session), {
          status: 201,
          headers: { "Content-Type": "application/json" },
        })
      )
    vi.stubGlobal("fetch", fetchMock)

    await listInventorySessions("inventory-token", session.warehouseId)
    const startIdempotencyKey = "00000000-0000-4000-8000-000000000101"
    await startInventorySession(
      "inventory-token",
      session.warehouseId,
      startIdempotencyKey
    )

    expect(fetchMock.mock.calls[0][0]).toContain(
      "/api/inventory/v1/sessions?warehouseId="
    )
    expect(
      new Headers(fetchMock.mock.calls[0][1].headers).get("Authorization")
    ).toBe("Bearer inventory-token")
    const start = fetchMock.mock.calls[1]
    expect(start[0]).toContain("/api/inventory/v1/sessions")
    expect(start[1].method).toBe("POST")
    expect(new Headers(start[1].headers).get("Idempotency-Key")).toBe(
      startIdempotencyKey
    )
    expect(JSON.parse(start[1].body)).toEqual({
      warehouseId: session.warehouseId,
    })
  })

  it("loads canonical detail and server-paged findings without browser synthesis", async () => {
    const finding = {
      id: "00000000-0000-0000-0000-000000000030",
      inventoryId: session.id,
      findingRevision: 4,
      origin: "EXPECTED",
      inspection: "NOT_INSPECTED",
      reconciliation: "MATCHED",
      assetId: "00000000-0000-0000-0000-000000000040",
      assetVersion: 7,
      displayCanonicalNumber: "A-1",
      identityMatchKey: "A-1",
      passportObservation: { presence: "ABSENT", value: null },
      equipmentObservation: { presence: "ABSENT", value: null },
      mutationState: "IDLE",
      planFingerprintSha256: null,
      comment: "",
      expectedSnapshot: null,
      currentSnapshot: null,
      inspectionBaseline: null,
      conflictResolution: null,
      conflicts: [],
      frozenPlan: null,
      media: [],
      publication: null,
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(session), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            content: [finding],
            page: { page: 0, size: 200, totalElements: 1, totalPages: 1 },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        )
      )
    vi.stubGlobal("fetch", fetchMock)

    const result = await getInventorySession("inventory-token", session.id)

    expect(result.findings).toEqual([finding])
    expect(fetchMock.mock.calls[1][0]).toContain(
      `/sessions/${session.id}/findings?page=0&size=200`
    )
  })

  it("loads the active session and treats a 204 response as empty", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(session), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            content: [],
            page: { page: 0, size: 200, totalElements: 0, totalPages: 0 },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        )
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      getActiveInventorySession("inventory-token", session.warehouseId)
    ).resolves.toMatchObject({
      id: session.id,
      sessionRevision: session.sessionRevision,
      findings: [],
    })
    await expect(
      getActiveInventorySession("inventory-token", session.warehouseId)
    ).resolves.toBeNull()

    expect(fetchMock.mock.calls[0][0]).toContain(
      `/sessions/active?warehouseId=${session.warehouseId}`
    )
  })

  it("sends server revisions, ready media generations and plan selection for inspection", async () => {
    const findingId = "00000000-0000-4000-8000-000000000130"
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: findingId }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await saveInventoryInspection({
      accessToken: "inventory-token",
      inventoryId: session.id,
      findingId,
      expectedSessionRevision: 3,
      expectedFindingRevision: 8,
      inspection: "WORK_STAGED",
      comment: "Осмотрено",
      media: [
        {
          mediaId: "00000000-0000-4000-8000-000000000131",
          generation: 2,
        },
      ],
      coverMediaId: "00000000-0000-4000-8000-000000000131",
      planSelection: {
        mode: "AUTO",
        priority: 3,
        coverMediaId: "00000000-0000-4000-8000-000000000131",
        logisticsPlanningMode: "AUTO",
        logisticsScheduledDate: null,
        lines: [
          {
            aggregationKind: "CATALOG",
            catalogNodeId: "00000000-0000-4000-8000-000000000132",
            description: null,
            type: null,
            unit: null,
            quantity: "1",
            unitPriceMinor: null,
            normativeMinutes: null,
            groupComment: null,
            mediaReferences: [],
          },
        ],
        stages: [],
      },
    })

    const request = fetchMock.mock.calls[0]
    expect(request[0]).toContain(
      `/sessions/${session.id}/findings/${findingId}/inspection`
    )
    expect(request[1].method).toBe("PUT")
    expect(JSON.parse(request[1].body)).toMatchObject({
      expectedSessionRevision: 3,
      expectedFindingRevision: 8,
      inspection: "WORK_STAGED",
      media: [
        {
          mediaId: "00000000-0000-4000-8000-000000000131",
          generation: 2,
        },
      ],
      planSelection: {
        mode: "AUTO",
        logisticsPlanningMode: "AUTO",
        logisticsScheduledDate: null,
      },
    })
  })

  it("sends an explicit conflict resolution with both current revisions", async () => {
    const findingId = "00000000-0000-4000-8000-000000000133"
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: findingId }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await resolveInventoryFindingConflict({
      accessToken: "inventory-token",
      inventoryId: session.id,
      findingId,
      expectedSessionRevision: 3,
      expectedFindingRevision: 8,
      strategy: "KEEP_INSPECTION",
      reason: "Осмотр подтверждён кладовщиком",
    })

    const request = fetchMock.mock.calls[0]
    expect(request[0]).toContain(
      `/sessions/${session.id}/findings/${findingId}/conflict-resolution`
    )
    expect(request[1].method).toBe("PUT")
    expect(JSON.parse(request[1].body)).toEqual({
      expectedSessionRevision: 3,
      expectedFindingRevision: 8,
      strategy: "KEEP_INSPECTION",
      reason: "Осмотр подтверждён кладовщиком",
    })
  })

  it("round-trips server preview revisions and hashes into completion", async () => {
    const view = {
      ...session,
      findings: [
        {
          id: "00000000-0000-0000-0000-000000000030",
          findingRevision: 4,
        },
      ],
    } as unknown as InventorySessionView
    const preview = {
      inventoryId: session.id,
      sessionRevision: 3,
      findingRevisions: [
        {
          findingId: "00000000-0000-0000-0000-000000000030",
          expectedFindingRevision: 4,
        },
      ],
      validationSha256: "a".repeat(64),
      validatedAt: "2026-07-17T10:01:00Z",
      acknowledgementSha256: "b".repeat(64),
      statistics: {
        expectedCount: 1,
        inspectedCount: 1,
        missingCount: 0,
        readyCount: 1,
        withWorkCount: 0,
        addedCount: 0,
        unexpectedExistingCount: 0,
        conflictCount: 0,
        workLineCount: 0,
        materialLineCount: 0,
        workTotalMinor: 0,
        materialTotalMinor: 0,
        grandTotalMinor: 0,
        roundingAdjustmentMinor: 0,
        normativeMinutes: "0",
        durationSeconds: 60,
        aggregateLines: [],
      },
      risks: [],
      validatedFindings: [],
    } as InventoryCompletionPreview
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(preview), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(preview), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ ...session, lifecycle: "COMPLETED" }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
    vi.stubGlobal("fetch", fetchMock)

    const previewIdempotencyKey = "00000000-0000-4000-8000-000000000102"
    const completeIdempotencyKey = "00000000-0000-4000-8000-000000000103"
    await previewInventoryCompletion({
      accessToken: "token",
      session: view,
      idempotencyKey: previewIdempotencyKey,
    })
    await previewInventoryCompletion({
      accessToken: "token",
      session: view,
      idempotencyKey: previewIdempotencyKey,
    })
    await completeInventorySession({
      accessToken: "token",
      preview,
      idempotencyKey: completeIdempotencyKey,
    })

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      expectedSessionRevision: 3,
      findingRevisions: preview.findingRevisions,
    })
    expect(
      new Headers(fetchMock.mock.calls[0][1].headers).get("Idempotency-Key")
    ).toBe(previewIdempotencyKey)
    expect(
      new Headers(fetchMock.mock.calls[1][1].headers).get("Idempotency-Key")
    ).toBe(previewIdempotencyKey)
    expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toEqual({
      expectedSessionRevision: 3,
      findingRevisions: preview.findingRevisions,
      acknowledgementSha256: preview.acknowledgementSha256,
      validationSha256: preview.validationSha256,
    })
    expect(
      new Headers(fetchMock.mock.calls[2][1].headers).get("Idempotency-Key")
    ).toBe(completeIdempotencyKey)
  })

  it("preserves caller-owned create identity and supports publication recovery", async () => {
    const findingId = "00000000-0000-4000-8000-000000000104"
    const createIdempotencyKey = "00000000-0000-4000-8000-000000000105"
    const retryIdempotencyKey = "00000000-0000-4000-8000-000000000106"
    const closeIdempotencyKey = "00000000-0000-4000-8000-000000000107"
    const publishIdempotencyKey = "00000000-0000-4000-8000-000000000108"
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify({}), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    const createInput = {
      accessToken: "token",
      inventoryId: session.id,
      findingId,
      expectedSessionRevision: 3,
      expectedFindingRevision: 0,
      origin: "ADDED_USED" as const,
      displayCanonicalNumber: "Б-77",
      safePassport: {},
      idempotencyKey: createIdempotencyKey,
    }
    await createAndAttachInventoryAsset(createInput)
    await createAndAttachInventoryAsset(createInput)
    await publishInventoryFindings({
      accessToken: "token",
      inventoryId: session.id,
      expectedSessionRevision: 3,
      idempotencyKey: publishIdempotencyKey,
    })
    await retryFindingPublication({
      accessToken: "token",
      inventoryId: session.id,
      findingId,
      expectedPublicationRevision: 7,
      reconcileReason: "Источник повторно сверен",
      currentPreconditionSha256: "a".repeat(64),
      idempotencyKey: retryIdempotencyKey,
    })
    await closeBlockedFindingPublication({
      accessToken: "token",
      inventoryId: session.id,
      findingId,
      expectedPublicationRevision: 8,
      reason: "Закрыто оператором после сверки",
      idempotencyKey: closeIdempotencyKey,
    })

    for (const call of fetchMock.mock.calls.slice(0, 2)) {
      expect(call[0]).toContain(`/findings/${findingId}/assets`)
      expect(new Headers(call[1].headers).get("Idempotency-Key")).toBe(
        createIdempotencyKey
      )
    }
    expect(
      new Headers(fetchMock.mock.calls[2][1].headers).get("Idempotency-Key")
    ).toBe(publishIdempotencyKey)
    expect(JSON.parse(fetchMock.mock.calls[3][1].body)).toEqual({
      expectedPublicationRevision: 7,
      reconcileReason: "Источник повторно сверен",
      currentPreconditionSha256: "a".repeat(64),
    })
    expect(JSON.parse(fetchMock.mock.calls[4][1].body)).toEqual({
      expectedPublicationRevision: 8,
      reason: "Закрыто оператором после сверки",
    })
  })

  it("reads persisted session statistics and the server summary without recomputing", async () => {
    const statistics = {
      expectedCount: 3,
      inspectedCount: 2,
      missingCount: 1,
      readyCount: 1,
      withWorkCount: 1,
      addedCount: 0,
      unexpectedExistingCount: 0,
      conflictCount: 1,
      workLineCount: 1,
      materialLineCount: 0,
      workTotalMinor: 12500,
      materialTotalMinor: 0,
      grandTotalMinor: 12500,
      roundingAdjustmentMinor: 0,
      normativeMinutes: "60",
      durationSeconds: 900,
      aggregateLines: [],
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            content: [
              {
                inventoryId: session.id,
                warehouseId: session.warehouseId,
                businessDate: session.businessDate,
                startedAt: session.startedAt,
                completedAt: "2026-07-17T10:15:00Z",
                statistics,
              },
            ],
            page: { page: 0, size: 50, totalElements: 1, totalPages: 1 },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        )
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ sessionCount: 1, statistics }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
    vi.stubGlobal("fetch", fetchMock)

    const page = await listInventorySessionStatistics(
      "inventory-token",
      session.warehouseId
    )
    const summary = await getInventoryStatisticsSummary(
      "inventory-token",
      session.warehouseId
    )

    expect(page.content[0].statistics.grandTotalMinor).toBe(12500)
    expect(summary).toEqual({ sessionCount: 1, statistics })
    expect(fetchMock.mock.calls[0][0]).toContain(
      "/api/inventory/v1/statistics/sessions"
    )
    expect(fetchMock.mock.calls[1][0]).toContain(
      "/api/inventory/v1/statistics/summary"
    )
  })

  it("preserves 401, 403, 404 and 409 Problem Details for the UI", async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal("fetch", fetchMock)

    for (const status of [401, 403]) {
      fetchMock.mockResolvedValueOnce(
        new Response(JSON.stringify({ detail: `Ошибка ${status}` }), {
          status,
          headers: { "Content-Type": "application/problem+json" },
        })
      )
      const request = listInventorySessions(
        "inventory-token",
        session.warehouseId
      )
      await expect(request).rejects.toMatchObject({ status })
    }

    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ detail: "Сессия не найдена" }), {
        status: 404,
        headers: { "Content-Type": "application/problem+json" },
      })
    )
    await expect(
      getInventorySession("inventory-token", session.id)
    ).rejects.toMatchObject({ status: 404 })

    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ detail: "Версия устарела" }), {
        status: 409,
        headers: { "Content-Type": "application/problem+json" },
      })
    )
    await expect(
      saveInventoryInspection({
        accessToken: "inventory-token",
        inventoryId: session.id,
        findingId: "00000000-0000-4000-8000-000000000140",
        expectedSessionRevision: 3,
        expectedFindingRevision: 8,
        inspection: "READY",
        comment: "",
        media: [],
        coverMediaId: null,
        planSelection: null,
      })
    ).rejects.toMatchObject({ status: 409 })
  })
})
