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
const defaultSetting = {
  warehouseId,
  version: 0,
  maxRepairsPerDay: 6,
  createdAt: null,
  updatedAt: null,
}
const defaultKpiSettings = {
  warehouseId,
  timeZone: "Europe/Moscow",
  status: "DRAFT",
  version: 3,
  dataAvailableFrom: null,
  repairComplexity: {
    lightBoundaryMinutes: 60,
    mediumBoundaryMinutes: 180,
    complexBoundaryMinutes: 360,
  },
  palette: {
    version: 1,
    ranges: [{ fromPercent: 0, toPercent: 100, color: "#16A34A" }],
    overdueColor: "#7F1D1D",
  },
  activeSchedule: null,
  pendingSchedule: null,
}

const mocks = vi.hoisted(() => ({
  getRepairCapacity: vi.fn(),
  updateRepairCapacity: vi.fn(),
  getKpiSettings: vi.fn(),
  saveKpiPalette: vi.fn(),
  saveRepairComplexity: vi.fn(),
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
  "@/features/settings/kpi/api/repair-capacity-api",
  async (importOriginal) => {
    const original =
      await importOriginal<
        typeof import("@/features/settings/kpi/api/repair-capacity-api")
      >()

    return {
      ...original,
      getRepairCapacity: mocks.getRepairCapacity,
      updateRepairCapacity: mocks.updateRepairCapacity,
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
      saveRepairComplexity: mocks.saveRepairComplexity,
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
  mocks.getRepairCapacity.mockResolvedValue(defaultSetting)
  mocks.updateRepairCapacity.mockResolvedValue({
    ...defaultSetting,
    version: 1,
  })
  mocks.getKpiSettings.mockResolvedValue(defaultKpiSettings)
  mocks.saveKpiPalette.mockResolvedValue({
    ...defaultKpiSettings,
    version: 4,
  })
  mocks.saveRepairComplexity.mockResolvedValue({
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
  it("shows the service default as 6 / 6 / 6", async () => {
    renderPage()

    const input = await screen.findByRole("spinbutton", {
      name: "Максимум ремонтов в день",
    })

    expect((input as HTMLInputElement).value).toBe("6")
    for (const day of ["Сегодня", "Завтра", "Послезавтра"]) {
      expect(screen.getByLabelText(`${day}: лимит ремонтов`).textContent).toBe(
        "6"
      )
    }
    expect(
      screen.getByText("Сейчас используется значение по умолчанию.")
    ).toBeTruthy()
  })

  it("recognizes the first saved setting even when its JPA version is zero", async () => {
    const user = userEvent.setup()
    mocks.getRepairCapacity.mockResolvedValue(defaultSetting)
    mocks.updateRepairCapacity.mockResolvedValue({
      ...defaultSetting,
      maxRepairsPerDay: 9,
      createdAt: "2026-07-25T12:00:00Z",
      updatedAt: "2026-07-25T12:00:00Z",
    })

    renderPage()

    const input = await screen.findByRole("spinbutton", {
      name: "Максимум ремонтов в день",
    })
    await user.clear(input)
    await user.type(input, "9")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(mocks.updateRepairCapacity).toHaveBeenCalledWith(
        "access-token",
        warehouseId,
        {
          expectedVersion: 0,
          maxRepairsPerDay: 9,
        }
      )
    )
    await waitFor(() =>
      expect(screen.getByLabelText("Сегодня: лимит ремонтов").textContent).toBe(
        "9"
      )
    )
    expect(mocks.toastSuccess).toHaveBeenCalledWith("Лимит ремонтов сохранён.")
    expect(
      screen.getByText("Настройка сохранена в сервисе ремонтов.")
    ).toBeTruthy()
  })

  it("does not read the setting without MANAGE access", async () => {
    mocks.auth.currentUser = {
      ...manager,
      warehouseAccesses: [{ warehouseId, level: "EDIT" }],
    }

    renderPage()

    expect(await screen.findByText("Недостаточно прав")).toBeTruthy()
    expect(mocks.getRepairCapacity).not.toHaveBeenCalled()
  })

  it("shows a service loading error without a local fallback", async () => {
    mocks.getRepairCapacity.mockRejectedValue(
      new ApiError("maintenance-service недоступен", 503)
    )

    renderPage()

    expect((await screen.findByRole("alert")).textContent).toContain(
      "maintenance-service недоступен"
    )
    expect(
      screen.getByText("Настройка не подменяется локальным значением.")
    ).toBeTruthy()
  })

  it("refetches the current value after a version conflict", async () => {
    const user = userEvent.setup()
    const loaded = {
      ...defaultSetting,
      version: 4,
    }
    const refreshed = {
      ...defaultSetting,
      version: 5,
      maxRepairsPerDay: 7,
      updatedAt: "2026-07-25T12:30:00Z",
    }
    mocks.getRepairCapacity
      .mockReset()
      .mockResolvedValueOnce(loaded)
      .mockResolvedValueOnce(refreshed)
    mocks.updateRepairCapacity.mockRejectedValue(
      new ApiError("Конфликт версий", 409)
    )

    renderPage()

    const input = await screen.findByRole("spinbutton", {
      name: "Максимум ремонтов в день",
    })
    await user.clear(input)
    await user.type(input, "8")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(mocks.getRepairCapacity).toHaveBeenCalledTimes(2)
    )
    expect((await screen.findByRole("alert")).textContent).toContain(
      "изменена другим пользователем"
    )
    await waitFor(() =>
      expect(
        (
          screen.getByRole("spinbutton", {
            name: "Максимум ремонтов в день",
          }) as HTMLInputElement
        ).value
      ).toBe("7")
    )
  })

  it("shows warehouse complexity, schedule, palette and the repair card", async () => {
    renderPage()

    expect(await screen.findByText("Сложность ремонта")).toBeTruthy()
    expect(await screen.findByText("Рабочий график")).toBeTruthy()
    expect(screen.getByText("Диапазоны KPI")).toBeTruthy()
    expect(screen.getByText("Лимит ремонтов")).toBeTruthy()
    expect(screen.getByText("Europe/Moscow")).toBeTruthy()
  })

  it("saves strictly increasing repair boundaries as minutes", async () => {
    const user = userEvent.setup()
    renderPage()

    const light = await screen.findByRole("spinbutton", {
      name: "Лёгкий ремонт, до и включая",
    })
    const medium = screen.getByRole("spinbutton", {
      name: "Средний ремонт, до и включая",
    })
    const complex = screen.getByRole("spinbutton", {
      name: "Сложный ремонт, до и включая",
    })
    fireEvent.change(light, { target: { value: "1.5" } })
    fireEvent.change(medium, { target: { value: "4" } })
    fireEvent.change(complex, { target: { value: "8" } })
    await user.click(screen.getByRole("button", { name: "Сохранить границы" }))

    await waitFor(() =>
      expect(mocks.saveRepairComplexity).toHaveBeenCalledWith(
        "access-token",
        warehouseId,
        {
          expectedVersion: 3,
          lightBoundaryMinutes: 90,
          mediumBoundaryMinutes: 240,
          complexBoundaryMinutes: 480,
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
