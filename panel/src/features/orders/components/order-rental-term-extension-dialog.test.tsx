import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"

const ordersApi = vi.hoisted(() => ({
  extendOrderRentalTerms: vi.fn(),
  createOrderIdempotencyKey: vi.fn(
    () => "99999999-9999-4999-8999-999999999999"
  ),
}))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))

vi.mock("@/features/orders/api/orders-api", () => ordersApi)
vi.mock("@/features/orders/orders-module-context", () => ({
  useOrdersModule: () => ({ accessToken: "orders-token" }),
}))
vi.mock("@/features/orders/components/order-unit-contents", () => ({
  OrderUnitContentsView: ({
    contents,
  }: {
    contents: Array<{ equipmentName: string; quantity: number }>
  }) => (
    <p>
      {contents.length === 0
        ? "Наполнение отсутствует"
        : `Фактическое наполнение: ${contents
            .map((content) => `${content.equipmentName} × ${content.quantity}`)
            .join(", ")}`}
    </p>
  ),
}))
vi.mock("sonner", () => ({ toast }))

import { OrderRentalTermExtensionDialog } from "@/features/orders/components/order-rental-term-extension-dialog"
import type { OrderDetail } from "@/features/orders/domain/orders"

const ORDER_ID = "33333333-3333-4333-8333-333333333333"
const UNIT_A_ID = "44444444-4444-4444-8444-444444444444"
const UNIT_B_ID = "55555555-5555-4555-8555-555555555555"
const UNIT_UNSHIPPED_ID = "66666666-6666-4666-8666-666666666666"

const order: OrderDetail = {
  id: ORDER_ID,
  version: 3,
  number: "ORD-000042",
  status: "SAVED",
  client: {
    id: "77777777-7777-4777-8777-777777777777",
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
    additionalContacts: [],
    createdAt: "2026-08-10T08:00:00Z",
    updatedAt: "2026-08-10T09:00:00Z",
  },
  managerId: "11111111-1111-4111-8111-111111111111",
  managerDisplayName: "Мария Менеджер",
  createdBy: "11111111-1111-4111-8111-111111111111",
  createdByDisplayName: "Мария Менеджер",
  warehouseId: "22222222-2222-4222-8222-222222222222",
  deliveryAddress: "Санкт-Петербург, Невский проспект, 1",
  latitude: 59.93,
  longitude: 30.33,
  contactPhone: "+79990000000",
  comment: null,
  additionalContacts: [],
  desiredDeliveryWindows: [],
  unitCount: 3,
  createdAt: "2026-08-10T08:00:00Z",
  updatedAt: "2026-08-10T09:00:00Z",
  movements: [],
  permissions: {
    canEdit: true,
    canReplaceUnits: false,
    canExtendRentalTerms: true,
    canViewOtherManagers: false,
  },
  units: [
    {
      reservationId: "88888888-8888-4888-8888-888888888888",
      added: true,
      reservationState: "ACTIVE",
      desiredContents: [
        {
          equipmentId: "99999999-9999-4999-8999-999999999999",
          equipmentName: "Кровать",
          quantity: 4,
          reservationState: "ACTIVE",
        },
      ],
      rentalTerm: {
        rentalMonths: 3,
        shipmentDate: "2026-08-01",
        returnDate: "2026-11-01",
      },
      unit: {
        id: UNIT_A_ID,
        version: 1,
        warehouseId: "22222222-2222-4222-8222-222222222222",
        number: "БЫТ-001",
        status: "RENTED",
        rentalType: "БК-1",
        dimensions: null,
        finishing: null,
        category: null,
        characteristics: null,
        linoleum: null,
        tags: [],
        contents: [
          {
            equipmentId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            equipmentName: "Стол",
            quantity: 1,
            locationKind: "CABIN_RENTED",
          },
        ],
        createdAt: "2026-08-10T08:00:00Z",
        updatedAt: "2026-08-10T09:00:00Z",
      },
    },
    {
      reservationId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      added: true,
      reservationState: "ACTIVE",
      desiredContents: [],
      rentalTerm: {
        rentalMonths: 6,
        shipmentDate: "2026-08-03",
        returnDate: "2027-02-03",
      },
      unit: {
        id: UNIT_B_ID,
        version: 1,
        warehouseId: "22222222-2222-4222-8222-222222222222",
        number: "БЫТ-002",
        status: "RENTED",
        rentalType: "БК-2",
        dimensions: null,
        finishing: null,
        category: null,
        characteristics: null,
        linoleum: null,
        tags: [],
        contents: [],
        createdAt: "2026-08-10T08:00:00Z",
        updatedAt: "2026-08-10T09:00:00Z",
      },
    },
    {
      reservationId: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
      added: true,
      reservationState: "ACTIVE",
      desiredContents: [],
      rentalTerm: {
        rentalMonths: 1,
        shipmentDate: null,
        returnDate: null,
      },
      unit: {
        id: UNIT_UNSHIPPED_ID,
        version: 1,
        warehouseId: "22222222-2222-4222-8222-222222222222",
        number: "БЫТ-003",
        status: "BOOKED",
        rentalType: "БК-3",
        dimensions: null,
        finishing: null,
        category: null,
        characteristics: null,
        linoleum: null,
        tags: [],
        contents: [],
        createdAt: "2026-08-10T08:00:00Z",
        updatedAt: "2026-08-10T09:00:00Z",
      },
    },
  ],
}

function renderDialog({
  renderedOrder = order,
  onOpenChange = vi.fn<(open: boolean) => void>(),
  onUpdated = vi.fn<(updated: OrderDetail) => void>(),
  onConflict = vi.fn<() => void>(),
}: {
  renderedOrder?: OrderDetail
  onOpenChange?: (open: boolean) => void
  onUpdated?: (updated: OrderDetail) => void
  onConflict?: () => void
} = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <OrderRentalTermExtensionDialog
        open
        order={renderedOrder}
        onOpenChange={onOpenChange}
        onUpdated={onUpdated}
        onConflict={onConflict}
      />
    </QueryClientProvider>
  )
  return { ...rendered, onOpenChange, onUpdated, onConflict }
}

beforeEach(() => {
  ordersApi.extendOrderRentalTerms.mockResolvedValue({
    ...order,
    version: 4,
    units: order.units.map((candidate) =>
      candidate.unit.id === UNIT_A_ID
        ? {
            ...candidate,
            rentalTerm: {
              ...candidate.rentalTerm!,
              rentalMonths: 4,
              returnDate: "2026-12-01",
            },
          }
        : candidate
    ),
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("OrderRentalTermExtensionDialog", () => {
  it("sends one exact extension command and keeps furniture informational", async () => {
    const user = userEvent.setup()
    const callbacks = renderDialog()

    expect(screen.getByText("Кровать × 4")).toBeTruthy()
    expect(screen.getByText("Фактическое наполнение: Стол × 1")).toBeTruthy()
    expect(
      screen.queryByRole("checkbox", {
        name: "Выбрать бытовку БЫТ-003 для продления",
      })
    ).toBeNull()
    expect(screen.queryByRole("button", { name: "Добавить мебель" })).toBeNull()

    await user.click(
      screen.getByRole("checkbox", {
        name: "Выбрать бытовку БЫТ-001 для продления",
      })
    )
    await user.click(
      screen.getByRole("button", { name: "Увеличить срок продления" })
    )
    await user.click(
      screen.getByRole("button", { name: "Продлить выбранные бытовки" })
    )

    await waitFor(() =>
      expect(ordersApi.extendOrderRentalTerms).toHaveBeenCalledWith({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 3,
        terms: [{ unitId: UNIT_A_ID, additionalMonths: 2 }],
        idempotencyKey: "99999999-9999-4999-8999-999999999999",
      })
    )
    const command = ordersApi.extendOrderRentalTerms.mock.calls[0]?.[0]
    expect(command.terms[0]).toEqual({ unitId: UNIT_A_ID, additionalMonths: 2 })
    expect(command.terms[0]).not.toHaveProperty("desiredContents")
    expect(command.terms[0]).not.toHaveProperty("contents")
    expect(callbacks.onUpdated).toHaveBeenCalledWith(
      expect.objectContaining({
        version: 4,
        units: expect.arrayContaining([
          expect.objectContaining({
            rentalTerm: expect.objectContaining({ returnDate: "2026-12-01" }),
          }),
        ]),
      })
    )
  })

  it("sends the same shared months for every selected shipped cabin", async () => {
    const user = userEvent.setup()
    renderDialog()

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
    await user.click(
      screen.getByRole("button", { name: "Увеличить срок продления" })
    )
    await user.click(
      screen.getByRole("button", { name: "Продлить выбранные бытовки" })
    )

    await waitFor(() =>
      expect(ordersApi.extendOrderRentalTerms).toHaveBeenCalledWith(
        expect.objectContaining({
          expectedVersion: 3,
          terms: [
            { unitId: UNIT_A_ID, additionalMonths: 2 },
            { unitId: UNIT_B_ID, additionalMonths: 2 },
          ],
        })
      )
    )
  })

  it("keeps the dialog selection after a 409 refresh boundary", async () => {
    ordersApi.extendOrderRentalTerms.mockRejectedValueOnce(
      new ApiError("Версия заказа устарела", 409, "ORDER_VERSION_CONFLICT")
    )
    ordersApi.extendOrderRentalTerms.mockResolvedValueOnce({
      ...order,
      version: 4,
    })
    const user = userEvent.setup()
    const callbacks = renderDialog()

    const checkbox = screen.getByRole("checkbox", {
      name: "Выбрать бытовку БЫТ-001 для продления",
    })
    await user.click(checkbox)
    await user.click(
      screen.getByRole("button", { name: "Продлить выбранные бытовки" })
    )

    expect(
      await screen.findByText(
        "Срок аренды изменился на сервере. Актуальные данные загружены; проверьте выбранные бытовки и повторите действие."
      )
    ).toBeTruthy()
    expect(callbacks.onConflict).toHaveBeenCalledOnce()
    expect(checkbox.getAttribute("data-state")).toBe("checked")

    callbacks.rerender(
      <QueryClientProvider client={new QueryClient()}>
        <OrderRentalTermExtensionDialog
          open
          order={{ ...order, version: 4 }}
          onOpenChange={callbacks.onOpenChange}
          onUpdated={callbacks.onUpdated}
          onConflict={callbacks.onConflict}
        />
      </QueryClientProvider>
    )
    expect(
      screen
        .getByRole("checkbox", {
          name: "Выбрать бытовку БЫТ-001 для продления",
        })
        .getAttribute("data-state")
    ).toBe("checked")

    await user.click(
      screen.getByRole("button", { name: "Продлить выбранные бытовки" })
    )
    await waitFor(() =>
      expect(ordersApi.extendOrderRentalTerms).toHaveBeenLastCalledWith(
        expect.objectContaining({ expectedVersion: 4 })
      )
    )
  })
})
