import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { KpiSettingsPage } from "@/features/settings/kpi/kpi-settings-page"
import { ApiError } from "@/lib/api-client"

const defaultKpiSettings = {
  status: "DRAFT" as const,
  version: 3,
  dataAvailableFrom: null,
  minimumEffectiveDate: "2026-07-30",
  palette: {
    version: 1,
    ranges: [{ fromPercent: 0, toPercent: 100, color: "#16A34A" }],
    overdueColor: "#7F1D1D",
  },
  activeSchedule: null,
  pendingSchedule: null,
}

const mocks = vi.hoisted(() => ({
  getKpiSettings: vi.fn(),
  saveWorkSchedule: vi.fn(),
  deletePendingWorkSchedule: vi.fn(),
  activateKpiSettings: vi.fn(),
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
      getKpiSettings: mocks.getKpiSettings,
      saveWorkSchedule: mocks.saveWorkSchedule,
      deletePendingWorkSchedule: mocks.deletePendingWorkSchedule,
      activateKpiSettings: mocks.activateKpiSettings,
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

function renderPage(presentation?: "default" | "admin") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <KpiSettingsPage presentation={presentation} />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.auth.accessToken = "access-token"
  mocks.getKpiSettings.mockResolvedValue(defaultKpiSettings)
  mocks.saveWorkSchedule.mockResolvedValue({
    ...defaultKpiSettings,
    version: 4,
  })
  mocks.deletePendingWorkSchedule.mockResolvedValue(undefined)
  mocks.activateKpiSettings.mockResolvedValue({
    ...defaultKpiSettings,
    version: 4,
    status: "ACTIVE",
    dataAvailableFrom: "2026-07-30",
  })
})

afterEach(cleanup)

describe("KpiSettingsPage", () => {
  it("shows the global work schedule, not palette controls", async () => {
    renderPage()

    expect(await screen.findByText("Рабочий график")).toBeTruthy()
    expect(screen.getByText("Черновик")).toBeTruthy()
    expect(screen.getByText(/для всех объектов/i)).toBeTruthy()
    expect(screen.queryByText("Диапазоны KPI")).toBeNull()
    expect(screen.queryByText("Сложность ремонта")).toBeNull()
  })

  it("loads without requiring a selected warehouse", async () => {
    renderPage()

    await screen.findByText("Рабочий график")
    expect(mocks.getKpiSettings).toHaveBeenCalledWith("access-token")
  })

  it("shows a safe service error when the local schedule cannot load", async () => {
    mocks.getKpiSettings.mockRejectedValue(
      new ApiError("task-board-service недоступен", 503)
    )

    renderPage()

    expect(
      await screen.findByText("Не удалось загрузить рабочий график")
    ).toBeTruthy()
    expect(screen.getByRole("alert").textContent).toContain(
      "Сервис временно недоступен. Повторите попытку позже."
    )
    expect(screen.queryByText("task-board-service недоступен")).toBeNull()
  })

  it("lets the admin workspace own scrolling for expanded settings", async () => {
    const view = renderPage("admin")

    await screen.findByText("Рабочий график")

    expect(view.container.firstElementChild?.className).not.toContain(
      "overflow-y-auto"
    )
    expect(view.container.firstElementChild?.className).toContain("pb-4")
    expect(screen.queryByText("Версия 3")).toBeNull()
  })

  it("saves a future work schedule for every object", async () => {
    const user = userEvent.setup()
    renderPage()

    const date = await screen.findByLabelText("Дата вступления графика")
    await user.clear(date)
    await user.type(date, "2099-08-01")
    await user.click(screen.getByRole("button", { name: "Сохранить график" }))

    await waitFor(() =>
      expect(mocks.saveWorkSchedule).toHaveBeenCalledWith(
        "access-token",
        expect.objectContaining({
          expectedVersion: 3,
          effectiveFrom: "2099-08-01",
          shiftStart: "08:00",
          shiftEnd: "17:00",
          daysOff: [6, 7],
          breaks: [],
        })
      )
    )
  })

  it("activates a pending schedule without changing the global palette", async () => {
    const user = userEvent.setup()
    mocks.getKpiSettings.mockResolvedValue({
      ...defaultKpiSettings,
      status: "DRAFT",
      pendingSchedule: {
        id: "00000000-0000-4000-8000-000000000003",
        version: 1,
        effectiveFrom: "2099-08-01",
        shiftStart: "08:00",
        shiftEnd: "17:00",
        daysOff: [6, 7],
        breaks: [],
      },
    })
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Активировать график" })
    )

    await waitFor(() =>
      expect(mocks.activateKpiSettings).toHaveBeenCalledWith(
        "access-token",
        3,
        expect.any(String)
      )
    )
  })
})
