import { afterEach, describe, expect, it, vi } from "vitest"

import {
  createCabinPhotoPresentation,
  getPublicCabinPhotoPresentation,
} from "@/features/rental-items/cabin-photo-presentations-api"

const CABIN_ID = "11111111-1111-4111-8111-111111111111"
const WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const IDEMPOTENCY_KEY = "33333333-3333-4333-8333-333333333333"

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("cabin photo presentation API", () => {
  it("creates an immutable snapshot through authenticated logistics", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "44444444-4444-4444-8444-444444444444",
          version: 1,
          cabinId: CABIN_ID,
          cabinNumber: "БЫТ-001",
          photoCount: 2,
          createdAt: "2026-08-24T12:00:00Z",
          publicPath: "/photos/public-token",
        }),
        { status: 201, headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await createCabinPhotoPresentation({
      accessToken: "access-token",
      cabinId: CABIN_ID,
      warehouseId: WAREHOUSE_ID,
      expectedRentalItemVersion: 7,
      idempotencyKey: IDEMPOTENCY_KEY,
    })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      `/api/logistics/v1/cabins/${CABIN_ID}/photo-presentations`
    )
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init.body))).toEqual({
      warehouseId: WAREHOUSE_ID,
      expectedRentalItemVersion: 7,
    })
  })

  it("loads the public gallery without a bearer token", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "44444444-4444-4444-8444-444444444444",
          cabinNumber: "БЫТ-001",
          dimensions: "6 × 2,4 м",
          finishing: "ПВХ",
          category: "Обычная",
          characteristics: ["Пластиковое окно"],
          linoleum: true,
          createdAt: "2026-08-24T12:00:00Z",
          photos: [],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    const presentation =
      await getPublicCabinPhotoPresentation("token/with spaces")

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      "/api/logistics/public/v1/cabin-photo-presentations/token%2Fwith%20spaces"
    )
    expect(new Headers(init.headers).has("Authorization")).toBe(false)
    expect(new Headers(init.headers).get("Accept")).toBe("application/json")
    expect(presentation).toEqual(
      expect.objectContaining({
        dimensions: "6 × 2,4 м",
        finishing: "ПВХ",
        category: "Обычная",
        characteristics: ["Пластиковое окно"],
        linoleum: true,
      })
    )
  })
})
