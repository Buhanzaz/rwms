import { describe, expect, it } from "vitest"

import { parseWarehouseForm } from "@/features/settings/warehouses/warehouse-settings-form"

const validValues = {
  name: "Северный склад",
  city: "Санкт-Петербург",
  address: "",
  timeZone: "Europe/Moscow",
  active: true,
  sortOrder: "2",
}

describe("warehouse settings form", () => {
  it("normalizes a valid create/edit payload to the OpenAPI shape", () => {
    expect(parseWarehouseForm(validValues)).toEqual({
      input: {
        name: "Северный склад",
        city: "Санкт-Петербург",
        address: null,
        timeZone: "Europe/Moscow",
        active: true,
        sortOrder: 2,
      },
      error: null,
    })
  })

  it.each([
    [{ ...validValues, timeZone: "Mars/Olympus" }],
    [{ ...validValues, sortOrder: "1.5" }],
  ])("rejects an invalid warehouse form", (values) => {
    expect(parseWarehouseForm(values).input).toBeNull()
  })
})
