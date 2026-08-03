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

import { KpiSettingsPage } from "@/features/settings/kpi/kpi-settings-page"
import { ApiError } from "@/lib/api-client"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const warehouse = {
  id: warehouseId,
  version: 0,
  name: "Северный склад",
  city: "Санкт-Петербург",
  address: null,
  timeZone: "Europe/Moscow",
  active: true,
  sortOrder: 1,
}
const manager = {
  id: "00000000-0000-4000-8000-000000000010",
  username: "manager",
  displayName: "Начальник склада",
  firstName: null,
  lastName: null,
  email: null,
  principalType: "USER",
  globalRole: "WAREHOUSE_MANAGER",
  warehouseAccessAll: false,
  warehouseAccesses: [{ warehouseId, level: "MANAGE" }],
}
const defaultComplexitySetting = {
  warehouseId,
  version: 2,
  lightBoundaryMinutes: 120,
  mediumBoundaryMinutes: 300,
  complexBoundaryMinutes: 500,
  importedFromTaskBoardVersion: 3,
  importedAt: "2026-07-30T12:00:00Z",
  createdAt: "2026-07-30T12:00:00Z",
  updatedAt: "2026-07-30T12:00:00Z",
}
const defaultKpiSettings = {
  warehouseId,
  timeZone: "Europe/Moscow",
  status: "DRAFT",
  version: 3,
  dataAvailableFrom: null,
  palette: {
    version: 1,
    ranges: [{ fromPercent: 0, toPercent: 100, color: "#16A34A" }],
    overdueColor: "#7F1D1D",
  },
  activeSchedule: null,
  pendingSchedule: null,
}

const mocks = vi.hoisted(() => ({
  getRepairComplexity: vi.fn(),
  updateRepairComplexity: vi.fn(),
  getKpiSettings: vi.fn(),
  saveKpiPalette: vi.fn(),
  saveWorkSchedule: vi.fn(),
  deletePendingWorkSchedule: vi.fn(),
  activateKpiSettings: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
  auth: {
    accessToken: "access-token" as string | null,
    currentUser: null as Record<string, unknown> | null,
  },
  warehouse: {
    selectedWarehouse: null as Record<string, unknown> | null,
  },
}))

vi.mock(
  "@/features/settings/kpi/api/repair-complexity-api",
  async (importOriginal) => {
    const original =
      await importOriginal<
        typeof import("@/features/settings/kpi/api/repair-complexity-api")
      >()

    return {
      ...original,
      getRepairComplexity: mocks.getRepairComplexity,
      updateRepairComplexity: mocks.updateRepairComplexity,
    }
  }
)

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
      saveKpiPalette: mocks.saveKpiPalette,
      saveWorkSchedule: mocks.saveWorkSchedule,
      deletePendingWorkSchedule: mocks.deletePendingWorkSchedule,
      activateKpiSettings: mocks.activateKpiSettings,
    }
  }
)

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => mocks.auth,
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => mocks.warehouse,
}))

vi.mock("sonner", () => ({
  toast: {
    success: mocks.toastSuccess,
    error: mocks.toastError,
  },
}))

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <KpiSettingsPage />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.auth.accessToken = "access-token"
  mocks.auth.currentUser = manager
  mocks.warehouse.selectedWarehouse = warehouse
  mocks.getRepairComplexity.mockResolvedValue(defaultComplexitySetting)
  mocks.updateRepairComplexity.mockResolvedValue({
    ...defaultComplexitySetting,
    version: 3,
  })
  mocks.getKpiSettings.mockResolvedValue(defaultKpiSettings)
  mocks.saveKpiPalette.mockResolvedValue({
    ...defaultKpiSettings,
    version: 4,
  })
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

afterEach(() => {
  cleanup()
})

describe("KpiSettingsPage", () => {
  it("shows warehouse complexity, schedule and palette", async () => {
    renderPage()

    expect(await screen.findByText("Сложность ремонта")).toBeTruthy()
    expect(await screen.findByText("Рабочий график")).toBeTruthy()
    expect(screen.getByText("Диапазоны KPI")).toBeTruthy()
    expect(screen.queryByText("Количество ремонтных мест")).toBeNull()
    expect(screen.getByText("Europe/Moscow")).toBeTruthy()
  })

  it("loads maintenance complexity independently when task-board KPI is unavailable", async () => {
    mocks.getKpiSettings.mockRejectedValue(
      new ApiError("task-board-service недоступен", 503)
    )

    renderPage()

    expect(
      await screen.findByRole("spinbutton", {
        name: "Лёгкий ремонт, до и включая, минуты",
      })
    ).toBeTruthy()
    expect(screen.getByText("task-board-service недоступен")).toBeTruthy()
    expect(mocks.getRepairComplexity).toHaveBeenCalledWith(
      "access-token",
      warehouseId
    )
  })

  it("does not fall back to task-board when maintenance complexity is unavailable", async () => {
    mocks.getRepairComplexity.mockRejectedValue(
      new ApiError("maintenance-service недоступен", 503)
    )

    renderPage()

    expect(
      await screen.findByText(
        "Границы не подменяются настройками доски задач."
      )
    ).toBeTruthy()
    expect(
      screen.queryByRole("spinbutton", {
        name: "Лёгкий ремонт, до и включая, минуты",
      })
    ).toBeNull()
  })

  it("saves strictly increasing repair boundaries as minutes", async () => {
    const user = userEvent.setup()
    renderPage()

    const light = await screen.findByRole("spinbutton", {
      name: "Лёгкий ремонт, до и включая, минуты",
    })
    const medium = screen.getByRole("spinbutton", {
      name: "Средний ремонт, до и включая, минуты",
    })
    const complex = screen.getByRole("spinbutton", {
      name: "Тяжёлый ремонт, до и включая, минуты",
    })
    fireEvent.change(light, { target: { value: "90" } })
    fireEvent.change(medium, { target: { value: "240" } })
    fireEvent.change(complex, { target: { value: "480" } })
    await user.click(screen.getByRole("button", { name: "Сохранить границы" }))

    await waitFor(() =>
      expect(mocks.updateRepairComplexity).toHaveBeenCalledWith(
        "access-token",
        warehouseId,
        {
          expectedVersion: 2,
          lightBoundaryMinutes: 90,
          mediumBoundaryMinutes: 240,
          complexBoundaryMinutes: 480,
        }
      )
    )
  })

  it("switches display to hours and minutes without changing saved minutes", async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByRole("spinbutton", {
      name: "Лёгкий ремонт, до и включая, минуты",
    })
    await user.click(
      screen.getByRole("radio", { name: "Часы и минуты" })
    )

    expect(
      (
        screen.getByRole("spinbutton", {
          name: "Тяжёлый ремонт, до и включая, часы",
        }) as HTMLInputElement
      ).value
    ).toBe("8")
    expect(
      (
        screen.getByRole("spinbutton", {
          name: "Тяжёлый ремонт, до и включая, остаток минут",
        }) as HTMLInputElement
      ).value
    ).toBe("20")

    await user.click(screen.getByRole("button", { name: "Сохранить границы" }))

    await waitFor(() =>
      expect(mocks.updateRepairComplexity).toHaveBeenCalledWith(
        "access-token",
        warehouseId,
        {
          expectedVersion: 2,
          lightBoundaryMinutes: 120,
          mediumBoundaryMinutes: 300,
          complexBoundaryMinutes: 500,
        }
      )
    )
  })

  it("keeps expanded KPI settings in a vertically scrollable page area", async () => {
    const view = renderPage()

    await screen.findByText("Рабочий график")

    expect(view.container.firstElementChild?.className).toContain(
      "overflow-y-auto"
    )
    expect(view.container.firstElementChild?.className).toContain("pb-4")
  })

  it("saves a future common work schedule for the selected warehouse", async () => {
    const user = userEvent.setup()
    renderPage()

    const date = await screen.findByLabelText("Дата вступления графика")
    await user.clear(date)
    await user.type(date, "2099-08-01")
    await user.click(screen.getByRole("button", { name: "Сохранить график" }))

    await waitFor(() =>
      expect(mocks.saveWorkSchedule).toHaveBeenCalledWith(
        "access-token",
        warehouseId,
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

  it("splits a range with inherited color and saves the complete palette", async () => {
    const user = userEvent.setup()
    renderPage()

    const boundary = await screen.findByRole("spinbutton", {
      name: "Новая граница, %",
    })
    await user.clear(boundary)
    await user.type(boundary, "35")
    await user.click(screen.getByRole("button", { name: "Добавить границу" }))
    await user.click(screen.getByRole("button", { name: "Сохранить палитру" }))

    await waitFor(() =>
      expect(mocks.saveKpiPalette).toHaveBeenCalledWith(
        "access-token",
        warehouseId,
        {
          expectedVersion: 3,
          ranges: [
            { fromPercent: 0, toPercent: 35, color: "#16A34A" },
            { fromPercent: 35, toPercent: 100, color: "#16A34A" },
          ],
          overdueColor: "#7F1D1D",
        }
      )
    )
  })

  it("opens the compact native RGB control for one selected range", async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText("Диапазоны KPI")
    await user.click(screen.getByRole("radio", { name: "Назначение цветов" }))
    await user.click(
      screen.getByRole("button", { name: "Диапазон от 0% до 100%" })
    )

    expect(screen.getByLabelText("Выбрать цвет диапазона 0–100%")).toBeTruthy()
    expect(screen.getByLabelText("RGB диапазона 0–100%")).toBeTruthy()
  })
})
