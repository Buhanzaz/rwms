import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest"

import type { WarehouseInfo } from "@/api/warehouse-api"
import {
  adminCatalogKeys,
  defaultAdminVehicleSpecification,
  emptyAdminPhysicalSpecification,
  type AdminCatalogResource,
  type AdminCatalogKind,
} from "@/features/settings/fleet/api/admin-catalog-api"
import { ApiError } from "@/lib/api-client"

const catalogApi = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: "admin-token" }),
}))

vi.mock(
  "@/features/settings/fleet/api/admin-catalog-api",
  async (importOriginal) => {
    const original =
      await importOriginal<
        typeof import("@/features/settings/fleet/api/admin-catalog-api")
      >()
    return {
      ...original,
      listAdminCatalogResources: catalogApi.list,
      createAdminCatalogResource: catalogApi.create,
      updateAdminCatalogResource: catalogApi.update,
      deleteAdminCatalogResource: catalogApi.remove,
    }
  }
)

vi.mock("sonner", () => ({
  toast: { error: vi.fn(), success: vi.fn() },
}))

import { AdminCatalogSettingsPage } from "@/features/settings/fleet/admin-catalog-settings-page"

const SOURCE_ID = "11111111-1111-4111-8111-111111111111"
const VEHICLE_ID = "33333333-3333-4333-8333-333333333333"
const TRAILER_ID = "44444444-4444-4444-8444-444444444444"

function warehouse(id: string, name: string, city: string): WarehouseInfo {
  return {
    id,
    version: 1,
    name,
    city,
    address: null,
    latitude: null,
    longitude: null,
    timeZone: "Europe/Moscow",
    active: true,
    lifecycleState: "ACTIVE",
    sortOrder: 1,
    representative: false,
    production: true,
    mainWarehouse: false,
    representativeParentWarehouseId: null,
  }
}

const source = warehouse(SOURCE_ID, "MSK", "Москва")
const vehicle: AdminCatalogResource = {
  id: VEHICLE_ID,
  version: 4,
  warehouseId: SOURCE_ID,
  name: "КамАЗ",
  registrationNumber: "А123ВС77",
  active: true,
  notes: "Манипулятор",
  capacity: 2,
  physical: {
    ...emptyAdminPhysicalSpecification(),
    tareWeightKg: 12_000,
  },
  vehicle: {
    ...defaultAdminVehicleSpecification(),
    vehicleType: "CRANE",
    loadProfiles: [
      { configurationType: "EMPTY_TRUCK", maxActualAxleLoadKg: 7_500 },
    ],
  },
}

const trailer: AdminCatalogResource = {
  ...vehicle,
  id: TRAILER_ID,
  name: "Тонар",
  registrationNumber: "АА123477",
  capacity: null,
  vehicle: null,
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

beforeAll(() => {
  vi.stubGlobal(
    "ResizeObserver",
    class ResizeObserver {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  Object.defineProperties(HTMLElement.prototype, {
    hasPointerCapture: { configurable: true, value: () => false },
    setPointerCapture: { configurable: true, value: () => undefined },
    releasePointerCapture: { configurable: true, value: () => undefined },
    scrollIntoView: { configurable: true, value: () => undefined },
  })
})

afterAll(() => {
  vi.unstubAllGlobals()
  for (const [name, descriptor] of pointerCaptureDescriptors) {
    if (descriptor) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor)
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, name)
    }
  }
})

function renderPage(kind: AdminCatalogKind = "vehicle") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  })
  const result = render(
    <QueryClientProvider client={queryClient}>
      <AdminCatalogSettingsPage kind={kind} warehouse={source} />
    </QueryClientProvider>
  )
  return { ...result, queryClient }
}

beforeEach(() => {
  catalogApi.create.mockReset()
  catalogApi.update.mockReset()
  catalogApi.remove.mockReset()
  catalogApi.list.mockImplementation(
    (_token: string, _warehouseId: string, kind: string) =>
      Promise.resolve(kind === "vehicle" ? [vehicle] : [])
  )
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("AdminCatalogSettingsPage", () => {
  it("shows the fleet catalog as an operational grid without relocation", async () => {
    renderPage()

    expect(await screen.findByText("КамАЗ")).toBeTruthy()
    expect(screen.getByRole("columnheader", { name: "Транспорт" })).toBeTruthy()
    expect(screen.getByRole("columnheader", { name: "Активен" })).toBeTruthy()
    expect(
      screen.getByRole("columnheader", { name: "Комментарий" })
    ).toBeTruthy()
    expect(screen.queryByText("v4")).toBeNull()
    expect(screen.getByText("Манипулятор").getAttribute("title")).toBe(
      "Манипулятор"
    )
    expect(screen.queryByRole("button", { name: /переместить/i })).toBeNull()
  })

  it("moves the destructive action into the editor and keeps the guarded delete error", async () => {
    const user = userEvent.setup()
    catalogApi.remove.mockRejectedValue(
      new ApiError(
        "Delete is forbidden while the vehicle has retained driver-shift history",
        409,
        "VEHICLE_HAS_LINKED_SHIFTS"
      )
    )
    renderPage()

    expect(await screen.findByText("КамАЗ")).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Изменить" }))
    const deleteButton = screen.getByRole("button", { name: "Удалить" })
    expect(deleteButton.getAttribute("data-variant")).toBe("destructive")
    await user.click(deleteButton)
    await user.click(screen.getByRole("button", { name: "Удалить" }))

    expect(
      await screen.findByText(
        "Транспорт нельзя удалить: с ним сохранена история смен."
      )
    ).toBeTruthy()
    expect(catalogApi.remove).toHaveBeenCalledWith(
      "admin-token",
      "vehicle",
      vehicle
    )
  })

  it("saves edited physical data and axle measurements as one configuration", async () => {
    const user = userEvent.setup()
    catalogApi.update.mockResolvedValue({ ...vehicle, version: 5 })
    renderPage()

    expect(await screen.findByText("КамАЗ")).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Изменить" }))
    await user.click(
      screen.getByRole("button", { name: "Масса, габариты и оси" })
    )
    const tareWeight = screen.getByRole("textbox", {
      name: "Снаряжённая масса, кг",
    })
    await user.clear(tareWeight)
    await user.type(tareWeight, "12500")
    await user.click(screen.getByRole("button", { name: "Осевые замеры" }))
    const emptyTruckLoad = screen.getByRole("textbox", {
      name: "Пустой автомобиль, кг",
    })
    await user.clear(emptyTruckLoad)
    await user.type(emptyTruckLoad, "7800")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    expect(catalogApi.update).toHaveBeenCalledWith(
      "admin-token",
      "vehicle",
      vehicle,
      expect.objectContaining({
        physical: expect.objectContaining({ tareWeightKg: 12_500 }),
        vehicle: expect.objectContaining({
          vehicleType: "CRANE",
          averageSpeedCity: 35,
          averageSpeedRegion: 65,
          loadProfiles: [
            {
              configurationType: "EMPTY_TRUCK",
              maxActualAxleLoadKg: 7_800,
            },
          ],
        }),
      })
    )
  })

  it("lets the keyboard reach capacity and nullable vehicle flags", async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole("button", { name: "Изменить" }))
    screen.getByRole("textbox", { name: "Регистрационный номер" }).focus()
    await user.tab()
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole("radio", { name: "2 бытовки" })
      )
    )
    await user.keyboard("{ArrowLeft}")
    await user.keyboard(" ")
    expect(
      screen
        .getByRole("radio", { name: "1 бытовка" })
        .getAttribute("aria-checked")
    ).toBe("true")
    await user.click(screen.getByRole("button", { name: "Автомобиль" }))
    screen.getByRole("textbox", { name: "Модель" }).focus()
    await user.tab()
    const hgv = screen.getByRole("radiogroup", {
      name: "Грузовой автомобиль (HGV)",
    })
    await waitFor(() =>
      expect(document.activeElement).toBe(
        within(hgv).getByRole("radio", { name: "Не указано" })
      )
    )
  })

  it("preserves every hidden specification when only the name changes", async () => {
    const user = userEvent.setup()
    catalogApi.update.mockResolvedValue({ ...vehicle, version: 5 })
    const { queryClient } = renderPage()
    const invalidate = vi.spyOn(queryClient, "invalidateQueries")
    await user.click(await screen.findByRole("button", { name: "Изменить" }))
    await user.clear(screen.getByRole("textbox", { name: "Название" }))
    await user.type(
      screen.getByRole("textbox", { name: "Название" }),
      "КамАЗ резерв"
    )
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    expect(catalogApi.update).toHaveBeenCalledWith(
      "admin-token",
      "vehicle",
      vehicle,
      {
        name: "КамАЗ резерв",
        registrationNumber: vehicle.registrationNumber,
        capacity: 2,
        active: true,
        notes: vehicle.notes,
        physical: vehicle.physical,
        vehicle: vehicle.vehicle,
      }
    )
    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith({
        queryKey: adminCatalogKeys.warehouse("vehicle", SOURCE_ID),
      })
    )
    expect(invalidate).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole("dialog")).toBeNull()
    invalidate.mockRestore()
  })

  it("clears physical data to null and deletes only the selected axle measurement", async () => {
    const user = userEvent.setup()
    const configured = {
      ...vehicle,
      vehicle: {
        ...vehicle.vehicle!,
        loadProfiles: [
          ...vehicle.vehicle!.loadProfiles,
          {
            configurationType: "CARGO_ON_TRUCK" as const,
            maxActualAxleLoadKg: 9_000,
          },
        ],
      },
    }
    catalogApi.list.mockImplementation((_token, _warehouse, kind) =>
      Promise.resolve(kind === "vehicle" ? [configured] : [])
    )
    catalogApi.update.mockResolvedValue({ ...configured, version: 5 })
    renderPage()
    await user.click(await screen.findByRole("button", { name: "Изменить" }))
    await user.click(
      screen.getByRole("button", { name: "Масса, габариты и оси" })
    )
    await user.clear(
      screen.getByRole("textbox", { name: "Снаряжённая масса, кг" })
    )
    await user.click(
      screen.getByRole("button", { name: "Масса, габариты и оси" })
    )
    await user.click(screen.getByRole("button", { name: "Осевые замеры" }))
    await user.clear(
      screen.getByRole("textbox", { name: "Пустой автомобиль, кг" })
    )
    await user.click(screen.getByRole("button", { name: "Осевые замеры" }))
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    const saved = catalogApi.update.mock.calls[0]![3]
    expect(saved.physical.tareWeightKg).toBeNull()
    expect(saved.vehicle.loadProfiles).toEqual([
      { configurationType: "CARGO_ON_TRUCK", maxActualAxleLoadKg: 9_000 },
    ])
  })

  it("creates unknown data without zero-filling and reuses its idempotency key after an error", async () => {
    const user = userEvent.setup()
    catalogApi.create
      .mockRejectedValueOnce(
        new ApiError("Сервис временно недоступен", 503, "SERVICE_UNAVAILABLE")
      )
      .mockResolvedValueOnce(vehicle)
    renderPage()
    await user.click(screen.getByRole("button", { name: "Добавить" }))
    await user.type(
      screen.getByRole("textbox", { name: "Название" }),
      "Новая машина"
    )
    await user.type(
      screen.getByRole("textbox", { name: "Регистрационный номер" }),
      "А123ВС77"
    )
    await user.click(screen.getByRole("button", { name: "Автомобиль" }))
    await user.click(
      within(
        screen.getByRole("radiogroup", { name: "Грузовой автомобиль (HGV)" })
      ).getByRole("radio", { name: "Нет" })
    )
    await user.click(screen.getByRole("button", { name: "Расчёт маршрута" }))
    const speed = screen.getByRole("textbox", {
      name: "Средняя скорость в городе, км/ч",
    })
    await user.clear(speed)
    await user.type(speed, "37,5")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    expect(await screen.findByText("Сервис временно недоступен")).toBeTruthy()
    expect(
      (screen.getByRole("textbox", { name: "Название" }) as HTMLInputElement)
        .value
    ).toBe("Новая машина")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    expect(catalogApi.create).toHaveBeenCalledTimes(2)
    const first = catalogApi.create.mock.calls[0]!
    expect(first.slice(0, 3)).toEqual(["admin-token", SOURCE_ID, "vehicle"])
    expect(first[3].physical).toEqual(emptyAdminPhysicalSpecification())
    expect(first[3].vehicle).toMatchObject({
      isHgv: false,
      canUseTrailer: null,
      defaultTrailerId: null,
      averageSpeedCity: 37.5,
      heightSafetyMarginMm: 0,
      loadProfiles: [],
    })
    expect(first[4]).toMatch(/^[0-9a-f-]{36}$/i)
    expect(catalogApi.create.mock.calls[1]![4]).toBe(first[4])
  })

  it.each([
    ["Масса, габариты и оси", "Высота транспорта, мм", "0"],
    ["Масса, габариты и оси", "Высота транспорта, мм", "-1"],
    ["Масса, габариты и оси", "Высота транспорта, мм", "1.5"],
    ["Расчёт маршрута", "Запас по высоте, мм", "-1"],
    ["Расчёт маршрута", "Средняя скорость в городе, км/ч", "0"],
    ["Осевые замеры", "Пустой автомобиль, кг", "0"],
  ])(
    "validates %s / %s = %s and reveals the invalid field",
    async (section, label, value) => {
      const user = userEvent.setup()
      renderPage()
      await user.click(await screen.findByRole("button", { name: "Изменить" }))
      await user.click(screen.getByRole("button", { name: section }))
      const input = screen.getByRole("textbox", { name: label })
      await user.clear(input)
      await user.type(input, value)
      await user.click(screen.getByRole("button", { name: section }))
      await user.click(screen.getByRole("button", { name: "Сохранить" }))
      expect(catalogApi.update).not.toHaveBeenCalled()
      const invalid = await screen.findByRole("textbox", { name: label })
      expect(invalid.getAttribute("aria-invalid")).toBe("true")
      await waitFor(() => expect(document.activeElement).toBe(invalid))
      expect((invalid as HTMLInputElement).value).toBe(value)
    }
  )

  it("edits trailer physical limits without inventing vehicle fields", async () => {
    const user = userEvent.setup()
    catalogApi.list.mockResolvedValue([trailer])
    catalogApi.update.mockResolvedValue({ ...trailer, version: 5 })
    renderPage("trailer")
    await user.click(await screen.findByRole("button", { name: "Изменить" }))
    expect(screen.queryByRole("button", { name: "Осевые замеры" })).toBeNull()
    expect(
      screen.queryByRole("radiogroup", { name: "Вместимость, бытовок" })
    ).toBeNull()
    await user.click(screen.getByRole("button", { name: "Платформа и груз" }))
    await user.type(
      screen.getByRole("textbox", { name: "Длина платформы, мм" }),
      "6200"
    )
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    expect(catalogApi.update).toHaveBeenCalledWith(
      "admin-token",
      "trailer",
      trailer,
      expect.objectContaining({
        capacity: null,
        vehicle: null,
        physical: { ...trailer.physical, platformLengthMm: 6200 },
      })
    )
  })

  it("keeps the assigned trailer when its directory cannot be loaded", async () => {
    const user = userEvent.setup()
    const configured = {
      ...vehicle,
      vehicle: {
        ...vehicle.vehicle!,
        canUseTrailer: true,
        defaultTrailerId: TRAILER_ID,
      },
    }
    catalogApi.list.mockImplementation((_token, _warehouse, kind) =>
      kind === "vehicle"
        ? Promise.resolve([configured])
        : Promise.reject(new Error("Ошибка каталога прицепов"))
    )
    catalogApi.update.mockResolvedValue({ ...configured, version: 5 })
    renderPage()
    await user.click(await screen.findByRole("button", { name: "Изменить" }))
    await user.click(screen.getByRole("button", { name: "Прицеп" }))
    expect(await screen.findByText("Не удалось загрузить прицепы")).toBeTruthy()
    await user.type(
      screen.getByRole("textbox", { name: "Название" }),
      " резерв"
    )
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    expect(catalogApi.update.mock.calls[0]![3].vehicle).toEqual(
      configured.vehicle
    )
  })

  it("requires explicit towing capability before assigning a same-warehouse trailer", async () => {
    const user = userEvent.setup()
    catalogApi.list.mockImplementation((_token, _warehouse, kind) =>
      Promise.resolve(kind === "vehicle" ? [vehicle] : [trailer])
    )
    catalogApi.update.mockResolvedValue({ ...vehicle, version: 5 })
    renderPage()
    await user.click(await screen.findByRole("button", { name: "Изменить" }))
    await user.click(screen.getByRole("button", { name: "Прицеп" }))
    await user.click(
      screen.getByRole("combobox", { name: "Прицеп по умолчанию" })
    )
    await user.click(
      await screen.findByRole("option", { name: "Тонар · АА123477" })
    )
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    expect(catalogApi.update).not.toHaveBeenCalled()
    expect(
      screen.getByText(
        "Для назначенного прицепа выберите «Да» или снимите назначение."
      )
    ).toBeTruthy()
    await user.click(
      within(
        screen.getByRole("radiogroup", { name: "Можно использовать прицеп" })
      ).getByRole("radio", { name: "Да" })
    )
    await user.click(screen.getByRole("button", { name: "Сохранить" }))
    expect(catalogApi.list).toHaveBeenCalledWith(
      "admin-token",
      SOURCE_ID,
      "trailer"
    )
    expect(catalogApi.update.mock.calls[0]![3].vehicle).toMatchObject({
      canUseTrailer: true,
      defaultTrailerId: TRAILER_ID,
    })
  })

  it.each([false, true])(
    "retains the draft and old version on conflict even when refetch fails=%s",
    async (refetchFails) => {
      const user = userEvent.setup()
      let reads = 0
      catalogApi.list.mockImplementation((_token, _warehouse, kind) => {
        if (kind === "trailer") return Promise.resolve([])
        reads += 1
        return reads === 1
          ? Promise.resolve([vehicle])
          : refetchFails
            ? Promise.reject(new Error("Каталог временно недоступен"))
            : Promise.resolve([
                { ...vehicle, version: 5, name: "Чужое изменение" },
              ])
      })
      catalogApi.update.mockRejectedValue(
        new ApiError("Version conflict", 409, "CATALOG_VERSION_CONFLICT")
      )
      renderPage()
      await user.click(await screen.findByRole("button", { name: "Изменить" }))
      await user.clear(screen.getByRole("textbox", { name: "Название" }))
      await user.type(
        screen.getByRole("textbox", { name: "Название" }),
        "Мой черновик"
      )
      await user.click(screen.getByRole("button", { name: "Сохранить" }))
      expect(
        await screen.findByText(/Запись уже изменена другим администратором/)
      ).toBeTruthy()
      await waitFor(() => expect(reads).toBe(2))
      if (refetchFails)
        await screen.findByText(
          /Запись уже изменена.*Не удалось обновить каталог/
        )
      expect(
        (screen.getByRole("textbox", { name: "Название" }) as HTMLInputElement)
          .value
      ).toBe("Мой черновик")
      await user.click(screen.getByRole("button", { name: "Сохранить" }))
      expect(catalogApi.update.mock.calls[1]![2].version).toBe(4)
      expect(catalogApi.update.mock.calls[1]![3].vehicle.loadProfiles).toEqual(
        vehicle.vehicle!.loadProfiles
      )
    }
  )
})
