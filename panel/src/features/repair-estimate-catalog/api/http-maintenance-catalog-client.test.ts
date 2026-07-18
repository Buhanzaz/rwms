import { afterEach, describe, expect, it, vi } from "vitest"

import {
  activateMaintenanceCatalog,
  importMaintenanceCatalog,
  listMaintenanceCatalogVersions,
  replaceMaintenanceCatalogNodes,
  type MaintenanceCatalogImportRequest,
} from "@/features/repair-estimate-catalog/api/http-maintenance-catalog-client"
import type { ApiError } from "@/lib/api-client"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const versionId = "00000000-0000-4000-8000-000000000002"
const commandId = "00000000-0000-4000-8000-000000000003"

const version = {
  id: versionId,
  warehouseId,
  version: 4,
  lifecycle: "DRAFT",
  sourceSha256: "a".repeat(64),
  counts: { nodes: 0, links: 0 },
  validation: {
    valid: true,
    errorCount: 0,
    warningCount: 0,
    reportSha256: "b".repeat(64),
  },
  createdAt: "2026-07-18T10:00:00Z",
  activatedAt: null,
} as const

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  })
}

describe("maintenance catalog HTTP client", () => {
  afterEach(() => vi.unstubAllGlobals())

  it("uses the same-origin gateway and Bearer token for version reads", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        json({ items: [version], page: 0, size: 200, totalElements: 1 })
      )
    vi.stubGlobal("fetch", fetchMock)

    await listMaintenanceCatalogVersions("catalog-token", warehouseId, "DRAFT")

    const [input, init] = fetchMock.mock.calls[0] as [URL, RequestInit]
    const endpoint = new URL(String(input))
    expect(endpoint.origin).toBe(window.location.origin)
    expect(endpoint.pathname).toBe("/api/maintenance/v1/catalog/versions")
    expect(Object.fromEntries(endpoint.searchParams)).toEqual({
      warehouseId,
      page: "0",
      size: "200",
      lifecycle: "DRAFT",
    })
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer catalog-token"
    )
  })

  it("sends CAS and idempotency headers for catalog commands", async () => {
    const fetchMock = vi.fn().mockImplementation(async () => json(version))
    vi.stubGlobal("fetch", fetchMock)

    await replaceMaintenanceCatalogNodes("token", warehouseId, versionId, 4, [])
    await activateMaintenanceCatalog(
      "token",
      warehouseId,
      versionId,
      5,
      commandId
    )
    await importMaintenanceCatalog("token", commandId, {
      warehouseId,
      sourceSha256: "a".repeat(64),
      nodes: [],
      links: [],
    } satisfies MaintenanceCatalogImportRequest)

    expect(JSON.parse(String(fetchMock.mock.calls[0]![1]?.body))).toEqual({
      expectedVersion: 4,
      nodes: [],
    })
    expect(JSON.parse(String(fetchMock.mock.calls[1]![1]?.body))).toEqual({
      expectedVersion: 5,
    })
    expect(
      new Headers(fetchMock.mock.calls[1]![1]?.headers).get("Idempotency-Key")
    ).toBe(commandId)
    expect(
      new Headers(fetchMock.mock.calls[2]![1]?.headers).get("Idempotency-Key")
    ).toBe(commandId)
  })

  it.each([
    [409, "Версия каталога устарела"],
    [403, "Недостаточно прав на управление каталогом"],
  ])("preserves Problem Details for HTTP %s", async (status, detail) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ detail }, status)))

    await expect(
      listMaintenanceCatalogVersions("token", warehouseId)
    ).rejects.toMatchObject({
      name: "ApiError",
      status,
      message: detail,
    } satisfies Partial<ApiError>)
  })
})
