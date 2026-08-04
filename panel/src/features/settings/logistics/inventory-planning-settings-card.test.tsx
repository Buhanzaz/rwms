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
  movementDailyCapacity: 6,
  repairDailyCapacity: 6,
  workingWeekdays: ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
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

describe("inventory planning settings", () => {
  it("saves separate capacities, weekdays and calendar holidays with the server revision", async () => {
    const user = userEvent.setup()
    renderCard()

    const movement = await screen.findByRole("spinbutton", {
      name: "Бытовок на перемещение в день",
    })
    const repair = screen.getByRole("spinbutton", {
      name: "Бытовок на ремонт в день",
    })
    await user.clear(movement)
    await user.type(movement, "8")
    await user.clear(repair)
    await user.type(repair, "5")
    await user.click(screen.getByRole("button", { name: "Сб" }))

    const holiday = screen.getByLabelText("Праздничная дата")
    await user.clear(holiday)
    await user.type(holiday, "2026-08-12")
    await user.click(screen.getByRole("button", { name: "Добавить дату" }))
    await user.click(
      screen.getByRole("button", { name: "Сохранить календарь" })
    )

    await waitFor(() => expect(mocks.update).toHaveBeenCalledTimes(1))
    expect(mocks.update).toHaveBeenCalledWith({
      accessToken: "inventory-token",
      warehouseId: WAREHOUSE_ID,
      request: {
        expectedSettingsRevision: 3,
        movementDailyCapacity: 8,
        repairDailyCapacity: 5,
        workingWeekdays: [
          "MONDAY",
          "TUESDAY",
          "WEDNESDAY",
          "THURSDAY",
          "FRIDAY",
          "SATURDAY",
        ],
        holidays: ["2026-08-10", "2026-08-12"],
      },
    })
    expect(mocks.success).toHaveBeenCalledWith(
      "Календарь итогового плана сохранён."
    )
  })

  it("does not allow an empty working week", async () => {
    const user = userEvent.setup()
    renderCard()

    await screen.findByText("Версия 3")
    for (const label of ["Пн", "Вт", "Ср", "Чт", "Пт"]) {
      await user.click(screen.getByRole("button", { name: label }))
    }
    await user.click(
      screen.getByRole("button", { name: "Сохранить календарь" })
    )

    expect(
      screen.getByText("Выберите хотя бы один рабочий день недели.")
    ).toBeTruthy()
    expect(mocks.update).not.toHaveBeenCalled()
  })
})
