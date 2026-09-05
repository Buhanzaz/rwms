import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import {
  PlanningSettingsForm,
  PlanningSettingsPage,
} from "./planning-settings-page"
import { planningApi } from "./planning-api"
import { MSK, SPB, planningFixture } from "./planning-fixtures"
import type {
  PlannerWarehouseSettings,
  PlanningSettingsInput,
} from "./planning-types"

const state = vi.hoisted(() => ({
  token: "admin-token" as string | null,
  warehouseId: "",
  userId: "administrator",
}))
vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: state.token,
    currentUser: { id: state.userId },
  }),
}))
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    warehouses: [],
    selectedWarehouseId: state.warehouseId,
    setSelectedWarehouseId: vi.fn(),
    isLoading: false,
    error: null,
    reloadWarehouses: vi.fn(),
  }),
}))
vi.mock("./policy-zone-manager", () => ({
  default: () => <p>Редактор исключений</p>,
}))

beforeEach(() => {
  state.token = "admin-token"
  state.warehouseId = SPB
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

function form(warehouse = planningFixture()) {
  const save = vi
    .fn<(input: PlanningSettingsInput, version: number) => Promise<void>>()
    .mockResolvedValue(undefined)
  const reload = vi.fn<() => Promise<void>>().mockResolvedValue(undefined)
  render(
    <PlanningSettingsForm
      warehouse={warehouse}
      token="admin-token"
      onSave={save}
      onReload={reload}
    />
  )
  return { save, reload }
}

describe("administrative planning settings", () => {
  it("saves one changed field with the complete tariff ladder, unseen settings and observed version", async () => {
    const user = userEvent.setup()
    const original = planningFixture()
    const { save } = form(original)
    fireEvent.change(screen.getByLabelText("Скорость в городе, км/ч"), {
      target: { value: "42" },
    })
    await user.click(
      screen.getByRole("button", { name: "Сохранить настройки" })
    )
    await waitFor(() => expect(save).toHaveBeenCalledOnce())
    expect(save).toHaveBeenCalledWith(
      {
        settings: { ...original.settings, city_speed_kmh: 42 },
        isochrone_tariffs: original.isochrone_tariffs,
      },
      7
    )
    expect(screen.queryByText(/backend|trace events/)).toBeNull()
    expect(
      (
        screen.getByLabelText(
          "В каждом цикле доставки раньше вывозов"
        ) as HTMLButtonElement
      ).disabled
    ).toBe(true)
  })

  it("retains algorithm edits across the tariff tab and saves contiguous whole-hour prices", async () => {
    const user = userEvent.setup()
    const { save } = form()
    fireEvent.change(
      screen.getByLabelText("Штраф дополнительной машины/водителя"),
      { target: { value: "240" } }
    )
    await user.click(screen.getByRole("tab", { name: "Тарифы" }))
    await user.click(screen.getByRole("button", { name: "Добавить 5-й час" }))
    fireEvent.change(screen.getByLabelText("До 5 ч, ₽"), {
      target: { value: "30000" },
    })
    expect(
      screen.getByText("Заказы дальше 5 ч от склада недоступны.")
    ).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Сохранить настройки" })
    )
    await waitFor(() => expect(save).toHaveBeenCalledOnce())
    expect(save.mock.calls[0][0]).toMatchObject({
      settings: { additional_resource_activation_penalty: 240 },
      isochrone_tariffs: [
        ...planningFixture().isochrone_tariffs,
        { travel_minutes: 300, price_rubles: 30000 },
      ],
    })
  })

  it("converts the overtime limit from hours to minutes while retaining the fleet controls", async () => {
    const user = userEvent.setup()
    const { save } = form()
    expect(
      (screen.getByLabelText("Максимальная переработка, ч") as HTMLInputElement)
        .disabled
    ).toBe(true)
    await user.click(
      screen.getByRole("switch", { name: "Разрешить переработку" })
    )
    fireEvent.change(screen.getByLabelText("Максимальная переработка, ч"), {
      target: { value: "2.5" },
    })
    await user.click(
      screen.getByRole("button", { name: "Сохранить настройки" })
    )
    await waitFor(() => expect(save).toHaveBeenCalledOnce())
    expect(save.mock.calls[0][0].settings).toMatchObject({
      allow_soft_overtime: true,
      soft_overtime_limit_minutes: 150,
      preferred_shift_utilization_percent: 80,
      default_cargo_weight_kg: 1200,
    })
  })

  it("keeps the draft and original version after conflict until explicit reload", async () => {
    const user = userEvent.setup()
    const { save, reload } = form()
    save.mockRejectedValue(
      new Error("Настройки изменены другим администратором")
    )
    fireEvent.change(screen.getByLabelText("Скорость в городе, км/ч"), {
      target: { value: "42" },
    })
    await user.click(
      screen.getByRole("button", { name: "Сохранить настройки" })
    )
    expect((await screen.findByRole("alert")).textContent).toContain(
      "Настройки изменены"
    )
    expect(
      (screen.getByLabelText("Скорость в городе, км/ч") as HTMLInputElement)
        .value
    ).toBe("42")
    expect(reload).not.toHaveBeenCalled()
    await user.click(
      screen.getByRole("button", { name: "Загрузить сохранённые настройки" })
    )
    expect(reload).toHaveBeenCalledOnce()
    expect(save.mock.calls[0][1]).toBe(7)
  })

  it("loads exceptional policies separately and does not offer the algorithm save there", async () => {
    const user = userEvent.setup()
    form(planningFixture({ capacity_publish_status: "FAILED" }))
    expect(screen.getByText(/не смог опубликовать/)).toBeTruthy()
    await user.click(screen.getByRole("tab", { name: "Исключения" }))
    expect(await screen.findByText("Редактор исключений")).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Сохранить настройки" })
    ).toBeNull()
  })

  it("ignores a late response from the previous warehouse and hides cached settings on logout", async () => {
    let resolveFirst!: (value: PlannerWarehouseSettings) => void
    const first = new Promise<PlannerWarehouseSettings>((resolve) => {
      resolveFirst = resolve
    })
    const read = vi
      .spyOn(planningApi, "getSettings")
      .mockReturnValueOnce(first)
      .mockResolvedValueOnce(
        planningFixture({
          warehouse_id: MSK,
          name: "MSK",
          settings: { ...planningFixture().settings, city_speed_kmh: 52 },
        })
      )
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const content = () => (
      <QueryClientProvider client={client}>
        <PlanningSettingsPage />
      </QueryClientProvider>
    )
    const view = render(content())
    await waitFor(() => expect(read).toHaveBeenCalledOnce())
    state.warehouseId = MSK
    view.rerender(content())
    expect(
      (
        (await screen.findByLabelText(
          "Скорость в городе, км/ч"
        )) as HTMLInputElement
      ).value
    ).toBe("52")
    await act(async () => {
      resolveFirst(planningFixture())
    })
    expect(
      (screen.getByLabelText("Скорость в городе, км/ч") as HTMLInputElement)
        .value
    ).toBe("52")
    state.token = null
    view.rerender(content())
    expect(screen.getByRole("alert").textContent).toContain(
      "авторизация администратора"
    )
    expect(screen.queryByLabelText("Скорость в городе, км/ч")).toBeNull()
    client.clear()
  })
})
