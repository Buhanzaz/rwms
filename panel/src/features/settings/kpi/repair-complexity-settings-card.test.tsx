import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  update: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}))

vi.mock("sonner", () => ({
  toast: { success: mocks.success, error: mocks.error },
}))
vi.mock("@/features/settings/kpi/api/repair-complexity-api", () => ({
  repairComplexityKeys: {
    all: ["maintenance", "repair-complexity"],
    warehouse: (warehouseId: string) => [
      "maintenance",
      "repair-complexity",
      warehouseId,
    ],
  },
  getRepairComplexity: mocks.get,
  updateRepairComplexity: mocks.update,
}))

import { WarehouseRepairComplexitySettingsCard } from "@/features/settings/kpi/repair-complexity-settings-card"

const setting = {
  warehouseId: "warehouse-1",
  version: 4,
  lightBoundaryMinutes: 60,
  mediumBoundaryMinutes: 180,
  complexBoundaryMinutes: 360,
  importedFromTaskBoardVersion: null,
  importedAt: null,
  createdAt: null,
  updatedAt: null,
}

function renderCard() {
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
          },
        })
      }
    >
      <WarehouseRepairComplexitySettingsCard
        accessToken="token"
        warehouseId="warehouse-1"
      />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.get.mockResolvedValue(setting)
  mocks.update.mockResolvedValue({ ...setting, version: 5 })
})

afterEach(cleanup)

describe("WarehouseRepairComplexitySettingsCard", () => {
  it("loads and saves only the selected object's version-fenced boundaries", async () => {
    const user = userEvent.setup()
    renderCard()

    const saveButton = await screen.findByRole("button", {
      name: "Сохранить границы",
    })
    expect(mocks.get).toHaveBeenCalledWith("token", "warehouse-1")

    await user.click(saveButton)

    await waitFor(() =>
      expect(mocks.update).toHaveBeenCalledWith("token", "warehouse-1", {
        expectedVersion: 4,
        lightBoundaryMinutes: 60,
        mediumBoundaryMinutes: 180,
        complexBoundaryMinutes: 360,
      })
    )
    expect(mocks.success).toHaveBeenCalledWith(
      "Границы сложности ремонта сохранены."
    )
  })
})
