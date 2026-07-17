import { afterEach, describe, expect, it, vi } from "vitest"

import {
  closeBlockedFindingPublication,
  completeInventorySession,
  createAndAttachInventoryAsset,
  getInventorySession,
  listInventorySessions,
  previewInventoryCompletion,
  publishInventoryFindings,
  retryFindingPublication,
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
      expectedSnapshot: null,
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

  it("round-trips server preview revisions and hashes into completion", async () => {
    const view = {
      ...session,
      findings: [
        {
          id: "00000000-0000-0000-0000-000000000030",
          findingRevision: 4,
        },
      ],
    } as InventorySessionView
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
})
