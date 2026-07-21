import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ReactNode } from "react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { ApiError } from "@/lib/api-client"
import type { EquipmentItemDto } from "@/types/equipment"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const RENTAL_ITEM_ID = "22222222-2222-4222-8222-222222222222"
const TARGET_RENTAL_ITEM_ID = "22222222-2222-4222-8222-222222222223"
const EQUIPMENT_ID = "33333333-3333-4333-8333-333333333333"
const SECOND_EQUIPMENT_ID = "33333333-3333-4333-8333-333333333334"
const STOCK_BALANCE_ID = "44444444-4444-4444-8444-444444444444"
const CABIN_BALANCE_ID = "55555555-5555-4555-8555-555555555555"

const equipmentApi = vi.hoisted(() => ({
  getEquipmentItems: vi.fn(),
  transferEquipment: vi.fn(),
}))
const rentalItemsApi = vi.hoisted(() => ({ listAssetRentalItems: vi.fn() }))

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
  transferEquipment: equipmentApi.transferEquipment,
}))

vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  listAssetRentalItems: rentalItemsApi.listAssetRentalItems,
}))

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

function equipment(
  params: {
    id?: string
    code?: string
    name?: string
    stockBalanceId?: string
    cabinBalanceId?: string
    targetCabinQuantity?: number
  } = {}
): EquipmentItemDto {
  const id = params.id ?? EQUIPMENT_ID
  const stockBalanceId = params.stockBalanceId ?? STOCK_BALANCE_ID
  const cabinBalanceId = params.cabinBalanceId ?? CABIN_BALANCE_ID
  const targetCabinQuantity = params.targetCabinQuantity ?? 0
  return {
    id,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    category: "FURNITURE",
    code: params.code ?? "TABLE",
    name: params.name ?? "Стол",
    active: true,
    comment: null,
    totalQuantity: 12 + targetCabinQuantity,
    stockQuantity: 10,
    cabinStockQuantity: 2 + targetCabinQuantity,
    rentedQuantity: 0,
    writtenOffQuantity: 0,
    lostQuantity: 0,
    activeHeldQuantity: 0,
    availableStock: 10,
    balances: [
      {
        id: stockBalanceId,
        version: 9,
        equipmentId: id,
        warehouseId: WAREHOUSE_ID,
        rentalItemId: null,
        locationKind: "STOCK",
        quantity: 10,
        activeHeldQuantity: 0,
        availableStock: 10,
      },
      {
        id: cabinBalanceId,
        version: 5,
        equipmentId: id,
        warehouseId: WAREHOUSE_ID,
        rentalItemId: RENTAL_ITEM_ID,
        locationKind: "CABIN_NON_RENTED",
        quantity: 2,
        activeHeldQuantity: 0,
        availableStock: 2,
      },
      ...(targetCabinQuantity > 0
        ? [
            {
              id: "55555555-5555-4555-8555-555555555599",
              version: 6,
              equipmentId: id,
              warehouseId: WAREHOUSE_ID,
              rentalItemId: TARGET_RENTAL_ITEM_ID,
              locationKind: "CABIN_NON_RENTED" as const,
              quantity: targetCabinQuantity,
              activeHeldQuantity: 0,
              availableStock: targetCabinQuantity,
            },
          ]
        : []),
    ],
    usages: [],
  }
}

function renderDialog(node: ReactNode) {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
          },
        })
      }
    >
      {node}
    </QueryClientProvider>
  )
}

beforeEach(() => {
  equipmentApi.getEquipmentItems.mockResolvedValue([equipment()])
  equipmentApi.transferEquipment.mockResolvedValue({ id: "movement-1" })
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
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("rental-item direct equipment transfer dialogs", () => {
  it("moves stock to a cabin with both current balance versions", async () => {
    const user = userEvent.setup()
    renderDialog(
      <AddContentsDialog item={rentalItem()} open onOpenChange={vi.fn()} />
    )

    await user.click(await screen.findByLabelText("Выбрать Стол"))
    await user.click(screen.getByRole("button", { name: "Добавить со склада" }))

    await waitFor(() =>
      expect(equipmentApi.transferEquipment).toHaveBeenCalledWith(
        "asset-token",
        expect.any(String),
        {
          equipmentId: EQUIPMENT_ID,
          sourceWarehouseId: WAREHOUSE_ID,
          sourceRentalItemId: null,
          sourceLocationKind: "STOCK",
          sourceExpectedVersion: 9,
          targetWarehouseId: WAREHOUSE_ID,
          targetRentalItemId: RENTAL_ITEM_ID,
          targetLocationKind: "CABIN_NON_RENTED",
          targetExpectedVersion: 5,
          quantity: 1,
        }
      )
    )
  })

  it("moves a cabin balance to another cabin and uses zero for its absent target", async () => {
    const user = userEvent.setup()
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
    await user.click(screen.getByRole("button", { name: "Переместить" }))

    await waitFor(() =>
      expect(equipmentApi.transferEquipment).toHaveBeenCalledWith(
        "asset-token",
        expect.any(String),
        {
          equipmentId: EQUIPMENT_ID,
          sourceWarehouseId: WAREHOUSE_ID,
          sourceRentalItemId: RENTAL_ITEM_ID,
          sourceLocationKind: "CABIN_NON_RENTED",
          sourceExpectedVersion: 5,
          targetWarehouseId: WAREHOUSE_ID,
          targetRentalItemId: TARGET_RENTAL_ITEM_ID,
          targetLocationKind: "CABIN_NON_RENTED",
          targetExpectedVersion: 0,
          quantity: 2,
        }
      )
    )
  })

  it("moves a cabin balance back to the versioned stock balance", async () => {
    const user = userEvent.setup()
    renderDialog(
      <MoveContentsToStockDialog
        item={rentalItem()}
        open
        onOpenChange={vi.fn()}
      />
    )

    await user.click(await screen.findByLabelText("Выбрать Стол"))
    await user.click(
      screen.getByRole("button", { name: "Переместить на склад" })
    )

    await waitFor(() =>
      expect(equipmentApi.transferEquipment).toHaveBeenCalledWith(
        "asset-token",
        expect.any(String),
        {
          equipmentId: EQUIPMENT_ID,
          sourceWarehouseId: WAREHOUSE_ID,
          sourceRentalItemId: RENTAL_ITEM_ID,
          sourceLocationKind: "CABIN_NON_RENTED",
          sourceExpectedVersion: 5,
          targetWarehouseId: WAREHOUSE_ID,
          targetRentalItemId: null,
          targetLocationKind: "STOCK",
          targetExpectedVersion: 9,
          quantity: 2,
        }
      )
    )
  })

  it("shows the source cabin's actual equipment composition", async () => {
    const user = userEvent.setup()
    equipmentApi.getEquipmentItems.mockResolvedValue([
      equipment({ targetCabinQuantity: 3 }),
    ])
    renderDialog(
      <AddContentsDialog item={rentalItem()} open onOpenChange={vi.fn()} />
    )

    await user.click(
      await screen.findByPlaceholderText("Введите номер бытовки")
    )
    expect(await screen.findByText("БЫТ-002 — Стол: 3")).toBeTruthy()
    expect(screen.queryByText(/\d+ позиц/)).toBeNull()
  })

  it("refreshes a 409 and retries the same command with its stable key", async () => {
    const user = userEvent.setup()
    equipmentApi.transferEquipment
      .mockRejectedValueOnce(new ApiError("Версия остатка устарела", 409))
      .mockResolvedValueOnce({ id: "movement-1" })
    renderDialog(
      <MoveContentsToStockDialog
        item={rentalItem()}
        open
        onOpenChange={vi.fn()}
      />
    )

    await user.click(await screen.findByLabelText("Выбрать Стол"))
    await user.click(
      screen.getByRole("button", { name: "Переместить на склад" })
    )
    expect(await screen.findByText(/Остатки изменились/)).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Переместить на склад" })
    )

    await waitFor(() =>
      expect(equipmentApi.transferEquipment).toHaveBeenCalledTimes(2)
    )
    expect(equipmentApi.transferEquipment.mock.calls[0]?.[1]).toBe(
      equipmentApi.transferEquipment.mock.calls[1]?.[1]
    )
  })

  it("does not repeat completed lines when a partial batch is retried", async () => {
    const user = userEvent.setup()
    const second = equipment({
      id: SECOND_EQUIPMENT_ID,
      code: "BETA",
      name: "Бета",
      stockBalanceId: "44444444-4444-4444-8444-444444444445",
      cabinBalanceId: "55555555-5555-4555-8555-555555555556",
    })
    const first = equipment({ name: "Альфа" })
    equipmentApi.getEquipmentItems.mockResolvedValue([first, second])
    let secondAttempt = 0
    equipmentApi.transferEquipment.mockImplementation(
      (_token: string, _key: string, input: { equipmentId: string }) => {
        if (
          input.equipmentId === SECOND_EQUIPMENT_ID &&
          secondAttempt++ === 0
        ) {
          return Promise.reject(new Error("Сервис временно недоступен."))
        }
        return Promise.resolve({ id: `movement-${input.equipmentId}` })
      }
    )
    renderDialog(
      <MoveContentsToStockDialog
        item={rentalItem()}
        open
        onOpenChange={vi.fn()}
      />
    )

    await user.click(await screen.findByRole("button", { name: "Выбрать всё" }))
    await user.click(
      screen.getByRole("button", { name: "Переместить на склад" })
    )
    expect(await screen.findByText(/Перемещено 1 из 2/)).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Переместить на склад" })
    )

    await waitFor(() => {
      const calls = equipmentApi.transferEquipment.mock.calls
      expect(
        calls.filter((call) => call[2].equipmentId === EQUIPMENT_ID)
      ).toHaveLength(1)
      expect(
        calls.filter((call) => call[2].equipmentId === SECOND_EQUIPMENT_ID)
      ).toHaveLength(2)
    })
    const secondKeys = equipmentApi.transferEquipment.mock.calls
      .filter((call) => call[2].equipmentId === SECOND_EQUIPMENT_ID)
      .map((call) => call[1])
    expect(secondKeys[0]).toBe(secondKeys[1])
  })
})
