import { afterEach, describe, expect, it, vi } from "vitest"

import {
  actOnRentalBookingAlert,
  confirmPublicPresentation,
  getPublicPresentation,
  getRentalBookingAlerts,
  publishClientPresentation,
  updateRentalSettings,
} from "@/features/assistant/api/rental-presentations-api"

const INQUIRY_ID = "11111111-1111-4111-8111-111111111111"
const WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const CABIN_ID = "33333333-3333-4333-8333-333333333333"
const IDEMPOTENCY_KEY = "44444444-4444-4444-8444-444444444444"

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("rental presentation API", () => {
  it("sends all rental hold settings in the exact PUT payload", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          version: 7,
          chatSelectionHoldMinutes: 10,
          manualBookingHoldMinutes: 60,
          presentationHoldMinutes: 60,
          draftReservationHoldMinutes: 1440,
          updatedBy: "admin",
          updatedAt: "2026-07-27T09:00:00Z",
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await updateRentalSettings({
      accessToken: "access-token",
      expectedVersion: 6,
      chatSelectionHoldMinutes: 10,
      manualBookingHoldMinutes: 60,
      presentationHoldMinutes: 60,
      draftReservationHoldMinutes: 1440,
    })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe("/api/logistics/v1/settings/rental")
    expect(init.method).toBe("PUT")
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
    expect(JSON.parse(String(init.body))).toEqual({
      expectedVersion: 6,
      chatSelectionHoldMinutes: 10,
      manualBookingHoldMinutes: 60,
      presentationHoldMinutes: 60,
      draftReservationHoldMinutes: 1440,
    })
  })

  it("publishes the exact selected groups through authenticated logistics", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "55555555-5555-4555-8555-555555555555",
          revision: 1,
          state: "ACTIVE",
          expiresAt: "2026-07-27T09:00:00Z",
          viewUntil: "2026-07-28T09:00:00Z",
          bookedOrderId: null,
          groups: [],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await publishClientPresentation({
      accessToken: "access-token",
      inquiryId: INQUIRY_ID,
      warehouseId: WAREHOUSE_ID,
      idempotencyKey: IDEMPOTENCY_KEY,
      groups: [{ key: "bk-1", label: "БК-1", rentalItemIds: [CABIN_ID] }],
    })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      `/api/logistics/v1/rental-inquiries/${INQUIRY_ID}/client-presentation`
    )
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init.body))).toEqual({
      warehouseId: WAREHOUSE_ID,
      groups: [{ key: "bk-1", label: "БК-1", rentalItemIds: [CABIN_ID] }],
    })
  })

  it("loads and resolves manager booking alerts through authenticated logistics", async () => {
    const bookingId = "66666666-6666-4666-8666-666666666666"
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify([]), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal("fetch", fetchMock)

    await getRentalBookingAlerts("access-token")
    await actOnRentalBookingAlert({
      accessToken: "access-token",
      bookingId,
      expectedVersion: 7,
      action: "KEEP_DRAFT",
      idempotencyKey: IDEMPOTENCY_KEY,
    })

    const [listUrl, listInit] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(listUrl).pathname).toBe(
      "/api/logistics/v1/rental-booking-alerts"
    )
    expect(new Headers(listInit.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )

    const [actionUrl, actionInit] = fetchMock.mock.calls[1] as [
      string,
      RequestInit,
    ]
    expect(new URL(actionUrl).pathname).toBe(
      `/api/logistics/v1/rental-booking-alerts/${bookingId}/actions`
    )
    expect(actionInit.method).toBe("POST")
    expect(new Headers(actionInit.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
    expect(new Headers(actionInit.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(actionInit.body))).toEqual({
      expectedVersion: 7,
      action: "KEEP_DRAFT",
    })
  })

  it("keeps the public view and confirmation bearer-free", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            id: "55555555-5555-4555-8555-555555555555",
            revision: 1,
            state: "ACTIVE",
            expiresAt: "2026-07-27T09:00:00Z",
            viewUntil: "2026-07-28T09:00:00Z",
            viewOnly: false,
            bookedOrderId: null,
            groups: [],
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        )
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            bookingId: "66666666-6666-4666-8666-666666666666",
            state: "PENDING",
            orderId: null,
            statusPath: "/status",
            errorCode: null,
          }),
          { status: 202, headers: { "Content-Type": "application/json" } }
        )
      )
    vi.stubGlobal("fetch", fetchMock)

    await getPublicPresentation("opaque-token")
    await confirmPublicPresentation({
      token: "opaque-token",
      selectedRentalItemIds: [CABIN_ID],
      idempotencyKey: IDEMPOTENCY_KEY,
    })

    for (const [, init] of fetchMock.mock.calls as [string, RequestInit][]) {
      expect(new Headers(init?.headers).has("Authorization")).toBe(false)
    }
    const [, confirmInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(new Headers(confirmInit.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
  })

  it("returns a typed rejected booking from the public 409 response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            bookingId: "66666666-6666-4666-8666-666666666666",
            state: "REJECTED",
            orderId: "77777777-7777-4777-8777-777777777777",
            statusPath: "/status",
            errorCode: "UNIT_NOT_AVAILABLE",
          }),
          { status: 409, headers: { "Content-Type": "application/json" } }
        )
      )
    )

    await expect(
      confirmPublicPresentation({
        token: "opaque-token",
        selectedRentalItemIds: [CABIN_ID],
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toMatchObject({ state: "REJECTED" })
  })
})
