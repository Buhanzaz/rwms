import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import type { EquipmentItemDto } from "@/types/equipment"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const RENTAL_ITEM_ID = "22222222-2222-4222-8222-222222222222"
const TARGET_RENTAL_ITEM_ID = "22222222-2222-4222-8222-222222222223"
const EQUIPMENT_ID = "33333333-3333-4333-8333-333333333333"
const STOCK_BALANCE_ID = "44444444-4444-4444-8444-444444444444"
const CABIN_BALANCE_ID = "55555555-5555-4555-8555-555555555555"

const equipmentApi = vi.hoisted(() => ({ getEquipmentItems: vi.fn() }))
const rentalItemsApi = vi.hoisted(() => ({ listAssetRentalItems: vi.fn() }))
const movementTasksApi = vi.hoisted(() => ({
  createTask: vi.fn(),
  createIdempotencyKey: vi.fn(),
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "asset-token",
    currentUser: {
      id: "operator-1",
      username: "operator",
      displayName: "Operator",
      firstName: null,
      lastName: null,
      email: null,
      principalType: "USER",
      globalRole: "WAREHOUSE_MANAGER",
      warehouseAccessAll: false,
      warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level: "MANAGE" }],
    },
  }),
}))

vi.mock("@/api/equipment-api", () => ({
  getEquipmentItems: equipmentApi.getEquipmentItems,
}))

vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  listAssetRentalItems: rentalItemsApi.listAssetRentalItems,
}))

vi.mock(
  "@/features/logistics/api/equipment-movement-tasks-api",
  async (importOriginal) => ({
    ...(await importOriginal<
      typeof import("@/features/logistics/api/equipment-movement-tasks-api")
    >()),
    createEquipmentMovementTask: movementTasksApi.createTask,
    createEquipmentMovementTaskIdempotencyKey:
      movementTasksApi.createIdempotencyKey,
  })
)

import { AddContentsDialog } from "@/features/rental-items/add-contents-dialog"
import { MoveContentsToRentalItemDialog } from "@/features/rental-items/move-contents-to-rental-item-dialog"
import { MoveContentsToStockDialog } from "@/features/rental-items/move-contents-to-stock-dialog"

function rentalItem(overrides: Partial<RentalItemDto> = {}): RentalItemDto {
  return {
    id: RENTAL_ITEM_ID,
    version: 3,
    warehouseId: WAREHOUSE_ID,
    number: "БЫТ-001",
    type: "БК-1",
    dimensions: "2,4 × 6",
    finishing: "ЛДСП",
    category: "Стандарт",
    characteristics: null,
    linoleum: true,
    status: "WAREHOUSE",
    comment: null,
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    locationNodeId: null,
    contents: "Стол 2 шт.",
    contentsItems: [
      {
        equipmentId: EQUIPMENT_ID,
        equipmentCode: "TABLE",
        equipmentName: "Стол",
        locationKind: "CABIN_NON_RENTED",
        name: "Стол",
        quantity: 2,
      },
    ],
    shipmentDate: null,
    tenant: null,
    price: null,
    ...overrides,
  }
}

function equipment(): EquipmentItemDto {
  return {
    id: EQUIPMENT_ID,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    category: "FURNITURE",
    code: "TABLE",
    name: "Стол",
    active: true,
    comment: null,
    totalQuantity: 12,
    stockQuantity: 10,
    cabinStockQuantity: 2,
    rentedQuantity: 0,
    writtenOffQuantity: 0,
    lostQuantity: 0,
    activeHeldQuantity: 0,
    reservedQuantity: 0,
    availableQuantity: 12,
    availableStock: 10,
    balances: [
      {
        id: STOCK_BALANCE_ID,
        version: 9,
        equipmentId: EQUIPMENT_ID,
        warehouseId: WAREHOUSE_ID,
        rentalItemId: null,
        locationKind: "STOCK",
        quantity: 10,
        activeHeldQuantity: 0,
        availableStock: 10,
      },
      {
        id: CABIN_BALANCE_ID,
        version: 5,
        equipmentId: EQUIPMENT_ID,
        warehouseId: WAREHOUSE_ID,
        rentalItemId: RENTAL_ITEM_ID,
        locationKind: "CABIN_NON_RENTED",
        quantity: 2,
        activeHeldQuantity: 0,
        availableStock: 2,
      },
    ],
    usages: [],
  }
}

function renderDialog(node: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>{node}</QueryClientProvider>
  )
}

beforeEach(() => {
  equipmentApi.getEquipmentItems.mockResolvedValue([equipment()])
  rentalItemsApi.listAssetRentalItems.mockResolvedValue({
    content: [
      rentalItem({
        id: TARGET_RENTAL_ITEM_ID,
        number: "БЫТ-002",
        contentsItems: [],
        contents: null,
      }),
    ],
    page: 0,
    size: 200,
    totalElements: 1,
    totalPages: 1,
  })
  movementTasksApi.createIdempotencyKey.mockReturnValue(
    "66666666-6666-4666-8666-666666666666"
  )
  movementTasksApi.createTask.mockResolvedValue({
    deadlineAt: "2030-07-21T10:30:00.000Z",
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("rental-item equipment movement task dialogs", () => {
  it("schedules a stock-to-cabin task with the source balance version", async () => {
    const user = userEvent.setup()
    const deadline = "2030-07-21T10:30"

    renderDialog(
      <AddContentsDialog item={rentalItem()} open onOpenChange={vi.fn()} />
    )

    await user.click(await screen.findByLabelText("Выбрать Стол"))
    fireEvent.change(screen.getByLabelText("Резерв до"), {
      target: { value: deadline },
    })
    await user.click(
      screen.getByRole("button", { name: "Создать задание со склада" })
    )

    await waitFor(() =>
      expect(movementTasksApi.createTask).toHaveBeenCalledWith({
        accessToken: "asset-token",
        idempotencyKey: "66666666-6666-4666-8666-666666666666",
        input: {
          warehouseId: WAREHOUSE_ID,
          unitNumber: "БЫТ-001",
          plannedDurationMinutes: null,
          deadlineAt: new Date(deadline).toISOString(),
          lines: [
            {
              equipmentId: EQUIPMENT_ID,
              sourceRentalItemId: null,
              sourceLocationKind: "STOCK",
              expectedSourceBalanceVersion: 9,
              targetRentalItemId: RENTAL_ITEM_ID,
              targetLocationKind: "CABIN_NON_RENTED",
              quantity: 1,
            },
          ],
        },
      })
    )
  })

  it("schedules a cabin-to-cabin task with the source balance version", async () => {
    const user = userEvent.setup()
    const deadline = "2030-07-21T11:30"

    renderDialog(
      <MoveContentsToRentalItemDialog
        item={rentalItem()}
        open
        onOpenChange={vi.fn()}
      />
    )

    await user.click(
      await screen.findByPlaceholderText("Введите номер бытовки")
    )
    await user.click(await screen.findByText("БЫТ-002"))
    await user.click(await screen.findByLabelText("Выбрать Стол"))
    fireEvent.change(screen.getByLabelText("Резерв до"), {
      target: { value: deadline },
    })
    await user.click(screen.getByRole("button", { name: "Создать задание" }))

    await waitFor(() =>
      expect(movementTasksApi.createTask).toHaveBeenCalledWith({
        accessToken: "asset-token",
        idempotencyKey: "66666666-6666-4666-8666-666666666666",
        input: {
          warehouseId: WAREHOUSE_ID,
          unitNumber: "БЫТ-001",
          plannedDurationMinutes: null,
          deadlineAt: new Date(deadline).toISOString(),
          lines: [
            {
              equipmentId: EQUIPMENT_ID,
              sourceRentalItemId: RENTAL_ITEM_ID,
              sourceLocationKind: "CABIN_NON_RENTED",
              expectedSourceBalanceVersion: 5,
              targetRentalItemId: TARGET_RENTAL_ITEM_ID,
              targetLocationKind: "CABIN_NON_RENTED",
              quantity: 2,
            },
          ],
        },
      })
    )
  })

  it("schedules a cabin-to-stock task with a reservation deadline", async () => {
    const user = userEvent.setup()
    const deadline = "2030-07-21T12:30"

    renderDialog(
      <MoveContentsToStockDialog
        item={rentalItem()}
        open
        onOpenChange={vi.fn()}
      />
    )

    await user.click(await screen.findByLabelText("Выбрать Стол"))
    fireEvent.change(screen.getByLabelText("Резерв до"), {
      target: { value: deadline },
    })
    await user.click(screen.getByRole("button", { name: "Создать задание" }))

    await waitFor(() =>
      expect(movementTasksApi.createTask).toHaveBeenCalledWith({
        accessToken: "asset-token",
        idempotencyKey: "66666666-6666-4666-8666-666666666666",
        input: {
          warehouseId: WAREHOUSE_ID,
          unitNumber: "БЫТ-001",
          plannedDurationMinutes: null,
          deadlineAt: new Date(deadline).toISOString(),
          lines: [
            {
              equipmentId: EQUIPMENT_ID,
              sourceRentalItemId: RENTAL_ITEM_ID,
              sourceLocationKind: "CABIN_NON_RENTED",
              expectedSourceBalanceVersion: 5,
              targetRentalItemId: null,
              targetLocationKind: "STOCK",
              quantity: 2,
            },
          ],
        },
      })
    )
  })
})
