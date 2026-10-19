import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import type { ComponentProps } from "react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { EquipmentItemDto } from "@/types/equipment"

const equipmentApi = vi.hoisted(() => ({ getEquipmentItems: vi.fn() }))

vi.mock("@/api/equipment-api", () => equipmentApi)

import {
  InventoryFurnitureObservationDialog,
  InventoryInspectionDetails,
} from "@/features/inventory/inventory-inspection-details"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const TYPE_ONE_ID = "22222222-2222-4222-8222-222222222222"
const TYPE_TWO_ID = "33333333-3333-4333-8333-333333333333"
const DIMENSION_ONE_ID = "44444444-4444-4444-8444-444444444444"
const DIMENSION_TWO_ID = "55555555-5555-4555-8555-555555555555"
const FINISHING_ID = "66666666-6666-4666-8666-666666666666"
const CATEGORY_ID = "77777777-7777-4777-8777-777777777777"
const CHARACTERISTIC_ID = "88888888-8888-4888-8888-888888888888"

const passportOptions = {
  newCategory: "Новая",
  usedCategories: ["Обычная"],
  rentalTypes: [
    { id: TYPE_ONE_ID, name: "БК-1" },
    { id: TYPE_TWO_ID, name: "БК-2" },
  ],
  dimensions: [
    { id: DIMENSION_ONE_ID, name: "2.4×6" },
    { id: DIMENSION_TWO_ID, name: "3×7" },
  ],
  finishings: [{ id: FINISHING_ID, name: "ДВП" }],
  categories: [{ id: CATEGORY_ID, name: "Новая" }],
  characteristics: [{ id: CHARACTERISTIC_ID, name: "Электрика КК" }],
  typeDimensions: [
    { typeId: TYPE_ONE_ID, dimensionId: DIMENSION_ONE_ID, sortOrder: 0 },
    { typeId: TYPE_TWO_ID, dimensionId: DIMENSION_TWO_ID, sortOrder: 0 },
  ],
}

const furniture: EquipmentItemDto = {
  id: "22222222-2222-4222-8222-222222222222",
  version: 4,
  warehouseId: WAREHOUSE_ID,
  category: "FURNITURE",
  name: "Стол",
  active: true,
  comment: null,
  maximumPerCabin: null,
  totalQuantity: 0,
  stockQuantity: 0,
  cabinStockQuantity: 0,
  rentedQuantity: 0,
  writtenOffQuantity: 0,
  lostQuantity: 0,
  activeHeldQuantity: 0,
  reservedQuantity: 0,
  availableQuantity: 0,
  availableStock: 0,
  balances: [],
  usages: [],
}

function renderFurnitureDialog(
  props: Partial<
    ComponentProps<typeof InventoryFurnitureObservationDialog>
  > = {}
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <InventoryFurnitureObservationDialog
        open
        step="decision"
        accessToken="inventory-token"
        warehouseId={WAREHOUSE_ID}
        observation={{ presence: "ABSENT", value: null }}
        contentsSnapshot={[]}
        onOpenChange={vi.fn()}
        onRequestItems={vi.fn()}
        onResolved={vi.fn()}
        {...props}
      />
    </QueryClientProvider>
  )
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

beforeEach(() => {
  equipmentApi.getEquipmentItems.mockResolvedValue([furniture])
})

describe("inventory inspection details", () => {
  it("records passport choices from cabin settings as inspection evidence", async () => {
    const user = userEvent.setup()
    const onPassportObservationChange = vi.fn()

    render(
      <InventoryInspectionDetails
        cabinNumber="БЫТ-001"
        statusLabel="Свободна"
        tenant={null}
        businessDate="2026-08-05"
        comment=""
        passportObservation={{ presence: "ABSENT", value: null }}
        passportSnapshot={{
          rentalType: "БК-1",
          dimensions: "2.4×6",
          finishing: "ДВП",
          category: "Новая",
          characteristics: ["Электрика КК"],
          linoleum: true,
        }}
        passportOptions={passportOptions}
        passportOptionsLoading={false}
        passportOptionsError={null}
        equipmentObservation={{ presence: "ABSENT", value: null }}
        readOnly={false}
        onCommentChange={vi.fn()}
        onPassportObservationChange={onPassportObservationChange}
        onEditFurniture={vi.fn()}
        onRetryPassportOptions={vi.fn()}
      />
    )

    await user.click(screen.getByRole("tab", { name: "Паспорт бытовки" }))
    expect(screen.queryByRole("textbox", { name: "Тип бытовки" })).toBeNull()

    await user.click(screen.getByRole("button", { name: "БК-1" }))
    await user.click(await screen.findByRole("button", { name: "БК-2" }))
    await user.click(screen.getByRole("button", { name: "Выберите габариты" }))
    await user.click(await screen.findByRole("button", { name: "3×7" }))

    expect(onPassportObservationChange).toHaveBeenLastCalledWith({
      presence: "PRESENT",
      value: {
        rentalType: "БК-2",
        dimensions: "3×7",
        finishing: "ДВП",
        category: "Новая",
        characteristics: ["Электрика КК"],
        linoleum: true,
      },
    })
  })

  it("records an explicit answer when the cabin has no furniture", async () => {
    const user = userEvent.setup()
    const onResolved = vi.fn()

    renderFurnitureDialog({ onResolved })

    await user.click(screen.getByRole("button", { name: "Мебели нет" }))

    expect(onResolved).toHaveBeenCalledWith({
      presence: "EXPLICIT_EMPTY",
      value: [],
    })
  })

  it("keeps selected furniture and quantities in the inspection observation", async () => {
    const user = userEvent.setup()
    const onResolved = vi.fn()

    renderFurnitureDialog({ step: "items", onResolved })

    await user.click(
      await screen.findByRole("button", {
        name: "Увеличить количество Стол",
      })
    )
    await user.click(screen.getByRole("button", { name: "Сохранить мебель" }))

    expect(onResolved).toHaveBeenCalledWith({
      presence: "PRESENT",
      value: [
        {
          equipmentId: furniture.id,
          equipmentName: "Стол",
          equipmentCategory: "FURNITURE",
          catalogVersion: 4,
          quantity: 1,
        },
      ],
    })
  })
})
