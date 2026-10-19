import { describe, expect, it } from "vitest"

import {
  createWarehouseFormValues,
  parseWarehouseForm,
} from "@/features/settings/warehouses/warehouse-settings-form"

const validValues = {
  name: "Северный склад",
  city: "Санкт-Петербург",
  address: "",
  latitude: "59,9343",
  longitude: "30.3351",
  timeZone: "Europe/Moscow",
  sortOrder: "2",
  production: true,
  mainWarehouse: false,
  representative: false,
  representativeParentWarehouseId: "",
}

const representativeParentWarehouseId =
  "00000000-0000-4000-8000-000000000010"

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
        production: true,
        mainWarehouse: false,
        representativeParentWarehouseId: null,
        representative: false,
      },
      error: null,
    })
  })

  it("requires an eligible parent for a representative object", () => {
    expect(
      parseWarehouseForm({
        ...validValues,
        production: false,
        representative: true,
        representativeParentWarehouseId,
      }).input
    ).toMatchObject({
      production: false,
      mainWarehouse: false,
      representativeParentWarehouseId,
      representative: true,
    })

    expect(
      parseWarehouseForm({
        ...validValues,
        production: false,
        representative: true,
      })
    ).toEqual({
      input: null,
      error:
        "Выберите объект с производством или основным складом для представительского объекта.",
    })
  })

  it("preserves the current warehouse classifications when editing", () => {
    expect(
      createWarehouseFormValues({
        id: "00000000-0000-4000-8000-000000000011",
        version: 1,
        name: "Представительство",
        city: "Псков",
        address: null,
        latitude: null,
        longitude: null,
        timeZone: "Europe/Moscow",
        active: true,
        lifecycleState: "ACTIVE",
        sortOrder: null,
        representative: true,
        production: false,
        mainWarehouse: false,
        representativeParentWarehouseId,
      })
    ).toMatchObject({
      production: false,
      mainWarehouse: false,
      representative: true,
      representativeParentWarehouseId,
    })
  })

  it("rejects the placeholder coordinates 0, 0 with an actionable message", () => {
    expect(
      parseWarehouseForm({ ...validValues, latitude: "0", longitude: "0" })
    ).toEqual({
      input: null,
      error:
        "Координаты 0, 0 не определяют местоположение объекта. Укажите фактические координаты или оставьте оба поля пустыми.",
    })
  })

  it.each([
    { latitude: "", longitude: "" },
    { latitude: "0", longitude: "30.3351" },
    { latitude: "59.9343", longitude: "0" },
  ])("accepts valid non-placeholder coordinate pairs", (coordinates) => {
    expect(
      parseWarehouseForm({ ...validValues, ...coordinates }).error
    ).toBeNull()
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
