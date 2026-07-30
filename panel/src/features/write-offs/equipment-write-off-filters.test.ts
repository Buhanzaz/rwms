import { describe, expect, it } from "vitest"

import type { EquipmentDispositionListItemDto } from "@/types/equipment"

import {
  buildEquipmentWriteOffFilterOptions,
  EMPTY_EQUIPMENT_WRITE_OFF_FILTERS,
  equipmentWriteOffOperationLabel,
  filterEquipmentWriteOffItems,
} from "./equipment-write-off-filters"

function item(
  id: string,
  overrides: Partial<EquipmentDispositionListItemDto> = {}
): EquipmentDispositionListItemDto {
  return {
    id,
    version: 1,
    equipmentId: `equipment-${id}`,
    sourceBalanceId: `source-${id}`,
    targetBalanceId: `target-${id}`,
    quantity: 2,
    kind: "EQUIPMENT_WRITTEN_OFF",
    occurredAt: "2026-07-20T10:00:00Z",
    equipmentName: "Стул",
    ...overrides,
  }
}

describe("equipment write-off filters", () => {
  it("builds unique options with Russian operation labels", () => {
    const options = buildEquipmentWriteOffFilterOptions([
      item("1"),
      item("2", {
        kind: "EQUIPMENT_LOST",
        equipmentName: "Стол",
      }),
    ])

    expect(options.names.map((option) => option.label)).toEqual([
      "Стол",
      "Стул",
    ])
    expect(options.operations).toEqual([
      { value: "EQUIPMENT_WRITTEN_OFF", label: "Списание" },
      { value: "EQUIPMENT_LOST", label: "Утрата" },
    ])
    expect(equipmentWriteOffOperationLabel("CUSTOM_OPERATION")).toBe(
      "CUSTOM_OPERATION"
    )
  })

  it("filters by name, operation and an inclusive date range", () => {
    const items = [
      item("1"),
      item("2", {
        kind: "EQUIPMENT_LOST",
        occurredAt: "2026-07-21T23:59:59Z",
        equipmentName: "Стол",
      }),
      item("3", {
        occurredAt: "2026-07-22T00:00:00Z",
        equipmentName: "Стул",
      }),
    ]

    expect(
      filterEquipmentWriteOffItems(items, {
        ...EMPTY_EQUIPMENT_WRITE_OFF_FILTERS,
        names: ["Стол"],
        states: ["EQUIPMENT_LOST"],
        dateFrom: "2026-07-21",
        dateTo: "2026-07-21",
      })
    ).toEqual([items[1]])
  })
})
