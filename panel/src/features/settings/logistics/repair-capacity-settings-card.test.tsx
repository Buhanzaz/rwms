import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { RepairCapacitySettingsCard } from "@/features/settings/logistics/repair-capacity-settings-card"
import { ApiError } from "@/lib/api-client"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const defaultSetting = {
  warehouseId,
  version: 0,
  repairPlaceCount: 6,
  createdAt: null,
  updatedAt: null,
}

const mocks = vi.hoisted(() => ({
  getRepairCapacity: vi.fn(),
  updateRepairCapacity: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock(
  "@/features/settings/logistics/api/repair-capacity-api",
  async (importOriginal) => {
    const original =
      await importOriginal<
        typeof import("@/features/settings/logistics/api/repair-capacity-api")
      >()

    return {
      ...original,
      getRepairCapacity: mocks.getRepairCapacity,
      updateRepairCapacity: mocks.updateRepairCapacity,
    }
  }
)

vi.mock("sonner", () => ({
  toast: {
    success: mocks.toastSuccess,
    error: mocks.toastError,
  },
}))

function renderCard() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <RepairCapacitySettingsCard
        accessToken="access-token"
        warehouseId={warehouseId}
        warehouseName="Северный склад"
      />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getRepairCapacity.mockResolvedValue(defaultSetting)
  mocks.updateRepairCapacity.mockResolvedValue({
    ...defaultSetting,
    version: 1,
  })
})

afterEach(() => {
  cleanup()
})

describe("RepairCapacitySettingsCard", () => {
  it("shows the service repair-place count", async () => {
    renderCard()

    const input = await screen.findByRole("spinbutton", {
      name: "Количество ремонтных мест",
    })

    expect((input as HTMLInputElement).value).toBe("6")
    expect(
      screen.queryByRole("spinbutton", {
        name: /Задержка автозаполнения/,
      })
    ).toBeNull()
    expect(screen.getByText(/заполняется автоматически и сразу/)).toBeTruthy()
    expect(
      screen.getByText("Настройка ещё не сохранялась для выбранного склада.")
    ).toBeTruthy()
  })

  it("recognizes the first saved setting even when its JPA version is zero", async () => {
    const user = userEvent.setup()
    mocks.updateRepairCapacity.mockResolvedValue({
      ...defaultSetting,
      repairPlaceCount: 9,
      createdAt: "2026-07-25T12:00:00Z",
      updatedAt: "2026-07-25T12:00:00Z",
    })

    renderCard()

    const input = await screen.findByRole("spinbutton", {
      name: "Количество ремонтных мест",
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
          repairPlaceCount: 9,
        }
      )
    )
    await waitFor(() =>
      expect(
        (
          screen.getByRole("spinbutton", {
            name: "Количество ремонтных мест",
          }) as HTMLInputElement
        ).value
      ).toBe("9")
    )
    expect(mocks.toastSuccess).toHaveBeenCalledWith(
      "Настройки ремонтных мест сохранены."
    )
    expect(
      screen.getByText("Настройка сохранена в сервисе ремонтов.")
    ).toBeTruthy()
  })

  it("shows a service loading error without a local fallback", async () => {
    mocks.getRepairCapacity.mockRejectedValue(
      new ApiError("maintenance-service недоступен", 503)
    )

    renderCard()

    expect((await screen.findByRole("alert")).textContent).toContain(
      "Сервис временно недоступен. Повторите попытку позже."
    )
    expect(screen.queryByText("maintenance-service недоступен")).toBeNull()
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
      repairPlaceCount: 7,
      updatedAt: "2026-07-25T12:30:00Z",
    }
    mocks.getRepairCapacity
      .mockReset()
      .mockResolvedValueOnce(loaded)
      .mockResolvedValueOnce(refreshed)
    mocks.updateRepairCapacity.mockRejectedValue(
      new ApiError("Конфликт версий", 409)
    )

    renderCard()

    const input = await screen.findByRole("spinbutton", {
      name: "Количество ремонтных мест",
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
            name: "Количество ремонтных мест",
          }) as HTMLInputElement
        ).value
      ).toBe("7")
    )
  })
})
