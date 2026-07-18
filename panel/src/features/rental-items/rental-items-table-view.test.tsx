import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import {
  ContentsCell,
  ExpandableTextCell,
} from "@/features/rental-items/rental-items-table-view"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

afterEach(cleanup)

function rentalItem(
  contentsItems: RentalItemDto["contentsItems"]
): RentalItemDto {
  return {
    id: "cabin-1",
    version: 1,
    warehouseId: "warehouse-1",
    number: "БЫТ-001",
    type: "БК-1",
    dimensions: "2,4 × 6",
    finishing: "ЛДСП",
    category: "Стандарт",
    characteristics: "Пластиковое окно",
    linoleum: true,
    status: "WAREHOUSE",
    comment: null,
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    locationNodeId: null,
    contents: null,
    contentsItems,
    shipmentDate: null,
    tenant: null,
    price: null,
  }
}

describe("rental items table cells", () => {
  it("opens the full text by click and keyboard without bubbling to the row", async () => {
    const user = userEvent.setup()
    const onRowDoubleClick = vi.fn()
    const value =
      "Пластиковое окно, металлическая дверь и длинное описание комплектации"

    render(
      <div onDoubleClick={onRowDoubleClick}>
        <ExpandableTextCell value={value} label="Характеристики" />
      </div>
    )

    const trigger = screen.getByRole("button", {
      name: `Характеристики: ${value}. Показать полностью`,
    })
    expect(trigger.className).toContain("truncate")

    await user.click(trigger)
    expect(
      document.querySelector('[data-slot="popover-content"]')?.textContent
    ).toBe(value)

    await user.keyboard("{Escape}")
    expect(document.querySelector('[data-slot="popover-content"]')).toBeNull()

    trigger.focus()
    await user.keyboard("{Enter}")
    expect(
      document.querySelector('[data-slot="popover-content"]')?.textContent
    ).toBe(value)

    fireEvent.doubleClick(trigger)
    expect(onRowDoubleClick).not.toHaveBeenCalled()
  })

  it("keeps an empty value as a noninteractive dash", () => {
    render(<ExpandableTextCell value="—" label="Комментарий" />)

    expect(screen.getByText("—")).toBeTruthy()
    expect(screen.queryByRole("button")).toBeNull()
  })

  it("renders empty contents without a browser-backed add control", () => {
    render(<ContentsCell item={rentalItem([])} />)

    expect(screen.getByText("Не указано")).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Добавить" })).toBeNull()
  })

  it("shows contents read-only without browser-backed move actions", async () => {
    const user = userEvent.setup()

    render(<ContentsCell item={rentalItem([{ name: "Стол", quantity: 2 }])} />)

    await user.click(screen.getByRole("button", { name: "Стол 2 шт." }))

    expect(screen.getByText(/Изменение наполнения будет доступно/)).toBeTruthy()
    expect(screen.queryByRole("button", { name: /^Переместить/ })).toBeNull()
  })
})
