import { afterEach, describe, expect, it, vi } from "vitest"
import {
  acknowledgeRentalBookingChangeAlert,
  getRentalBookingChangeAlerts,
} from "./rental-booking-change-alerts-api"

const mutationId = "11111111-1111-4111-8111-111111111111"
const alert = {
  mutationId,
  bookingId: "22222222-2222-4222-8222-222222222222",
  orderId: "33333333-3333-4333-8333-333333333333",
  warehouseId: "44444444-4444-4444-8444-444444444444",
  version: 3,
  operation: "RESCHEDULE",
  canOpenOrder: true,
  occurredAt: "2026-09-05T10:00:00Z",
  previousDeliveryDate: "2026-09-07",
  newDeliveryDate: "2026-09-09",
  deliveryAddress: "Москва, ул. Лесная, 10",
  feeRubles: "9223372036854775807",
  settlement: "TEST_PAID",
}
afterEach(() => vi.unstubAllGlobals())

describe("rental booking change alerts API", () => {
  it("reads the authorized feed through the same-origin gateway with exact rubles", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify([alert]), { status: 200 }))
    vi.stubGlobal("fetch", fetch)
    await expect(getRentalBookingChangeAlerts("token")).resolves.toEqual([
      alert,
    ])
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      "/api/logistics/v1/rental-booking-change-alerts"
    )
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer token")
  })

  it("acknowledges only the mutation with its version and idempotency key", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal("fetch", fetch)
    await acknowledgeRentalBookingChangeAlert({
      accessToken: "token",
      mutationId,
      expectedVersion: 3,
      idempotencyKey: "55555555-5555-4555-8555-555555555555",
    })
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      `/api/logistics/v1/rental-booking-change-alerts/${mutationId}/acknowledgement`
    )
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      "55555555-5555-4555-8555-555555555555"
    )
    expect(JSON.parse(String(init.body))).toEqual({ expectedVersion: 3 })
  })

  it.each([null, "0"])(
    "keeps null and zero distinct: %s",
    async (feeRubles) => {
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockResolvedValue(
            new Response(JSON.stringify([{ ...alert, feeRubles }]), {
              status: 200,
            })
          )
      )
      expect((await getRentalBookingChangeAlerts("token"))[0].feeRubles).toBe(
        feeRubles
      )
    }
  )

  it.each([
    { mutationId: "invalid" },
    { version: -1 },
    { operation: "UPDATE" },
    { canOpenOrder: undefined },
    { previousDeliveryDate: "2026-02-30" },
    { newDeliveryDate: "2026-9-9" },
    { occurredAt: "2026-09-05" },
    { feeRubles: 1500 },
    { feeRubles: "9223372036854775808" },
    { feeRubles: "1.50" },
    { feeRubles: undefined },
    { settlement: "PAID" },
    { deliveryAddress: "" },
  ])("rejects malformed server facts %j", async (invalid) => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify([{ ...alert, ...invalid }]), {
            status: 200,
          })
        )
    )
    await expect(getRentalBookingChangeAlerts("token")).rejects.toMatchObject({
      code: "INVALID_API_RESPONSE",
    })
  })

  it("rejects duplicate mutation identities", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify([alert, alert]), { status: 200 })
        )
    )
    await expect(getRentalBookingChangeAlerts("token")).rejects.toMatchObject({
      code: "INVALID_API_RESPONSE",
    })
  })

  it.each([403, 409, 503])(
    "preserves server failure %s without fake acknowledgement",
    async (status) => {
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockResolvedValue(
            new Response(
              JSON.stringify({
                code: "CHANGE_ALERT_FAILURE",
                detail: "Unavailable",
              }),
              { status }
            )
          )
      )
      await expect(
        acknowledgeRentalBookingChangeAlert({
          accessToken: "token",
          mutationId,
          expectedVersion: 3,
          idempotencyKey: mutationId,
        })
      ).rejects.toMatchObject({ status, code: "CHANGE_ALERT_FAILURE" })
    }
  )
})
