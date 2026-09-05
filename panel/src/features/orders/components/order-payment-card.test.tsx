import { act, cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { OrderPaymentCard } from "./order-payment-card"
import { observeOrderPayment } from "@/features/orders/domain/order-payment"
import { orderPaymentFixture } from "@/features/orders/domain/order-payment.fixtures"

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe("immutable order payment card", () => {
  it("shows a supermarket-style bill with furniture quantity, monthly unit price, term and total", () => {
    const onConfirm = vi.fn()
    render(
      <OrderPaymentCard
        mode="TEST"
        observation={observeOrderPayment(orderPaymentFixture())}
        onConfirm={onConfirm}
        onRefresh={() => {}}
      />
    )
    expect(screen.getByText("Чек заказа ORD-000042")).toBeTruthy()
    expect(screen.getByText("Кровать")).toBeTruthy()
    expect(screen.getByText(/2 шт. × 700 ₽\/шт.\/мес. × 3 мес./)).toBeTruthy()
    expect(screen.getByText(/1 услуга × 12\s000 ₽ · однократно/)).toBeTruthy()
    expect(screen.getByText(/41\s700 ₽/)).toBeTruthy()
    expect(
      screen.getByText(/Не является кассовым или фискальным чеком/)
    ).toBeTruthy()
    expect(
      screen.getByText(/Тестовый режим: деньги не списываются/)
    ).toBeTruthy()
    expect(screen.queryByText("Оплачено — тестовый режим")).toBeNull()
    expect(onConfirm).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole("button", { name: "Оплатить (тест)" }))
    expect(onConfirm).toHaveBeenCalledOnce()
    expect(screen.queryByText("Оплачено — тестовый режим")).toBeNull()
  })

  it("labels excluded delivery as unknown instead of a free line", () => {
    const payment = orderPaymentFixture()
    payment.receipt = {
      ...payment.receipt!,
      deliveryIncluded: false,
      lines: payment.receipt!.lines.slice(0, 2),
      totalRubles: "29700",
    }
    render(
      <OrderPaymentCard
        mode="TEST"
        observation={observeOrderPayment(payment)}
        onRefresh={() => {}}
      />
    )
    expect(screen.getByText(/Доставка в сумму чека не включена/)).toBeTruthy()
    expect(screen.queryByText("0 ₽")).toBeNull()
  })

  it("disables the action exactly at the server-anchored deadline and requests reconciliation only once", () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] })
    let monotonicNow = 10_000
    vi.spyOn(performance, "now").mockImplementation(() => monotonicNow)
    const onRefresh = vi.fn()
    render(
      <OrderPaymentCard
        mode="TEST"
        observation={observeOrderPayment(
          orderPaymentFixture({ serverTime: "2026-09-05T12:04:59Z" })
        )}
        onConfirm={() => {}}
        onRefresh={onRefresh}
      />
    )
    expect(screen.getByRole("timer").textContent).toBe("00:01")
    act(() => {
      monotonicNow += 1_000
      vi.advanceTimersByTime(1_000)
    })
    expect(screen.getByRole("timer").textContent).toBe("00:00")
    expect(
      (
        screen.getByRole("button", {
          name: "Оплатить (тест)",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    expect(screen.getByText("Время оплаты истекло")).toBeTruthy()
    expect(
      screen.queryByText("Бронь отменена: время оплаты истекло")
    ).toBeNull()
    expect(onRefresh).toHaveBeenCalledOnce()
    act(() => {
      monotonicNow += 10_000
      vi.advanceTimersByTime(10_000)
    })
    expect(onRefresh).toHaveBeenCalledOnce()
  })

  it.each([
    ["EXPIRING", "Освобождаем бронь"],
    ["EXPIRED", "Бронь отменена: время оплаты истекло"],
    ["CANCELLED", "Заказ отменён"],
  ] as const)(
    "renders the server %s outcome without a pay button",
    (state, label) => {
      render(
        <OrderPaymentCard
          mode="TEST"
          observation={observeOrderPayment(
            orderPaymentFixture({ state, canConfirm: false })
          )}
          onConfirm={() => {}}
          onRefresh={() => {}}
        />
      )
      expect(screen.getByText(label)).toBeTruthy()
      expect(
        screen.queryByRole("button", { name: "Оплатить (тест)" })
      ).toBeNull()
      expect(screen.getByText(/41\s700 ₽/)).toBeTruthy()
    }
  )

  it("shows a paid badge only with server confirmation and its exact source", () => {
    const source = orderPaymentFixture({
      state: "CONFIRMED",
      canConfirm: false,
      source: "MANAGER_CONFIRMATION",
    })
    const view = render(
      <OrderPaymentCard
        mode="MANAGER"
        observation={observeOrderPayment(source)}
        onRefresh={() => {}}
      />
    )
    expect(screen.getByText("Оплата подтверждена менеджером")).toBeTruthy()
    expect(screen.queryByText("Оплачено — тестовый режим")).toBeNull()
    view.rerender(
      <OrderPaymentCard
        mode="TEST"
        observation={observeOrderPayment({
          ...source,
          source: "PRESENTATION_TEST",
        })}
        onRefresh={() => {}}
      />
    )
    expect(screen.getByText("Оплачено — тестовый режим")).toBeTruthy()
    expect(screen.queryByRole("timer")).toBeNull()
  })

  it("does not infer zero payment from historical null evidence", () => {
    render(
      <OrderPaymentCard
        mode="MANAGER"
        observation={observeOrderPayment(
          orderPaymentFixture({
            state: null,
            receipt: null,
            startedAt: null,
            expiresAt: null,
            canConfirm: false,
          })
        )}
        onConfirm={() => {}}
        onRefresh={() => {}}
      />
    )
    expect(screen.getByText("Нет сведений об оплате")).toBeTruthy()
    expect(
      screen.getByText(/Это не подтверждение оплаты и не нулевая стоимость/)
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Подтвердить оплату" })
    ).toBeNull()
    expect(screen.queryByText("0 ₽")).toBeNull()
  })

  it("keeps a failed refresh visible and blocks confirming stale evidence", () => {
    render(
      <OrderPaymentCard
        mode="MANAGER"
        observation={observeOrderPayment(orderPaymentFixture())}
        unavailable
        error="Сервис недоступен"
        onConfirm={() => {}}
        onRefresh={() => {}}
      />
    )
    expect(screen.getByRole("alert").textContent).toContain("Сервис недоступен")
    expect(
      (
        screen.getByRole("button", {
          name: "Подтвердить оплату",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    expect(screen.queryByText("Оплата подтверждена менеджером")).toBeNull()
  })
})
