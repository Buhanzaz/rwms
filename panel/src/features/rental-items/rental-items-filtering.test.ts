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
    rentalTypeId: `type-${id}`,
    dimensionId: `dimension-${id}`,
    finishingId: `finishing-${id}`,
    type,
    dimensions: "2,4 × 6",
    finishing: "ЛДСП",
    category: "Стандарт",
    characteristics: [],
    linoleum: true,
    status: "FREE",
    comment: null,
    contents: null,
    contentsItems: [],
    shipmentDate: null,
    tenant: null,
    price: null,
    passport: {},
    tags: [],
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

  it("does not infer any legacy-prefixed fields", () => {
    const schema = buildRentalItemsTableSchema([
      {
        ...rentalItem("БЫТ-001", "БК-1"),
        legacyId: "spb-1",
        LEGACYWarehouseId: "spb",
        LeGaCyFutureMarker: "technical",
      },
    ])

    for (const fieldId of [
      "legacyId",
      "LEGACYWarehouseId",
      "LeGaCyFutureMarker",
    ]) {
      expect(schema.columns.map((column) => column.id)).not.toContain(fieldId)
      expect(schema.filters.map((filter) => filter.id)).not.toContain(fieldId)
      expect(schema.searchableFieldIds).not.toContain(fieldId)
    }
  })
})
