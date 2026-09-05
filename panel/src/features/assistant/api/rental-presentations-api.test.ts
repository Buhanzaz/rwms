import { afterEach, describe, expect, it, vi } from "vitest"

import {
  actOnRentalBookingAlert,
  confirmPublicPresentation,
  confirmPublicPresentationTestPayment,
  getPublicPresentation,
  getPublicPresentationPayment,
  getClientPresentation,
  getRentalBookingAlerts,
  getRentalSettings,
  publishClientPresentation,
  updateRentalSettings,
} from "@/features/assistant/api/rental-presentations-api"
import { orderPaymentFixture } from "@/features/orders/domain/order-payment.fixtures"

const INQUIRY_ID = "11111111-1111-4111-8111-111111111111"
const WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const CABIN_ID = "33333333-3333-4333-8333-333333333333"
const IDEMPOTENCY_KEY = "44444444-4444-4444-8444-444444444444"

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("rental presentation API", () => {
  it("reads and confirms only the exact public booking payment without Bearer or client money", async () => {
    const payment = orderPaymentFixture()
    const fetchMock = vi
      .fn()
      .mockImplementation(async () => Response.json(payment))
    vi.stubGlobal("fetch", fetchMock)
    const scope = {
      token: "opaque/token",
      bookingId: INQUIRY_ID,
      orderId: payment.orderId,
    }
    await expect(getPublicPresentationPayment(scope)).resolves.toEqual(payment)
    await expect(
      confirmPublicPresentationTestPayment({
        ...scope,
        expectedVersion: 4,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toEqual(payment)
    const [readUrl, readInit] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(readUrl).pathname).toBe(
      "/api/logistics/public/v1/client-presentations/opaque%2Ftoken/bookings/" +
        INQUIRY_ID +
        "/payment"
    )
    expect(new Headers(readInit.headers).get("Authorization")).toBeNull()
    const [confirmUrl, confirmInit] = fetchMock.mock.calls[1] as [
      string,
      RequestInit,
    ]
    expect(confirmUrl).toBe(readUrl + "/confirm-test")
    expect(new Headers(confirmInit.headers).get("Authorization")).toBeNull()
    expect(new Headers(confirmInit.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(confirmInit.method).toBe("POST")
    expect(JSON.parse(String(confirmInit.body))).toEqual({ expectedVersion: 4 })
  })

  it("keeps public payment expiry conflicts and checks order identity", async () => {
    const payment = orderPaymentFixture()
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json(
          {
            code: "ORDER_PAYMENT_NOT_PENDING",
            detail: "Время оплаты истекло",
          },
          { status: 409 }
        )
      )
    )
    await expect(
      confirmPublicPresentationTestPayment({
        token: "token",
        bookingId: INQUIRY_ID,
        orderId: payment.orderId,
        expectedVersion: 4,
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).rejects.toMatchObject({ status: 409, code: "ORDER_PAYMENT_NOT_PENDING" })
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(payment)))
    await expect(
      getPublicPresentationPayment({
        token: "token",
        bookingId: INQUIRY_ID,
        orderId: WAREHOUSE_ID,
      })
    ).rejects.toMatchObject({ code: "INVALID_API_RESPONSE" })
  })

  it("requires a nullable server booking identity for reload instead of accepting a missing field", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation(async () => Response.json({ groups: [] }))
    vi.stubGlobal("fetch", fetchMock)
    await expect(getPublicPresentation("token")).rejects.toMatchObject({
      code: "INVALID_API_RESPONSE",
    })
    fetchMock.mockImplementation(async () =>
      Response.json({ groups: [], bookingId: INQUIRY_ID })
    )
    await expect(getPublicPresentation("token")).resolves.toMatchObject({
      bookingId: INQUIRY_ID,
    })
  })

  it("validates snapshot prices on both public and authenticated presentation reads", async () => {
    const fetchMock = vi.fn().mockImplementation(async () =>
      Response.json({
        groups: [{ cabins: [{ pricingVersion: 1, monthlyPriceRubles: 9500 }] }],
      })
    )
    vi.stubGlobal("fetch", fetchMock)
    await expect(getPublicPresentation("token")).rejects.toMatchObject({
      code: "INVALID_API_RESPONSE",
    })
    await expect(
      getClientPresentation({ accessToken: "token", inquiryId: INQUIRY_ID })
    ).rejects.toMatchObject({ code: "INVALID_API_RESPONSE" })
  })
  it("sends all rental hold settings in the exact PUT payload", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          version: 7,
          chatSelectionHoldMinutes: 10,
          manualBookingHoldMinutes: 60,
          presentationHoldMinutes: 60,
          draftReservationHoldMinutes: 1440,
          lateChangeNoticeDays: 2,
          lateChangeFeeMode: "FIXED",
          lateChangeFeeValue: "9223372036854775807",
          rentalSupportPhone: "+74951234567",
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
      lateChangeNoticeDays: 2,
      lateChangeFeeMode: "FIXED",
      lateChangeFeeValue: "9223372036854775807",
      rentalSupportPhone: "+74951234567",
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
      lateChangeNoticeDays: 2,
      lateChangeFeeMode: "FIXED",
      lateChangeFeeValue: "9223372036854775807",
      rentalSupportPhone: "+74951234567",
    })
  })

  it.each([
    { lateChangeFeeMode: undefined },
    { lateChangeFeeMode: "UNKNOWN" },
    { lateChangeFeeMode: "FIXED", lateChangeFeeValue: 1500 },
    { lateChangeFeeMode: "FIXED", lateChangeFeeValue: "9223372036854775808" },
    { lateChangeFeeMode: "FIXED", lateChangeFeeValue: "1.25" },
    { lateChangeFeeMode: "PERCENT", lateChangeFeeValue: "100.01" },
    { lateChangeFeeMode: "PERCENT", lateChangeFeeValue: "1.001" },
    { lateChangeFeeMode: null, lateChangeFeeValue: "0" },
    { lateChangeNoticeDays: -1 },
    { rentalSupportPhone: "customer phone" },
  ])("rejects incomplete or unsafe rental settings: %j", async (invalid) => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            version: 0,
            lateChangeNoticeDays: 2,
            lateChangeFeeMode: null,
            lateChangeFeeValue: null,
            rentalSupportPhone: null,
            ...invalid,
          }),
          { status: 200 }
        )
      )
    )
    await expect(getRentalSettings("access-token")).rejects.toMatchObject({
      code: "INVALID_API_RESPONSE",
    })
  })

  it("preserves unconfigured policy as null instead of zero", async () => {
    const settings = {
      version: 0,
      lateChangeNoticeDays: 2,
      lateChangeFeeMode: null,
      lateChangeFeeValue: null,
      rentalSupportPhone: null,
    }
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify(settings), { status: 200 })
        )
    )
    await expect(getRentalSettings("access-token")).resolves.toEqual(settings)
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
    const requestableDeliveryDates = [
      "2026-08-25",
      "2026-08-26",
      "2026-08-27",
      "2026-08-28",
    ]
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
            requestableDeliveryDates,
            bookingId: null,
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

    const publicPresentation = await getPublicPresentation("opaque-token")
    await confirmPublicPresentation({
      token: "opaque-token",
      selections: [{ rentalItemId: CABIN_ID, equipment: [] }],
      idempotencyKey: IDEMPOTENCY_KEY,
    })

    for (const [, init] of fetchMock.mock.calls as [string, RequestInit][]) {
      expect(new Headers(init?.headers).has("Authorization")).toBe(false)
    }
    expect(publicPresentation.requestableDeliveryDates).toEqual(
      requestableDeliveryDates
    )
    const [, confirmInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(new Headers(confirmInit.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(confirmInit.body))).toEqual({
      selections: [{ rentalItemId: CABIN_ID, equipment: [] }],
    })
  })

  it("sends normal client date, duration and delivery details exactly once", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
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

    await confirmPublicPresentation({
      token: "opaque-token",
      selections: [{ rentalItemId: CABIN_ID, equipment: [] }],
      desiredDeliveryWindows: [
        {
          startDate: "2026-08-14",
          endDate: "2026-08-14",
        },
      ],
      rentalMonths: 6,
      deliveryAddress: "Санкт-Петербург, Невский проспект, 1",
      latitude: 59.9343,
      longitude: 30.3351,
      additionalContacts: [{ name: "Иван", phone: "+79990000000" }],
      idempotencyKey: IDEMPOTENCY_KEY,
    })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      "/api/logistics/public/v1/client-presentations/opaque-token/bookings"
    )
    expect(new Headers(init.headers).get("Authorization")).toBeNull()
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      IDEMPOTENCY_KEY
    )
    expect(JSON.parse(String(init.body))).toEqual({
      selections: [{ rentalItemId: CABIN_ID, equipment: [] }],
      desiredDeliveryWindows: [
        {
          startDate: "2026-08-14",
          endDate: "2026-08-14",
        },
      ],
      rentalMonths: 6,
      deliveryAddress: "Санкт-Петербург, Невский проспект, 1",
      latitude: 59.9343,
      longitude: 30.3351,
      additionalContacts: [{ name: "Иван", phone: "+79990000000" }],
    })
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
        selections: [{ rentalItemId: CABIN_ID, equipment: [] }],
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).resolves.toMatchObject({ state: "REJECTED" })
  })

  it("maps a public presentation transport failure to safe Russian copy", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("Failed to fetch public token"))
    )

    await expect(getPublicPresentation("opaque-token")).rejects.toMatchObject({
      status: 0,
      code: "NETWORK_ERROR",
      message:
        "Не удалось связаться с сервером. Проверьте подключение и повторите попытку.",
      diagnosticMessage: "Failed to fetch public token",
    })
  })

  it("maps malformed successful public presentation JSON to a typed safe error", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("{not-json", {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
    )

    const request = getPublicPresentation("opaque-token")
    await expect(request).rejects.toMatchObject({
      status: 502,
      code: "INVALID_API_RESPONSE",
      message:
        "Сервис вернул некорректные данные. Обновите страницу или повторите попытку позже.",
    })
    await expect(request).rejects.toHaveProperty("diagnosticMessage")
  })

  it("rejects a malformed successful booking response without exposing it", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ internalState: "BROKEN" }), {
          status: 202,
          headers: { "Content-Type": "application/json" },
        })
      )
    )

    await expect(
      confirmPublicPresentation({
        token: "opaque-token",
        selections: [{ rentalItemId: CABIN_ID, equipment: [] }],
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    ).rejects.toMatchObject({
      status: 502,
      code: "INVALID_API_RESPONSE",
      message:
        "Сервис вернул некорректные данные. Обновите страницу или повторите попытку позже.",
      diagnosticMessage: "Public presentation booking response is invalid",
    })
  })
})
