import { beforeEach, describe, expect, it, vi } from "vitest"

vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  getAssetRentalItem: vi.fn(),
  listAssetRentalItems: vi.fn(),
}))

vi.mock("@/features/repair-estimates/api/maintenance-auth", () => ({
  currentMaintenanceAccessToken: vi.fn().mockResolvedValue("access-token"),
}))

import {
  getAssetRentalItem,
  listAssetRentalItems,
} from "@/features/rental-items/api/asset-rental-items-api"
import { panelEstimateRentalItemsClient } from "@/features/repair-estimates/adapters/panel-estimate-rental-items-client"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

const WAREHOUSE_ID = "69d4ca7e-d4d6-48d3-a5b4-0f60e43680f3"
const RENTAL_ITEM_ID = "4b87e123-1f2a-4a38-ae57-c0d0e2a05c01"

function rentalItem(overrides: Partial<RentalItemDto> = {}): RentalItemDto {
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
    status: "WAREHOUSE",
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
    ...overrides,
  }
}

describe("panel estimate rental-items client", () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it("searches the asset service with maintenance-ineligible statuses excluded", async () => {
    vi.mocked(listAssetRentalItems).mockResolvedValue({
      content: [rentalItem()],
      page: 0,
      size: 40,
      totalElements: 1,
      totalPages: 1,
    })

    await expect(
      panelEstimateRentalItemsClient.search({
        warehouseId: WAREHOUSE_ID,
        search: "042",
        page: 0,
        size: 40,
      })
    ).resolves.toMatchObject({
      items: [
        {
          id: RENTAL_ITEM_ID,
          warehouseId: WAREHOUSE_ID,
          number: "БЫТ-042",
        },
      ],
      totalElements: 1,
    })

    expect(listAssetRentalItems).toHaveBeenCalledWith({
      accessToken: "access-token",
      warehouseId: WAREHOUSE_ID,
      search: "042",
      page: 0,
      size: 40,
      excludeStatuses: ["WRITTEN_OFF", "WAITING_ESTIMATE_CONFIRMATION"],
    })
  })

  it("resolves an eligible asset-service rental item", async () => {
    vi.mocked(getAssetRentalItem).mockResolvedValue(rentalItem())

    await expect(
      panelEstimateRentalItemsClient.resolveById(WAREHOUSE_ID, RENTAL_ITEM_ID)
    ).resolves.toEqual({
      id: RENTAL_ITEM_ID,
      warehouseId: WAREHOUSE_ID,
      number: "БЫТ-042",
    })

    expect(getAssetRentalItem).toHaveBeenCalledWith(
      "access-token",
      RENTAL_ITEM_ID
    )
  })
})
