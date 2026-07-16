import { beforeEach, describe, expect, it } from "vitest"

import {
  getRentalItemsForContentsMove,
  moveRentalItemContentsToStock,
  updateRentalItemManualStatus,
  updateRentalItemStatusForRepairWorkflow,
  writeRentalItems,
} from "@/features/rental-items/api/rental-items-api"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { panelEstimateRentalItemsClient } from "@/features/repair-estimates/adapters/panel-estimate-rental-items-client"
import { WAREHOUSE_TRANSFERS_STORAGE_KEY } from "@/features/logistics/warehouse-transfers/adapters/local-storage-warehouse-transfer-store"
import { createImportedReturnIntake } from "@/features/logistics/api/logistics-api"

function cabin(id: string): RentalItemDto {
  return {
    id,
    version: 3,
    warehouseId: "spb",
    number: id,
    type: "БК-1",
    dimensions: "2.4x6",
    finishing: "ЛДСП",
    category: "Стандарт",
    characteristics: null,
    linoleum: true,
    status: "WAREHOUSE",
    comment: null,
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    locationNodeId: null,
    contents: "Стул: 2",
    contentsItems: [{ name: "Стул", quantity: 2 }],
    shipmentDate: null,
    tenant: null,
    price: null,
  }
}

function seedActiveTransfer(rentalItemId: string) {
  window.localStorage.setItem(
    WAREHOUSE_TRANSFERS_STORAGE_KEY,
    JSON.stringify({
      documents: [
        {
          id: "transfer-1",
          lines: [{ rentalItemId, status: "READY_TO_DEPART" }],
        },
      ],
      events: [],
      corrections: [],
    })
  )
}

describe("warehouse transfer cross-flow guards", () => {
  beforeEach(() => {
    window.localStorage.clear()
    writeRentalItems([cabin("БЫТ-001"), cabin("БЫТ-002")])
    seedActiveTransfer("БЫТ-001")
  })

  it("blocks lifecycle and contents commands while a transfer is active", async () => {
    await expect(
      updateRentalItemStatusForRepairWorkflow({
        rentalItemId: "БЫТ-001",
        warehouseId: "spb",
        status: "REPAIR",
      })
    ).rejects.toThrow("межскладском перемещении")
    await expect(
      updateRentalItemManualStatus({
        rentalItemId: "БЫТ-001",
        warehouseId: "spb",
        expectedVersion: 3,
        status: "FREE",
      })
    ).rejects.toThrow("межскладском перемещении")
    await expect(
      moveRentalItemContentsToStock("БЫТ-001", [
        { name: "Стул", quantity: 1 },
      ])
    ).rejects.toThrow("межскладском перемещении")
  })

  it("hides active-transfer cabins from estimates and contents targets", async () => {
    const estimates = await panelEstimateRentalItemsClient.search({
      warehouseId: "spb",
      search: "",
      page: 0,
      size: 20,
    })
    expect(estimates.items.map((item) => item.id)).toEqual(["БЫТ-002"])
    await expect(
      panelEstimateRentalItemsClient.resolveById("spb", "БЫТ-001")
    ).resolves.toBeNull()

    const targets = await getRentalItemsForContentsMove({
      warehouseId: "spb",
      sourceRentalItemId: "БЫТ-002",
    })
    expect(targets.map((item) => item.id)).not.toContain("БЫТ-001")
  })

  it("blocks an imported-return overwrite while the cabin is transferring", async () => {
    await expect(
      createImportedReturnIntake({
        commandId: "import-active-transfer",
        warehouseId: "spb",
        number: "БЫТ-001",
        fromParty: "ООО Клиент",
        shipmentDate: "2026-06-01",
        returnDate: "2026-07-12",
        driverId: null,
        driverName: null,
        createdBy: "Приёмщик",
        passport: {
          type: "БК-1",
          dimensions: "2.4x6",
          finishing: "ЛДСП",
          category: "Стандарт",
          characteristics: [],
          linoleum: true,
        },
        expectedContents: [{ name: "Стул", quantity: 2 }],
        returnedContents: [{ name: "Стул", quantity: 2 }],
        expectedContentsEditedReason: null,
        overrideExisting: true,
        passportChangeConfirmed: false,
        photos: [],
      })
    ).rejects.toThrow("межскладском перемещении")
  })
})
