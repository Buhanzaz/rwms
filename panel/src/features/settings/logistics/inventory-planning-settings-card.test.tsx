import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { InventoryPlanningSettings } from "@/features/inventory/model/inventory-service"

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  update: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}))

vi.mock("sonner", () => ({
  toast: { success: mocks.success, error: mocks.error },
}))

vi.mock("@/features/inventory/adapters/http-inventory-adapter", () => ({
  getInventoryPlanningSettings: mocks.get,
  updateInventoryPlanningSettings: mocks.update,
}))

import { InventoryPlanningSettingsCard } from "@/features/settings/logistics/inventory-planning-settings-card"

const WAREHOUSE_ID = "00000000-0000-4000-8000-000000000201"
const setting: InventoryPlanningSettings = {
  warehouseId: WAREHOUSE_ID,
  settingsRevision: 3,
  updatedAt: "2026-08-09T09:30:00Z",
  holidays: ["2026-08-10"],
}

function renderCard() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <InventoryPlanningSettingsCard
        accessToken="inventory-token"
        warehouseId={WAREHOUSE_ID}
        warehouseName="Основной склад"
      />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  mocks.get.mockResolvedValue(setting)
  mocks.update.mockResolvedValue({ ...setting, settingsRevision: 4 })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("inventory holiday settings", () => {
  it("saves only holidays with the server revision", async () => {
    const user = userEvent.setup()
    renderCard()

    const holiday = await screen.findByLabelText("Праздничная дата")
    expect(screen.queryByRole("spinbutton")).toBeNull()
    expect(screen.queryByText(/бытовок.*день/i)).toBeNull()
    expect(screen.queryByRole("button", { name: "Пн" })).toBeNull()

    await user.type(holiday, "2026-08-12")
    await user.click(screen.getByRole("button", { name: "Добавить дату" }))
    await user.click(
      screen.getByRole("button", { name: "Сохранить праздники" })
    )

    await waitFor(() => expect(mocks.update).toHaveBeenCalledTimes(1))
    expect(mocks.update).toHaveBeenCalledWith({
      accessToken: "inventory-token",
      warehouseId: WAREHOUSE_ID,
      request: {
        expectedSettingsRevision: 3,
        holidays: ["2026-08-10", "2026-08-12"],
      },
    })
    expect(mocks.success).toHaveBeenCalledWith(
      "Праздничные выходные сохранены."
    )
  })

  it("does not add the same holiday twice", async () => {
    const user = userEvent.setup()
    renderCard()

    const holiday = await screen.findByLabelText("Праздничная дата")
    await user.type(holiday, "2026-08-10")
    await user.click(screen.getByRole("button", { name: "Добавить дату" }))

    expect(screen.getByText("Эта праздничная дата уже добавлена.")).toBeTruthy()
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it("removes a holiday without restoring retired planning fields", async () => {
    const user = userEvent.setup()
    renderCard()

    await screen.findByText("10.08.2026")
    await user.click(
      screen.getByRole("button", { name: "Удалить праздник 10.08.2026" })
    )
    await user.click(
      screen.getByRole("button", { name: "Сохранить праздники" })
    )

    await waitFor(() => expect(mocks.update).toHaveBeenCalledTimes(1))
    expect(mocks.update.mock.calls[0]?.[0].request).toEqual({
      expectedSettingsRevision: 3,
      holidays: [],
    })
  })
})
