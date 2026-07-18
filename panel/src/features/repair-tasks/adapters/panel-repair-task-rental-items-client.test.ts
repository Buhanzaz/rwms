import { beforeEach, describe, expect, it, vi } from "vitest"

vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  getAssetRentalItem: vi.fn(),
}))

vi.mock("@/features/repair-estimates/api/maintenance-auth", () => ({
  currentMaintenanceAccessToken: vi.fn().mockResolvedValue("access-token"),
}))

import { getAssetRentalItem } from "@/features/rental-items/api/asset-rental-items-api"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { panelRepairTaskRentalItemsClient } from "@/features/repair-tasks/adapters/panel-repair-task-rental-items-client"

const WAREHOUSE_ID = "69d4ca7e-d4d6-48d3-a5b4-0f60e43680f3"
const RENTAL_ITEM_ID = "4b87e123-1f2a-4a38-ae57-c0d0e2a05c01"

function rentalItem(status: RentalItemDto["status"]): RentalItemDto {
  return {
    id: RENTAL_ITEM_ID,
    version: 4,
    warehouseId: WAREHOUSE_ID,
    number: "БЫТ-042",
    type: "БК-2",
    dimensions: "2.4x6",
    finishing: "ЛДСП",
    category: "Обычная",
    characteristics: "Окно",
    linoleum: true,
    status,
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

describe("panel repair-task rental-items client", () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it("resolves an eligible rental item through the asset service", async () => {
    vi.mocked(getAssetRentalItem).mockResolvedValue(rentalItem("WAREHOUSE"))

    await expect(
      panelRepairTaskRentalItemsClient.resolveById(WAREHOUSE_ID, RENTAL_ITEM_ID)
    ).resolves.toEqual({
      id: RENTAL_ITEM_ID,
      warehouseId: WAREHOUSE_ID,
      number: "БЫТ-042",
    })
  })

  it("rejects a maintenance-ineligible rental item", async () => {
    vi.mocked(getAssetRentalItem).mockResolvedValue(rentalItem("WRITTEN_OFF"))

    await expect(
      panelRepairTaskRentalItemsClient.resolveById(WAREHOUSE_ID, RENTAL_ITEM_ID)
    ).resolves.toBeNull()
  })
})
