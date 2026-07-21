import { cleanup, render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import { RepairEstimateWorkspaceLayout } from "@/features/repair-estimates/repair-estimate-workspace-layout"

afterEach(() => {
  cleanup()
})

describe("RepairEstimateWorkspaceLayout", () => {
  it("keeps catalog results and controls in scrollable card content", async () => {
    const user = userEvent.setup()
    const chooseCatalogResult = vi.fn()
    const saveEstimate = vi.fn()

    render(
      <RepairEstimateWorkspaceLayout
        photos={<p>Фото</p>}
        information={<p>Информация</p>}
        estimate={<p>Смета</p>}
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
