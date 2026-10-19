import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const palette = {
  version: 4,
  palette: {
    version: 2,
    ranges: [{ fromPercent: 0, toPercent: 100, color: "#16A34A" }],
    overdueColor: "#7F1D1D",
    problemColor: "#FF3B30",
    completedColor: "#238636",
  },
}

const mocks = vi.hoisted(() => ({
  getKpiPalette: vi.fn(),
  saveKpiPalette: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
  auth: {
    accessToken: "access-token" as string | null,
  },
}))

vi.mock(
  "@/features/settings/kpi/api/kpi-settings-api",
  async (importOriginal) => {
    const original =
      await importOriginal<
        typeof import("@/features/settings/kpi/api/kpi-settings-api")
      >()

    return {
      ...original,
      getKpiPalette: mocks.getKpiPalette,
      saveKpiPalette: mocks.saveKpiPalette,
    }
  }
)

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => mocks.auth,
}))

vi.mock("sonner", () => ({
  toast: {
    success: mocks.toastSuccess,
    error: mocks.toastError,
  },
}))

import { KpiPaletteSettingsPage } from "@/features/settings/kpi/kpi-palette-settings-page"

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <KpiPaletteSettingsPage />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.auth.accessToken = "access-token"
  mocks.getKpiPalette.mockResolvedValue(palette)
  mocks.saveKpiPalette.mockResolvedValue({
    ...palette,
    version: 5,
  })
})

afterEach(cleanup)

describe("KpiPaletteSettingsPage", () => {
  it("loads and saves the global palette without a selected warehouse", async () => {
    const user = userEvent.setup()
    renderPage()

    expect(await screen.findByText("Единая палитра")).toBeTruthy()
    expect(
      screen.getByText(
        "Применяется ко всем объектам сразу после сохранения."
      )
    ).toBeTruthy()
    expect(screen.queryByText("Склад не выбран")).toBeNull()
    expect(mocks.getKpiPalette).toHaveBeenCalledWith("access-token")

    await user.click(screen.getByRole("button", { name: "Сохранить палитру" }))

    await waitFor(() =>
      expect(mocks.saveKpiPalette).toHaveBeenCalledWith(
        "access-token",
        {
          expectedVersion: 4,
          ranges: [{ fromPercent: 0, toPercent: 100, color: "#16A34A" }],
          overdueColor: "#7F1D1D",
          problemColor: "#FF3B30",
          completedColor: "#238636",
        }
      )
    )
    expect(mocks.toastSuccess).toHaveBeenCalledWith(
      "Общая палитра KPI сохранена для всех объектов."
    )
  })
})
