import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import { WarehouseSettingsPage } from "@/features/settings/warehouses/warehouse-settings-page"

const {
  completeWarehouseInactivation,
  createWarehouse,
  listWarehouseSupportLinks,
  listWarehouses,
  replaceWarehouse,
  replaceWarehouseSupportLinks,
  reloadWarehouses,
  scheduleWarehouseTimeZone,
  startWarehouseDraining,
} = vi.hoisted(() => ({
  completeWarehouseInactivation: vi.fn(),
  createWarehouse: vi.fn(),
  listWarehouseSupportLinks: vi.fn(),
  listWarehouses: vi.fn(),
  replaceWarehouse: vi.fn(),
  replaceWarehouseSupportLinks: vi.fn(),
  reloadWarehouses: vi.fn(),
  scheduleWarehouseTimeZone: vi.fn(),
  startWarehouseDraining: vi.fn(),
}))

vi.mock("@/api/warehouse-api", () => ({
  completeWarehouseInactivation,
  createWarehouse,
  listWarehouseSupportLinks,
  listWarehouses,
  replaceWarehouse,
  replaceWarehouseSupportLinks,
  scheduleWarehouseTimeZone,
  startWarehouseDraining,
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "access-token",
    currentUser: { globalRole: "SYSTEM_ADMIN" },
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({ reloadWarehouses }),
}))

const warehouse = {
  id: "00000000-0000-4000-8000-000000000001",
  version: 3,
  name: "Северный склад",
  city: "Санкт-Петербург",
  address: null,
  latitude: 59.9343,
  longitude: 30.3351,
  timeZone: "Europe/Moscow",
  active: true,
  lifecycleState: "ACTIVE",
  sortOrder: 2,
  production: true,
  mainWarehouse: false,
  representativeParentWarehouseId: null,
  representative: false,
}

const inactiveWarehouse = {
  ...warehouse,
  id: "00000000-0000-4000-8000-000000000003",
  name: "Южный склад",
  city: "Москва",
  active: false,
  lifecycleState: "INACTIVE",
}

const drainingWarehouse = {
  ...warehouse,
  id: "00000000-0000-4000-8000-000000000004",
  version: 5,
  name: "Склад на выводе",
  active: false,
  lifecycleState: "DRAINING",
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <WarehouseSettingsPage />
    </QueryClientProvider>
  )
}

function setField(
  user: ReturnType<typeof userEvent.setup>,
  name: string,
  value: string
) {
  return user
    .clear(screen.getByLabelText(name))
    .then(() => user.type(screen.getByLabelText(name), value))
}

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const pointerCaptureDescriptors = new Map(
  [
    "hasPointerCapture",
    "setPointerCapture",
    "releasePointerCapture",
    "scrollIntoView",
  ].map((name) => [
    name,
    Object.getOwnPropertyDescriptor(HTMLElement.prototype, name),
  ])
)

beforeEach(() => {
  vi.stubGlobal("ResizeObserver", ResizeObserverMock)
  Object.defineProperties(HTMLElement.prototype, {
    hasPointerCapture: { configurable: true, value: () => false },
    setPointerCapture: { configurable: true, value: () => undefined },
    releasePointerCapture: { configurable: true, value: () => undefined },
    scrollIntoView: { configurable: true, value: () => undefined },
  })
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
  for (const [name, descriptor] of pointerCaptureDescriptors) {
    if (descriptor) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor)
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, name)
    }
  }
})

describe("WarehouseSettingsPage", () => {
  it("keeps selected filters, search and create action when filters are collapsed", async () => {
    const user = userEvent.setup()
    listWarehouses.mockResolvedValue([warehouse, inactiveWarehouse])

    renderPage()

    await screen.findByRole("button", { name: "Статус" })
    await user.click(screen.getByRole("button", { name: "Статус" }))
    await user.click(screen.getByLabelText("Активные"))
    await user.click(screen.getByRole("button", { name: "Применить" }))

    await waitFor(() => expect(screen.queryByText("Южный склад")).toBeNull())

    await user.click(
      screen.getByRole("button", { name: "Скрыть фильтры объектов" })
    )

    expect(
      document
        .getElementById("warehouse-settings-filters")
        ?.hasAttribute("hidden")
    ).toBe(true)
    expect(
      screen.getByRole("searchbox", { name: "Поиск объектов" })
    ).toBeTruthy()
    expect(screen.getByRole("button", { name: "Создать объект" })).toBeTruthy()

    await user.click(
      screen.getByRole("button", { name: "Показать фильтры объектов" })
    )

    const filtersPanel = document.getElementById("warehouse-settings-filters")
    if (filtersPanel === null) throw new Error("Не найдена панель фильтров")

    await user.click(
      within(filtersPanel).getByRole("button", { name: /Статус/ })
    )
    expect(screen.getByLabelText("Активные").getAttribute("data-state")).toBe(
      "checked"
    )
  })

  it("creates a warehouse through the canonical idempotent command", async () => {
    const user = userEvent.setup()
    listWarehouses.mockResolvedValue([warehouse])
    createWarehouse.mockResolvedValue(warehouse)
    reloadWarehouses.mockResolvedValue(undefined)
    vi.stubGlobal("crypto", {
      randomUUID: () => "00000000-0000-4000-8000-000000000002",
    })

    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Создать объект" })
    )
    const orderedFields = [
      screen.getByLabelText("Адрес"),
      screen.getByLabelText("Временная зона"),
      screen.getByLabelText("Порядок"),
      screen.getByLabelText("Долгота"),
      screen.getByLabelText("Широта"),
    ]
    for (let index = 1; index < orderedFields.length; index += 1) {
      expect(
        orderedFields[index - 1]!.compareDocumentPosition(
          orderedFields[index]!
        ) & Node.DOCUMENT_POSITION_FOLLOWING
      ).not.toBe(0)
    }
    await setField(user, "Код города", "MSK")
    await setField(user, "Город", "Москва")
    await setField(user, "Широта", "55.7558")
    await setField(user, "Долгота", "37.6173")
    await user.click(
      screen.getByRole("checkbox", { name: "Представительский склад" })
    )
    await user.click(screen.getByLabelText("Код города объекта"))
    await user.click(
      screen.getByRole("option", {
        name: "Северный склад · Санкт-Петербург",
      })
    )
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(createWarehouse).toHaveBeenCalledWith(
        "access-token",
        "00000000-0000-4000-8000-000000000002",
        {
          name: "MSK",
          city: "Москва",
          address: null,
          latitude: 55.7558,
          longitude: 37.6173,
          timeZone: "Europe/Moscow",
          sortOrder: null,
          production: false,
          mainWarehouse: false,
          representativeParentWarehouseId: warehouse.id,
          representative: true,
        }
      )
    )
  })

  it("refreshes canonical data and closes a stale edit instead of overwriting it", async () => {
    const user = userEvent.setup()
    listWarehouses.mockResolvedValue([warehouse])
    replaceWarehouse.mockRejectedValue(new ApiError("Конфликт версий", 409))
    reloadWarehouses.mockResolvedValue(undefined)

    renderPage()

    await screen.findAllByRole("button", { name: "Изменить" })
    await user.click(screen.getAllByRole("button", { name: "Изменить" })[0]!)
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() => {
      expect(replaceWarehouse).toHaveBeenCalledWith(
        "access-token",
        warehouse.id,
        warehouse.version,
        {
          name: warehouse.name,
          city: warehouse.city,
          address: warehouse.address,
          latitude: warehouse.latitude,
          longitude: warehouse.longitude,
          timeZone: warehouse.timeZone,
          sortOrder: warehouse.sortOrder,
          production: true,
          mainWarehouse: false,
          representativeParentWarehouseId: null,
          representative: false,
        }
      )
      expect(reloadWarehouses).toHaveBeenCalled()
      expect(screen.queryByRole("dialog")).toBeNull()
    })
  })

  it("shows the warehouse classification and parent production in list and edit", async () => {
    const user = userEvent.setup()
    const productionWarehouse = {
      ...warehouse,
      production: true,
      mainWarehouse: false,
      representativeParentWarehouseId: null,
    }
    const representativeWarehouse = {
      ...warehouse,
      id: "00000000-0000-4000-8000-000000000021",
      name: "Псковский склад",
      city: "Псков",
      production: false,
      mainWarehouse: false,
      representativeParentWarehouseId: productionWarehouse.id,
      representative: true,
    }
    const additionalProductionWarehouse = {
      ...productionWarehouse,
      id: "00000000-0000-4000-8000-000000000022",
      name: "Дополнительное производство",
    }
    listWarehouses.mockResolvedValue([
      representativeWarehouse,
      productionWarehouse,
      additionalProductionWarehouse,
    ])
    listWarehouseSupportLinks.mockResolvedValue({
      servedWarehouseId: representativeWarehouse.id,
      warehouseVersion: representativeWarehouse.version,
      links: [],
    })
    replaceWarehouse.mockResolvedValue({
      ...representativeWarehouse,
      version: representativeWarehouse.version + 1,
    })
    reloadWarehouses.mockResolvedValue(undefined)

    renderPage()

    expect(
      (await screen.findAllByText("Представительский склад: Северный склад"))
        .length
    ).toBe(2)
    expect(screen.getAllByText("Производство").length).toBeGreaterThan(0)
    await user.click(
      (await screen.findAllByRole("button", { name: "Изменить" }))[0]!
    )
    expect(
      screen
        .getByRole("checkbox", { name: "Представительский склад" })
        .getAttribute("data-state")
    ).toBe("checked")
    await user.click(screen.getByLabelText("Код города объекта"))
    expect(
      screen.getByRole("option", {
        name: "Северный склад · Санкт-Петербург",
      })
    ).toBeTruthy()
    expect(
      screen.queryByRole("option", {
        name: "Дополнительное производство · Санкт-Петербург",
      })
    ).toBeTruthy()
    await user.keyboard("{Escape}")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    await waitFor(() =>
      expect(replaceWarehouse).toHaveBeenCalledWith(
        "access-token",
        representativeWarehouse.id,
        representativeWarehouse.version,
        expect.objectContaining({
          production: false,
          mainWarehouse: false,
          representativeParentWarehouseId: productionWarehouse.id,
          representative: true,
        })
      )
    )
  })

  it("edits support warehouses through the version-fenced collection command", async () => {
    const user = userEvent.setup()
    const representativeWarehouse = {
      ...warehouse,
      production: false,
      mainWarehouse: false,
      representativeParentWarehouseId: "00000000-0000-4000-8000-000000000005",
      representative: true,
    }
    const supportWarehouse = {
      ...warehouse,
      id: "00000000-0000-4000-8000-000000000005",
      name: "Западный склад",
      city: "Псков",
    }
    const link = {
      id: "00000000-0000-4000-8000-000000000006",
      version: 1,
      supportWarehouseId: supportWarehouse.id,
      servedWarehouseId: representativeWarehouse.id,
      active: true,
      priority: 1,
      allowDrivers: true,
      allowVehicles: true,
      allowInventory: true,
      allowDirectFulfillment: true,
      allowInterwarehouseTransfer: true,
      allowContractorFallback: false,
      allowedWeekdays: ["TUESDAY"],
      allowedDates: [],
      excludedDates: [],
      serviceStart: "08:00:00",
      serviceEnd: "18:00:00",
    }
    listWarehouses.mockResolvedValue([
      representativeWarehouse,
      supportWarehouse,
    ])
    listWarehouseSupportLinks.mockResolvedValue({
      servedWarehouseId: representativeWarehouse.id,
      warehouseVersion: representativeWarehouse.version,
      links: [link],
    })
    replaceWarehouseSupportLinks.mockResolvedValue({
      servedWarehouseId: representativeWarehouse.id,
      warehouseVersion: representativeWarehouse.version + 1,
      links: [{ ...link, version: 2, allowContractorFallback: true }],
    })
    reloadWarehouses.mockResolvedValue(undefined)

    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Изменить" }))[0]!
    )
    expect(await screen.findByText("Логистическое обслуживание")).toBeTruthy()
    await user.click(screen.getByText("Предлагать наёмного водителя"))
    await user.click(
      screen.getByRole("button", { name: "Сохранить обслуживание" })
    )

    await waitFor(() =>
      expect(replaceWarehouseSupportLinks).toHaveBeenCalledWith(
        "access-token",
        representativeWarehouse.id,
        representativeWarehouse.version,
        [
          expect.objectContaining({
            supportWarehouseId: supportWarehouse.id,
            allowContractorFallback: true,
            allowedWeekdays: ["TUESDAY"],
            serviceStart: "08:00",
            serviceEnd: "18:00",
          }),
        ]
      )
    )
  })

  it("starts the irreversible draining lifecycle with an expected version", async () => {
    const user = userEvent.setup()
    listWarehouses.mockResolvedValue([warehouse])
    startWarehouseDraining.mockResolvedValue({
      ...warehouse,
      version: 4,
      active: false,
      lifecycleState: "DRAINING",
    })
    reloadWarehouses.mockResolvedValue(undefined)

    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Изменить" }))[0]!
    )
    await user.click(screen.getByRole("button", { name: "Начать вывод" }))
    const dialog = screen.getByRole("alertdialog")
    expect(within(dialog).getByText(/Переход необратим/)).toBeTruthy()
    await user.click(
      within(dialog).getByRole("button", { name: "Начать вывод" })
    )

    await waitFor(() =>
      expect(startWarehouseDraining).toHaveBeenCalledWith(
        "access-token",
        warehouse.id,
        warehouse.version
      )
    )
  })

  it("completes inactivation only through the dedicated lifecycle command", async () => {
    const user = userEvent.setup()
    listWarehouses.mockResolvedValue([drainingWarehouse])
    completeWarehouseInactivation.mockResolvedValue({
      ...drainingWarehouse,
      version: 6,
      lifecycleState: "INACTIVE",
    })
    reloadWarehouses.mockResolvedValue(undefined)

    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Изменить" }))[0]!
    )
    await user.click(screen.getByRole("button", { name: "Завершить вывод" }))
    const dialog = screen.getByRole("alertdialog")
    expect(within(dialog).getByText(/подтверждения готовности/)).toBeTruthy()
    await user.click(
      within(dialog).getByRole("button", { name: "Завершить вывод" })
    )

    await waitFor(() =>
      expect(completeWarehouseInactivation).toHaveBeenCalledWith(
        "access-token",
        drainingWarehouse.id,
        drainingWarehouse.version
      )
    )
  })

  it("schedules an effective-dated timezone change without rewriting metadata", async () => {
    const user = userEvent.setup()
    listWarehouses.mockResolvedValue([warehouse])
    scheduleWarehouseTimeZone.mockResolvedValue({
      warehouseId: warehouse.id,
      warehouseVersion: 4,
      timeZone: "Europe/Samara",
      effectiveFrom: "2099-09-01T00:00:00+04:00",
    })
    reloadWarehouses.mockResolvedValue(undefined)

    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Изменить" }))[0]!
    )
    await user.click(
      screen.getByRole("button", { name: "Сменить часовой пояс" })
    )
    await user.click(screen.getByLabelText("Новая временная зона"))
    await user.click(screen.getByRole("option", { name: /Europe\/Samara/ }))
    await setField(user, "Начать с даты и времени", "2099-09-01T00:00:00+04:00")
    await user.click(screen.getByRole("button", { name: "Запланировать" }))

    await waitFor(() =>
      expect(scheduleWarehouseTimeZone).toHaveBeenCalledWith(
        "access-token",
        warehouse.id,
        warehouse.version,
        "Europe/Samara",
        "2099-09-01T00:00:00+04:00"
      )
    )
  })
})
