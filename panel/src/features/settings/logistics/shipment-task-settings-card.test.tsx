import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ShipmentTaskSettingsCard } from "@/features/settings/logistics/shipment-task-settings-card"
import { ApiError } from "@/lib/api-client"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const defaultSetting = {
  warehouseId,
  version: 0,
  maxCabinsPerShipmentTask: 3,
  updatedBy: "00000000-0000-4000-8000-000000000010",
  updatedAt: "2026-08-10T10:00:00Z",
}

const mocks = vi.hoisted(() => ({
  getShipmentTaskSettings: vi.fn(),
  updateShipmentTaskSettings: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock(
  "@/features/settings/logistics/api/shipment-task-settings-api",
  async (importOriginal) => {
    const original =
      await importOriginal<
        typeof import("@/features/settings/logistics/api/shipment-task-settings-api")
      >()

    return {
      ...original,
      getShipmentTaskSettings: mocks.getShipmentTaskSettings,
      updateShipmentTaskSettings: mocks.updateShipmentTaskSettings,
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
      <ShipmentTaskSettingsCard
        accessToken="access-token"
        warehouseId={warehouseId}
        warehouseName="Северный склад"
      />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getShipmentTaskSettings.mockResolvedValue(defaultSetting)
  mocks.updateShipmentTaskSettings.mockResolvedValue({
    ...defaultSetting,
    version: 1,
  })
})

afterEach(() => {
  cleanup()
})

describe("ShipmentTaskSettingsCard", () => {
  it("shows the warehouse-scoped shipment task cap", async () => {
    renderCard()

    const input = await screen.findByRole("spinbutton", {
      name: "Максимум бытовок в одном задании отгрузки",
    })

    expect((input as HTMLInputElement).value).toBe("3")
    expect(screen.getByText(/Лимит для склада «Северный склад»/)).toBeTruthy()
    expect(screen.getByText("Версия 0")).toBeTruthy()
  })

  it("saves a valid cap with the loaded optimistic version", async () => {
    const user = userEvent.setup()
    mocks.updateShipmentTaskSettings.mockResolvedValue({
      ...defaultSetting,
      version: 1,
      maxCabinsPerShipmentTask: 5,
    })

    renderCard()

    const input = await screen.findByRole("spinbutton", {
      name: "Максимум бытовок в одном задании отгрузки",
    })
    await user.clear(input)
    await user.type(input, "5")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(mocks.updateShipmentTaskSettings).toHaveBeenCalledWith(
        "access-token",
        warehouseId,
        {
          expectedVersion: 0,
          maxCabinsPerShipmentTask: 5,
        }
      )
    )
    expect(mocks.toastSuccess).toHaveBeenCalledWith(
      "Лимит бытовок в задании отгрузки сохранён."
    )
  })

  it("does not accept a cap outside the supported range", async () => {
    const user = userEvent.setup()
    renderCard()

    const input = await screen.findByRole("spinbutton", {
      name: "Максимум бытовок в одном задании отгрузки",
    })
    await user.clear(input)
    await user.type(input, "101")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    expect(
      await screen.findByText("Укажите целое количество бытовок от 1 до 100.")
    ).toBeTruthy()
    expect(mocks.updateShipmentTaskSettings).not.toHaveBeenCalled()
  })

  it("refetches the current cap after a version conflict", async () => {
    const user = userEvent.setup()
    const refreshed = {
      ...defaultSetting,
      version: 2,
      maxCabinsPerShipmentTask: 4,
      updatedAt: "2026-08-10T12:00:00Z",
    }
    mocks.getShipmentTaskSettings
      .mockReset()
      .mockResolvedValueOnce({ ...defaultSetting, version: 1 })
      .mockResolvedValueOnce(refreshed)
    mocks.updateShipmentTaskSettings.mockRejectedValue(
      new ApiError("Конфликт версий", 409)
    )

    renderCard()

    const input = await screen.findByRole("spinbutton", {
      name: "Максимум бытовок в одном задании отгрузки",
    })
    await user.clear(input)
    await user.type(input, "5")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(mocks.getShipmentTaskSettings).toHaveBeenCalledTimes(2)
    )
    expect((await screen.findByRole("alert")).textContent).toContain(
      "изменена другим пользователем"
    )
    await waitFor(() =>
      expect(
        (
          screen.getByRole("spinbutton", {
            name: "Максимум бытовок в одном задании отгрузки",
          }) as HTMLInputElement
        ).value
      ).toBe("4")
    )
  })

  it("shows a load failure without supplying a client default", async () => {
    mocks.getShipmentTaskSettings.mockRejectedValue(
      new ApiError("logistics-service недоступен", 503)
    )

    renderCard()

    expect((await screen.findByRole("alert")).textContent).toContain(
      "Сервис временно недоступен. Повторите попытку позже."
    )
    expect(screen.queryByText("logistics-service недоступен")).toBeNull()
    expect(
      screen.getByText(
        "Локальное значение не подставляется: лимит задания задаёт сервис логистики."
      )
    ).toBeTruthy()
    expect(
      screen.queryByRole("spinbutton", {
        name: "Максимум бытовок в одном задании отгрузки",
      })
    ).toBeNull()
  })
})
