import { beforeEach, describe, expect, it, vi } from "vitest"

vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  getAssetRentalItem: vi.fn(),
  listAssetRentalItems: vi.fn(),
}))

vi.mock("@/features/repair-estimates/api/maintenance-auth", () => ({
  currentMaintenanceAccessToken: vi.fn().mockResolvedValue("access-token"),
}))

vi.mock("@/features/logistics/returns/api", () => ({
  listReturns: vi.fn(),
}))

import {
  getAssetRentalItem,
  listAssetRentalItems,
} from "@/features/rental-items/api/asset-rental-items-api"
import { listReturns } from "@/features/logistics/returns/api"
import { panelEstimateRentalItemsClient } from "@/features/repair-estimates/adapters/panel-estimate-rental-items-client"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import type { ReturnDocument } from "@/features/logistics/returns/model"

const WAREHOUSE_ID = "69d4ca7e-d4d6-48d3-a5b4-0f60e43680f3"
const RENTAL_ITEM_ID = "4b87e123-1f2a-4a38-ae57-c0d0e2a05c01"

function rentalItem(overrides: Partial<RentalItemDto> = {}): RentalItemDto {
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
    status: "AFTER_RENT",
    comment: null,
    contents: null,
    contentsItems: [],
    shipmentDate: null,
    tenant: null,
    price: null,
    passport: {},
    tags: [],
    ...overrides,
  }
}

function returnDocument(
  overrides: Partial<ReturnDocument> = {}
): ReturnDocument {
  return {
    id: "0f38fbbc-98c8-4fbd-94a3-68e37dd2ec1e",
    version: 3,
    documentType: "RETURN",
    state: "INSPECTION_REQUIRED",
    warehouseId: WAREHOUSE_ID,
    destinationWarehouseId: null,
    partySnapshot: "ООО Арендатор",
    driverSnapshot: null,
    clientId: null,
    equipmentMovementTaskId: null,
    scheduledDate: null,
    rentalOrderId: null,
    lines: [
      {
        id: "7d244fd4-4e41-4a7e-8f57-7ccfbff7b0f3",
        version: 1,
        lineNumber: 1,
        assetId: RENTAL_ITEM_ID,
        assetVersion: 4,
        state: "ARRIVED",
        tenantSnapshot: "ООО Арендатор из строки",
        rentalOrderId: null,
      },
    ],
    createdAt: "2026-07-18T08:00:00Z",
    updatedAt: "2026-07-18T08:10:00Z",
    ...overrides,
  }
}

describe("panel estimate rental-items client", () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listReturns).mockResolvedValue([returnDocument()])
  })

  it("searches only after-rent cabins and includes their return metadata", async () => {
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
          counterparty: "ООО Арендатор",
          arrivalDate: "2026-07-18",
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
      excludeStatuses: [
        "RENTED",
        "BOOKED",
        "REPAIR",
        "WAITING_REPAIR_CHECK",
        "WRITTEN_OFF",
        "LOST",
        "CAPITAL_REPAIR",
        "WAITING_ESTIMATE_CONFIRMATION",
        "SALE",
        "USED_SALE",
        "RESERVED",
        "FREE",
        "WAREHOUSE",
        "OWN_NEEDS",
        "IN_TRANSFER",
      ],
    })
    expect(listReturns).toHaveBeenCalledWith("access-token", WAREHOUSE_ID)
  })

  it("resolves an after-rent item with its counterparty and arrival date", async () => {
    vi.mocked(getAssetRentalItem).mockResolvedValue(rentalItem())

    await expect(
      panelEstimateRentalItemsClient.resolveById(WAREHOUSE_ID, RENTAL_ITEM_ID)
    ).resolves.toEqual({
      id: RENTAL_ITEM_ID,
      warehouseId: WAREHOUSE_ID,
      number: "БЫТ-042",
      counterparty: "ООО Арендатор",
      arrivalDate: "2026-07-18",
    })

    expect(getAssetRentalItem).toHaveBeenCalledWith(
      "access-token",
      RENTAL_ITEM_ID
    )
  })

  it("does not resolve an item that is not after rent", async () => {
    vi.mocked(getAssetRentalItem).mockResolvedValue(
      rentalItem({ status: "USED_SALE" })
    )

    await expect(
      panelEstimateRentalItemsClient.resolveById(WAREHOUSE_ID, RENTAL_ITEM_ID)
    ).resolves.toBeNull()

    expect(listReturns).not.toHaveBeenCalled()
  })
})
