import { afterEach, describe, expect, it, vi } from "vitest"

import {
  createCabinPhotoPresentation,
  getPublicCabinPhotoPresentation,
} from "@/features/rental-items/cabin-photo-presentations-api"
import { ApiError } from "@/lib/api-client"

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

  it("maps a public gallery transport failure without exposing browser text", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockRejectedValue(new TypeError("Failed to fetch photo.internal"))
    )

    await expect(
      getPublicCabinPhotoPresentation("public-token")
    ).rejects.toMatchObject({
      status: 0,
      code: "NETWORK_ERROR",
      message:
        "Не удалось связаться с сервером. Проверьте подключение и повторите попытку.",
      diagnosticMessage: "Failed to fetch photo.internal",
    })
  })

  it("maps malformed successful public gallery JSON to a typed safe error", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("<html>internal proxy response</html>", {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
    )

    let failure: unknown
    try {
      await getPublicCabinPhotoPresentation("public-token")
    } catch (error) {
      failure = error
    }

    expect(failure).toBeInstanceOf(ApiError)
    expect(failure).toMatchObject({
      status: 502,
      code: "INVALID_API_RESPONSE",
      message:
        "Сервис вернул некорректные данные. Обновите страницу или повторите попытку позже.",
    })
    const apiFailure = failure as ApiError
    expect(apiFailure.message).not.toMatch(/html|internal|proxy|</i)
    expect(apiFailure.diagnosticMessage).toMatch(/json|unexpected|valid/i)
    expect(apiFailure.diagnosticMessage).not.toBe(apiFailure.message)
  })
})
