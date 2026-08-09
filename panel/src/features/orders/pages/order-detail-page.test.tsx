import {
  focusManager,
  QueryClient,
  QueryClientProvider,
} from "@tanstack/react-query"
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"

const ordersApi = vi.hoisted(() => ({
  getOrder: vi.fn(),
  listAvailableOrderUnits: vi.fn(),
  listOrderHistory: vi.fn(),
  addOrderUnit: vi.fn(),
  removeOrderUnit: vi.fn(),
  selectOrderWarehouse: vi.fn(),
  deleteOrder: vi.fn(),
  listOrderClients: vi.fn(),
  updateOrder: vi.fn(),
  saveOrder: vi.fn(),
  setOrderRentalTerms: vi.fn(),
  extendOrderRentalTerms: vi.fn(),
  createOrderIdempotencyKey: vi.fn(
    () => "99999999-9999-4999-8999-999999999999"
  ),
}))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))

vi.mock("sonner", () => ({ toast }))
vi.mock("@/features/orders/api/orders-api", () => ({
  ORDERS_QUERY_KEY: ["orders"],
  ...ordersApi,
}))
vi.mock("@/features/orders/orders-module-context", () => ({
  useOrdersModule: () => ({
    accessToken: "orders-token",
    currentUser: {
      id: "11111111-1111-4111-8111-111111111111",
      globalRole: "RENTAL_MANAGER",
    },
    warehouses: [
      {
        id: "22222222-2222-4222-8222-222222222222",
        name: "Москва",
        city: "Москва",
        address: "Складская, 1",
      },
    ],
  }),
}))
vi.mock("@/features/orders/components/order-warehouse-unit-selection", () => ({
  OrderWarehouseUnitSelection: ({
    candidates,
    conflictingUnitIds,
    onAdd,
    onEditContents,
  }: {
    candidates: Array<{
      added: boolean
      unit: { id: string; number: string }
    }>
    conflictingUnitIds: ReadonlySet<string>
    onAdd: (candidate: {
      added: boolean
      unit: { id: string; number: string }
    }) => void
    onEditContents: (candidate: {
      added: boolean
      unit: { id: string; number: string }
    }) => void
  }) => {
    const candidate = candidates[0]!
    const conflicting = conflictingUnitIds.has(candidate.unit.id)
    if (candidate.added) {
      return (
        <>
          <button type="button" disabled>
            Добавлено
          </button>
          <button
            type="button"
            aria-label={`Изменить наполнение ${candidate.unit.number}`}
            onClick={() => onEditContents(candidate)}
          >
            +
          </button>
        </>
      )
    }

    return (
      <button
        type="button"
        disabled={conflicting}
        onClick={() => onAdd(candidate)}
      >
        {conflicting ? "Уже занята" : "Добавить"}
      </button>
    )
  },
}))
vi.mock("@/features/orders/components/order-unit-contents", () => ({
  OrderUnitContentsView: () => null,
  OrderUnitEquipmentDialog: () => null,
}))
vi.mock("@/features/orders/components/order-unit-dossier-evidence", () => ({
  OrderUnitDossierEvidence: () => null,
}))

import { OrderDetailPage } from "@/features/orders/pages/order-detail-page"

const ORDER_ID = "33333333-3333-4333-8333-333333333333"
const CLIENT_ID = "44444444-4444-4444-8444-444444444444"
const UNIT_ID = "55555555-5555-4555-8555-555555555555"
const WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"

const detail = {
  id: ORDER_ID,
  version: 4,
  number: "ORD-000001",
  status: "DRAFT" as const,
  client: {
    id: CLIENT_ID,
    version: 1,
    type: "LEGAL_ENTITY" as const,
    displayName: "ООО Тест",
    phone: "+79990000000",
    contactPerson: "Иван Иванов",
    email: null,
    responsibleManagerId: "11111111-1111-4111-8111-111111111111",
    responsibleManagerDisplayName: "Менеджер",
    comment: null,
    source: null,
    createdAt: "2026-07-19T08:00:00Z",
    updatedAt: "2026-07-19T08:00:00Z",
  },
  managerId: "11111111-1111-4111-8111-111111111111",
  managerDisplayName: "Менеджер",
  createdBy: "11111111-1111-4111-8111-111111111111",
  createdByDisplayName: "Менеджер",
  warehouseId: WAREHOUSE_ID,
  deliveryAddress: "Москва, Складская, 1",
  latitude: 55.75,
  longitude: 37.62,
  contactPhone: "+79990000000",
  comment: "Позвонить за час",
  acceptableDeliveryDates: ["2026-08-15"],
  unitCount: 0,
  createdAt: "2026-07-19T08:00:00Z",
  updatedAt: "2026-07-19T09:00:00Z",
  units: [],
  movements: [],
  permissions: { canEdit: true, canViewOtherManagers: false },
}

const candidate = {
  reservationId: null,
  added: false,
  desiredContents: [],
  unit: {
    id: UNIT_ID,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    number: "БЫТ-001",
    status: "FREE" as const,
    rentalType: null,
    dimensions: null,
    finishing: null,
    category: null,
    characteristics: null,
    linoleum: null,
    tags: [],
    contents: [],
    createdAt: "2026-07-19T08:00:00Z",
    updatedAt: "2026-07-19T08:00:00Z",
  },
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    queryClient,
    ...render(
      <MemoryRouter initialEntries={[`/orders/${ORDER_ID}`]}>
        <QueryClientProvider client={queryClient}>
          <Routes>
            <Route path="/orders/:orderId" element={<OrderDetailPage />} />
          </Routes>
        </QueryClientProvider>
      </MemoryRouter>
    ),
  }
}

beforeEach(() => {
  ordersApi.getOrder.mockResolvedValue(detail)
  ordersApi.listAvailableOrderUnits.mockResolvedValue({
    content: [candidate],
    page: 0,
    size: 40,
    totalElements: 1,
    totalPages: 1,
  })
  ordersApi.listOrderHistory.mockResolvedValue([])
  ordersApi.addOrderUnit.mockRejectedValue(
    new ApiError("Бытовка уже зарезервирована", 409, "UNIT_ALREADY_RESERVED")
  )
  ordersApi.listOrderClients.mockResolvedValue({
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  })
  ordersApi.updateOrder.mockResolvedValue(detail)
  ordersApi.saveOrder.mockResolvedValue({
    ...detail,
    version: 5,
    status: "SAVED",
    permissions: { ...detail.permissions, canEdit: false },
  })
  ordersApi.setOrderRentalTerms.mockResolvedValue(detail)
  ordersApi.extendOrderRentalTerms.mockResolvedValue(detail)
  ordersApi.deleteOrder.mockResolvedValue({
    ...detail,
    version: 5,
    status: "CANCELLED",
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("OrderDetailPage reservation conflict", () => {
  it("removes a newly occupied candidate on visible polling and keeps this order unit", async () => {
    const selectedCandidate = {
      ...candidate,
      reservationId: "66666666-6666-4666-8666-666666666666",
      added: true,
      unit: {
        ...candidate.unit,
        id: "77777777-7777-4777-8777-777777777777",
        number: "БЫТ-002",
      },
    }
    ordersApi.getOrder.mockResolvedValue({
      ...detail,
      unitCount: 1,
      units: [selectedCandidate],
    })
    const polledPage = {
      content: [selectedCandidate],
      page: 0,
      size: 40,
      totalElements: 1,
      totalPages: 1,
    }
    let resolvePolledPage!: (page: typeof polledPage) => void
    const polledResponse = new Promise<typeof polledPage>((resolve) => {
      resolvePolledPage = resolve
    })
    ordersApi.listAvailableOrderUnits
      .mockReset()
      .mockResolvedValueOnce({
        content: [candidate, selectedCandidate],
        page: 0,
        size: 40,
        totalElements: 2,
        totalPages: 1,
      })
      .mockImplementation(() => polledResponse)

    renderPage()

    await waitFor(
      () => expect(ordersApi.listAvailableOrderUnits).toHaveBeenCalledTimes(1),
      { timeout: 3_500 }
    )
    expect(
      await screen.findByRole(
        "button",
        { name: "Добавить" },
        { timeout: 3_500 }
      )
    ).toBeTruthy()
    await waitFor(
      () =>
        expect(
          ordersApi.listAvailableOrderUnits.mock.calls.length
        ).toBeGreaterThanOrEqual(2),
      { timeout: 3_500 }
    )
    await act(async () => {
      resolvePolledPage(polledPage)
      await polledResponse
    })
    await waitFor(
      () =>
        expect(screen.queryByRole("button", { name: "Добавить" })).toBeNull(),
      { timeout: 3_500 }
    )
    expect(screen.getByRole("button", { name: "Добавлено" })).toBeTruthy()
    expect(screen.getAllByText("БЫТ-002").length).toBeGreaterThan(0)
    expect(
      ordersApi.listAvailableOrderUnits.mock.calls.length
    ).toBeGreaterThanOrEqual(2)
  })

  it("does not keep availability polling active in a hidden page", async () => {
    focusManager.setFocused(false)
    const { unmount } = renderPage()

    try {
      await waitFor(() =>
        expect(ordersApi.listAvailableOrderUnits).toHaveBeenCalledTimes(1)
      )
      await new Promise((resolve) => window.setTimeout(resolve, 2_200))
      expect(ordersApi.listAvailableOrderUnits).toHaveBeenCalledTimes(1)
    } finally {
      unmount()
      focusManager.setFocused(undefined)
    }
  })

  it("shows the confirmed reservation immediately from the command projection", async () => {
    const user = userEvent.setup()
    const addedCandidate = {
      ...candidate,
      reservationId: "66666666-6666-4666-8666-666666666666",
      added: true,
    }
    ordersApi.addOrderUnit.mockResolvedValueOnce({
      ...detail,
      version: 5,
      unitCount: 1,
      units: [addedCandidate],
    })

    renderPage()
    await user.click(await screen.findByRole("button", { name: "Добавить" }))

    expect(
      await screen.findByRole("button", { name: "Добавлено" })
    ).toBeTruthy()
    expect(
      screen.getAllByRole("button", {
        name: "Изменить наполнение БЫТ-001",
      }).length
    ).toBeGreaterThan(0)
    expect(screen.queryByRole("button", { name: "Добавить" })).toBeNull()
  })

  it("rolls back the action state, explains the conflict and refreshes projections", async () => {
    ordersApi.listAvailableOrderUnits
      .mockResolvedValueOnce({
        content: [candidate],
        page: 0,
        size: 40,
        totalElements: 1,
        totalPages: 1,
      })
      .mockResolvedValue({
        content: [],
        page: 0,
        size: 40,
        totalElements: 0,
        totalPages: 0,
      })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole("button", { name: "Добавить" }))

    await waitFor(() => expect(ordersApi.addOrderUnit).toHaveBeenCalledTimes(1))
    expect(toast.error).toHaveBeenCalledWith(
      "Бытовка уже занята другим бронированием. Список доступных бытовок обновлён."
    )
    await waitFor(() => {
      expect(ordersApi.getOrder.mock.calls.length).toBeGreaterThanOrEqual(2)
      expect(
        ordersApi.listAvailableOrderUnits.mock.calls.length
      ).toBeGreaterThanOrEqual(2)
      expect(
        ordersApi.listOrderHistory.mock.calls.length
      ).toBeGreaterThanOrEqual(2)
    })
    expect(screen.queryByRole("button", { name: "Добавить" })).toBeNull()
  })

  it("allows a later server-confirmed free unit after an earlier conflict", async () => {
    const freePage = {
      content: [candidate],
      page: 0,
      size: 40,
      totalElements: 1,
      totalPages: 1,
    }
    ordersApi.listAvailableOrderUnits
      .mockResolvedValueOnce(freePage)
      .mockResolvedValueOnce({
        content: [],
        page: 0,
        size: 40,
        totalElements: 0,
        totalPages: 0,
      })
      .mockResolvedValue(freePage)
    const user = userEvent.setup()
    const { queryClient } = renderPage()

    await user.click(await screen.findByRole("button", { name: "Добавить" }))
    await waitFor(() =>
      expect(ordersApi.listAvailableOrderUnits).toHaveBeenCalledTimes(2)
    )
    expect(screen.queryByRole("button", { name: "Добавить" })).toBeNull()

    await act(async () => {
      await queryClient.invalidateQueries({
        queryKey: ["orders", "available-units"],
      })
    })

    expect(await screen.findByRole("button", { name: "Добавить" })).toBeTruthy()
  })
})

describe("OrderDetailPage draft actions", () => {
  it("keeps the return control and order metadata in the page toolbar", async () => {
    renderPage()

    const back = await screen.findByRole("link", { name: "Назад" })
    expect(back.getAttribute("href")).toBe("/orders")
    expect(screen.getByText("Черновик")).toBeTruthy()
    expect(screen.getByText(/Изменён/)).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Сохранить бронирование" })
    ).toBeTruthy()
    expect(
      screen.queryByRole("heading", { name: "Бронирование ORD-000001" })
    ).toBeNull()
  })

  it("shows delivery metadata and the planned/actual logistics timeline", async () => {
    ordersApi.getOrder.mockResolvedValue({
      ...detail,
      movements: [
        {
          documentId: "99999999-9999-4999-8999-999999999991",
          documentType: "SHIPMENT",
          state: "COMPLETED",
          scheduledDate: "2026-08-15",
          actualAt: "2026-08-15T08:30:00Z",
          rentalShipmentId: "99999999-9999-4999-8999-999999999992",
          createdAt: "2026-08-14T08:00:00Z",
          updatedAt: "2026-08-15T08:30:00Z",
          cabins: [],
        },
        {
          documentId: "99999999-9999-4999-8999-999999999993",
          documentType: "RETURN",
          state: "PLANNED",
          scheduledDate: "2026-09-15",
          actualAt: null,
          rentalShipmentId: null,
          createdAt: "2026-08-15T09:00:00Z",
          updatedAt: "2026-08-15T09:00:00Z",
          cabins: [],
        },
      ],
    })

    renderPage()

    expect(await screen.findByText("Москва, Складская, 1")).toBeTruthy()
    expect(screen.getByText("Координаты: 55.75, 37.62")).toBeTruthy()
    expect(screen.getByText("+79990000000")).toBeTruthy()
    expect(screen.getByText("Позвонить за час")).toBeTruthy()
    expect(screen.getAllByText("15.08.2026").length).toBeGreaterThan(0)
    expect(screen.getByText("Отвоз клиенту")).toBeTruthy()
    expect(screen.getByText("Возврат от клиента")).toBeTruthy()
    expect(screen.getByText("Ещё не выполнено")).toBeTruthy()
  })

  it("shows only human-readable order data and history", async () => {
    const actorId = "66666666-6666-4666-8666-666666666666"
    const equipmentId = "77777777-7777-4777-8777-777777777777"
    ordersApi.listOrderHistory.mockResolvedValue([
      {
        id: "88888888-8888-4888-8888-888888888888",
        orderId: ORDER_ID,
        eventType: "ORDER_CREATED",
        actorSubjectId: actorId,
        actorRole: "RENTAL_MANAGER",
        subjectType: "ORDER",
        subjectId: ORDER_ID,
        previousValues: null,
        newValues: {
          number: "ORD-000001",
          status: "DRAFT",
          managerId: actorId,
        },
        occurredAt: "2026-07-19T08:00:00Z",
      },
      {
        id: "99999999-9999-4999-8999-999999999999",
        orderId: ORDER_ID,
        eventType: "EQUIPMENT_INCREASED",
        actorSubjectId: actorId,
        actorRole: "RENTAL_MANAGER",
        subjectType: "EQUIPMENT",
        subjectId: equipmentId,
        previousValues: {
          unitNumber: "БЫТ-001",
          equipmentName: "Стол",
          quantity: 1,
        },
        newValues: {
          unitNumber: "БЫТ-001",
          equipmentName: "Стол",
          quantity: 2,
          equipmentId,
        },
        occurredAt: "2026-07-19T09:00:00Z",
      },
    ])

    renderPage()

    expect((await screen.findAllByText("Менеджер")).length).toBeGreaterThan(0)
    expect(await screen.findByText("Бронирование создано")).toBeTruthy()
    expect(screen.getByText("Автор")).toBeTruthy()
    expect(
      screen.getAllByText(/Номер бронирования: ORD-000001/).length
    ).toBeGreaterThan(0)
    expect(screen.getAllByText(/Статус: Черновик/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Бытовка: БЫТ-001/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Мебель: Стол/).length).toBeGreaterThan(0)
    expect(screen.queryByText(actorId)).toBeNull()
    expect(screen.queryByText(equipmentId)).toBeNull()
    expect(screen.queryByText("Manager ID")).toBeNull()
    expect(screen.queryByText("Actor:")).toBeNull()
  })

  it("keeps a saved booking editable while its shipment is still a draft", async () => {
    const selectedCandidate = {
      ...candidate,
      reservationId: "66666666-6666-4666-8666-666666666666",
      added: true,
      rentalTerm: {
        rentalMonths: 3,
        shipmentDate: null,
        returnDate: null,
      },
    }
    ordersApi.getOrder.mockResolvedValue({
      ...detail,
      unitCount: 1,
      units: [selectedCandidate],
    })
    ordersApi.saveOrder.mockResolvedValue({
      ...detail,
      version: 5,
      status: "SAVED",
      unitCount: 1,
      units: [selectedCandidate],
      permissions: { ...detail.permissions, canEdit: true },
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Сохранить бронирование" })
    )

    await waitFor(() =>
      expect(ordersApi.saveOrder).toHaveBeenCalledWith({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 4,
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
      })
    )
    expect(toast.success).toHaveBeenCalledWith(
      "Бронирование сохранено. Создайте отгрузку в разделе «Задания» логистики."
    )
    expect(
      screen.getByRole("button", { name: "Редактировать бронирование" })
    ).toBeTruthy()
    expect(screen.getByRole("button", { name: "Создать заказ" })).toBeTruthy()
    expect(
      screen.queryByRole("button", { name: "Сохранить бронирование" })
    ).toBeNull()
    expect(
      screen.queryByRole("button", { name: "Удалить черновик" })
    ).toBeNull()
  })

  it("saves the complete rental-term vector before the draft can be saved", async () => {
    const selectedCandidate = {
      ...candidate,
      reservationId: "66666666-6666-4666-8666-666666666666",
      added: true,
      rentalTerm: null,
    }
    ordersApi.getOrder.mockResolvedValue({
      ...detail,
      unitCount: 1,
      units: [selectedCandidate],
    })
    ordersApi.setOrderRentalTerms.mockResolvedValue({
      ...detail,
      version: 5,
      unitCount: 1,
      units: [
        {
          ...selectedCandidate,
          rentalTerm: {
            rentalMonths: 18,
            shipmentDate: null,
            returnDate: null,
          },
        },
      ],
    })
    const user = userEvent.setup()

    renderPage()

    const term = await screen.findByRole("combobox", {
      name: "Срок аренды в месяцах для БЫТ-001",
    })
    expect((term as HTMLSelectElement).disabled).toBe(false)
    expect(within(term).getByRole("option", { name: "1 месяц" })).toBeTruthy()
    expect(
      within(term).getByRole("option", { name: "Другое положительное целое" })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Сохранить бронирование" })
    ).toHaveProperty("disabled", true)

    await user.selectOptions(term, "custom")
    const customTerm = screen.getByRole("spinbutton", {
      name: "Срок аренды в месяцах для БЫТ-001: произвольное значение",
    })
    await user.type(customTerm, "18")
    await user.click(
      screen.getByRole("button", { name: "Сохранить сроки аренды" })
    )

    await waitFor(() =>
      expect(ordersApi.setOrderRentalTerms).toHaveBeenCalledWith({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 4,
        terms: [{ unitId: UNIT_ID, rentalMonths: 18 }],
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
      })
    )
    expect(toast.success).toHaveBeenCalledWith("Сроки аренды сохранены.")
    expect(await screen.findByText("18 месяцев")).toBeTruthy()
    expect(screen.getAllByText("Не назначена")).toHaveLength(2)
    expect(
      screen.getByRole("button", { name: "Сохранить бронирование" })
    ).toHaveProperty("disabled", false)
  })

  it("extends one or several shipped cabins and shows their return dates", async () => {
    const secondUnitId = "77777777-7777-4777-8777-777777777777"
    const firstShippedCandidate = {
      ...candidate,
      reservationId: "66666666-6666-4666-8666-666666666666",
      added: true,
      unit: {
        ...candidate.unit,
        status: "RENTED" as const,
      },
      rentalTerm: {
        rentalMonths: 3,
        shipmentDate: "2026-07-20",
        returnDate: "2026-10-20",
      },
    }
    const secondShippedCandidate = {
      ...candidate,
      reservationId: "88888888-8888-4888-8888-888888888888",
      added: true,
      unit: {
        ...candidate.unit,
        id: secondUnitId,
        number: "БЫТ-002",
        status: "RENTED" as const,
      },
      rentalTerm: {
        rentalMonths: 6,
        shipmentDate: "2026-07-22",
        returnDate: "2027-01-22",
      },
    }
    const shippedDetail = {
      ...detail,
      version: 8,
      status: "FULFILLED" as const,
      unitCount: 2,
      units: [firstShippedCandidate, secondShippedCandidate],
      permissions: { ...detail.permissions, canEdit: false },
    }
    ordersApi.getOrder.mockResolvedValue(shippedDetail)
    ordersApi.extendOrderRentalTerms.mockResolvedValue({
      ...shippedDetail,
      version: 9,
      units: [
        {
          ...firstShippedCandidate,
          rentalTerm: {
            ...firstShippedCandidate.rentalTerm,
            rentalMonths: 4,
            returnDate: "2026-11-20",
          },
        },
        {
          ...secondShippedCandidate,
          rentalTerm: {
            ...secondShippedCandidate.rentalTerm,
            rentalMonths: 12,
            returnDate: "2027-07-22",
          },
        },
      ],
    })
    const user = userEvent.setup()

    renderPage()

    expect(await screen.findByText("20.10.2026")).toBeTruthy()
    expect(screen.getByText("22.01.2027")).toBeTruthy()
    await user.click(
      screen.getByRole("checkbox", {
        name: "Выбрать бытовку БЫТ-001 для продления",
      })
    )
    await user.click(
      screen.getByRole("checkbox", {
        name: "Выбрать бытовку БЫТ-002 для продления",
      })
    )
    await user.selectOptions(
      screen.getByRole("combobox", {
        name: "Продление в месяцах для БЫТ-001",
      }),
      "1"
    )
    await user.selectOptions(
      screen.getByRole("combobox", {
        name: "Продление в месяцах для БЫТ-002",
      }),
      "6"
    )
    await user.click(
      screen.getByRole("button", { name: "Продлить выбранные бытовки" })
    )

    await waitFor(() =>
      expect(ordersApi.extendOrderRentalTerms).toHaveBeenCalledWith({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 8,
        terms: [
          { unitId: UNIT_ID, additionalMonths: 1 },
          { unitId: secondUnitId, additionalMonths: 6 },
        ],
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
      })
    )
    expect(toast.success).toHaveBeenCalledWith("Срок аренды продлён.")
    expect(await screen.findByText("20.11.2026")).toBeTruthy()
    expect(screen.getByText("22.07.2027")).toBeTruthy()
  })

  it("updates the detail projection after selecting an existing client", async () => {
    const replacementClient = {
      id: "66666666-6666-4666-8666-666666666666",
      version: 1,
      type: "LEGAL_ENTITY" as const,
      displayName: "ООО Новый клиент",
      phone: "+79991111111",
      contactPerson: "Пётр Петров",
      email: null,
      responsibleManagerId: "11111111-1111-4111-8111-111111111111",
      responsibleManagerDisplayName: "Менеджер",
      comment: null,
      source: null,
      createdAt: "2026-07-19T08:00:00Z",
      updatedAt: "2026-07-19T08:00:00Z",
    }
    ordersApi.listOrderClients.mockResolvedValue({
      content: [replacementClient],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    })
    ordersApi.updateOrder.mockResolvedValue({
      ...detail,
      version: 5,
      client: replacementClient,
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Редактировать бронирование" })
    )
    const input = screen.getByPlaceholderText("Например, ООО Петров")
    await user.clear(input)
    await user.type(input, "Новый")
    await user.click(await screen.findByText(replacementClient.displayName))
    await user.click(
      screen.getByRole("button", { name: "Сохранить изменения" })
    )

    await waitFor(() =>
      expect(ordersApi.updateOrder).toHaveBeenCalledWith({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 4,
        clientId: replacementClient.id,
        delivery: {
          deliveryAddress: "Москва, Складская, 1",
          latitude: 55.75,
          longitude: 37.62,
          contactPhone: "+79990000000",
          comment: "Позвонить за час",
          acceptableDeliveryDates: ["2026-08-15"],
        },
        idempotencyKey: expect.any(String),
      })
    )
    expect(await screen.findByText(replacementClient.displayName)).toBeTruthy()
    expect(toast.success).toHaveBeenCalledWith("Бронирование изменено.")
  })

  it("labels draft deletion as a logical cancellation and releases reservations", async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(
      await screen.findByRole("button", { name: "Удалить черновик" })
    )

    const confirmation = await screen.findByRole("alertdialog")
    expect(
      within(confirmation).getByText(
        "Это логическое удаление: бронирование будет отменено, а все активные резервирования бытовок будут освобождены. Действие фиксируется в истории."
      )
    ).toBeTruthy()
    expect(
      within(confirmation).getByRole("button", { name: "Не удалять" })
    ).toBeTruthy()

    await user.click(
      within(confirmation).getByRole("button", { name: "Удалить черновик" })
    )

    await waitFor(() =>
      expect(ordersApi.deleteOrder).toHaveBeenCalledWith({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 4,
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
      })
    )
    expect(toast.success).toHaveBeenCalledWith(
      "Черновик удалён: бронирование логически отменено, резервирования освобождены."
    )
  })
})
