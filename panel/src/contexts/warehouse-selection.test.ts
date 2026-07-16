import { describe, expect, it } from "vitest"

import type { WarehouseInfo } from "@/api/warehouse-api"
import {
  LEGACY_WAREHOUSE_SELECTIONS,
  resolveWarehouseSelection,
} from "@/contexts/warehouse-selection"

const warehouses: WarehouseInfo[] = [
  {
    id: LEGACY_WAREHOUSE_SELECTIONS.spb,
    version: 0,
    code: "WH_00000000000000000000000000000001",
    name: "СПБ",
    city: "Санкт-Петербург",
    address: null,
    timeZone: "Europe/Moscow",
    active: true,
    sortOrder: null,
  },
  {
    id: LEGACY_WAREHOUSE_SELECTIONS.msk,
    version: 0,
    code: "WH_00000000000000000000000000000002",
    name: "Москва",
    city: "Москва",
    address: null,
    timeZone: "Europe/Moscow",
    active: true,
    sortOrder: null,
  },
]

describe("resolveWarehouseSelection", () => {
  it.each([
    ["spb", LEGACY_WAREHOUSE_SELECTIONS.spb],
    ["msk", LEGACY_WAREHOUSE_SELECTIONS.msk],
    [LEGACY_WAREHOUSE_SELECTIONS.msk, LEGACY_WAREHOUSE_SELECTIONS.msk],
    ["unknown-legacy-slug", LEGACY_WAREHOUSE_SELECTIONS.spb],
    [null, LEGACY_WAREHOUSE_SELECTIONS.spb],
  ])("maps %s to the canonical active warehouse UUID", (saved, expected) => {
    expect(resolveWarehouseSelection(saved, warehouses)).toBe(expected)
  })

  it("returns null only when no active warehouse exists", () => {
    expect(resolveWarehouseSelection("spb", [])).toBeNull()
  })
})
