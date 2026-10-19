import { render, screen, within } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { InventoryStartDialog } from "@/features/inventory/inventory-start-dialog"

describe("InventoryStartDialog", () => {
  it("shows immutable warehouse, current author, and date values", () => {
    render(
      <InventoryStartDialog
        open
        warehouseName="Склад СПБ"
        authorName="Текущий кладовщик"
        businessDate="2026-07-11"
        pending={false}
        error={null}
        onOpenChange={vi.fn()}
        onConfirm={vi.fn()}
      />
    )

    const warehouse = screen.getByRole("group", { name: "Склад" })
    const author = screen.getByRole("group", { name: "Автор" })
    const date = screen.getByRole("group", { name: "Дата" })
    const surfaces = [
      screen.getByTestId("inventory-start-warehouse-value"),
      screen.getByTestId("inventory-start-author-value"),
      screen.getByTestId("inventory-start-date-value"),
    ]

    expect(within(warehouse).getByText("Склад СПБ")).toBe(surfaces[0])
    expect(within(author).getByText("Текущий кладовщик")).toBe(surfaces[1])
    expect(within(date).getByText("2026-07-11")).toBe(surfaces[2])
    expect(screen.queryByRole("textbox")).toBeNull()
    expect(document.querySelector("input")).toBeNull()
    surfaces.forEach((surface) => {
      expect(surface.tabIndex).toBe(-1)
      expect(surface.getAttribute("tabindex")).toBeNull()
      expect(surface.classList.contains("pointer-events-none")).toBe(true)
      expect(surface.classList.contains("select-none")).toBe(true)
    })
    expect(screen.queryByText("Дата склада")).toBeNull()
  })
})
