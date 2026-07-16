import { beforeEach, describe, expect, it } from "vitest"
import {
  createRentalItem,
  createRentalItemForInventory,
  lookupRentalItemForInventory,
} from "@/features/rental-items/api/rental-items-api"

describe("inventory rental-item commands", () => {
  beforeEach(() => window.localStorage.clear())

  it("creates once by inventory source key and rejects global number duplicates", async () => {
    const payload = {
      warehouseId: "spb",
      inventoryId: "inventory-1",
      findingId: "finding-1",
      condition: "USED" as const,
      number: "  inv-unique-1 ",
      type: "БК-1" as const,
      dimensions: "2.4x6",
      finishing: "ДВП" as const,
      category: "Обычная" as const,
      characteristics: [],
      linoleum: false,
    }
    const first = await createRentalItemForInventory(payload)
    const retried = await createRentalItemForInventory({
      ...payload,
      number: "ДРУГОЙ НОМЕР",
    })
    expect(retried.id).toBe(first.id)
    expect(retried.status).toBe("FREE")
    expect(
      await lookupRentalItemForInventory({
        warehouseId: "spb",
        number: " INV-UNIQUE-1 ",
      })
    ).toMatchObject({
      kind: "CURRENT_WAREHOUSE",
      item: { id: first.id },
    })
    await expect(
      createRentalItemForInventory({
        ...payload,
        inventoryId: "inventory-2",
        findingId: "finding-2",
      })
    ).rejects.toThrow("уже существует")
  })

  it("keeps the rental snapshot when an imported cabin is created from return logistics", async () => {
    const created = await createRentalItem({
      warehouseId: "spb",
      number: "имп-аренда-1",
      type: "БК-2",
      dimensions: "2.4x6",
      finishing: "ЛДСП",
      category: "Обычная",
      characteristics: [],
      photos: [],
      linoleum: false,
      status: "RENTED",
      tenant: "ООО Север",
      shipmentDate: "2026-07-12",
      contentsItems: [{ name: "Стол", quantity: 2 }],
    })

    expect(created).toMatchObject({
      number: "имп-аренда-1",
      status: "RENTED",
      tenant: "ООО Север",
      shipmentDate: "2026-07-12",
      contentsItems: [{ name: "Стол", quantity: 2 }],
    })
    await expect(
      createRentalItem({
        warehouseId: "spb",
        number: " ИМП-АРЕНДА-1 ",
        type: "БК-2",
        dimensions: "2.4x6",
        finishing: "ЛДСП",
        category: "Обычная",
        characteristics: [],
        photos: [],
        linoleum: false,
        status: "RENTED",
        tenant: "ООО Север",
        shipmentDate: "2026-07-12",
      })
    ).rejects.toThrow("уже существует")
  })
})
