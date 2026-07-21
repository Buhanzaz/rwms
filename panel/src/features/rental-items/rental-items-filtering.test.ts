import { describe, expect, it } from "vitest"

import {
  buildRentalItemsFilterOptions,
  filterRentalItemsByFilters,
  pruneRentalItemsFilters,
} from "@/features/rental-items/rental-items-filtering"
import {
  buildRentalItemsTableSchema,
  normalizeRentalItemsColumnConfig,
  type RentalItemDto,
} from "@/features/rental-items/model/rental-item"

function rentalItem(id: string, type: string): RentalItemDto {
  return {
    id,
    version: 1,
    warehouseId: "warehouse-1",
    number: id,
    type,
    dimensions: "2,4 × 6",
    finishing: "ЛДСП",
    category: "Стандарт",
    characteristics: null,
    linoleum: true,
    status: "FREE",
    comment: null,
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    locationNodeId: null,
    contents: null,
    contentsItems: [],
    shipmentDate: null,
    tenant: null,
    price: null,
  }
}

describe("shared rental item filtering", () => {
  it("preserves the warehouse page build, prune and filter behavior", () => {
    const items = [rentalItem("БЫТ-001", "БК-1"), rentalItem("БЫТ-002", "БК-2")]
    const schema = buildRentalItemsTableSchema(items)
    const columns = normalizeRentalItemsColumnConfig(schema.columns, [])
    const options = buildRentalItemsFilterOptions(items, schema, columns)

    expect(options.find((option) => option.id === "type")?.values).toEqual([
      "БК-1",
      "БК-2",
    ])

    const filters = pruneRentalItemsFilters(
      { type: ["БК-2"], comment: ["скрытый фильтр"], status: [] },
      options
    )

    expect(filters).toEqual({ type: ["БК-2"] })
    expect(filterRentalItemsByFilters(items, filters)).toEqual([items[1]])
  })
})
