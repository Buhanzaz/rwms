import { ApiError } from "@/lib/api-client"
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
  },
  getRepairComplexity: mocks.get,
  updateRepairComplexity: mocks.update,
}))

import { GlobalRepairComplexitySettingsCard } from "@/features/settings/kpi/repair-complexity-settings-card"

const setting = {
  version: 4,
  lightBoundaryMinutes: 60,
  mediumBoundaryMinutes: 180,
  complexBoundaryMinutes: 360,
  createdAt: null,
  updatedAt: null,
}

function renderCard(readOnly = false) {
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
      <GlobalRepairComplexitySettingsCard
        accessToken="token"
        readOnly={readOnly}
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

describe("GlobalRepairComplexitySettingsCard", () => {
  it("loads and saves the global version-fenced boundaries", async () => {
    const user = userEvent.setup()
    renderCard()

    const saveButton = await screen.findByRole("button", {
      name: "Сохранить границы",
    })
    expect(mocks.get).toHaveBeenCalledWith("token")

    await user.click(saveButton)

    await waitFor(() =>
      expect(mocks.update).toHaveBeenCalledWith("token", {
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
  it("prevents a non-administrator from changing global boundaries", async () => {
    const user = userEvent.setup()
    renderCard(true)
    const save = await screen.findByRole("button", { name: "Сохранить границы" })
    expect((save as HTMLButtonElement).disabled).toBe(true)
    await user.click(save)
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it("reloads the global version after a conflict", async () => {
    const user = userEvent.setup()
    renderCard()
    await screen.findByRole("button", { name: "Сохранить границы" })
    mocks.get.mockResolvedValue({ ...setting, version: 8, lightBoundaryMinutes: 90 })
    mocks.update.mockRejectedValueOnce(new ApiError("conflict", 409))
    await user.click(screen.getByRole("button", { name: "Сохранить границы" }))
    await waitFor(() => expect((screen.getByLabelText("Лёгкий ремонт, до и включая, минуты") as HTMLInputElement).value).toBe("90"))
    await user.click(screen.getByRole("button", { name: "Сохранить границы" }))
    await waitFor(() => expect(mocks.update).toHaveBeenLastCalledWith("token", expect.objectContaining({ expectedVersion: 8, lightBoundaryMinutes: 90 })))
  })

})
