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
    rentalTypeId: "af57f2b0-3a71-4b7f-8d2f-000000000002",
    dimensionId: "af57f2b0-3a71-4b7f-8d2f-000000000107",
    finishingId: "af57f2b0-3a71-4b7f-8d2f-000000000202",
    type: "БК-2",
    dimensions: "2.4x6",
    finishing: "ЛДСП",
    category: "Обычная",
    characteristics: [
      {
        id: "af57f2b0-3a71-4b7f-8d2f-000000000301",
        name: "Пластиковое окно",
      },
    ],
    linoleum: true,
    status,
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

  it("searches every direct-repair status except rented", async () => {
    vi.mocked(listAssetRentalItems).mockResolvedValue({
      content: [rentalItem("WAREHOUSE")],
      page: 0,
      size: 40,
      totalElements: 1,
      totalPages: 1,
    })

    await expect(
      panelRepairTaskRentalItemsClient.search({
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
      excludeStatuses: ["RENTED"],
    })
  })

  it.each(["RENTED"] as const)(
    "rejects a maintenance-ineligible %s rental item",
    async (status) => {
      vi.mocked(getAssetRentalItem).mockResolvedValue(rentalItem(status))

      await expect(
        panelRepairTaskRentalItemsClient.resolveById(
          WAREHOUSE_ID,
          RENTAL_ITEM_ID
        )
      ).resolves.toBeNull()
    }
  )

  it("keeps an after-rent rental item eligible for direct repair", async () => {
    vi.mocked(getAssetRentalItem).mockResolvedValue(rentalItem("AFTER_RENT"))

    await expect(
      panelRepairTaskRentalItemsClient.resolveById(WAREHOUSE_ID, RENTAL_ITEM_ID)
    ).resolves.toEqual({
      id: RENTAL_ITEM_ID,
      warehouseId: WAREHOUSE_ID,
      number: "БЫТ-042",
    })
  })
})
