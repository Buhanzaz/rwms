import { afterEach, describe, expect, it, vi } from "vitest"

import {
  getRepairComplexity,
  updateRepairComplexity,
} from "@/features/settings/kpi/api/repair-complexity-api"

const setting = {
  version: 4,
  lightBoundaryMinutes: 120,
  mediumBoundaryMinutes: 300,
  complexBoundaryMinutes: 500,
  createdAt: "2026-07-31T07:00:00Z",
  updatedAt: "2026-07-31T07:00:00Z",
}

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
  })
}

afterEach(() => vi.restoreAllMocks())

describe("repair complexity API", () => {
  it("loads global boundaries from the public maintenance route", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(setting))

    await expect(
      getRepairComplexity("access-token")
    ).resolves.toEqual(setting)

    const [input, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(input)).pathname).toBe(
      "/api/maintenance/v1/settings/repair-complexity"
    )
    expect(new Headers(init?.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
  })

  it("accepts nullable lifecycle fields from the contract", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse({
        ...setting,
        version: 0,
        createdAt: null,
        updatedAt: null,
      })
    )

    await expect(
      getRepairComplexity("access-token")
    ).resolves.toMatchObject({
      version: 0,
      lightBoundaryMinutes: 120,
      createdAt: null,
      updatedAt: null,
    })
  })

  it("rejects a legacy warehouse-scoped response", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse({
        ...setting,
        warehouseId: "another-warehouse",
      })
    )

    await expect(
      getRepairComplexity("access-token")
    ).rejects.toThrow("некорректные границы")
  })

  it("saves only canonical integer minute boundaries", async () => {
    const saved = { ...setting, version: 5, complexBoundaryMinutes: 520 }
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(saved))
    const input = {
      expectedVersion: 4,
      lightBoundaryMinutes: 120,
      mediumBoundaryMinutes: 300,
      complexBoundaryMinutes: 520,
    }

    await expect(
      updateRepairComplexity("access-token", input)
    ).resolves.toEqual(saved)

    const [url, init] = fetchMock.mock.calls[0]!
    expect(new URL(String(url)).pathname).toBe(
      "/api/maintenance/v1/settings/repair-complexity"
    )
    expect(init?.method).toBe("PUT")
    expect(JSON.parse(String(init?.body))).toEqual(input)
  })
})
