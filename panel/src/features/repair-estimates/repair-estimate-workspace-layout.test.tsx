import { cleanup, render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import { RepairEstimateWorkspaceLayout } from "@/features/repair-estimates/repair-estimate-workspace-layout"

afterEach(() => {
  cleanup()
})

describe("RepairEstimateWorkspaceLayout", () => {
  it("keeps estimate lines and catalog controls in bounded scrollable cards", async () => {
    const user = userEvent.setup()
    const chooseCatalogResult = vi.fn()
    const saveEstimate = vi.fn()

    render(
      <RepairEstimateWorkspaceLayout
        photos={<p>Фото</p>}
        information={<p>Информация</p>}
        estimate={<p data-testid="estimate-lines">Смета</p>}
        controls={
          <div>
            <button
              type="button"
              aria-label="Выбрать: Утилизация мусора"
              onClick={chooseCatalogResult}
            >
              Утилизация мусора
            </button>
            <button type="button" onClick={saveEstimate}>
              Сохранить черновик
            </button>
          </div>
        }
      />
    )

    const estimateLines = screen.getByTestId("estimate-lines")
    const estimateContent = estimateLines.closest('[data-slot="card-content"]')
    const estimateCard = estimateLines.closest('[data-slot="card"]')
    expect(estimateContent).not.toBeNull()
    expect(estimateContent?.classList.contains("overflow-y-auto")).toBe(true)
    expect(estimateContent?.classList.contains("overflow-hidden")).toBe(false)
    expect(estimateCard?.classList.contains("h-full")).toBe(true)
    expect(estimateCard?.classList.contains("min-h-0")).toBe(true)

    const catalogTitle = screen.getByText("Каталог")
    const catalogCard = catalogTitle.closest('[data-slot="card"]')
    expect(catalogCard).not.toBeNull()

    const catalogContent = catalogCard?.querySelector(
      '[data-slot="card-content"]'
    )
    expect(catalogContent).not.toBeNull()
    expect(catalogContent?.classList.contains("overflow-y-auto")).toBe(true)
    expect(catalogContent?.classList.contains("overflow-hidden")).toBe(false)

    const catalog = within(catalogCard as HTMLElement)
    await user.click(
      catalog.getByRole("button", {
        name: "Выбрать: Утилизация мусора",
      })
    )
    await user.click(
      catalog.getByRole("button", { name: "Сохранить черновик" })
    )

    expect(chooseCatalogResult).toHaveBeenCalledOnce()
    expect(saveEstimate).toHaveBeenCalledOnce()
  })
})
