import { cleanup, render, screen } from "@testing-library/react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, describe, expect, it } from "vitest"

import { OrderCustomerOverview } from "./order-customer-overview"

afterEach(cleanup)

describe("OrderCustomerOverview", () => {
  it("keeps the known order navigable when its detail read is unavailable", () => {
    render(
      <MemoryRouter>
        <OrderCustomerOverview
          order={null}
          orderId="11111111-1111-4111-8111-111111111111"
          orderNumber="ORD-000123"
          state="unavailable"
        />
      </MemoryRouter>
    )

    expect(
      screen
        .getByRole("link", { name: "Заказ ORD-000123" })
        .getAttribute("href")
    ).toBe("/orders/11111111-1111-4111-8111-111111111111")
    expect(screen.getByText("Данные заказа и клиента недоступны.")).toBeTruthy()
  })
})
