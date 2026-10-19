import { describe, expect, it } from "vitest"

import type { WarehouseInfo } from "@/api/warehouse-api"
import { isConfigurableObject } from "@/apps/admin/admin-object"

function warehouse(overrides: Partial<WarehouseInfo> = {}): WarehouseInfo {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    version: 1,
    name: "MSK",
    city: "Москва",
    address: null,
    latitude: null,
    longitude: null,
    timeZone: "Europe/Moscow",
    active: true,
    lifecycleState: "ACTIVE",
    sortOrder: 1,
    representative: false,
    production: true,
    mainWarehouse: false,
    representativeParentWarehouseId: null,
    ...overrides,
  }
}

describe("admin object eligibility", () => {
  it.each([
    ["производство", { production: true, mainWarehouse: false }],
    ["основной склад", { production: false, mainWarehouse: true }],
    ["оба признака", { production: true, mainWarehouse: true }],
  ])("allows an active object with %s", (_label, flags) => {
    expect(isConfigurableObject(warehouse(flags))).toBe(true)
  })

  it.each([
    ["без нужного назначения", { production: false, mainWarehouse: false }],
    [
      "представительство",
      {
        representative: true,
        production: false,
        mainWarehouse: false,
        representativeParentWarehouseId:
          "22222222-2222-4222-8222-222222222222",
      },
    ],
    ["неактивный объект", { lifecycleState: "INACTIVE" as const }],
  ])("excludes %s", (_label, overrides) => {
    expect(isConfigurableObject(warehouse(overrides))).toBe(false)
  })
})
