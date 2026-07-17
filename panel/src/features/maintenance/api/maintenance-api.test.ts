import { afterEach, describe, expect, it, vi } from "vitest"

import {
  createMaintenanceEstimate,
  listMaintenanceCatalogVersions,
} from "@/features/maintenance/api/maintenance-api"
import { ApiError } from "@/lib/api-client"

vi.mock("@/features/maintenance/maintenance-runtime", () => ({
  getMaintenanceAccessToken: vi.fn().mockResolvedValue("maintenance-token"),
}))

describe("maintenance HTTP API", () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it("uses the gateway route and bearer authentication", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ items: [], page: 0, size: 200, totalElements: 0 }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        )
      )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      listMaintenanceCatalogVersions("warehouse/id", "ACTIVE")
    ).resolves.toEqual({ items: [], page: 0, size: 200, totalElements: 0 })

    const [requestUrl, requestInit] = fetchMock.mock.calls[0] as [
      URL,
      RequestInit,
    ]
    expect(requestUrl.pathname).toBe("/api/maintenance/v1/catalog/versions")
    expect(requestUrl.searchParams.get("warehouseId")).toBe("warehouse/id")
    expect(requestUrl.searchParams.get("lifecycle")).toBe("ACTIVE")
    expect(new Headers(requestInit.headers).get("Authorization")).toBe(
      "Bearer maintenance-token"
    )
  })

  it("sends idempotency and exposes Problem Details conflicts", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ detail: "Версия сметы устарела" }), {
        status: 409,
        headers: { "Content-Type": "application/problem+json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      createMaintenanceEstimate("command-key", { warehouseId: "warehouse-id" })
    ).rejects.toEqual(
      expect.objectContaining<ApiError>({
        name: "ApiError",
        status: 409,
        message: "Версия сметы устарела",
      })
    )

    const [, requestInit] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = new Headers(requestInit.headers)
    expect(requestInit.method).toBe("POST")
    expect(headers.get("Idempotency-Key")).toBe("command-key")
    expect(headers.get("Authorization")).toBe("Bearer maintenance-token")
  })
})
