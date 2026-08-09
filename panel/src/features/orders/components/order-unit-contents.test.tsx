import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const equipmentApi = vi.hoisted(() => ({ getEquipmentItems: vi.fn() }))
const ordersApi = vi.hoisted(() => ({
  createOrderIdempotencyKey: vi.fn(),
  setOrderUnitDesiredEquipment: vi.fn(),
}))

vi.mock("@/api/equipment-api", () => equipmentApi)
vi.mock("@/features/orders/api/orders-api", () => ordersApi)
vi.mock("@/features/orders/orders-module-context", () => ({
  useOrdersModule: () => ({
    accessToken: "orders-token",
    currentUser: {
      id: "11111111-1111-4111-8111-111111111111",
      globalRole: "RENTAL_MANAGER",
    },
    warehouses: [],
  }),
}))
vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

import {
  OrderUnitContentsView,
  OrderUnitEquipmentDialog,
} from "@/features/orders/components/order-unit-contents"
import type { OrderDetail } from "@/features/orders/domain/orders"
import type { EquipmentItemDto } from "@/types/equipment"

const ORDER_ID = "22222222-2222-4222-8222-222222222222"
const CLIENT_ID = "33333333-3333-4333-8333-333333333333"
const WAREHOUSE_ID = "44444444-4444-4444-8444-444444444444"
const UNIT_ID = "55555555-5555-4555-8555-555555555555"
const CHAIR_ID = "66666666-6666-4666-8666-666666666666"
const RESERVATION_ID = "77777777-7777-4777-8777-777777777777"
const DESK_ID = "88888888-8888-4888-8888-888888888888"
const IDEMPOTENCY_KEY = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

const order: OrderDetail = {
  id: ORDER_ID,
  version: 7,
  number: "ORD-000001",
  status: "DRAFT",
  client: {
    id: CLIENT_ID,
    version: 1,
    type: "LEGAL_ENTITY",
    displayName: "ООО Тест",
    phone: "+79990000000",
    contactPerson: "Иван Иванов",
    email: null,
    responsibleManagerId: "11111111-1111-4111-8111-111111111111",
    responsibleManagerDisplayName: "Менеджер",
    comment: null,
    source: null,
    createdAt: "2026-07-19T08:00:00Z",
    updatedAt: "2026-07-19T09:00:00Z",
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
  comment: null,
  acceptableDeliveryDates: ["2026-08-15"],
  unitCount: 1,
  createdAt: "2026-07-19T08:00:00Z",
  updatedAt: "2026-07-19T09:00:00Z",
  permissions: { canEdit: true, canViewOtherManagers: false },
  movements: [],
  units: [
    {
      reservationId: RESERVATION_ID,
      added: true,
      desiredContents: [
        {
          equipmentId: CHAIR_ID,
          equipmentName: "Стул",
          quantity: 2,
        },
      ],
      unit: {
        id: UNIT_ID,
        version: 3,
        warehouseId: WAREHOUSE_ID,
        number: "БЫТ-001",
        status: "FREE",
        rentalType: "БК-1",
        dimensions: null,
        finishing: null,
        category: null,
        characteristics: null,
        linoleum: null,
        tags: [],
        contents: [
          {
            equipmentId: CHAIR_ID,
            equipmentName: "Стул",
            quantity: 4,
            locationKind: "CABIN_NON_RENTED",
          },
        ],
        createdAt: "2026-07-18T08:00:00Z",
        updatedAt: "2026-07-18T09:00:00Z",
      },
    },
  ],
}

function equipmentItem(
  overrides: Partial<EquipmentItemDto> & Pick<EquipmentItemDto, "id" | "name">
): EquipmentItemDto {
  const { id, name, ...rest } = overrides
  return {
    id,
    version: 2,
    warehouseId: WAREHOUSE_ID,
    category: "FURNITURE",
    name,
    active: true,
    comment: null,
    totalQuantity: 10,
    stockQuantity: 3,
    cabinStockQuantity: 7,
    rentedQuantity: 0,
    writtenOffQuantity: 0,
    lostQuantity: 0,
    activeHeldQuantity: 0,
    availableStock: 3,
    balances: [],
    usages: [],
    ...rest,
    reservedQuantity: rest.reservedQuantity ?? 0,
    availableQuantity: rest.availableQuantity ?? 10,
  }
}

const desk = equipmentItem({
  id: DESK_ID,
  name: "Стол офисный",
  availableQuantity: 2,
})

const chair = equipmentItem({
  id: CHAIR_ID,
  name: "Стул",
  availableQuantity: 6,
})

function renderDialog({
  currentOrder = order,
  onOpenChange = vi.fn<(open: boolean) => void>(),
  onConflict = vi.fn<() => void>(),
}: {
  currentOrder?: OrderDetail
  onOpenChange?: (open: boolean) => void
  onConflict?: () => void
} = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <OrderUnitEquipmentDialog
        open
        order={currentOrder}
        candidate={currentOrder.units[0]!}
        onOpenChange={onOpenChange}
        onConflict={onConflict}
      />
    </QueryClientProvider>
  )
  return { onOpenChange, onConflict }
}

beforeEach(() => {
  equipmentApi.getEquipmentItems.mockResolvedValue([desk, chair])
  ordersApi.createOrderIdempotencyKey.mockReturnValue(IDEMPOTENCY_KEY)
  ordersApi.setOrderUnitDesiredEquipment.mockResolvedValue(order)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("order unit contents", () => {
  it("shows an explanatory empty state without an inline Add action", () => {
    render(<OrderUnitContentsView contents={[]} />)

    expect(screen.getByText("Наполнение отсутствует")).toBeTruthy()
    expect(screen.queryByRole("button", { name: /Добавить/i })).toBeNull()
  })

  it("saves desired furniture from the full catalogue without a reservation date", async () => {
    const callbacks = renderDialog()
    const user = userEvent.setup()

    const chairDecrease = await screen.findByRole("button", {
      name: "Уменьшить Стул",
    })
    expect(screen.getByText("Желаемое наполнение")).toBeTruthy()
    expect(screen.queryByText(/Сейчас в бытовке:/)).toBeNull()
    expect(screen.getByText(/Доступно: 2 шт\./)).toBeTruthy()
    expect(screen.queryByLabelText("Резерв до")).toBeNull()
    expect(screen.queryByText("Создать задание")).toBeNull()
    expect(screen.queryByRole("slider")).toBeNull()

    await user.click(chairDecrease)
    await user.click(
      screen.getByRole("button", { name: "Увеличить Стол офисный" })
    )
    await user.click(
      screen.getByRole("button", { name: "Увеличить Стол офисный" })
    )
    await user.click(
      screen.getByRole("button", { name: "Сохранить наполнение" })
    )

    await waitFor(() =>
      expect(ordersApi.setOrderUnitDesiredEquipment).toHaveBeenCalledWith({
        accessToken: "orders-token",
        orderId: ORDER_ID,
        expectedVersion: 7,
        unitId: UNIT_ID,
        idempotencyKey: IDEMPOTENCY_KEY,
        requirements: [
          { equipmentId: DESK_ID, quantity: 2 },
          { equipmentId: CHAIR_ID, quantity: 1 },
        ],
      })
    )
    expect(callbacks.onConflict).toHaveBeenCalledOnce()
    expect(callbacks.onOpenChange).toHaveBeenCalledWith(false)
  })

  it("does not permit quantity above the available catalogue total", async () => {
    const user = userEvent.setup()
    renderDialog()

    const increase = await screen.findByRole("button", {
      name: "Увеличить Стол офисный",
    })
    await user.click(increase)
    await user.click(increase)
    await user.click(increase)

    expect(increase).toHaveProperty("disabled", true)
  })

  it("keeps another cabin's desired quantity unavailable while editing this cabin", async () => {
    const anotherUnitId = "99999999-9999-4999-8999-999999999999"
    const multiUnitOrder: OrderDetail = {
      ...order,
      unitCount: 2,
      units: [
        order.units[0]!,
        {
          reservationId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
          added: true,
          desiredContents: [
            {
              equipmentId: CHAIR_ID,
              equipmentName: "Стул",
              quantity: 3,
            },
          ],
          unit: {
            ...order.units[0]!.unit,
            id: anotherUnitId,
            number: "БЫТ-002",
            contents: [],
          },
        },
      ],
    }
    equipmentApi.getEquipmentItems.mockResolvedValue([
      desk,
      { ...chair, availableQuantity: 3 },
    ])
    const user = userEvent.setup()
    renderDialog({ currentOrder: multiUnitOrder })

    const increase = await screen.findByRole("button", {
      name: "Увеличить Стул",
    })
    await user.click(increase)
    await user.click(increase)
    await user.click(increase)

    expect(increase).toHaveProperty("disabled", true)
  })
})
