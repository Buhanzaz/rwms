import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import type { EquipmentItemDto } from "@/types/equipment"

const api = vi.hoisted(() => ({ getCabin: vi.fn(), getEquipment: vi.fn() }))
vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  getAssetRentalItem: api.getCabin,
}))
vi.mock("@/api/equipment-api", () => ({ getEquipmentItems: api.getEquipment }))

import { RepairTaskWriteOffDialog } from "./repair-task-write-off-dialog"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const CABIN_ID = "22222222-2222-4222-8222-222222222222"
const EQUIPMENT_ID = "33333333-3333-4333-8333-333333333333"

function cabin(contentsItems: RentalItemDto["contentsItems"]): RentalItemDto {
  return {
    id: CABIN_ID,
    version: 4,
    warehouseId: WAREHOUSE_ID,
    number: "БЫТ-1",
    rentalTypeId: "",
    dimensionId: "",
    finishingId: "",
    type: "Стандарт",
    dimensions: null,
    finishing: null,
    category: null,
    characteristics: [],
    linoleum: null,
    status: "WAREHOUSE",
    comment: null,
    contents: null,
    contentsItems,
    shipmentDate: null,
    tenant: null,
    price: null,
    passport: {},
    tags: [],
  }
}

function equipment(): EquipmentItemDto {
  return {
    id: EQUIPMENT_ID,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    category: "FURNITURE",
    name: "Стул",
    active: true,
    comment: null,
    totalQuantity: 4,
    stockQuantity: 0,
    cabinStockQuantity: 4,
    rentedQuantity: 0,
    writtenOffQuantity: 0,
    lostQuantity: 0,
    activeHeldQuantity: 0,
    reservedQuantity: 0,
    availableQuantity: 4,
    availableStock: 0,
    usages: [],
    balances: [
      {
        id: "44444444-4444-4444-8444-444444444444",
        version: 9,
        equipmentId: EQUIPMENT_ID,
        warehouseId: WAREHOUSE_ID,
        rentalItemId: CABIN_ID,
        locationKind: "CABIN_NON_RENTED",
        quantity: 4,
        activeHeldQuantity: 0,
        availableStock: 0,
      },
    ],
  }
}

function renderDialog() {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <RepairTaskWriteOffDialog
        accessToken="token"
        warehouseId={WAREHOUSE_ID}
        rentalItemId={CABIN_ID}
        open
        pending={false}
        onOpenChange={vi.fn()}
        onConfirm={vi.fn()}
      />
    </QueryClientProvider>
  )
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("repair write-off contents choice", () => {
  it("does not show a contents-choice section for an empty cabin", async () => {
    api.getCabin.mockResolvedValue(cabin([]))
    api.getEquipment.mockResolvedValue([])
    renderDialog()
    expect(await screen.findByLabelText("Причина списания")).toBeTruthy()
    expect(screen.queryByText("Наполнение бытовки")).toBeNull()
    expect(screen.queryByText("Переместить на склад")).toBeNull()
  })

  it("shows each nonzero item with its current quantity and format", async () => {
    api.getCabin.mockResolvedValue(
      cabin([
        {
          equipmentId: EQUIPMENT_ID,
          equipmentName: "Стул",
          name: "Стул",
          quantity: 4,
          locationKind: "CABIN_NON_RENTED",
        },
      ])
    )
    api.getEquipment.mockResolvedValue([equipment()])
    renderDialog()
    expect(await screen.findByText("Наполнение бытовки")).toBeTruthy()
    expect(screen.getByText("Переместить на склад")).toBeTruthy()
    expect(screen.getByText("Списать с бытовкой")).toBeTruthy()
    expect(screen.getByText(/Стул — в бытовке 4 шт\./)).toBeTruthy()
    const input = screen.getByRole("spinbutton") as HTMLInputElement
    expect(input.max).toBe("4")
  })
})
