import { render, screen, within } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { InventoryStartDialog } from "@/features/inventory/inventory-start-dialog"

describe("InventoryStartDialog", () => {
  it("shows only the immutable warehouse and explains server-owned metadata", () => {
    render(
      <InventoryStartDialog
        open
        warehouseName="Склад СПБ"
        pending={false}
        error={null}
        onOpenChange={vi.fn()}
        onConfirm={vi.fn()}
      />
    )

    const warehouse = screen.getByRole("group", { name: "Склад" })
    const surface = screen.getByTestId("inventory-start-warehouse-value")

    expect(within(warehouse).getByText("Склад СПБ")).toBe(surface)
    expect(
      screen.getByText("Будут определены сервером из склада и Bearer-сессии.")
    ).not.toBeNull()
    expect(screen.queryByText("Текущий кладовщик")).toBeNull()
    expect(screen.queryByText("2026-07-11")).toBeNull()
    expect(screen.queryByRole("textbox")).toBeNull()
    expect(document.querySelector("input")).toBeNull()
    expect(surface.tabIndex).toBe(-1)
    expect(surface.getAttribute("tabindex")).toBeNull()
    expect(surface.classList.contains("pointer-events-none")).toBe(true)
    expect(surface.classList.contains("select-none")).toBe(true)
  })
})
