import { ApiError } from "@/lib/api-client"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
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
vi.mock(
  "@/features/settings/estimates-repairs/api/estimate-creation-window-settings-api",
  () => ({
    estimateCreationWindowKeys: {
      all: ["maintenance", "estimate-creation-window"],
      },
    getEstimateCreationWindow: mocks.get,
    updateEstimateCreationWindow: mocks.update,
  })
)

import { EstimateCreationWindowSettingsCard } from "@/features/settings/estimates-repairs/estimate-creation-window-settings-card"

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
      <EstimateCreationWindowSettingsCard
        accessToken="token"
        readOnly={readOnly}
      />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.get.mockResolvedValue({
    version: 0,
    days: 7,
    createdAt: null,
    updatedAt: null,
  })
  mocks.update.mockResolvedValue({
    version: 1,
    days: 10,
    createdAt: "2026-08-21T08:00:00Z",
    updatedAt: "2026-08-21T08:00:00Z",
  })
})

afterEach(cleanup)

describe("EstimateCreationWindowSettingsCard", () => {
  it("shows the server default and saves with its version fence", async () => {
    const user = userEvent.setup()
    renderCard()

    const input = await screen.findByLabelText("Количество дней")
    expect((input as HTMLInputElement).value).toBe("7")
    expect(
      screen.getByText("Применяется ко всем складам.")
    ).toBeTruthy()

    fireEvent.change(input, { target: { value: "10" } })
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(mocks.update).toHaveBeenCalledWith("token", {
        expectedVersion: 0,
        days: 10,
      })
    )
    expect(mocks.success).toHaveBeenCalledWith("Срок создания сметы сохранён.")
  })

  it("blocks an out-of-range value", async () => {
    const user = userEvent.setup()
    renderCard()
    const input = await screen.findByLabelText("Количество дней")
    fireEvent.change(input, { target: { value: "0" } })
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    expect(
      await screen.findByText("Укажите целое количество дней от 1 до 3650.")
    ).toBeTruthy()
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it("shows an explicit load failure without a local fallback", async () => {
    mocks.get.mockRejectedValue(new Error("maintenance unavailable"))
    renderCard()

    expect((await screen.findByRole("alert")).textContent).toContain(
      "maintenance unavailable"
    )
    expect(
      screen.getByText("Настройка не подменяется локальным значением.")
    ).toBeTruthy()
  })
  it("keeps global settings read-only for non-administrators", async () => {
    const user = userEvent.setup()
    renderCard(true)
    const input = await screen.findByLabelText("Количество дней")
    expect((input as HTMLInputElement).disabled).toBe(true)
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it("reloads the global version after a conflict before saving again", async () => {
    const user = userEvent.setup()
    renderCard()
    await screen.findByLabelText("Количество дней")
    mocks.get.mockResolvedValue({ version: 3, days: 14, createdAt: null, updatedAt: null })
    mocks.update.mockRejectedValueOnce(new ApiError("conflict", 409))
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    await waitFor(() => expect((screen.getByLabelText("Количество дней") as HTMLInputElement).value).toBe("14"))
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    await waitFor(() => expect(mocks.update).toHaveBeenLastCalledWith("token", { expectedVersion: 3, days: 14 }))
  })

})
