import { describe, expect, it } from "vitest"

import {
  createRentalItemContentsTransferInput,
  formatRentalItemContentsSourceSummary,
  rentalItemContentsTransferRows,
  warehouseStockTransferRows,
} from "@/features/rental-items/rental-item-contents-transfer-support"
import type { EquipmentItemDto } from "@/types/equipment"

function rows(count: number) {
  return Array.from({ length: count }, (_, index) => ({
    name: `Предмет ${index + 1}`,
    availableQuantity: index + 1,
  }))
}

describe("rental-item contents source summary", () => {
  it("handles empty and one-item cabins and formats large quantities", () => {
    expect(formatRentalItemContentsSourceSummary("БЫТ-001", [])).toBe(
      "БЫТ-001 — без наполнения"
    )

    const quantity = 1_234_567
    expect(
      formatRentalItemContentsSourceSummary("БЫТ-002", [
        { name: "Стул", availableQuantity: quantity },
      ])
    ).toBe(`БЫТ-002 — Стул: ${new Intl.NumberFormat("ru-RU").format(quantity)}`)
  })

  it("shows actual composition and the count of omitted rows", () => {
    expect(formatRentalItemContentsSourceSummary("БЫТ-003", rows(3))).toBe(
      "БЫТ-003 — Предмет 1: 1, Предмет 2: 2, Предмет 3: 3"
    )
    expect(formatRentalItemContentsSourceSummary("БЫТ-004", rows(4))).toBe(
      "БЫТ-004 — Предмет 1: 1, Предмет 2: 2, Предмет 3: 3, ещё 1"
    )
    expect(formatRentalItemContentsSourceSummary("БЫТ-013", rows(13))).toBe(
      "БЫТ-013 — Предмет 1: 1, Предмет 2: 2, Предмет 3: 3, ещё 10"
    )
  })

  it("keeps a long equipment name concise without losing its quantity", () => {
    const summary = formatRentalItemContentsSourceSummary("БЫТ-005", [
      {
        name: "Очень длинное название электрического оборудования для бытовки",
        availableQuantity: 987,
      },
    ])

    expect(summary).toContain("Очень длинное название")
    expect(summary).toContain("…")
    expect(summary).toContain(": 987")
    expect(summary.length).toBeLessThan(70)
  })

  it("does not offer furniture already reserved from a cabin source", () => {
    const equipment = {
      id: "equipment-1",
      version: 1,
      warehouseId: "warehouse-1",
      category: "FURNITURE",
      code: "TABLE",
      name: "Стол",
      active: true,
      comment: null,
      totalQuantity: 2,
      stockQuantity: 0,
      cabinStockQuantity: 2,
      rentedQuantity: 0,
      writtenOffQuantity: 0,
      lostQuantity: 0,
      activeHeldQuantity: 2,
      availableStock: 0,
      usages: [],
      balances: [
        {
          id: "balance-1",
          version: 3,
          equipmentId: "equipment-1",
          warehouseId: "warehouse-1",
          rentalItemId: "cabin-1",
          locationKind: "CABIN_NON_RENTED",
          quantity: 2,
          activeHeldQuantity: 2,
          availableStock: 0,
        },
      ],
    } satisfies EquipmentItemDto

    expect(rentalItemContentsTransferRows([equipment], "cabin-1")).toEqual([])
  })

  it("uses both balance versions and zero only for an absent target", () => {
    const equipment = {
      id: "equipment-1",
      version: 1,
      warehouseId: "warehouse-1",
      category: "FURNITURE",
      code: "TABLE",
      name: "Стол",
      active: true,
      comment: null,
      totalQuantity: 5,
      stockQuantity: 3,
      cabinStockQuantity: 2,
      rentedQuantity: 0,
      writtenOffQuantity: 0,
      lostQuantity: 0,
      activeHeldQuantity: 0,
      availableStock: 3,
      usages: [],
      balances: [
        {
          id: "balance-stock",
          version: 8,
          equipmentId: "equipment-1",
          warehouseId: "warehouse-1",
          rentalItemId: null,
          locationKind: "STOCK",
          quantity: 3,
          activeHeldQuantity: 0,
          availableStock: 3,
        },
        {
          id: "balance-cabin",
          version: 4,
          equipmentId: "equipment-1",
          warehouseId: "warehouse-1",
          rentalItemId: "cabin-1",
          locationKind: "CABIN_NON_RENTED",
          quantity: 2,
          activeHeldQuantity: 0,
          availableStock: 2,
        },
      ],
    } satisfies EquipmentItemDto

    const cabinRow = rentalItemContentsTransferRows([equipment], "cabin-1")[0]
    const stockRow = warehouseStockTransferRows([equipment])[0]
    expect(cabinRow).toBeDefined()
    expect(stockRow).toBeDefined()

    expect(
      createRentalItemContentsTransferInput({
        row: cabinRow!,
        targetWarehouseId: "warehouse-1",
        targetRentalItemId: null,
        targetLocationKind: "STOCK",
        quantity: 2,
      })
    ).toMatchObject({
      sourceExpectedVersion: 4,
      targetExpectedVersion: 8,
    })
    expect(
      createRentalItemContentsTransferInput({
        row: stockRow!,
        targetWarehouseId: "warehouse-1",
        targetRentalItemId: "cabin-2",
        targetLocationKind: "CABIN_NON_RENTED",
        quantity: 1,
      })
    ).toMatchObject({
      sourceExpectedVersion: 8,
      targetExpectedVersion: 0,
    })
  })
})
