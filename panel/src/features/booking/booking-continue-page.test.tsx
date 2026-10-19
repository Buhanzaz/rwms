import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { MemoryRouter, Route, Routes, useNavigate } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const flow = vi.hoisted(() => ({
  getOrder: vi.fn(),
  getHold: vi.fn(),
  putHold: vi.fn(),
  listAvailable: vi.fn(),
  checkAvailability: vi.fn(),
  listInquiries: vi.fn(),
  createInquiry: vi.fn(),
  getPresentation: vi.fn(),
  publish: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "access-token",
    currentUser: { id: "manager-1", rentalAccess: true },
    status: "authenticated",
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    selectedWarehouseId: "99999999-9999-4999-8999-999999999999",
    selectedWarehouse: {
      id: "99999999-9999-4999-8999-999999999999",
      name: "Другой склад",
    },
  }),
}))

vi.mock("@/features/assistant/components/manager-booking-alert-dialog", () => ({
  ManagerBookingAlertDialog: () => null,
}))

vi.mock("@/features/orders/api/orders-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/orders/api/orders-api")
  >("@/features/orders/api/orders-api")
  return { ...actual, getOrder: flow.getOrder }
})

vi.mock("@/features/assistant/api/rental-presentations-api", () => ({
  getClientPresentation: flow.getPresentation,
  publishClientPresentation: flow.publish,
}))

vi.mock("@/features/booking/api/rental-inquiries-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/booking/api/rental-inquiries-api")
  >("@/features/booking/api/rental-inquiries-api")
  return {
    ...actual,
    listRentalInquiriesForOrder: flow.listInquiries,
    createManualRentalInquiry: flow.createInquiry,
  }
})

vi.mock("@/features/booking/api/booking-availability-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/booking/api/booking-availability-api")
  >("@/features/booking/api/booking-availability-api")
  return {
    ...actual,
    listAvailableRentalItems: flow.listAvailable,
    checkRentalItemsAvailability: flow.checkAvailability,
  }
})

vi.mock("@/features/booking/api/manual-booking-drafts-api", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/booking/api/manual-booking-drafts-api")
  >("@/features/booking/api/manual-booking-drafts-api")
  return {
    ...actual,
    getManualBookingDraftHold: flow.getHold,
    putManualBookingDraftHold: flow.putHold,
  }
})

vi.mock("@/features/booking/booking-cabin-browser", () => ({
  BookingCabinBrowser: ({
    items,
    selectedIds,
    onToggle,
    actions,
    footer,
    warehouseId,
    stagedIds,
  }: {
    items: RentalItemDto[]
    selectedIds: ReadonlySet<string>
    onToggle: (item: RentalItemDto) => void
    actions: ReactNode
    footer: ReactNode
    warehouseId: string
    stagedIds?: ReadonlySet<string>
  }) => (
    <div data-testid="booking-continue-grid" data-warehouse={warehouseId}>
      <span>Показано бытовок: {items.length}</span>
      <span>Финально выбрано: {selectedIds.size}</span>
      {items[0] ? (
        <button type="button" onClick={() => onToggle(items[0])}>
          {stagedIds ? `Выбрать ${items[0].number}` : "Изменить первую"}
        </button>
      ) : null}
      {actions}
      {footer}
    </div>
  ),
}))

import { BookingCatalogPage } from "@/features/booking/booking-catalog-page"
import { BookingContinuePage } from "@/features/booking/booking-continue-page"
import { useBookingSelection } from "@/features/booking/booking-selection-context"
import { BookingSelectionProvider } from "@/features/booking/booking-selection-provider"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { ApiError } from "@/lib/api-client"

const ORDER_ID = "77777777-7777-4777-8777-777777777777"
const CLIENT_ID = "88888888-8888-4888-8888-888888888888"
const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const OLD_1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const OLD_2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

function cabin(index: number): RentalItemDto {
  return {
    id: `00000000-0000-4000-8000-${String(index).padStart(12, "0")}`,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    number: `БЫТ-${String(index).padStart(3, "0")}`,
    rentalTypeId: "33333333-3333-4333-8333-333333333333",
    dimensionId: "44444444-4444-4444-8444-444444444444",
    finishingId: "55555555-5555-4555-8555-555555555555",
    type: "БК-1",
    dimensions: "2.4x6",
    finishing: "ДВП",
    category: "Новая",
    characteristics: [],
    linoleum: true,
    status: "FREE",
    comment: null,
    contents: null,
    contentsItems: [],
    shipmentDate: null,
    tenant: null,
    price: null,
    passport: {},
    tags: [],
  }
}

function order() {
  const orderUnit = (id: string, number: string) => ({
    reservationId: `reservation-${id}`,
    added: true,
    reservationState: "ACTIVE",
    unit: {
      id,
      version: 1,
      warehouseId: WAREHOUSE_ID,
      number,
      status: "BOOKED",
      rentalType: "БК-1",
      dimensions: "6x2.4",
      finishing: "ДВП",
      category: "Обычная",
      characteristics: null,
      linoleum: true,
      tags: [],
      contents: [],
      createdAt: "2026-08-10T10:00:00Z",
      updatedAt: "2026-08-10T10:00:00Z",
    },
    desiredContents: [],
    rentalTerm: null,
  })
  return {
    id: ORDER_ID,
    version: 3,
    number: "З-101",
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
      responsibleManagerId: "manager-1",
      responsibleManagerDisplayName: "Менеджер",
      comment: null,
      source: null,
      additionalContacts: [],
      createdAt: "2026-08-10T10:00:00Z",
      updatedAt: "2026-08-10T10:00:00Z",
    },
    managerId: "manager-1",
    managerDisplayName: "Менеджер",
    createdBy: "manager-1",
    createdByDisplayName: "Менеджер",
    warehouseId: WAREHOUSE_ID,
    deliveryAddress: "СПб",
    latitude: 59.9,
    longitude: 30.3,
    contactPhone: "+79990000000",
    comment: null,
    additionalContacts: [],
    desiredDeliveryWindows: [
      {
        startDate: "2026-08-11",
        endDate: "2026-08-11",
      },
    ],
    unitCount: 2,
    createdAt: "2026-08-10T10:00:00Z",
    updatedAt: "2026-08-10T10:00:00Z",
    units: [orderUnit(OLD_1, "СТАРАЯ-1"), orderUnit(OLD_2, "СТАРАЯ-2")],
    movements: [],
    permissions: {
      canEdit: true,
      canReplaceUnits: true,
      canViewOtherManagers: false,
    },
  }
}

function SeedSelection({
  items,
  search,
}: {
  items: RentalItemDto[]
  search: string
}) {
  const selection = useBookingSelection()
  const navigate = useNavigate()
  return (
    <button
      type="button"
      onClick={() => {
        items.forEach(selection.toggleChecked)
        selection.addCheckedToStaged()
        selection.setActiveHold({
          draftId: selection.draftId,
          warehouseId: WAREHOUSE_ID,
          expiresAt: new Date(Date.now() + 60 * 60_000).toISOString(),
          rentalItemIds: items.map((item) => item.id),
        })
        navigate(`/booking/continue?${search}`)
      }}
    >
      Подготовить {items.length}
    </button>
  )
}

function renderFlow(items: RentalItemDto[], search: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <MemoryRouter initialEntries={["/seed"]}>
      <QueryClientProvider client={queryClient}>
        <BookingSelectionProvider>
          <Routes>
            <Route
              path="/seed"
              element={<SeedSelection items={items} search={search} />}
            />
            <Route path="/booking/continue" element={<BookingContinuePage />} />
            <Route path="/booking" element={<div>Каталог заказа</div>} />
          </Routes>
        </BookingSelectionProvider>
      </QueryClientProvider>
    </MemoryRouter>
  )
}

function renderOrderLinkedContinuation(search: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <MemoryRouter initialEntries={[`/booking/continue?${search}`]}>
      <QueryClientProvider client={queryClient}>
        <BookingSelectionProvider>
          <Routes>
            <Route path="/booking" element={<BookingCatalogPage />} />
            <Route path="/booking/continue" element={<BookingContinuePage />} />
          </Routes>
        </BookingSelectionProvider>
      </QueryClientProvider>
    </MemoryRouter>
  )
}

function normalSearch() {
  return new URLSearchParams({
    clientId: CLIENT_ID,
    orderId: ORDER_ID,
  }).toString()
}

beforeEach(() => {
  flow.getOrder.mockResolvedValue(order())
  flow.putHold.mockImplementation(async (params) => ({
    draftId: params.draftId,
    warehouseId: params.warehouseId,
    expiresAt: new Date(Date.now() + 60 * 60_000).toISOString(),
    rentalItemIds: params.rentalItemIds,
  }))
  flow.listAvailable.mockResolvedValue({
    content: [cabin(1)],
    page: 0,
    size: 50,
    totalElements: 1,
    totalPages: 1,
  })
  flow.checkAvailability.mockImplementation(async (params) => ({
    warehouseId: params.warehouseId,
    items: params.rentalItemIds.map((rentalItemId: string) => ({
      rentalItemId,
      available: true,
      reason: "AVAILABLE",
    })),
  }))
  flow.getHold.mockImplementation(async (params) => ({
    draftId: params.draftId,
    warehouseId: params.warehouseId,
    expiresAt: new Date(Date.now() + 60 * 60_000).toISOString(),
    rentalItemIds: [cabin(1).id, cabin(2).id],
  }))
  flow.listInquiries.mockResolvedValue([])
  flow.createInquiry.mockResolvedValue({
    id: "inquiry-new",
    state: "ACTIVE",
    rentalOrderId: ORDER_ID,
    client: order().client,
  })
  flow.getPresentation.mockRejectedValue(new Error("not found"))
  flow.publish.mockResolvedValue({
    id: "presentation-1",
    version: 1,
    revision: 1,
    inquiryId: "inquiry-new",
    warehouseId: WAREHOUSE_ID,
    state: "ACTIVE",
    expiresAt: new Date(Date.now() + 60 * 60_000).toISOString(),
    viewUntil: new Date(Date.now() + 2 * 60 * 60_000).toISOString(),
    canConfirm: true,
    publicPath: "/client-presentations/public-token",
    bookedOrderId: null,
    mode: "NORMAL",
    replacementUnitIds: [],
    requiredSelectionCount: null,
    requiresDesiredDeliveryWindows: false,
    desiredDeliveryWindows: order().desiredDeliveryWindows,
    equipmentAvailability: [],
    groups: [],
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("BookingContinuePage", () => {
  it("opens the order-linked catalog from an empty normal continuation and publishes in the same order", async () => {
    const user = userEvent.setup()
    renderOrderLinkedContinuation(normalSearch())

    await user.click(
      await screen.findByRole("button", { name: "Выбрать БЫТ-001" })
    )
    expect(
      screen.queryByText("Сначала выберите свободные бытовки для этого заказа.")
    ).toBeNull()
    await user.click(
      screen.getByRole("button", { name: "Добавить 1 выбранных" })
    )
    await user.click(
      screen.getByRole("button", { name: "Продолжить бронирование" })
    )
    await user.click(
      await screen.findByRole("button", {
        name: "Создать представление для клиента",
      })
    )

    expect(
      await screen.findByRole("heading", { name: "Представление готово" })
    ).toBeTruthy()
    expect(flow.createInquiry).toHaveBeenCalledWith(
      expect.objectContaining({
        clientId: CLIENT_ID,
        rentalOrderId: ORDER_ID,
      })
    )
    expect(flow.publish).toHaveBeenCalledWith(
      expect.objectContaining({
        inquiryId: "inquiry-new",
        mode: "NORMAL",
      })
    )
  })

  it("keeps selected cabins and the server error for a generic publish conflict", async () => {
    const user = userEvent.setup()
    flow.publish.mockRejectedValue(
      new ApiError(
        "Склад заказа не зафиксирован. Повторите публикацию после обновления заказа.",
        409,
        "PRESENTATION_ORDER_MISMATCH"
      )
    )
    renderFlow([cabin(1)], normalSearch())

    await user.click(screen.getByRole("button", { name: "Подготовить 1" }))
    const publish = await screen.findByRole("button", {
      name: "Создать представление для клиента",
    })
    flow.getHold.mockClear()
    await user.click(publish)

    expect(
      await screen.findByText(
        "Склад заказа не зафиксирован. Повторите публикацию после обновления заказа."
      )
    ).toBeTruthy()
    expect(screen.getByText("Показано бытовок: 1")).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Создать представление для клиента" })
    ).not.toHaveProperty("disabled", true)
    expect(flow.getHold).not.toHaveBeenCalled()
  })

  it("removes a selected cabin only after its availability conflict confirms the lost hold", async () => {
    const user = userEvent.setup()
    flow.publish.mockRejectedValue(
      new ApiError("Бытовка больше недоступна", 409, "UNIT_NOT_AVAILABLE")
    )
    renderFlow([cabin(1)], normalSearch())

    await user.click(screen.getByRole("button", { name: "Подготовить 1" }))
    const publish = await screen.findByRole("button", {
      name: "Создать представление для клиента",
    })
    flow.getHold.mockImplementation(async (params) => ({
      draftId: params.draftId,
      warehouseId: params.warehouseId,
      expiresAt: new Date(Date.now() + 60 * 60_000).toISOString(),
      rentalItemIds: [],
    }))
    await user.click(publish)

    expect(await screen.findByText("Каталог заказа")).toBeTruthy()
    expect(flow.getHold).toHaveBeenCalled()
  })

  it("recovers an empty continuation through the catalog and reuses its active manual inquiry", async () => {
    const user = userEvent.setup()
    flow.listInquiries.mockResolvedValue([
      {
        id: "inquiry-active",
        conversationId: null,
        state: "ACTIVE",
        rentalOrderId: ORDER_ID,
      },
    ])
    flow.getPresentation.mockRejectedValue(
      new ApiError("Представление ещё не создано", 404)
    )
    renderOrderLinkedContinuation(normalSearch())

    await user.click(
      await screen.findByRole("button", { name: "Выбрать БЫТ-001" })
    )
    await user.click(
      screen.getByRole("button", { name: "Добавить 1 выбранных" })
    )
    await user.click(
      screen.getByRole("button", { name: "Продолжить бронирование" })
    )
    await user.click(
      await screen.findByRole("button", {
        name: "Создать представление для клиента",
      })
    )

    await waitFor(() => expect(flow.publish).toHaveBeenCalledOnce())
    expect(flow.createInquiry).not.toHaveBeenCalled()
    expect(flow.publish).toHaveBeenCalledWith(
      expect.objectContaining({ inquiryId: "inquiry-active" })
    )
  })

  it("creates a direct manual inquiry for the same order and fixed warehouse", async () => {
    const user = userEvent.setup()
    const items = [cabin(1)]
    renderFlow(items, normalSearch())

    await user.click(screen.getByRole("button", { name: "Подготовить 1" }))
    expect(await screen.findByText("Показано бытовок: 1")).toBeTruthy()
    expect(screen.getByTestId("booking-continue-grid").dataset.warehouse).toBe(
      WAREHOUSE_ID
    )
    await user.click(
      screen.getByRole("button", { name: "Создать представление для клиента" })
    )

    expect(
      await screen.findByRole("heading", { name: "Представление готово" })
    ).toBeTruthy()
    expect(flow.createInquiry).toHaveBeenCalledWith(
      expect.objectContaining({
        clientId: CLIENT_ID,
        rentalOrderId: ORDER_ID,
      })
    )
    expect(flow.publish).toHaveBeenCalledWith(
      expect.objectContaining({
        inquiryId: "inquiry-new",
        warehouseId: WAREHOUSE_ID,
        mode: "NORMAL",
      })
    )
  })

  it("reopens and replaces the active same-order presentation without another inquiry", async () => {
    flow.listInquiries.mockResolvedValue([
      {
        id: "inquiry-active",
        conversationId: null,
        state: "ACTIVE",
        rentalOrderId: ORDER_ID,
      },
    ])
    flow.getPresentation.mockResolvedValue({
      ...(await flow.publish()),
      inquiryId: "inquiry-active",
    })
    flow.publish.mockClear()
    const user = userEvent.setup()
    renderFlow([cabin(1)], normalSearch())

    await user.click(screen.getByRole("button", { name: "Подготовить 1" }))
    await user.click(
      await screen.findByRole("button", {
        name: "Создать представление для клиента",
      })
    )
    await waitFor(() => expect(flow.publish).toHaveBeenCalledOnce())
    expect(flow.createInquiry).not.toHaveBeenCalled()
    expect(flow.publish).toHaveBeenCalledWith(
      expect.objectContaining({ inquiryId: "inquiry-active" })
    )
  })

  it("does not reuse or overwrite an active AI-linked inquiry", async () => {
    flow.listInquiries.mockResolvedValue([
      {
        id: "inquiry-ai",
        conversationId: "conversation-ai",
        state: "ACTIVE",
        rentalOrderId: ORDER_ID,
      },
    ])
    const user = userEvent.setup()
    renderFlow([cabin(1)], normalSearch())

    await user.click(screen.getByRole("button", { name: "Подготовить 1" }))
    await user.click(
      await screen.findByRole("button", {
        name: "Создать представление для клиента",
      })
    )

    await waitFor(() => expect(flow.publish).toHaveBeenCalledOnce())
    expect(flow.getPresentation).not.toHaveBeenCalled()
    expect(flow.createInquiry).toHaveBeenCalledWith(
      expect.objectContaining({
        clientId: CLIENT_ID,
        rentalOrderId: ORDER_ID,
      })
    )
    expect(flow.publish).toHaveBeenCalledWith(
      expect.objectContaining({ inquiryId: "inquiry-new" })
    )
  })

  it("publishes ordered replacement targets and requires exactly two new cabins", async () => {
    const query = new URLSearchParams({
      clientId: CLIENT_ID,
      orderId: ORDER_ID,
      mode: "REPLACEMENT",
    })
    query.append("replacementUnitId", OLD_1)
    query.append("replacementUnitId", OLD_2)
    const user = userEvent.setup()
    renderFlow([cabin(1), cabin(2)], query.toString())

    await user.click(screen.getByRole("button", { name: "Подготовить 2" }))
    expect(await screen.findByText("Для замены выбрано: 2 из 2")).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Создать представление для клиента" })
    )
    await waitFor(() => expect(flow.publish).toHaveBeenCalledOnce())
    expect(flow.publish).toHaveBeenCalledWith(
      expect.objectContaining({
        mode: "REPLACEMENT",
        replacementUnitIds: [OLD_1, OLD_2],
      })
    )
  })

  it("does not create a hidden assistant conversation or inquiry without an order", async () => {
    const user = userEvent.setup()
    renderFlow([cabin(1)], `clientId=${CLIENT_ID}`)

    await user.click(screen.getByRole("button", { name: "Подготовить 1" }))
    expect(
      await screen.findByText(
        "Откройте добавление бытовок из карточки существующего заказа."
      )
    ).toBeTruthy()
    expect(flow.createInquiry).not.toHaveBeenCalled()
    expect(flow.publish).not.toHaveBeenCalled()
  })
})
