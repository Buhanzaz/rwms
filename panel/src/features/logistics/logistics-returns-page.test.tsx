import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, useLocation } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type {
  ReturnDocument,
  ReturnLine,
} from "@/features/logistics/returns/model"

Object.defineProperties(HTMLElement.prototype, {
  hasPointerCapture: { configurable: true, value: () => false },
  releasePointerCapture: { configurable: true, value: () => undefined },
  scrollIntoView: { configurable: true, value: () => undefined },
  setPointerCapture: { configurable: true, value: () => undefined },
})

const returnApi = vi.hoisted(() => ({
  acceptUndamagedReturn: vi.fn(),
  createReturn: vi.fn(),
  listReturns: vi.fn(),
  registerReturn: vi.fn(),
  startReturnEstimates: vi.fn(),
}))
const driverDirectoryApi = vi.hoisted(() => ({
  listRepairWorkerGroups: vi.fn(),
}))
const rentalItemsApi = vi.hoisted(() => ({
  getAssetRentalItem: vi.fn(),
}))
const ordersApi = vi.hoisted(() => ({
  getOrder: vi.fn(),
}))
const equipmentApi = vi.hoisted(() => ({
  getEquipmentItems: vi.fn(),
}))
const shipmentApi = vi.hoisted(() => ({
  listShipments: vi.fn(),
}))
const authState = vi.hoisted(() => ({
  level: "EDIT" as "VIEW" | "EDIT" | "MANAGE",
}))

vi.mock("@/features/logistics/returns/api", () => ({
  RETURNS_QUERY_KEY: ["logistics", "returns"],
  ...returnApi,
}))

vi.mock("@/features/repair-tasks/api/repair-worker-directory-api", () => ({
  repairWorkerGroupsQueryKey: (query: unknown) => [
    "repair-worker-groups",
    query,
  ],
  listRepairWorkerGroups: driverDirectoryApi.listRepairWorkerGroups,
}))

vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  getAssetRentalItem: rentalItemsApi.getAssetRentalItem,
}))

vi.mock("@/features/orders/api/orders-api", () => ({
  ORDERS_QUERY_KEY: ["orders"],
  getOrder: ordersApi.getOrder,
}))

vi.mock("@/features/logistics/shipments/api", () => ({
  SHIPMENTS_QUERY_KEY: ["logistics", "shipments"],
  listShipments: shipmentApi.listShipments,
}))

vi.mock("@/features/logistics/logistics-driver-picker", () => ({
  LogisticsDriverPicker: ({
    id,
    value,
    onChange,
  }: {
    id: string
    value: { id: string; name: string } | null
    onChange: (value: { id: string; name: string } | null) => void
  }) => (
    <label htmlFor={id}>
      Водитель
      <select
        id={id}
        value={value?.id ?? ""}
        onChange={(event) =>
          onChange(
            event.target.value
              ? {
                  id: event.target.value,
                  name: "Иванов Иван",
                }
              : null
          )
        }
      >
        <option value="">Выберите водителя</option>
        <option value="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb">
          Иванов Иван
        </option>
      </select>
    </label>
  ),
}))

vi.mock("@/api/equipment-api", () => ({
  getEquipmentItems: equipmentApi.getEquipmentItems,
}))

vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: ({
    title,
    onReadyReferencesChange,
  }: {
    title: string
    onReadyReferencesChange?: (
      references: Array<{ mediaId: string; generation: number }>
    ) => void
  }) => (
    <button
      type="button"
      onClick={() =>
        onReadyReferencesChange?.([
          {
            mediaId: "88888888-8888-4888-8888-888888888888",
            generation: 1,
          },
        ])
      }
    >
      Подготовить {title}
    </button>
  ),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "return-token",
    currentUser: {
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      username: "operator",
      displayName: "Оператор",
      firstName: null,
      lastName: null,
      email: null,
      principalType: "USER",
      globalRole: "WAREHOUSE_MANAGER",
      warehouseAccessAll: false,
      warehouseAccesses: [
        {
          warehouseId: "11111111-1111-4111-8111-111111111111",
          level: authState.level,
        },
      ],
    },
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouseId: "11111111-1111-4111-8111-111111111111",
    selectedWarehouse: {
      id: "11111111-1111-4111-8111-111111111111",
      name: "Склад обслуживания",
      timeZone: "Europe/Moscow",
    },
  }),
}))

import { LogisticsReturnsPage } from "@/features/logistics/logistics-returns-page"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DOCUMENT_ID = "22222222-2222-4222-8222-222222222222"
const INSPECTION_ID = "33333333-3333-4333-8333-333333333333"
const LINE_ID = "44444444-4444-4444-8444-444444444444"
const ASSET_ID = "55555555-5555-4555-8555-555555555555"
const CLIENT_ID = "66666666-6666-4666-8666-666666666666"
const ORDER_ID = "77777777-7777-4777-8777-777777777777"
const EQUIPMENT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"
const IDEMPOTENCY_KEY = "99999999-9999-4999-8999-999999999999"
const DRIVER_WORKER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
const ASSET_NUMBER = "БЫТ-041"
const ORDER_NUMBER = "ORD-000007"
const SHIPMENT_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

function returnLine(
  id: string,
  assetId: string,
  lineNumber: number
): ReturnLine {
  return {
    id,
    version: 2,
    lineNumber,
    assetId,
    assetVersion: 8,
    state: "PENDING",
    tenantSnapshot: "ООО Тест",
    rentalOrderId: ORDER_ID,
  }
}

function returnDocument(
  id: string,
  state: ReturnDocument["state"],
  version: number,
  lines: ReturnLine[] = [returnLine(LINE_ID, ASSET_ID, 1)]
): ReturnDocument {
  return {
    id,
    version,
    documentType: "RETURN",
    customerDeliveryPurpose: null,
    state,
    warehouseId: WAREHOUSE_ID,
    destinationWarehouseId: null,
    partySnapshot: null,
    driverSnapshot: null,
    driverWorkerId: null,
    clientId: CLIENT_ID,
    equipmentMovementTaskId: null,
    scheduledDate: state === "DRAFT" ? null : "2026-07-18",
    rentalOrderId: ORDER_ID,
    rentalShipmentId: SHIPMENT_ID,
    lines,
    createdAt: "2026-07-18T08:00:00Z",
    updatedAt: "2026-07-18T08:10:00Z",
  }
}

function LocationProbe() {
  const location = useLocation()
  return (
    <output data-testid="current-location">{`${location.pathname}${location.search}`}</output>
  )
}

function renderPage(initialEntry = "/") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <QueryClientProvider client={queryClient}>
        <LocationProbe />
        <LogisticsReturnsPage />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  authState.level = "EDIT"
  vi.stubGlobal("crypto", { randomUUID: () => IDEMPOTENCY_KEY })
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  driverDirectoryApi.listRepairWorkerGroups.mockResolvedValue([
    {
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      warehouseId: WAREHOUSE_ID,
      name: "Водители",
      active: true,
      queueIds: [],
      routeQueueKinds: ["MOVEMENT"],
      members: [
        {
          id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
          name: "Иванов Иван",
        },
      ],
    },
  ])
  returnApi.listReturns.mockResolvedValue([
    returnDocument(DOCUMENT_ID, "DRAFT", 2),
    returnDocument(INSPECTION_ID, "INSPECTION_REQUIRED", 4),
  ])
  rentalItemsApi.getAssetRentalItem.mockResolvedValue({
    id: ASSET_ID,
    number: ASSET_NUMBER,
    contentsItems: [
      {
        equipmentId: EQUIPMENT_ID,
        equipmentName: "Стул",
        name: "Стул",
        quantity: 2,
      },
    ],
  })
  ordersApi.getOrder.mockResolvedValue({
    id: ORDER_ID,
    number: ORDER_NUMBER,
    status: "SAVED",
    customerDeliveryPurpose: "RENTAL_DELIVERY",
    client: {
      id: CLIENT_ID,
      displayName: "ООО Тест",
      contactPerson: "Анна Смирнова",
      phone: "+7 900 100-20-30",
      additionalContacts: [
        { name: "Павел Сидоров", phone: "+7 900 200-30-40" },
      ],
    },
    deliveryAddress: "Москва, ул. Тестовая, 1",
    latitude: 55.751244,
    longitude: 37.618423,
    contactPhone: "+7 900 300-40-50",
    comment: "Позвонить за час",
    additionalContacts: [{ name: "Олег Кузнецов", phone: "+7 900 400-50-60" }],
    desiredDeliveryWindows: [
      {
        startDate: "2026-07-23",
        endDate: "2026-07-23",
      },
    ],
    units: [
      {
        unit: { id: ASSET_ID, number: ASSET_NUMBER },
        desiredContents: [
          {
            equipmentId: EQUIPMENT_ID,
            equipmentName: "Стул",
            quantity: 2,
          },
        ],
        rentalTerm: {
          rentalMonths: 3,
          shipmentDate: "2026-07-18",
          returnDate: "2026-10-18",
        },
      },
    ],
  })
  shipmentApi.listShipments.mockResolvedValue([
    {
      id: SHIPMENT_ID,
      driverSnapshot: "Иванов Иван",
      lines: [{ assetId: ASSET_ID }],
    },
  ])
  equipmentApi.getEquipmentItems.mockResolvedValue([
    {
      id: EQUIPMENT_ID,
      warehouseId: WAREHOUSE_ID,
      name: "Стул",
      category: "FURNITURE",
      active: true,
    },
  ])
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe("LogisticsReturnsPage", () => {
  it("loads returns for the day restored from the URL", async () => {
    renderPage("/logistics/returns?date=2026-07-22")

    await waitFor(() =>
      expect(returnApi.listReturns).toHaveBeenCalledWith(
        "return-token",
        WAREHOUSE_ID,
        "2026-07-22"
      )
    )
    expect(screen.getByTestId("current-location").textContent).toBe(
      "/logistics/returns?date=2026-07-22"
    )
  })

  it("shows cabin and order numbers in the return composition", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать состав" }))[0]!
    )
    expect(
      (await screen.findAllByText(`Бытовка ${ASSET_NUMBER}`)).length
    ).toBeGreaterThan(0)
    expect(
      screen.getAllByRole("link", { name: `Заказ ${ORDER_NUMBER}` }).length
    ).toBeGreaterThan(0)
    expect(rentalItemsApi.getAssetRentalItem).toHaveBeenCalledWith(
      "return-token",
      ASSET_ID
    )
    expect(ordersApi.getOrder).toHaveBeenCalledWith("return-token", ORDER_ID)
    expect(screen.queryByText(new RegExp(ASSET_ID))).toBeNull()
    expect(screen.queryByText(new RegExp(ORDER_ID))).toBeNull()
  })

  it("shows the customer, rental, filling, and both drivers when a return is expanded", async () => {
    returnApi.listReturns.mockResolvedValue([
      {
        ...returnDocument(DOCUMENT_ID, "REGISTERING", 3),
        partySnapshot: "ООО Тест",
        driverSnapshot: "Петров Пётр",
        driverWorkerId: DRIVER_WORKER_ID,
      },
    ])
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать состав" }))[0]!
    )

    expect(
      screen.getAllByRole("region", { name: "Заказ и контакты клиента" }).length
    ).toBeGreaterThan(0)
    expect(screen.getAllByText("Анна Смирнова").length).toBeGreaterThan(0)
    expect(
      screen.getAllByText("Москва, ул. Тестовая, 1").length
    ).toBeGreaterThan(0)
    expect(screen.getAllByText("55.751244, 37.618423").length).toBeGreaterThan(
      0
    )
    expect(screen.getAllByText("Наполнение загружено").length).toBeGreaterThan(
      0
    )
    expect(screen.getAllByText("Срок аренды: 3 мес.").length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Когда уехала:/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Отгрузил:/).length).toBeGreaterThan(0)
    expect(screen.getAllByText("Иванов Иван").length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Привёз:/).length).toBeGreaterThan(0)
    expect(screen.getAllByText("Петров Пётр").length).toBeGreaterThan(0)
  })

  it("does not claim an outbound driver when the linked shipment read fails", async () => {
    shipmentApi.listShipments.mockRejectedValue(
      new Error("shipments unavailable")
    )
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Показать состав" }))[0]!
    )

    expect(
      (await screen.findAllByText("Данные отгрузки недоступны")).length
    ).toBeGreaterThan(0)
    expect(screen.queryByText("Иванов Иван")).toBeNull()
  })

  it("keeps VIEW access read-only while preserving service reads", async () => {
    authState.level = "VIEW"
    renderPage()

    await screen.findAllByText("Требуется осмотр")
    expect(returnApi.listReturns).toHaveBeenCalledWith(
      "return-token",
      WAREHOUSE_ID,
      expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/)
    )
    expect(screen.queryByRole("button", { name: "Создать вывоз" })).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Принять без сметы" })
    ).toBeNull()
  })

  it("shows warehouse-style filters and the lifecycle actions", async () => {
    renderPage()

    await screen.findAllByText("Требуется осмотр")
    expect(screen.getAllByRole("button", { name: "Статус" })).not.toHaveLength(
      0
    )
    expect(
      screen.getAllByRole("button", { name: "Контрагент" })
    ).not.toHaveLength(0)
    expect(
      screen.getAllByRole("button", { name: "Водитель" })
    ).not.toHaveLength(0)
    expect(screen.getByRole("button", { name: /^Календарь:/ })).toBeTruthy()
    expect(screen.queryByLabelText("Вывоз с")).toBeNull()
    expect(screen.queryByLabelText("Вывоз по")).toBeNull()
    expect(screen.queryByText("Дата", { exact: true })).toBeNull()
    expect(screen.queryByRole("button", { name: "Обновить" })).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Показать все документы" })
    ).toBeNull()
    expect(
      screen.getAllByRole("button", { name: "Создать вывоз" })
    ).not.toHaveLength(0)
    expect(
      screen.getAllByRole("button", { name: "Принять без сметы" })
    ).not.toHaveLength(0)
    expect(
      screen.getAllByRole("button", { name: "Создать смету" })
    ).not.toHaveLength(0)
  })

  it("keeps the return search compact and lets the user hide filters", async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findAllByText("Требуется осмотр")
    const search = screen.getByRole("textbox", { name: "Поиск возвратов" })
    expect(search.parentElement?.classList.contains("max-w-xl")).toBe(true)

    const hideFilters = screen.getByRole("button", {
      name: "Скрыть фильтры",
    })
    expect(hideFilters.getAttribute("aria-controls")).toBe(
      "logistics-return-filters"
    )
    expect(hideFilters.getAttribute("aria-expanded")).toBe("true")

    await user.click(hideFilters)

    expect(document.getElementById("logistics-return-filters")?.hidden).toBe(
      true
    )
    const showFilters = screen.getByRole("button", {
      name: "Показать фильтры",
    })
    expect(showFilters.getAttribute("aria-expanded")).toBe("false")

    await user.click(showFilters)

    expect(document.getElementById("logistics-return-filters")?.hidden).toBe(
      false
    )
  })

  it("filters returns locally by the selected counterparty", async () => {
    returnApi.listReturns.mockResolvedValue([
      {
        ...returnDocument(DOCUMENT_ID, "DRAFT", 2),
        partySnapshot: "ООО Альфа",
        driverSnapshot: "Иванов Иван",
      },
      {
        ...returnDocument(INSPECTION_ID, "INSPECTION_REQUIRED", 4),
        partySnapshot: "ООО Бета",
        driverSnapshot: "Петров Пётр",
      },
    ])
    const user = userEvent.setup()
    renderPage()

    await screen.findAllByText("ООО Альфа")
    await user.click(screen.getAllByRole("button", { name: "Контрагент" })[0]!)
    await user.click(screen.getByRole("checkbox", { name: "ООО Альфа" }))
    await user.click(screen.getByRole("button", { name: "Применить" }))

    await waitFor(() => expect(screen.queryByText("ООО Бета")).toBeNull())
    expect(screen.getAllByText("ООО Альфа")).not.toHaveLength(0)
  })

  it("opens the configured driver and date picker for a new return", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Создать вывоз" }))[0]!
    )

    const pickupDialog = screen.getByRole("dialog")
    expect(
      pickupDialog.querySelector('[aria-label="Бытовки возврата"]')
    ).toBeTruthy()
    expect(screen.getByText(`Бытовка ${ASSET_NUMBER}`)).toBeTruthy()
    expect(screen.getByText("Наполнение загружено")).toBeTruthy()
    expect(screen.getByRole("combobox", { name: "Водитель" })).toBeTruthy()
    expect(screen.getByText("Фактическая дата вывоза")).toBeTruthy()
    expect(document.querySelector('[data-slot="calendar"]')).toBeTruthy()
  })

  it("uses the server document version, driver and date for pickup", async () => {
    const user = userEvent.setup()
    returnApi.registerReturn.mockResolvedValue(
      returnDocument(DOCUMENT_ID, "REGISTERING", 3)
    )
    renderPage()

    await user.click(
      (await screen.findAllByRole("button", { name: "Создать вывоз" }))[0]!
    )
    await user.selectOptions(
      screen.getByRole("combobox", { name: "Водитель" }),
      "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    )
    const desiredDay = document.querySelector<HTMLButtonElement>(
      '[data-day="23.07.2026"][data-desired-window="true"]'
    )
    expect(desiredDay).toBeTruthy()
    await user.click(desiredDay!)
    expect(screen.queryByLabelText("Фактическое время ходки")).toBeNull()
    await user.click(
      screen.getByRole("dialog").querySelector('button[type="submit"]')!
    )

    await waitFor(() =>
      expect(returnApi.registerReturn).toHaveBeenCalledWith({
        accessToken: "return-token",
        documentId: DOCUMENT_ID,
        expectedVersion: 2,
        driverSnapshot: "Иванов Иван",
        driverWorkerId: DRIVER_WORKER_ID,
        scheduledDate: "2026-07-23",
        idempotencyKey: IDEMPOTENCY_KEY,
      })
    )
  })

  it("sends media and explicit additional-equipment lines to one service command", async () => {
    const user = userEvent.setup()
    returnApi.acceptUndamagedReturn.mockResolvedValue(
      returnDocument(INSPECTION_ID, "ACCEPTING", 5)
    )
    renderPage()

    await user.click(
      (
        await screen.findAllByRole("button", {
          name: "Принять без сметы",
        })
      )[0]!
    )
    expect(screen.getByText("Дополнительная мебель")).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Подготовить Фотографии строки 1" })
    )
    await user.click(screen.getByRole("button", { name: "Принять без сметы" }))
    expect(
      screen.getByText(
        "Подтвердите проверку комплектности мебели и оборудования для каждой строки."
      )
    ).toBeTruthy()
    expect(returnApi.acceptUndamagedReturn).not.toHaveBeenCalled()

    await user.click(
      screen.getByRole("checkbox", {
        name: "Комплектность мебели и оборудования проверена",
      })
    )
    await user.click(screen.getByRole("button", { name: "Принять без сметы" }))

    await waitFor(() =>
      expect(returnApi.acceptUndamagedReturn).toHaveBeenCalledWith({
        accessToken: "return-token",
        documentId: INSPECTION_ID,
        expectedVersion: 4,
        idempotencyKey: IDEMPOTENCY_KEY,
        lines: [
          {
            lineId: LINE_ID,
            references: [
              {
                mediaId: "88888888-8888-4888-8888-888888888888",
                generation: 1,
              },
            ],
            equipmentConfirmed: true,
            additionalEquipment: [],
          },
        ],
      })
    )
  })

  it("requires READY line photos and starts one separate estimate per return line", async () => {
    const user = userEvent.setup()
    returnApi.startReturnEstimates.mockResolvedValue(
      returnDocument(INSPECTION_ID, "ESTIMATE_PENDING", 5)
    )
    renderPage()

    await user.click(
      (
        await screen.findAllByRole("button", {
          name: "Создать смету",
        })
      )[0]!
    )
    await user.click(
      screen.getByRole("dialog").querySelector('button[type="submit"]')!
    )

    expect(
      screen.getByText(
        "Добавьте хотя бы одну готовую фотографию осмотра для каждой строки."
      )
    ).toBeTruthy()
    expect(returnApi.startReturnEstimates).not.toHaveBeenCalled()

    await user.click(
      screen.getByRole("button", { name: "Подготовить Фотографии строки 1" })
    )
    await user.click(
      screen.getByRole("dialog").querySelector('button[type="submit"]')!
    )

    await waitFor(() =>
      expect(returnApi.startReturnEstimates).toHaveBeenCalledWith({
        accessToken: "return-token",
        documentId: INSPECTION_ID,
        expectedVersion: 4,
        idempotencyKey: IDEMPOTENCY_KEY,
        lines: [
          {
            lineId: LINE_ID,
            references: [
              {
                mediaId: "88888888-8888-4888-8888-888888888888",
                generation: 1,
              },
            ],
          },
        ],
      })
    )
  })
})
