import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import type { CurrentUser } from "@/features/auth/auth-model"
import type { PendingBookingChangeQuote } from "@/features/orders/api/order-booking-change-quotes-api"
import { ApiError } from "@/lib/api-client"

const api = vi.hoisted(() => ({
  listPendingBookingChangeQuotes: vi.fn(),
  listOrderBookingChangeQuotes: vi.fn(),
  waiveOrderBookingChangeQuote: vi.fn(),
  getOrder: vi.fn(),
}))
const auth = vi.hoisted(() => ({
  accessToken: "token" as string | null,
  currentUser: null as CurrentUser | null,
}))
vi.mock("@/features/auth/use-auth", () => ({ useAuth: () => auth }))
vi.mock("@/features/orders/api/order-booking-change-quotes-api", () => ({
  ...api,
  PENDING_BOOKING_CHANGE_QUOTES_QUERY_KEY: ["pending-booking-change-quotes"],
  ORDER_BOOKING_CHANGE_QUOTES_QUERY_KEY: ["order-booking-change-quotes"],
}))
vi.mock("@/features/orders/api/orders-api", () => ({
  ORDERS_QUERY_KEY: ["orders"],
  getOrder: api.getOrder,
  createOrderIdempotencyKey: () => "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
}))
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

import { ManagerBookingChangeQuotes } from "./manager-booking-change-quotes"

const item: PendingBookingChangeQuote = {
  orderId: "22222222-2222-4222-8222-222222222222",
  warehouseId: "33333333-3333-4333-8333-333333333333",
  quote: {
    quoteId: "44444444-4444-4444-8444-444444444444",
    version: 3,
    bookingId: "55555555-5555-4555-8555-555555555555",
    bookingVersion: 12,
    operation: "RESCHEDULE",
    oldSlotId: "66666666-6666-4666-8666-666666666666",
    slotId: "77777777-7777-4777-8777-777777777777",
    slotVersion: 8,
    amountRubles: "2500",
    settlement: "PAYMENT_REQUIRED",
    applicationState: "OFFERED",
    testPaymentAvailable: false,
    supportPhone: null,
    expiresAt: "2099-09-09T10:00:00Z",
    noticeDays: 2,
    deliveryDate: "2026-09-07",
    warehouseTimeZone: "Europe/Moscow",
    targetDeliveryDate: "2026-09-09",
    targetWindowStart: "10:00:00",
    targetWindowEnd: "12:00:00",
  },
}

beforeEach(() => {
  auth.accessToken = "token"
  auth.currentUser = {
    id: "11111111-1111-4111-8111-111111111111",
    username: "manager",
    displayName: "Менеджер",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: "SYSTEM_ADMIN",
    rentalAccess: true,
    warehouseAccessAll: false,
    warehouseAccesses: [{ warehouseId: item.warehouseId, level: "EDIT" }],
  }
  api.listPendingBookingChangeQuotes.mockReset().mockResolvedValue([item])
  api.listOrderBookingChangeQuotes
    .mockReset()
    .mockRejectedValue(new Error("Full order quotes forbidden"))
  api.getOrder.mockReset().mockRejectedValue(new Error("Full order forbidden"))
  api.waiveOrderBookingChangeQuote.mockReset().mockResolvedValue({
    ...item.quote,
    version: 4,
    amountRubles: "0",
    settlement: "WAIVED",
  })
})
afterEach(cleanup)

function renderQuotes() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ManagerBookingChangeQuotes />
    </QueryClientProvider>
  )
}

async function expand() {
  const user = userEvent.setup()
  await user.click(await screen.findByRole("button", { name: "Неустойки · 1" }))
  return user
}

describe("manager fee-only quote entry", () => {
  it("uses one initially collapsed feed and shows target date without opening the order", async () => {
    renderQuotes()
    const trigger = await screen.findByRole("button", { name: "Неустойки · 1" })
    expect(trigger.getAttribute("aria-expanded")).toBe("false")
    expect(screen.queryByText("Заказ 22222222")).toBeNull()
    await userEvent.setup().click(trigger)
    expect(screen.getByText("Заказ 22222222")).toBeTruthy()
    expect(screen.getByText("09.09.2026 · 10:00–12:00")).toBeTruthy()
    expect(screen.queryByRole("link")).toBeNull()
    expect(api.listPendingBookingChangeQuotes).toHaveBeenCalledTimes(1)
    expect(api.listOrderBookingChangeQuotes).not.toHaveBeenCalled()
    expect(api.getOrder).not.toHaveBeenCalled()
  })

  it.each(["view", "rental", "role", "token"] as const)(
    "does not request fee facts without %s access",
    (missing) => {
      if (missing === "view")
        auth.currentUser!.warehouseAccesses[0].level = "VIEW"
      if (missing === "rental") auth.currentUser!.rentalAccess = false
      if (missing === "role") auth.currentUser!.globalRole = "CUSTOMER"
      if (missing === "token") auth.accessToken = null
      renderQuotes()
      expect(api.listPendingBookingChangeQuotes).not.toHaveBeenCalled()
      expect(screen.queryByRole("button")).toBeNull()
    }
  )

  it("shares one feed across multiple orders instead of issuing per-order reads", async () => {
    api.listPendingBookingChangeQuotes.mockResolvedValue([
      item,
      {
        ...item,
        orderId: "88888888-8888-4888-8888-888888888888",
        quote: {
          ...item.quote,
          quoteId: "99999999-9999-4999-8999-999999999999",
        },
      },
    ])
    renderQuotes()
    await userEvent
      .setup()
      .click(await screen.findByRole("button", { name: "Неустойки · 2" }))
    expect(screen.getByText("Заказ 22222222")).toBeTruthy()
    expect(screen.getByText("Заказ 88888888")).toBeTruthy()
    expect(api.listPendingBookingChangeQuotes).toHaveBeenCalledTimes(1)
    expect(api.listOrderBookingChangeQuotes).not.toHaveBeenCalled()
    expect(api.getOrder).not.toHaveBeenCalled()
  })

  it("submits a fee-only waiver even when full-order reads are forbidden", async () => {
    renderQuotes()
    const user = await expand()
    await user.click(screen.getByRole("button", { name: "Отменить неустойку" }))
    const dialog = screen.getByRole("dialog")
    fireEvent.change(within(dialog).getByRole("textbox"), {
      target: { value: "  Форс-мажор  " },
    })
    api.listPendingBookingChangeQuotes.mockResolvedValue([])
    await user.click(
      within(dialog).getByRole("button", { name: "Отменить неустойку" })
    )
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(api.waiveOrderBookingChangeQuote).toHaveBeenCalledWith({
      accessToken: "token",
      orderId: item.orderId,
      quoteId: item.quote.quoteId,
      expectedVersion: 3,
      reason: "Форс-мажор",
      idempotencyKey: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    })
    expect(screen.queryByRole("button", { name: /Неустойки ·/ })).toBeNull()
    expect(api.getOrder).not.toHaveBeenCalled()
    expect(api.listOrderBookingChangeQuotes).not.toHaveBeenCalled()
  })

  it("keeps the form and reason when a 409 refresh removes the final pending quote", async () => {
    api.waiveOrderBookingChangeQuote.mockRejectedValueOnce(
      new ApiError("Conflict", 409)
    )
    renderQuotes()
    const user = await expand()
    await user.click(screen.getByRole("button", { name: "Отменить неустойку" }))
    const dialog = screen.getByRole("dialog")
    const reason = within(dialog).getByRole("textbox")
    await user.type(reason, "Форс-мажор")
    api.listPendingBookingChangeQuotes.mockResolvedValue([])
    await user.click(
      within(dialog).getByRole("button", { name: "Отменить неустойку" })
    )
    await screen.findByText("Предложение больше недоступно")
    expect((reason as HTMLTextAreaElement).value).toBe("Форс-мажор")
    expect(
      (
        within(dialog).getByRole("button", {
          name: "Отменить неустойку",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    expect(api.waiveOrderBookingChangeQuote).toHaveBeenCalledTimes(1)
  })

  it("shows a real error and retries the fee feed while collapsed", async () => {
    api.listPendingBookingChangeQuotes.mockRejectedValueOnce(
      new Error("Логистика недоступна")
    )
    renderQuotes()
    expect(await screen.findByText("Логистика недоступна")).toBeTruthy()
    await userEvent
      .setup()
      .click(
        screen.getByRole("button", { name: "Повторить загрузку неустоек" })
      )
    expect(
      await screen.findByRole("button", { name: "Неустойки · 1" })
    ).toBeTruthy()
  })
})
