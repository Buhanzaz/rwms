import { describe, expect, it } from "vitest"

import type { WarehouseInfo } from "@/api/warehouse-api"
import { resolveWarehouseSelection } from "@/contexts/warehouse-selection"

const firstWarehouse: WarehouseInfo = {
  id: "00000000-0000-4000-8000-000000000001",
  version: 0,
  name: "Северный",
  city: "Санкт-Петербург",
  address: null,
  timeZone: "Europe/Moscow",
  active: true,
  lifecycleState: "ACTIVE",
  sortOrder: null,
}

const secondWarehouse: WarehouseInfo = {
  ...firstWarehouse,
  id: "00000000-0000-4000-8000-000000000002",
  name: "Южный",
}

describe("resolveWarehouseSelection", () => {
  it("keeps a saved canonical UUID", () => {
    expect(
      resolveWarehouseSelection(secondWarehouse.id, [
        firstWarehouse,
        secondWarehouse,
      ])
    ).toBe(secondWarehouse.id)
  })

  it.each(["spb", "msk", "removed-warehouse", null])(
    "treats %s as stale and selects the first available warehouse",
    (savedWarehouseId) => {
      expect(
        resolveWarehouseSelection(savedWarehouseId, [
          firstWarehouse,
          secondWarehouse,
        ])
      ).toBe(firstWarehouse.id)
    }
  )

  it("returns null only when the directory has no available warehouses", () => {
    expect(resolveWarehouseSelection(firstWarehouse.id, [])).toBeNull()
  })
})
