import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

const warehouse = vi.hoisted(() => ({
  id: "11111111-1111-4111-8111-111111111111" as string | null,
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({ selectedWarehouseId: warehouse.id }),
}))

import { useBookingSelection } from "@/features/booking/booking-selection-context"
import { BookingSelectionProvider } from "@/features/booking/booking-selection-provider"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

const CABIN_ID = "22222222-2222-4222-8222-222222222222"
const renderProbe = vi.fn()

function cabin(number = "БЫТ-001"): RentalItemDto {
  return {
    id: CABIN_ID,
    version: 1,
    warehouseId: "11111111-1111-4111-8111-111111111111",
    number,
    rentalTypeId: "33333333-3333-4333-8333-333333333333",
    dimensionId: "44444444-4444-4444-8444-444444444444",
    finishingId: "55555555-5555-4555-8555-555555555555",
    type: "БК-1",
    dimensions: "2.4x6",
    finishing: "ДВП",
    category: "Новая",
    characteristics: [],
    linoleum: true,
    status: "FREE",
    comment: null,
    contents: null,
    contentsItems: [],
    shipmentDate: null,
    tenant: null,
    price: null,
    passport: {},
    tags: [],
  }
}

function Probe() {
  renderProbe()
  const selection = useBookingSelection()
  return (
    <div>
      <span data-testid="selected">
        {selection.selectedItems.map((item) => item.number).join(",")}
      </span>
      <button type="button" onClick={() => selection.select(STABLE_CABIN)}>
        Выбрать
      </button>
      <button type="button" onClick={() => selection.select(STABLE_CABIN)}>
        Выбрать тот же snapshot
      </button>
      <button
        type="button"
        onClick={() => selection.select(cabin("БЫТ-001-ОБНОВЛЕНА"))}
      >
        Обновить snapshot
      </button>
    </div>
  )
}

const STABLE_CABIN = cabin()

afterEach(() => {
  cleanup()
  warehouse.id = "11111111-1111-4111-8111-111111111111"
  renderProbe.mockClear()
})

describe("BookingSelectionProvider", () => {
  it("keeps business selection in memory and updates selected snapshots", async () => {
    const user = userEvent.setup()
    const storageSpy = vi.spyOn(Storage.prototype, "setItem")
    render(
      <BookingSelectionProvider>
        <Probe />
      </BookingSelectionProvider>
    )

    await user.click(screen.getByRole("button", { name: "Выбрать" }))
    expect(screen.getByTestId("selected").textContent).toBe("БЫТ-001")
    const rendersAfterSelection = renderProbe.mock.calls.length

    await user.click(
      screen.getByRole("button", { name: "Выбрать тот же snapshot" })
    )
    expect(renderProbe).toHaveBeenCalledTimes(rendersAfterSelection)

    await user.click(screen.getByRole("button", { name: "Обновить snapshot" }))
    expect(screen.getByTestId("selected").textContent).toBe("БЫТ-001-ОБНОВЛЕНА")
    expect(storageSpy).not.toHaveBeenCalled()
    storageSpy.mockRestore()
  })

  it("clears the active selection when the warehouse scope changes", async () => {
    const user = userEvent.setup()
    const view = render(
      <BookingSelectionProvider>
        <Probe />
      </BookingSelectionProvider>
    )
    await user.click(screen.getByRole("button", { name: "Выбрать" }))
    expect(screen.getByTestId("selected").textContent).toBe("БЫТ-001")

    warehouse.id = "99999999-9999-4999-8999-999999999999"
    view.rerender(
      <BookingSelectionProvider>
        <Probe />
      </BookingSelectionProvider>
    )

    expect(screen.getByTestId("selected").textContent).toBe("")
  })
})
