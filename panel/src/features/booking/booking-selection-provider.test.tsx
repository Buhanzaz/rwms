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

const STABLE_CABIN = cabin()

function Probe() {
  renderProbe()
  const selection = useBookingSelection()
  return (
    <div>
      <span data-testid="draft-id">{selection.draftId}</span>
      <span data-testid="checked">
        {selection.checkedItems.map((item) => item.number).join(",")}
      </span>
      <span data-testid="staged">
        {selection.stagedItems.map((item) => item.number).join(",")}
      </span>
      <button
        type="button"
        onClick={() => selection.toggleChecked(STABLE_CABIN)}
      >
        Отметить
      </button>
      <button type="button" onClick={selection.addCheckedToStaged}>
        Добавить
      </button>
      <button
        type="button"
        onClick={() => selection.syncSnapshot(STABLE_CABIN)}
      >
        Тот же snapshot
      </button>
      <button
        type="button"
        onClick={() => selection.syncSnapshot(cabin("БЫТ-001-ОБНОВЛЕНА"))}
      >
        Обновить snapshot
      </button>
      <button type="button" onClick={selection.clear}>
        Завершить публикацию
      </button>
    </div>
  )
}

afterEach(() => {
  cleanup()
  warehouse.id = "11111111-1111-4111-8111-111111111111"
  renderProbe.mockClear()
})

describe("BookingSelectionProvider", () => {
  it("keeps checked items preliminary and stages them only on Add", async () => {
    const user = userEvent.setup()
    const storageSpy = vi.spyOn(Storage.prototype, "setItem")
    render(
      <BookingSelectionProvider>
        <Probe />
      </BookingSelectionProvider>
    )

    const draftId = screen.getByTestId("draft-id").textContent
    await user.click(screen.getByRole("button", { name: "Отметить" }))
    expect(screen.getByTestId("checked").textContent).toBe("БЫТ-001")
    expect(screen.getByTestId("staged").textContent).toBe("")

    await user.click(screen.getByRole("button", { name: "Добавить" }))
    expect(screen.getByTestId("checked").textContent).toBe("")
    expect(screen.getByTestId("staged").textContent).toBe("БЫТ-001")
    expect(screen.getByTestId("draft-id").textContent).toBe(draftId)

    const rendersAfterStaging = renderProbe.mock.calls.length
    await user.click(screen.getByRole("button", { name: "Тот же snapshot" }))
    expect(renderProbe).toHaveBeenCalledTimes(rendersAfterStaging)

    await user.click(screen.getByRole("button", { name: "Обновить snapshot" }))
    expect(screen.getByTestId("staged").textContent).toBe("БЫТ-001-ОБНОВЛЕНА")
    expect(storageSpy).not.toHaveBeenCalled()

    await user.click(
      screen.getByRole("button", { name: "Завершить публикацию" })
    )
    expect(screen.getByTestId("staged").textContent).toBe("")
    expect(screen.getByTestId("draft-id").textContent).not.toBe(draftId)
    storageSpy.mockRestore()
  })

  it("clears the draft selection when the warehouse scope changes", async () => {
    const user = userEvent.setup()
    const view = render(
      <BookingSelectionProvider>
        <Probe />
      </BookingSelectionProvider>
    )
    await user.click(screen.getByRole("button", { name: "Отметить" }))
    await user.click(screen.getByRole("button", { name: "Добавить" }))
    expect(screen.getByTestId("staged").textContent).toBe("БЫТ-001")

    warehouse.id = "99999999-9999-4999-8999-999999999999"
    view.rerender(
      <BookingSelectionProvider>
        <Probe />
      </BookingSelectionProvider>
    )

    expect(screen.getByTestId("staged").textContent).toBe("")
  })
})
