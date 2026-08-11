import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"

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
const ANOTHER_UNIT_ID = "99999999-9999-4999-8999-999999999999"
const ANOTHER_RESERVATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
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
    additionalContacts: [],
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
  additionalContacts: [],
  desiredDeliveryWindows: [
    {
      startDate: "2026-08-15",
      endDate: "2026-08-15",
    },
  ],
  unitCount: 1,
  createdAt: "2026-07-19T08:00:00Z",
  updatedAt: "2026-07-19T09:00:00Z",
  permissions: {
    canEdit: true,
    canReplaceUnits: false,
    canExtendRentalTerms: false,
    canViewOtherManagers: false,
  },
  movements: [],
  units: [
    {
      reservationId: RESERVATION_ID,
      added: true,
      reservationState: "ACTIVE",
      desiredContents: [
        {
          equipmentId: CHAIR_ID,
          equipmentName: "Стул",
          quantity: 2,
          reservationState: "ACTIVE",
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
    maximumPerCabin: null,
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
    const deskIncrease = await screen.findByRole("button", {
      name: "Увеличить Стол офисный",
    })
    expect(screen.getByText("Желаемое наполнение")).toBeTruthy()
    expect(screen.queryByText(/Сейчас в бытовке:/)).toBeNull()
    expect(screen.getByText(/Доступно этому заказу: 2 шт\./)).toBeTruthy()
    expect(screen.queryByLabelText("Резерв до")).toBeNull()
    expect(screen.queryByText("Создать задание")).toBeNull()
    expect(screen.queryByRole("slider")).toBeNull()

    await user.click(chairDecrease)
    await user.click(deskIncrease)
    await user.click(deskIncrease)
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

  it("limits free quantity 10 to the configured maximum 4 per cabin", async () => {
    equipmentApi.getEquipmentItems.mockResolvedValue([
      equipmentItem({
        id: DESK_ID,
        name: "Кровать",
        availableQuantity: 10,
        maximumPerCabin: 4,
      }),
    ])
    const user = userEvent.setup()
    renderDialog()

    const increase = await screen.findByRole("button", {
      name: "Увеличить Кровать",
    })
    await user.click(increase)
    await user.click(increase)
    await user.click(increase)
    await user.click(increase)
    await user.click(increase)

    expect(screen.getByText("Максимум в одной бытовке: 4 шт.")).toBeTruthy()
    expect(screen.getByLabelText("Количество Кровать")).toHaveProperty(
      "value",
      "4"
    )
    expect(increase).toHaveProperty("disabled", true)
  })

  it("limits free quantity 3 below the configured maximum 4 per cabin", async () => {
    equipmentApi.getEquipmentItems.mockResolvedValue([
      equipmentItem({
        id: DESK_ID,
        name: "Кровать",
        availableQuantity: 3,
        maximumPerCabin: 4,
      }),
    ])
    const user = userEvent.setup()
    renderDialog()

    const quantity = await screen.findByLabelText("Количество Кровать")
    await user.clear(quantity)
    await user.type(quantity, "9")

    expect(quantity).toHaveProperty("value", "3")
    expect(
      screen.getByRole("button", { name: "Увеличить Кровать" })
    ).toHaveProperty("disabled", true)
  })

  it("keeps an owned saved quantity editable when global availability is zero", async () => {
    const savedQuantityOrder: OrderDetail = {
      ...order,
      units: [
        {
          ...order.units[0]!,
          desiredContents: [
            {
              equipmentId: CHAIR_ID,
              equipmentName: "Стул",
              quantity: 4,
              reservationState: "ACTIVE",
            },
          ],
        },
      ],
    }
    equipmentApi.getEquipmentItems.mockResolvedValue([
      { ...chair, availableQuantity: 0, maximumPerCabin: 4 },
    ])
    const user = userEvent.setup()
    renderDialog({ currentOrder: savedQuantityOrder })

    const quantity = await screen.findByLabelText("Количество Стул")
    const decrease = screen.getByRole("button", { name: "Уменьшить Стул" })
    const increase = screen.getByRole("button", { name: "Увеличить Стул" })
    expect(quantity).toHaveProperty("value", "4")
    expect(increase).toHaveProperty("disabled", true)

    await user.click(decrease)
    expect(quantity).toHaveProperty("value", "3")
    expect(increase).toHaveProperty("disabled", false)
    await user.click(increase)
    expect(quantity).toHaveProperty("value", "4")
  })

  it("uses unassigned physical furniture inside this order when global availability is zero", async () => {
    const physicalSurplusOrder: OrderDetail = {
      ...order,
      units: [
        {
          ...order.units[0]!,
          desiredContents: [],
        },
      ],
    }
    equipmentApi.getEquipmentItems.mockResolvedValue([
      { ...chair, availableQuantity: 0, maximumPerCabin: 4 },
    ])
    const user = userEvent.setup()
    renderDialog({ currentOrder: physicalSurplusOrder })

    const quantity = await screen.findByLabelText("Количество Стул")
    const increase = screen.getByRole("button", { name: "Увеличить Стул" })
    await user.click(increase)
    await user.click(increase)
    await user.click(increase)
    await user.click(increase)
    await user.click(increase)

    expect(quantity).toHaveProperty("value", "4")
    expect(increase).toHaveProperty("disabled", true)
  })

  it("does not reuse physical furniture already desired by another cabin in this order", async () => {
    const fullyAssignedPhysicalOrder: OrderDetail = {
      ...order,
      unitCount: 2,
      units: [
        {
          ...order.units[0]!,
          desiredContents: [],
        },
        {
          reservationId: ANOTHER_RESERVATION_ID,
          added: true,
          reservationState: "ACTIVE",
          desiredContents: [
            {
              equipmentId: CHAIR_ID,
              equipmentName: "Стул",
              quantity: 4,
              reservationState: "ACTIVE",
            },
          ],
          unit: {
            ...order.units[0]!.unit,
            id: ANOTHER_UNIT_ID,
            number: "БЫТ-002",
            contents: [],
          },
        },
      ],
    }
    equipmentApi.getEquipmentItems.mockResolvedValue([
      { ...chair, availableQuantity: 0, maximumPerCabin: 4 },
    ])
    renderDialog({ currentOrder: fullyAssignedPhysicalOrder })

    expect(await screen.findByLabelText("Количество Стул")).toHaveProperty(
      "value",
      "0"
    )
    expect(
      screen.getByRole("button", { name: "Увеличить Стул" })
    ).toHaveProperty("disabled", true)
  })

  it("offers only the physical surplus after other cabins' desired quantities", async () => {
    const partialPhysicalSurplusOrder: OrderDetail = {
      ...order,
      unitCount: 2,
      units: [
        {
          ...order.units[0]!,
          desiredContents: [],
          unit: {
            ...order.units[0]!.unit,
            contents: [
              {
                equipmentId: CHAIR_ID,
                equipmentName: "Стул",
                quantity: 6,
                locationKind: "CABIN_NON_RENTED",
              },
            ],
          },
        },
        {
          reservationId: ANOTHER_RESERVATION_ID,
          added: true,
          reservationState: "ACTIVE",
          desiredContents: [
            {
              equipmentId: CHAIR_ID,
              equipmentName: "Стул",
              quantity: 4,
              reservationState: "ACTIVE",
            },
          ],
          unit: {
            ...order.units[0]!.unit,
            id: ANOTHER_UNIT_ID,
            number: "БЫТ-002",
            contents: [],
          },
        },
      ],
    }
    equipmentApi.getEquipmentItems.mockResolvedValue([
      { ...chair, availableQuantity: 0, maximumPerCabin: 4 },
    ])
    const user = userEvent.setup()
    renderDialog({ currentOrder: partialPhysicalSurplusOrder })

    const quantity = await screen.findByLabelText("Количество Стул")
    const increase = screen.getByRole("button", { name: "Увеличить Стул" })
    await user.click(increase)
    await user.click(increase)
    await user.click(increase)

    expect(quantity).toHaveProperty("value", "2")
    expect(increase).toHaveProperty("disabled", true)
  })

  it("refreshes authoritative availability after a concurrent conflict", async () => {
    ordersApi.setOrderUnitDesiredEquipment.mockRejectedValue(
      new ApiError("Остаток изменился", 409, "EQUIPMENT_RESERVATION_CONFLICT")
    )
    const callbacks = renderDialog()
    const user = userEvent.setup()

    await user.click(
      await screen.findByRole("button", {
        name: "Увеличить Стол офисный",
      })
    )
    await user.click(
      screen.getByRole("button", { name: "Сохранить наполнение" })
    )

    expect(
      await screen.findByText(
        "Состав бронирования или доступный остаток изменились. Актуальные данные загружены с сервера."
      )
    ).toBeTruthy()
    expect(callbacks.onConflict).toHaveBeenCalledOnce()
    expect(callbacks.onOpenChange).not.toHaveBeenCalledWith(false)
    await waitFor(() =>
      expect(equipmentApi.getEquipmentItems.mock.calls.length).toBeGreaterThan(
        1
      )
    )
  })

  it("keeps another cabin's desired quantity unavailable while editing this cabin", async () => {
    const multiUnitOrder: OrderDetail = {
      ...order,
      unitCount: 2,
      units: [
        order.units[0]!,
        {
          reservationId: ANOTHER_RESERVATION_ID,
          added: true,
          reservationState: "ACTIVE",
          desiredContents: [
            {
              equipmentId: CHAIR_ID,
              equipmentName: "Стул",
              quantity: 3,
              reservationState: "ACTIVE",
            },
          ],
          unit: {
            ...order.units[0]!.unit,
            id: ANOTHER_UNIT_ID,
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
