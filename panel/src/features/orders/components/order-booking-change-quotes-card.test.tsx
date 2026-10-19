import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import type { OrderBookingChangeQuote } from "@/features/orders/api/order-booking-change-quotes-api"

const api = vi.hoisted(() => ({
  listOrderBookingChangeQuotes: vi.fn(),
  waiveOrderBookingChangeQuote: vi.fn(),
}))
const runtime = vi.hoisted(() => ({
  accessToken: "orders-token" as string | null,
  currentUser: { id: "11111111-1111-4111-8111-111111111111" },
  canManageBookingChanges: vi.fn(),
}))
vi.mock("@/features/orders/api/order-booking-change-quotes-api", () => ({
  ORDER_BOOKING_CHANGE_QUOTES_QUERY_KEY: ["order-booking-change-quotes"],
  ...api,
}))
vi.mock("@/features/orders/orders-module-context", () => ({
  useOrdersModule: () => runtime,
}))
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

import { OrderBookingChangeQuotesCard } from "./order-booking-change-quotes-card"

const ORDER_ID = "22222222-2222-4222-8222-222222222222"
const WAREHOUSE_ID = "33333333-3333-4333-8333-333333333333"
const QUOTE_ID = "44444444-4444-4444-8444-444444444444"

function quote(
  changes: Partial<OrderBookingChangeQuote> = {}
): OrderBookingChangeQuote {
  return {
    quoteId: QUOTE_ID,
    version: 3,
    bookingId: "55555555-5555-4555-8555-555555555555",
    bookingVersion: 12,
    operation: "CANCEL",
    oldSlotId: "66666666-6666-4666-8666-666666666666",
    slotId: null,
    slotVersion: null,
    amountRubles: "2500",
    settlement: "PAYMENT_REQUIRED",
    applicationState: "OFFERED",
    testPaymentAvailable: false,
    supportPhone: null,
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
    noticeDays: 2,
    deliveryDate: "2026-09-07",
    warehouseTimeZone: "Europe/Moscow",
    targetDeliveryDate: null,
    targetWindowStart: null,
    targetWindowEnd: null,
    ...changes,
  }
}

function renderCard(warehouseId: string | null = WAREHOUSE_ID) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <OrderBookingChangeQuotesCard
        orderId={ORDER_ID}
        warehouseId={warehouseId}
      />
    </QueryClientProvider>
  )
  return { ...view, queryClient }
}

async function openWaiver() {
  const user = userEvent.setup()
  await user.click(
    await screen.findByRole("button", { name: "Отменить неустойку" })
  )
  return {
    user,
    dialog: screen.getByRole("dialog"),
    reason: screen.getByRole("textbox", { name: "Причина отмены неустойки" }),
  }
}

beforeEach(() => {
  runtime.accessToken = "orders-token"
  runtime.canManageBookingChanges.mockReset().mockReturnValue(true)
  api.listOrderBookingChangeQuotes.mockReset().mockResolvedValue([quote()])
  api.waiveOrderBookingChangeQuote
    .mockReset()
    .mockResolvedValue(
      quote({ version: 4, settlement: "WAIVED", amountRubles: "0" })
    )
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe("OrderBookingChangeQuotesCard", () => {
  it.each(["capability", "token", "warehouse"] as const)(
    "does not fetch or reveal controls without %s",
    (missing) => {
      if (missing === "capability")
        runtime.canManageBookingChanges.mockReturnValue(false)
      if (missing === "token") runtime.accessToken = null
      renderCard(missing === "warehouse" ? null : WAREHOUSE_ID)
      expect(api.listOrderBookingChangeQuotes).not.toHaveBeenCalled()
      expect(screen.queryByText("Изменения клиента и неустойка")).toBeNull()
    }
  )

  it("adds no empty section to orders without current quotes", async () => {
    api.listOrderBookingChangeQuotes.mockResolvedValue([])
    renderCard()
    await waitFor(() =>
      expect(api.listOrderBookingChangeQuotes).toHaveBeenCalledWith(
        "orders-token",
        ORDER_ID
      )
    )
    expect(screen.queryByText("Изменения клиента и неустойка")).toBeNull()
  })

  it("shows exact whole rubles and the warehouse calendar date", async () => {
    api.listOrderBookingChangeQuotes.mockResolvedValue([
      quote({ amountRubles: "9223372036854775807" }),
    ])
    renderCard()
    expect(await screen.findByText("9 223 372 036 854 775 807 ₽")).toBeTruthy()
    expect(screen.getByText("07.09.2026")).toBeTruthy()
    expect(screen.getByText("Ожидает оплаты")).toBeTruthy()
  })

  it("does not replace an unconfigured fee with zero and permits its explicit waiver", async () => {
    api.listOrderBookingChangeQuotes.mockResolvedValue([
      quote({ amountRubles: null, settlement: "POLICY_UNCONFIGURED" }),
    ])
    renderCard()
    expect(await screen.findByText("Не указана")).toBeTruthy()
    expect(screen.getByText("Правило неустойки не настроено")).toBeTruthy()
    expect(
      (
        screen.getByRole("button", {
          name: "Отменить неустойку",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(false)
  })

  it.each([
    { settlement: "NOT_REQUIRED", amountRubles: "0" },
    { settlement: "WAIVED", amountRubles: "0" },
    { settlement: "TEST_PAID" },
    { applicationState: "APPLYING" },
    { applicationState: "APPLIED" },
  ] as Partial<OrderBookingChangeQuote>[])(
    "never offers waiver for settled or applying facts %j",
    async (changes) => {
      api.listOrderBookingChangeQuotes.mockResolvedValue([quote(changes)])
      renderCard()
      await screen.findByText("Изменения клиента и неустойка")
      expect(
        screen.queryByRole("button", { name: "Отменить неустойку" })
      ).toBeNull()
    }
  )

  it("disables an already expired offer", async () => {
    api.listOrderBookingChangeQuotes.mockResolvedValue([
      quote({ expiresAt: new Date(Date.now() - 1000).toISOString() }),
    ])
    renderCard()
    expect(
      (
        (await screen.findByRole("button", {
          name: "Отменить неустойку",
        })) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    expect(screen.getByText(/Срок предложения истёк/)).toBeTruthy()
  })

  it("disables an open confirmation at expiry without another poll or click", async () => {
    vi.useFakeTimers()
    api.listOrderBookingChangeQuotes.mockResolvedValue([
      quote({ expiresAt: new Date(Date.now() + 1000).toISOString() }),
    ])
    renderCard()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    fireEvent.click(screen.getByRole("button", { name: "Отменить неустойку" }))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1001)
    })
    const dialog = screen.getByRole("dialog")
    expect(
      (
        within(dialog).getByRole("button", {
          name: "Отменить неустойку",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    expect(api.waiveOrderBookingChangeQuote).not.toHaveBeenCalled()
  })

  it("requires a trimmed reason no longer than 2000 characters", async () => {
    renderCard()
    const { user, dialog, reason } = await openWaiver()
    fireEvent.change(reason, { target: { value: "   " } })
    await user.click(
      within(dialog).getByRole("button", { name: "Отменить неустойку" })
    )
    expect(reason.getAttribute("aria-invalid")).toBe("true")
    fireEvent.change(reason, { target: { value: "я".repeat(2001) } })
    await user.click(
      within(dialog).getByRole("button", { name: "Отменить неустойку" })
    )
    expect(api.waiveOrderBookingChangeQuote).not.toHaveBeenCalled()
  })

  it("waives only the quote with its own fence, then refreshes quotes and audit", async () => {
    const { queryClient } = renderCard()
    const invalidation = vi.spyOn(queryClient, "invalidateQueries")
    const { user, dialog, reason } = await openWaiver()
    fireEvent.change(reason, {
      target: { value: "  Подтверждённый форс-мажор  " },
    })
    api.listOrderBookingChangeQuotes.mockResolvedValue([
      quote({ version: 4, amountRubles: "0", settlement: "WAIVED" }),
    ])
    await user.click(
      within(dialog).getByRole("button", { name: "Отменить неустойку" })
    )
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(api.waiveOrderBookingChangeQuote).toHaveBeenCalledWith({
      accessToken: "orders-token",
      orderId: ORDER_ID,
      quoteId: QUOTE_ID,
      expectedVersion: 3,
      reason: "Подтверждённый форс-мажор",
      idempotencyKey: expect.stringMatching(/^[0-9a-f-]{36}$/i),
    })
    expect(await screen.findByText("0 ₽")).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Отменить неустойку" })
    ).toBeNull()
    expect(invalidation).toHaveBeenCalledWith({
      queryKey: [
        "order-booking-change-quotes",
        runtime.currentUser.id,
        ORDER_ID,
      ],
      exact: true,
    })
    expect(invalidation).toHaveBeenCalledWith({
      queryKey: ["orders", "history", runtime.currentUser.id, ORDER_ID],
      exact: true,
    })
    expect(invalidation).toHaveBeenCalledTimes(2)
  })

  it("retains reason and reuses identity after an uncertain failure", async () => {
    api.waiveOrderBookingChangeQuote.mockRejectedValueOnce(
      new Error("Сеть недоступна")
    )
    renderCard()
    const { user, dialog, reason } = await openWaiver()
    await user.type(reason, "Форс-мажор")
    await user.click(
      within(dialog).getByRole("button", { name: "Отменить неустойку" })
    )
    expect(await screen.findByText("Сеть недоступна")).toBeTruthy()
    expect((reason as HTMLTextAreaElement).value).toBe("Форс-мажор")
    await user.click(
      within(dialog).getByRole("button", { name: "Отменить неустойку" })
    )
    await waitFor(() =>
      expect(api.waiveOrderBookingChangeQuote).toHaveBeenCalledTimes(2)
    )
    expect(
      api.waiveOrderBookingChangeQuote.mock.calls[1][0].idempotencyKey
    ).toBe(api.waiveOrderBookingChangeQuote.mock.calls[0][0].idempotencyKey)
  })

  it("reloads a 409 fence without auto-submit or loss of the reason", async () => {
    api.waiveOrderBookingChangeQuote.mockRejectedValueOnce(
      new ApiError("Conflict", 409, "STALE_VERSION")
    )
    renderCard()
    const { user, dialog, reason } = await openWaiver()
    await user.type(reason, "Форс-мажор")
    api.listOrderBookingChangeQuotes.mockResolvedValue([quote({ version: 7 })])
    await user.click(
      within(dialog).getByRole("button", { name: "Отменить неустойку" })
    )
    await screen.findByText(/Проверьте обновлённые данные/)
    await waitFor(() =>
      expect(
        (
          within(dialog).getByRole("button", {
            name: "Отменить неустойку",
          }) as HTMLButtonElement
        ).disabled
      ).toBe(false)
    )
    expect(api.waiveOrderBookingChangeQuote).toHaveBeenCalledTimes(1)
    expect((reason as HTMLTextAreaElement).value).toBe("Форс-мажор")
    await user.click(
      within(dialog).getByRole("button", { name: "Отменить неустойку" })
    )
    await waitFor(() =>
      expect(api.waiveOrderBookingChangeQuote).toHaveBeenCalledTimes(2)
    )
    expect(
      api.waiveOrderBookingChangeQuote.mock.calls[1][0].expectedVersion
    ).toBe(7)
    expect(
      api.waiveOrderBookingChangeQuote.mock.calls[1][0].idempotencyKey
    ).not.toBe(api.waiveOrderBookingChangeQuote.mock.calls[0][0].idempotencyKey)
  })

  it("retains reason but cannot submit when the owner removes a stale quote", async () => {
    api.waiveOrderBookingChangeQuote.mockRejectedValueOnce(
      new ApiError("Conflict", 409, "STALE_VERSION")
    )
    renderCard()
    const { user, dialog, reason } = await openWaiver()
    await user.type(reason, "Форс-мажор")
    api.listOrderBookingChangeQuotes.mockResolvedValue([])
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
  })

  it("can retry a failed conflict refresh from the dialog without discarding the reason", async () => {
    api.waiveOrderBookingChangeQuote.mockRejectedValueOnce(
      new ApiError("Conflict", 409, "STALE_VERSION")
    )
    renderCard()
    const { user, dialog, reason } = await openWaiver()
    await user.type(reason, "Форс-мажор")
    api.listOrderBookingChangeQuotes.mockRejectedValueOnce(
      new Error("Временно недоступно")
    )
    await user.click(
      within(dialog).getByRole("button", { name: "Отменить неустойку" })
    )
    await within(dialog).findByRole("button", { name: "Обновить предложение" })
    expect(
      (
        within(dialog).getByRole("button", {
          name: "Отменить неустойку",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    api.listOrderBookingChangeQuotes.mockResolvedValue([quote({ version: 7 })])
    await user.click(
      within(dialog).getByRole("button", { name: "Обновить предложение" })
    )
    await waitFor(() =>
      expect(
        (
          within(dialog).getByRole("button", {
            name: "Отменить неустойку",
          }) as HTMLButtonElement
        ).disabled
      ).toBe(false)
    )
    expect((reason as HTMLTextAreaElement).value).toBe("Форс-мажор")
    expect(api.waiveOrderBookingChangeQuote).toHaveBeenCalledTimes(1)
  })

  it("locks duplicate submissions, editing and dismissal while pending", async () => {
    let finish!: (result: OrderBookingChangeQuote) => void
    api.waiveOrderBookingChangeQuote.mockReturnValue(
      new Promise<OrderBookingChangeQuote>((resolve) => {
        finish = resolve
      })
    )
    renderCard()
    const { user, dialog, reason } = await openWaiver()
    await user.type(reason, "Форс-мажор")
    await user.click(
      within(dialog).getByRole("button", { name: "Отменить неустойку" })
    )
    expect((reason as HTMLTextAreaElement).disabled).toBe(true)
    expect(
      (
        within(dialog).getByRole("button", {
          name: "Сохранение…",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    expect(
      (
        within(dialog).getByRole("button", {
          name: "Оставить неустойку",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    await user.keyboard("{Escape}")
    expect(screen.getByRole("dialog")).toBeTruthy()
    await act(async () =>
      finish(quote({ version: 4, settlement: "WAIVED", amountRubles: "0" }))
    )
  })

  it("surfaces a real feed error and supports retry", async () => {
    api.listOrderBookingChangeQuotes.mockRejectedValueOnce(
      new Error("В доступе отказано")
    )
    renderCard()
    expect(await screen.findByText("В доступе отказано")).toBeTruthy()
    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: "Повторить загрузку" }))
    expect(
      await screen.findByRole("button", { name: "Отменить неустойку" })
    ).toBeTruthy()
  })
})
