import { describe, expect, it } from "vitest"

import type { WarehouseInfo } from "@/api/warehouse-api"
import {
  createEmptyWarehouseFilters,
  filterWarehouses,
  getWarehouseFilterOptions,
} from "./warehouse-settings-filtering"

const northWarehouse: WarehouseInfo = {
  id: "00000000-0000-4000-8000-000000000001",
  version: 1,
  name: "Северный склад",
  city: "Санкт-Петербург",
  address: null,
  timeZone: "Europe/Moscow",
  active: true,
  sortOrder: 1,
}

const southWarehouse: WarehouseInfo = {
  ...northWarehouse,
  id: "00000000-0000-4000-8000-000000000002",
  name: "Южный склад",
  city: "Москва",
  active: false,
}

describe("warehouse settings filtering", () => {
  it("builds unique column options from the loaded warehouses", () => {
    expect(getWarehouseFilterOptions([southWarehouse, northWarehouse])).toEqual(
      {
        names: [
          { value: "Северный склад", label: "Северный склад" },
          { value: "Южный склад", label: "Южный склад" },
        ],
        cities: [
          { value: "Москва", label: "Москва" },
          { value: "Санкт-Петербург", label: "Санкт-Петербург" },
        ],
        timeZones: [{ value: "Europe/Moscow", label: "Europe/Moscow" }],
      }
    )
  })

  it("combines search with name, city and status filters", () => {
    const filters = createEmptyWarehouseFilters()

    expect(
      filterWarehouses([northWarehouse, southWarehouse], "юж", filters)
    ).toEqual([southWarehouse])

    filters.names = ["Северный склад"]
    filters.cities = ["Санкт-Петербург"]
    filters.statuses = ["active"]

    expect(
      filterWarehouses([northWarehouse, southWarehouse], "", filters)
    ).toEqual([northWarehouse])
    expect(
      filterWarehouses([northWarehouse, southWarehouse], "", {
        ...filters,
        statuses: ["inactive"],
      })
    ).toEqual([])
  })
})
