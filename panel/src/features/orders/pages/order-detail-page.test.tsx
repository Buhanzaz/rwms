import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const ordersApi = vi.hoisted(() => ({
  getOrder: vi.fn(),
  listOrderHistory: vi.fn(),
  deleteOrder: vi.fn(),
  removeOrderUnit: vi.fn(),
  listOrderReplacementCandidates: vi.fn(),
  replaceOrderUnit: vi.fn(),
  saveOrder: vi.fn(),
  extendOrderRentalTerms: vi.fn(),
  createOrderIdempotencyKey: vi.fn(
    () => "99999999-9999-4999-8999-999999999999"
  ),
}))
const warehouseApi = vi.hoisted(() => ({
  listWarehouseSupportLinks: vi.fn(),
}))
const ordersRuntime = vi.hoisted(() => ({
  capabilities: {
    manualBooking: true,
    logisticsTaskNavigation: false,
    dossierEvidence: true,
    equipmentEditing: true,
    directWarehouseReplacement: true,
  },
}))

vi.mock("@/features/orders/api/orders-api", () => ({
  ORDERS_QUERY_KEY: ["orders"],
  ...ordersApi,
}))
vi.mock("@/api/warehouse-api", () => warehouseApi)
vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    warehouses: [
      {
        id: "22222222-2222-4222-8222-222222222222",
        name: "СПб",
        city: "Санкт-Петербург",
        address: "Складская, 1",
      },
    ],
  }),
}))
vi.mock("@/features/orders/orders-module-context", () => ({
  useOrdersModule: () => ({
    accessToken: "orders-token",
    currentUser: {
      id: "11111111-1111-4111-8111-111111111111",
      displayName: "Мария Менеджер",
      globalRole: "RENTAL_MANAGER",
    },
    warehouses: [
      {
        id: "22222222-2222-4222-8222-222222222222",
        name: "СПб",
        city: "Санкт-Петербург",
        address: "Складская, 1",
      },
    ],
    capabilities: ordersRuntime.capabilities,
    canManageBookingChanges: () => false,
  }),
}))
vi.mock("@/features/orders/components/order-unit-dossier-evidence", () => ({
  OrderUnitDossierEvidence: () => <div>Досье бытовок</div>,
}))
vi.mock("@/features/orders/components/order-unit-contents", () => ({
  OrderUnitContentsView: () => <div>Фактическое наполнение</div>,
  OrderUnitEquipmentDialog: () => null,
}))
vi.mock("@/features/rental-items/rental-item-status-badge", () => ({
  RentalItemStatusBadge: () => <span>Забронирована</span>,
}))
vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

import { OrderDetailPage } from "@/features/orders/pages/order-detail-page"
import type { OrderDetail } from "@/features/orders/domain/orders"

const ORDER_ID = "33333333-3333-4333-8333-333333333333"
const CLIENT_ID = "44444444-4444-4444-8444-444444444444"
const UNIT_ID = "55555555-5555-4555-8555-555555555555"
const REPLACEMENT_UNIT_ID = "99999999-9999-4999-8999-999999999999"

const baseOrder: OrderDetail = {
  id: ORDER_ID,
  version: 3,
  number: "ORD-000042",
  status: "DRAFT",
  customerDeliveryPurpose: "RENTAL_DELIVERY",
  client: {
    id: CLIENT_ID,
    version: 1,
    type: "LEGAL_ENTITY",
    displayName: "ООО Клиент",
    phone: "+79990000000",
    contactPerson: "Иван Иванов",
    email: null,
    responsibleManagerId: "11111111-1111-4111-8111-111111111111",
    responsibleManagerDisplayName: "Мария Менеджер",
    comment: null,
    source: null,
    additionalContacts: [{ name: "Бухгалтер", phone: "+79990000001" }],
    createdAt: "2026-08-10T08:00:00Z",
    updatedAt: "2026-08-10T09:00:00Z",
  },
  managerId: "11111111-1111-4111-8111-111111111111",
  managerDisplayName: "Мария Менеджер",
  createdBy: "11111111-1111-4111-8111-111111111111",
  createdByDisplayName: "Мария Менеджер",
  warehouseId: null,
  deliveryAddress: "Санкт-Петербург, Невский проспект, 1",
  latitude: 59.93,
  longitude: 30.33,
  contactPhone: "+79990000002",
  comment: "Позвонить заранее",
  additionalContacts: [{ name: "Прораб", phone: "+79990000003" }],
  desiredDeliveryWindows: [
    {
      startDate: "2026-08-15",
      endDate: "2026-08-17",
    },
  ],
  unitCount: 0,
  createdAt: "2026-08-10T08:00:00Z",
  updatedAt: "2026-08-10T09:00:00Z",
  units: [],
  movements: [],
  permissions: {
    canEdit: true,
    canReplaceUnits: false,
    canExtendRentalTerms: false,
    canViewOtherManagers: false,
  },
}

const selectedUnit: OrderDetail["units"][number] = {
  reservationId: "66666666-6666-4666-8666-666666666666",
  added: true,
  reservationState: "ACTIVE",
  desiredContents: [
    {
      equipmentId: "77777777-7777-4777-8777-777777777777",
      equipmentName: "Кровать",
      quantity: 4,
      reservationState: "ACTIVE",
    },
  ],
  rentalTerm: { rentalMonths: 3, shipmentDate: null, returnDate: null },
  unit: {
    id: UNIT_ID,
    version: 1,
    warehouseId: "22222222-2222-4222-8222-222222222222",
    number: "БЫТ-001",
    status: "BOOKED",
    rentalType: "БК-1",
    dimensions: "6×2,4",
    finishing: null,
    category: null,
    characteristics: null,
    linoleum: null,
    tags: [],
    contents: [],
    createdAt: "2026-08-10T08:00:00Z",
    updatedAt: "2026-08-10T09:00:00Z",
  },
}

const availableReplacement: OrderDetail["units"][number] = {
  reservationId: null,
  added: false,
  reservationState: null,
  desiredContents: [],
  rentalTerm: null,
  unit: {
    ...selectedUnit.unit,
    id: REPLACEMENT_UNIT_ID,
    number: "БЫТ-099",
    status: "FREE",
  },
}

const shippedUnit: OrderDetail["units"][number] = {
  ...selectedUnit,
  rentalTerm: {
    rentalMonths: 3,
    shipmentDate: "2026-08-20",
    returnDate: "2026-11-20",
  },
  unit: {
    ...selectedUnit.unit,
    status: "RENTED",
    contents: [
      {
        equipmentId: "88888888-8888-4888-8888-888888888888",
        equipmentName: "Стол",
        quantity: 1,
        locationKind: "CABIN_RENTED",
      },
    ],
  },
}

function LocationProbe() {
  const location = useLocation()
  return (
    <output aria-label="Текущий маршрут">
      {location.pathname + location.search}
    </output>
  )
}

function renderPage(order: OrderDetail) {
  ordersApi.getOrder.mockResolvedValue(order)
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <MemoryRouter initialEntries={[`/orders/${ORDER_ID}`]}>
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route path="/orders/:orderId" element={<OrderDetailPage />} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  Object.assign(ordersRuntime.capabilities, {
    manualBooking: true,
    logisticsTaskNavigation: false,
    dossierEvidence: true,
    equipmentEditing: true,
    directWarehouseReplacement: true,
  })
  ordersApi.listOrderHistory.mockResolvedValue([])
  warehouseApi.listWarehouseSupportLinks.mockResolvedValue({
    servedWarehouseId: "22222222-2222-4222-8222-222222222222",
    warehouseVersion: 1,
    links: [],
  })
  ordersApi.listOrderReplacementCandidates.mockResolvedValue({
    content: [availableReplacement],
    page: 0,
    size: 50,
    totalElements: 1,
    totalPages: 1,
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("OrderDetailPage cabin entry", () => {
  it("shows a waiver reason and exact fee only for the owner charge audit subject", async () => {
    const audit = {
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      orderId: ORDER_ID,
      eventType: "ORDER_CHANGED",
      actorSubjectId: baseOrder.managerId,
      actorRole: "WAREHOUSE_MANAGER",
      subjectId: CLIENT_ID,
      subjectType: "CUSTOMER_BOOKING_CHANGE_CHARGE",
      occurredAt: "2026-09-05T10:00:00Z",
      previousValues: {
        settlement: "PAYMENT_REQUIRED",
        amountRubles: "9223372036854775807",
      },
      newValues: {
        settlement: "WAIVED",
        amountRubles: "0",
        reason: "Перекрытие дороги подтверждено",
        unexpected: "Скрытый JSON",
      },
    }
    ordersApi.listOrderHistory.mockResolvedValue([
      audit,
      {
        ...audit,
        id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        subjectType: "ORDER",
        previousValues: null,
        newValues: {
          reason: "Не показывать чужую причину",
          settlement: "WAIVED",
          amountRubles: "12345",
        },
      },
    ])
    renderPage(baseOrder)
    expect(
      await screen.findByText(
        "Стало: Неустойка отменена · Неустойка: 0 ₽ · Причина: Перекрытие дороги подтверждено"
      )
    ).toBeTruthy()
    expect(
      screen.getByText(
        "Было: Ожидает оплаты · Неустойка: 9 223 372 036 854 775 807 ₽"
      )
    ).toBeTruthy()
    expect(
      screen.queryByText(/Не показывать чужую причину|Скрытый JSON/)
    ).toBeNull()
  })

  it("removes the old inline grid and offers exactly two order-linked paths", async () => {
    const user = userEvent.setup()
    renderPage(baseOrder)

    expect(
      await screen.findByRole("button", { name: "Добавить бытовки" })
    ).toBeTruthy()
    expect(screen.queryByText("Выбор бытовок")).toBeNull()
    expect(screen.queryByLabelText("Склад бронирования")).toBeNull()

    await user.click(
      screen.getAllByRole("button", { name: "Добавить бытовки" })[0]
    )
    const assistant = screen.getByRole("link", { name: "Открыть AI-чат" })
    const booking = screen.getByRole("link", {
      name: "Открыть бронирование",
    })
    expect(assistant.getAttribute("href")).toBe(
      `/assistant?clientId=${CLIENT_ID}&orderId=${ORDER_ID}`
    )
    expect(booking.getAttribute("href")).toBe(
      `/booking?clientId=${CLIENT_ID}&orderId=${ORDER_ID}`
    )
    expect(
      screen.getAllByRole("link", {
        name: /Открыть (AI-чат|бронирование)/,
      })
    ).toHaveLength(2)
  })

  it.each(["DRAFT", "SAVED"] as const)(
    "keeps Add cabins available for an editable %s order after the first booking",
    async (status) => {
      renderPage({
        ...baseOrder,
        status,
        warehouseId: selectedUnit.unit.warehouseId,
        unitCount: 1,
        units: [selectedUnit],
      })

      expect(
        await screen.findByRole("button", { name: "Добавить бытовки" })
      ).toBeTruthy()
      expect(screen.getByText(/Резерв бытовки:/).textContent).toContain(
        "активен"
      )
      expect(screen.getByText(/Кровать × 4/).textContent).toContain(
        "резерв активен"
      )
      expect(screen.queryByText("Выбор бытовок")).toBeNull()
    }
  )

  it.each(["DRAFT", "SAVED"] as const)(
    "opens both cabin paths without desired windows for an editable %s order",
    async (status) => {
      const user = userEvent.setup()
      renderPage({
        ...baseOrder,
        status,
        desiredDeliveryWindows: [],
      })

      await user.click(
        await screen.findByRole("button", { name: "Добавить бытовки" })
      )
      expect(
        await screen.findByText(
          /Клиент укажет желаемые дату и срок аренды в представлении/
        )
      ).toBeTruthy()
      expect(screen.getByRole("link", { name: "Открыть AI-чат" })).toBeTruthy()
      expect(
        screen.getByRole("link", { name: "Открыть бронирование" })
      ).toBeTruthy()
    }
  )

  it("uses server canEdit and hides the cabin command when editing is closed", async () => {
    renderPage({
      ...baseOrder,
      status: "FULFILLED",
      permissions: { ...baseOrder.permissions, canEdit: false },
    })

    expect(await screen.findByText("Исполнен")).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Добавить бытовки" })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Заменить бытовки" })
    ).toBeNull()
  })

  it("keeps the shared manager screen inside its authorized application boundary", async () => {
    Object.assign(ordersRuntime.capabilities, {
      manualBooking: false,
      logisticsTaskNavigation: false,
      dossierEvidence: false,
      equipmentEditing: false,
      directWarehouseReplacement: false,
    })
    const user = userEvent.setup()
    renderPage({
      ...baseOrder,
      status: "SAVED",
      warehouseId: selectedUnit.unit.warehouseId,
      unitCount: 1,
      units: [selectedUnit],
      permissions: {
        ...baseOrder.permissions,
        canReplaceUnits: true,
      },
    })

    expect(await screen.findByText("Сохранён")).toBeTruthy()
    expect(
      screen.queryByRole("link", { name: "Перейти к заданиям" })
    ).toBeNull()
    expect(screen.queryByText("Досье бытовок")).toBeNull()
    expect(
      screen.queryByRole("button", {
        name: `Добавить мебель ${selectedUnit.unit.number}`,
      })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Заменить бытовки" })
    ).toBeNull()

    await user.click(
      screen.getAllByRole("button", { name: "Добавить бытовки" })[0]
    )
    expect(screen.getByRole("link", { name: "Открыть AI-чат" })).toBeTruthy()
    expect(
      screen.queryByRole("link", { name: "Открыть бронирование" })
    ).toBeNull()
  })

  it("does not offer the removed logistics tasks page for a saved RWMS order", async () => {
    renderPage({
      ...baseOrder,
      status: "SAVED",
      warehouseId: selectedUnit.unit.warehouseId,
      unitCount: 1,
      units: [selectedUnit],
    })

    expect(await screen.findByText("Сохранён")).toBeTruthy()
    expect(
      screen.queryByRole("link", { name: "Перейти к заданиям" })
    ).toBeNull()
  })

  it("keeps extension available for a shipped fulfilled cabin when ordinary editing is closed", async () => {
    renderPage({
      ...baseOrder,
      status: "FULFILLED",
      warehouseId: shippedUnit.unit.warehouseId,
      unitCount: 1,
      units: [shippedUnit],
      permissions: {
        ...baseOrder.permissions,
        canEdit: false,
        canExtendRentalTerms: true,
      },
    })

    expect(
      await screen.findByRole("button", { name: "Продлить аренду" })
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Добавить бытовки" })
    ).toBeNull()
  })

  it("requires the server extension permission for a shipped cabin", async () => {
    renderPage({
      ...baseOrder,
      status: "FULFILLED",
      warehouseId: shippedUnit.unit.warehouseId,
      unitCount: 1,
      units: [shippedUnit],
      permissions: {
        ...baseOrder.permissions,
        canEdit: false,
        canExtendRentalTerms: false,
      },
    })

    expect(await screen.findByText("Исполнен")).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Продлить аренду" })).toBeNull()
  })

  it("uses server canReplaceUnits independently of canEdit and role inference", async () => {
    renderPage({
      ...baseOrder,
      status: "SAVED",
      warehouseId: selectedUnit.unit.warehouseId,
      unitCount: 1,
      units: [selectedUnit],
      permissions: {
        ...baseOrder.permissions,
        canEdit: false,
        canReplaceUnits: true,
      },
      movements: [
        {
          documentId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
          documentType: "SHIPMENT",
          customerDeliveryPurpose: "RENTAL_DELIVERY",
          state: "WAITING",
          scheduledDate: "2026-08-20",
          actualAt: null,
          rentalShipmentId: null,
          createdAt: "2026-08-10T08:00:00Z",
          updatedAt: "2026-08-10T09:00:00Z",
          cabins: [{ rentalItemId: UNIT_ID, lineState: "WAITING" }],
        },
      ],
    })

    expect(
      await screen.findByRole("button", { name: "Заменить бытовки" })
    ).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Добавить бытовки" })
    ).toBeNull()
  })

  it("renders the completed replacement projection with transferred furniture", async () => {
    const replacementUnit: OrderDetail["units"][number] = {
      ...selectedUnit,
      reservationId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      unit: {
        ...availableReplacement.unit,
        status: "BOOKED",
      },
    }
    const replacementOrder: OrderDetail = {
      ...baseOrder,
      version: 4,
      status: "SAVED",
      warehouseId: selectedUnit.unit.warehouseId,
      unitCount: 1,
      units: [replacementUnit],
      permissions: {
        ...baseOrder.permissions,
        canEdit: false,
        canReplaceUnits: true,
      },
    }
    ordersApi.replaceOrderUnit.mockResolvedValue(replacementOrder)
    const user = userEvent.setup()
    renderPage({
      ...replacementOrder,
      version: 3,
      units: [selectedUnit],
    })

    await user.click(
      await screen.findByRole("button", { name: "Заменить бытовки" })
    )
    await user.click(
      screen.getByRole("checkbox", { name: "Заменить бытовку БЫТ-001" })
    )
    await user.click(
      screen.getByRole("button", {
        name: "Выбрать замену самостоятельно",
      })
    )
    await user.click(
      await screen.findByRole("button", {
        name: "Выбрать заменяющую бытовку БЫТ-099",
      })
    )
    await user.type(screen.getByLabelText("Причина замены"), "Протечка")
    await user.click(screen.getByRole("button", { name: "Подтвердить замену" }))

    expect(await screen.findByText("БЫТ-099")).toBeTruthy()
    expect(screen.queryByText("БЫТ-001")).toBeNull()
    expect(screen.getByText(/Кровать × 4/).textContent).toContain(
      "резерв активен"
    )
  })

  it("shows client and order contacts plus wishes separately from the actual date", async () => {
    renderPage({
      ...baseOrder,
      movements: [
        {
          documentId: "88888888-8888-4888-8888-888888888888",
          documentType: "SHIPMENT",
          customerDeliveryPurpose: "RENTAL_DELIVERY",
          state: "DRAFT",
          scheduledDate: "2026-08-20",
          actualAt: null,
          rentalShipmentId: null,
          createdAt: "2026-08-10T08:00:00Z",
          updatedAt: "2026-08-10T09:00:00Z",
          cabins: [],
        },
      ],
    })

    expect(
      await screen.findByText("Основное контактное лицо клиента")
    ).toBeTruthy()
    expect(screen.getAllByText("Доставка в аренду")).toHaveLength(2)
    expect(screen.getByText("Иван Иванов")).toBeTruthy()
    expect(screen.getByText("+79990000000")).toBeTruthy()
    expect(screen.getByText("Дополнительные контакты клиента")).toBeTruthy()
    expect(screen.getByText(/Бухгалтер: \+79990000001/)).toBeTruthy()
    expect(screen.getByText("Дополнительные контакты заказа")).toBeTruthy()
    expect(screen.getByText(/Прораб: \+79990000003/)).toBeTruthy()
    expect(screen.getByText("Выбрано клиентом в представлении")).toBeTruthy()
    expect(screen.getByText(/15\.08\.2026 — 17\.08\.2026/)).toBeTruthy()
    expect(
      screen.getByText(
        "Желаемые даты доступны только для просмотра и не являются назначенным расписанием ходки."
      )
    ).toBeTruthy()
    expect(screen.queryByLabelText("Время с")).toBeNull()
    expect(screen.queryByText(/10:00/)).toBeNull()
    expect(screen.queryByText(/15:30/)).toBeNull()
    expect(screen.getByText(/20\.08\.2026/)).toBeTruthy()
  })

  it("keeps only shipped rental terms as read-only projections and opens extension", async () => {
    renderPage({
      ...baseOrder,
      status: "SAVED",
      warehouseId: shippedUnit.unit.warehouseId,
      unitCount: 1,
      units: [shippedUnit],
      permissions: {
        ...baseOrder.permissions,
        canExtendRentalTerms: true,
      },
    })

    expect(
      await screen.findByRole("button", { name: "Продлить аренду" })
    ).toBeTruthy()
    expect(screen.getByText("Длительность")).toBeTruthy()
    expect(screen.getByText("Дата отгрузки")).toBeTruthy()
    expect(screen.getByText("Расчётная дата возврата")).toBeTruthy()
    expect(screen.getByText("20.11.2026")).toBeTruthy()
    expect(screen.queryByText("Сохранить сроки аренды")).toBeNull()
    expect(
      screen.queryByLabelText("Срок аренды в месяцах для БЫТ-001")
    ).toBeNull()
  })
})
