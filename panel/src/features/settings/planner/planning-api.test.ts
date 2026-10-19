import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { planningApi } from "./planning-api"
import { MSK, SPB, planningFixture } from "./planning-fixtures"

const fetchMock = vi.fn()
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal("fetch", fetchMock)
})
afterEach(() => vi.unstubAllGlobals())

describe("administrative planner boundary", () => {
  it("reads through the public admin route with the canonical SPB identity and bearer token", async () => {
    fetchMock.mockResolvedValue(Response.json(planningFixture()))
    expect(await planningApi.getSettings("admin-token", SPB)).toEqual(
      planningFixture()
    )
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).origin).toBe(window.location.origin)
    expect(new URL(url).pathname).toBe(
      `/api/logistics-planner/v1/admin/warehouses/${SPB}/planning-settings`
    )
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer admin-token"
    )
  })
  it("saves the full configuration and expected version while preserving publication failure", async () => {
    fetchMock.mockResolvedValue(
      Response.json(
        planningFixture({ version: 8, capacity_publish_status: "FAILED" })
      )
    )
    const original = planningFixture()
    const result = await planningApi.saveSettings(
      "admin-token",
      SPB,
      {
        settings: original.settings,
        isochrone_tariffs: original.isochrone_tariffs,
      },
      7
    )
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.method).toBe("PUT")
    expect(JSON.parse(init.body as string)).toEqual({
      settings: original.settings,
      isochrone_tariffs: original.isochrone_tariffs,
      expected_version: 7,
    })
    expect(result.capacity_publish_status).toBe("FAILED")
  })
  it.each([
    () => planningFixture({ warehouse_id: MSK }),
    () => planningFixture({ version: 0 }),
    () => planningFixture({ isochrone_tariffs: [] }),
    () =>
      planningFixture({
        isochrone_tariffs: [{ travel_minutes: 120, price_rubles: 1000 }],
      }),
    () =>
      planningFixture({
        isochrone_tariffs: [{ travel_minutes: 60, price_rubles: 1.5 }],
      }),
    () => ({ ...planningFixture(), settings: { city_speed_kmh: 40 } }),
  ])(
    "rejects incomplete or inconsistent settings instead of defaulting them",
    async (payload) => {
      fetchMock.mockResolvedValue(Response.json(payload()))
      await expect(
        planningApi.getSettings("admin-token", SPB)
      ).rejects.toMatchObject({ code: "INVALID_API_RESPONSE" })
    }
  )
  it("retains an explicit authorization error and never requests without a token", async () => {
    await expect(planningApi.getSettings("", SPB)).rejects.toThrow("токен")
    expect(fetchMock).not.toHaveBeenCalled()
    fetchMock.mockResolvedValue(
      Response.json({ detail: "Только администратор" }, { status: 403 })
    )
    await expect(
      planningApi.getSettings("admin-token", SPB)
    ).rejects.toMatchObject({ status: 403 })
  })
  it("preserves the supplied policy create receipt and the version fences for later commands", async () => {
    const input = {
      name: "Без прицепа",
      kind: "NO_TRAILER" as const,
      color: "#F59E0B",
      geometry: {
        type: "MultiPolygon" as const,
        coordinates: [
          [
            [
              [30, 59],
              [31, 59],
              [31, 60],
              [30, 59],
            ],
          ],
        ],
      },
      delivery_price_rubles: null,
      pickup_price_rubles: null,
    }
    const zone = {
      ...input,
      id: "00000000-0000-0000-0000-000000000003",
      warehouse_id: SPB,
      version: 4,
      created_at: "2026-09-05T10:00:00Z",
      updated_at: "2026-09-05T10:00:00Z",
    }
    fetchMock.mockImplementation(() => Promise.resolve(Response.json(zone)))
    await planningApi.createPolicyZone(
      "admin-token",
      SPB,
      input,
      "stable-create"
    )
    expect(
      new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers).get(
        "Idempotency-Key"
      )
    ).toBe("stable-create")
    await planningApi.updatePolicyZone("admin-token", SPB, zone.id, input, 4)
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({
      ...input,
      expected_version: 4,
    })
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    await planningApi.deletePolicyZone("admin-token", SPB, zone.id, 4)
    expect(
      new URL(fetchMock.mock.calls[2][0]).searchParams.get("expected_version")
    ).toBe("4")
  })
})
