import { describe, expect, it } from "vitest"

import { parseWarehouseForm } from "@/features/settings/warehouses/warehouse-settings-form"

const validValues = {
  name: "Северный склад",
  city: "Санкт-Петербург",
  address: "",
  latitude: "59,9343",
  longitude: "30.3351",
  timeZone: "Europe/Moscow",
  sortOrder: "2",
  representative: false,
}

describe("warehouse settings form", () => {
  it("normalizes a valid create/edit payload to the OpenAPI shape", () => {
    expect(parseWarehouseForm(validValues)).toEqual({
      input: {
        name: "Северный склад",
        city: "Санкт-Петербург",
        address: null,
        latitude: 59.9343,
        longitude: 30.3351,
        timeZone: "Europe/Moscow",
        sortOrder: 2,
        representative: false,
      },
      error: null,
    })
  })

  it("preserves the representative characteristic in an edit payload", () => {
    expect(
      parseWarehouseForm({ ...validValues, representative: true }).input
    ).toMatchObject({ representative: true })
  })

  it.each([
    [{ ...validValues, timeZone: "Mars/Olympus" }],
    [{ ...validValues, sortOrder: "1.5" }],
    [{ ...validValues, longitude: "" }],
    [{ ...validValues, latitude: "91" }],
  ])("rejects an invalid warehouse form", (values) => {
    expect(parseWarehouseForm(values).input).toBeNull()
  })
})
