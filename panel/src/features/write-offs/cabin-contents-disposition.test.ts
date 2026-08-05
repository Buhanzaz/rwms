import { describe, expect, it } from "vitest"

import type { EquipmentItemDto } from "@/types/equipment"

import {
  buildCabinContentsPlan,
  cabinContentsSnapshotMatches,
  cabinDispositionContents,
} from "./cabin-contents-disposition"

const CABIN_ID = "11111111-1111-4111-8111-111111111111"
const WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"

function equipment(
  id: string,
  name: string,
  quantity: number,
  rentalItemId: string | null = CABIN_ID
): EquipmentItemDto {
  return {
    id,
    version: 2,
    warehouseId: WAREHOUSE_ID,
    category: "FURNITURE",
    name,
    active: true,
    comment: null,
    totalQuantity: quantity,
    stockQuantity: 0,
    cabinStockQuantity: quantity,
    rentedQuantity: 0,
    writtenOffQuantity: 0,
    lostQuantity: 0,
    activeHeldQuantity: 0,
    reservedQuantity: 0,
    availableQuantity: quantity,
    availableStock: 0,
    usages: [],
    balances: [
      {
        id: `${id.slice(0, -1)}9`,
        version: 7,
        equipmentId: id,
        warehouseId: WAREHOUSE_ID,
        rentalItemId,
        locationKind: "CABIN_NON_RENTED",
        quantity,
        activeHeldQuantity: 0,
        availableStock: 0,
      },
    ],
  }
}

describe("cabin contents disposition plan", () => {
  it("omits zero catalogue extras but sends every nonzero cabin position", () => {
    const chairId = "33333333-3333-4333-8333-333333333333"
    const tableId = "44444444-4444-4444-8444-444444444444"
    const extraId = "55555555-5555-4555-8555-555555555555"
    const contents = cabinDispositionContents(CABIN_ID, [
      equipment(chairId, "Стул", 4),
      equipment(tableId, "Стол", 1),
      equipment(extraId, "Нулевая позиция", 0),
    ])

    expect(contents.map((item) => item.equipmentName)).toEqual(["Стол", "Стул"])
    expect(
      buildCabinContentsPlan("MOVE_SELECTED_TO_STOCK", contents, {
        [chairId]: 3,
      })
    ).toEqual({
      mode: "MOVE_SELECTED_TO_STOCK",
      lines: [
        {
          equipmentId: tableId,
          expectedBalanceVersion: 7,
          moveToStockQuantity: 0,
        },
        {
          equipmentId: chairId,
          expectedBalanceVersion: 7,
          moveToStockQuantity: 3,
        },
      ],
    })
  })

  it("uses null for an empty cabin and never invents a contents choice", () => {
    expect(buildCabinContentsPlan("MOVE_SELECTED_TO_STOCK", [], {})).toBeNull()
  })

  it("rejects a movement quantity above the current cabin quantity", () => {
    const id = "66666666-6666-4666-8666-666666666666"
    const contents = cabinDispositionContents(CABIN_ID, [
      equipment(id, "Кровать", 2),
    ])
    expect(() =>
      buildCabinContentsPlan("MOVE_SELECTED_TO_STOCK", contents, { [id]: 3 })
    ).toThrow("от 0 до 2")
  })

  it("forces every movement quantity to zero when contents stay with the cabin", () => {
    const id = "77777777-7777-4777-8777-777777777777"
    const contents = cabinDispositionContents(CABIN_ID, [
      equipment(id, "Шкаф", 2),
    ])
    expect(
      buildCabinContentsPlan("DISPOSE_WITH_CABIN", contents, { [id]: 2 })
    ).toEqual({
      mode: "DISPOSE_WITH_CABIN",
      lines: [
        {
          equipmentId: id,
          expectedBalanceVersion: 7,
          moveToStockQuantity: 0,
        },
      ],
    })
  })

  it("requires the cabin passport and balance snapshot to match by identity and quantity", () => {
    const id = "88888888-8888-4888-8888-888888888888"
    const contents = cabinDispositionContents(CABIN_ID, [
      equipment(id, "Тумба", 2),
    ])

    expect(
      cabinContentsSnapshotMatches([{ equipmentId: id, quantity: 2 }], contents)
    ).toBe(true)
    expect(
      cabinContentsSnapshotMatches([{ equipmentId: id, quantity: 1 }], contents)
    ).toBe(false)
    expect(cabinContentsSnapshotMatches([{ quantity: 2 }], contents)).toBe(
      false
    )
    expect(cabinContentsSnapshotMatches([], [])).toBe(true)
  })
})
