import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const equipmentApi = vi.hoisted(() => ({ getEquipmentItems: vi.fn() }))
const movementTasksApi = vi.hoisted(() => ({
  createEquipmentMovementTask: vi.fn(),
  createEquipmentMovementTaskIdempotencyKey: vi.fn(),
}))

vi.mock("@/api/equipment-api", () => equipmentApi)
vi.mock(
  "@/features/logistics/api/equipment-movement-tasks-api",
  async (importOriginal) => ({
    ...(await importOriginal<
      typeof import("@/features/logistics/api/equipment-movement-tasks-api")
    >()),
    createEquipmentMovementTask: movementTasksApi.createEquipmentMovementTask,
    createEquipmentMovementTaskIdempotencyKey:
      movementTasksApi.createEquipmentMovementTaskIdempotencyKey,
  })
)
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
const STOCK_BALANCE_ID = "99999999-9999-4999-8999-999999999999"
const CABIN_BALANCE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const IDEMPOTENCY_KEY = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
const TASK_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

const order: OrderDetail = {
  id: ORDER_ID,
  version: 7,
  number: "ORD-000001",
  status: "DRAFT",
  client: { id: CLIENT_ID, type: "LEGAL_ENTITY", displayName: "ООО Тест" },
  managerId: "11111111-1111-4111-8111-111111111111",
  managerDisplayName: "Менеджер",
  createdBy: "11111111-1111-4111-8111-111111111111",
  createdByDisplayName: "Менеджер",
  warehouseId: WAREHOUSE_ID,
  unitCount: 1,
  createdAt: "2026-07-19T08:00:00Z",
  updatedAt: "2026-07-19T09:00:00Z",
  permissions: { canEdit: true, canViewOtherManagers: false },
  units: [
    {
      reservationId: RESERVATION_ID,
      added: true,
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
            equipmentCode: "CHAIR",
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
  overrides: Partial<EquipmentItemDto> &
    Pick<EquipmentItemDto, "id" | "code" | "name">
): EquipmentItemDto {
  const { id, code, name, ...rest } = overrides
  return {
    id,
    version: 2,
    warehouseId: WAREHOUSE_ID,
    category: "FURNITURE",
    code,
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
  }
}

const desk = equipmentItem({
  id: DESK_ID,
  code: "OFFICE_TABLE",
  name: "Стол офисный",
  availableStock: 2,
  balances: [
    {
      id: STOCK_BALANCE_ID,
      version: 11,
      equipmentId: DESK_ID,
      warehouseId: WAREHOUSE_ID,
      rentalItemId: null,
      locationKind: "STOCK",
      quantity: 2,
      activeHeldQuantity: 0,
      availableStock: 2,
    },
  ],
})

const chair = equipmentItem({
  id: CHAIR_ID,
  code: "CHAIR",
  name: "Стул",
  availableStock: 0,
  balances: [
    {
      id: CABIN_BALANCE_ID,
      version: 6,
      equipmentId: CHAIR_ID,
      warehouseId: WAREHOUSE_ID,
      rentalItemId: UNIT_ID,
      locationKind: "CABIN_NON_RENTED",
      quantity: 4,
      activeHeldQuantity: 0,
      availableStock: 4,
    },
  ],
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
  movementTasksApi.createEquipmentMovementTaskIdempotencyKey
    .mockReset()
    .mockReturnValue(IDEMPOTENCY_KEY)
  movementTasksApi.createEquipmentMovementTask.mockResolvedValue({
    id: TASK_ID,
    deadlineAt: "2030-07-21T09:30:00.000Z",
  })
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

  it("uses current quantities as the desired contents and schedules one reserved movement task", async () => {
    const callbacks = renderDialog()
    const user = userEvent.setup()

    const chairDecrease = await screen.findByRole("button", {
      name: "Уменьшить Стул",
    })
    const chairRow = chairDecrease.closest("tr")
    expect(chairRow).not.toBeNull()
    expect(within(chairRow!).getByText("4 шт.")).toBeTruthy()
    expect(screen.getByText("Желаемое наполнение")).toBeTruthy()
    expect(screen.queryByText("Уже в бытовке")).toBeNull()

    await user.click(chairDecrease)
    await user.click(chairDecrease)
    await user.click(
      screen.getByRole("button", { name: "Увеличить Стол офисный" })
    )
    await user.click(
      screen.getByRole("button", { name: "Увеличить Стол офисный" })
    )
    const deadline = "2030-07-21T12:30"
    fireEvent.change(screen.getByLabelText("Резерв до"), {
      target: { value: deadline },
    })
    await user.click(screen.getByRole("button", { name: "Создать задание" }))

    await waitFor(() =>
      expect(movementTasksApi.createEquipmentMovementTask).toHaveBeenCalledWith(
        {
          accessToken: "orders-token",
          idempotencyKey: IDEMPOTENCY_KEY,
          input: {
            warehouseId: WAREHOUSE_ID,
            unitNumber: "БЫТ-001",
            plannedDurationMinutes: null,
            deadlineAt: new Date(deadline).toISOString(),
            lines: [
              {
                equipmentId: DESK_ID,
                sourceRentalItemId: null,
                sourceLocationKind: "STOCK",
                expectedSourceBalanceVersion: 11,
                targetRentalItemId: UNIT_ID,
                targetLocationKind: "CABIN_NON_RENTED",
                quantity: 2,
              },
              {
                equipmentId: CHAIR_ID,
                sourceRentalItemId: UNIT_ID,
                sourceLocationKind: "CABIN_NON_RENTED",
                expectedSourceBalanceVersion: 6,
                targetRentalItemId: null,
                targetLocationKind: "STOCK",
                quantity: 2,
              },
            ],
          },
        }
      )
    )
    expect(callbacks.onConflict).toHaveBeenCalledOnce()
    expect(callbacks.onOpenChange).toHaveBeenCalledWith(false)
  })

  it("does not let a stale order projection remove furniture without its current cabin balance", async () => {
    equipmentApi.getEquipmentItems.mockResolvedValue([desk])

    renderDialog()

    const decrease = await screen.findByRole("button", {
      name: "Уменьшить Стул",
    })
    expect(decrease).toHaveProperty("disabled", true)
    expect(
      screen.getByText(/актуальный остаток в бытовке не найден/i)
    ).toBeTruthy()
  })

  it("uses the rented cabin location for a removal task", async () => {
    const rentedOrder: OrderDetail = {
      ...order,
      units: [
        {
          ...order.units[0]!,
          unit: {
            ...order.units[0]!.unit,
            status: "RENTED",
            contents: [
              {
                ...order.units[0]!.unit.contents[0]!,
                locationKind: "CABIN_RENTED",
              },
            ],
          },
        },
      ],
    }
    equipmentApi.getEquipmentItems.mockResolvedValue([
      {
        ...chair,
        balances: [
          {
            ...chair.balances[0]!,
            locationKind: "CABIN_RENTED",
          },
        ],
      },
    ])
    const user = userEvent.setup()
    renderDialog({ currentOrder: rentedOrder })

    await user.click(
      await screen.findByRole("button", { name: "Уменьшить Стул" })
    )
    fireEvent.change(screen.getByLabelText("Резерв до"), {
      target: { value: "2030-07-21T12:30" },
    })
    await user.click(screen.getByRole("button", { name: "Создать задание" }))

    await waitFor(() =>
      expect(movementTasksApi.createEquipmentMovementTask).toHaveBeenCalledWith(
        expect.objectContaining({
          input: expect.objectContaining({
            lines: [
              expect.objectContaining({
                sourceLocationKind: "CABIN_RENTED",
                targetLocationKind: "STOCK",
              }),
            ],
          }),
        })
      )
    )
  })
})
