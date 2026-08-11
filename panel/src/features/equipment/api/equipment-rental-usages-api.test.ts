import { beforeEach, describe, expect, it, vi } from "vitest"

import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import type { EquipmentItemDto } from "@/types/equipment"

const WAREHOUSE_ID = "00000000-0000-0000-0000-000000000001"
const EQUIPMENT_ID = "00000000-0000-0000-0000-000000000101"
const RENTAL_ITEM_ID_2 = "00000000-0000-0000-0000-000000000202"
const RENTAL_ITEM_ID_10 = "00000000-0000-0000-0000-000000000210"

const equipmentApi = vi.hoisted(() => ({
  getEquipmentItems: vi.fn(),
}))
const rentalItemsApi = vi.hoisted(() => ({
  listAssetRentalItems: vi.fn(),
}))

vi.mock("@/api/equipment-api", () => ({
  getEquipmentItems: equipmentApi.getEquipmentItems,
}))
vi.mock("@/features/rental-items/api/asset-rental-items-api", () => ({
  listAssetRentalItems: rentalItemsApi.listAssetRentalItems,
}))

import { getEquipmentItemsWithRentalUsages } from "@/features/equipment/api/equipment-rental-usages-api"

function rentalItem(
  id: string,
  number: string,
  status: RentalItemDto["status"]
): RentalItemDto {
  return {
    id,
    version: 3,
    warehouseId: WAREHOUSE_ID,
    number,
    rentalTypeId: "00000000-0000-0000-0000-000000000011",
    dimensionId: "00000000-0000-0000-0000-000000000012",
    finishingId: "00000000-0000-0000-0000-000000000013",
    type: "БК-01",
    dimensions: null,
    finishing: null,
    category: null,
    characteristics: [],
    linoleum: null,
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

function equipment(): EquipmentItemDto {
  return {
    id: EQUIPMENT_ID,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    category: "ELECTRICAL",
    name: "Конвектор",
    active: true,
    comment: null,
    maximumPerCabin: null,
    totalQuantity: 12,
    stockQuantity: 3,
    cabinStockQuantity: 5,
    rentedQuantity: 4,
    writtenOffQuantity: 0,
    lostQuantity: 0,
    activeHeldQuantity: 0,
    reservedQuantity: 0,
    availableQuantity: 8,
    availableStock: 3,
    balances: [
      {
        id: "00000000-0000-0000-0000-000000000301",
        version: 7,
        equipmentId: EQUIPMENT_ID,
        warehouseId: WAREHOUSE_ID,
        rentalItemId: RENTAL_ITEM_ID_10,
        locationKind: "CABIN_RENTED",
        quantity: 4,
        activeHeldQuantity: 0,
        availableStock: 4,
      },
      {
        id: "00000000-0000-0000-0000-000000000302",
        version: 9,
        equipmentId: EQUIPMENT_ID,
        warehouseId: WAREHOUSE_ID,
        rentalItemId: RENTAL_ITEM_ID_2,
        locationKind: "CABIN_NON_RENTED",
        quantity: 5,
        activeHeldQuantity: 0,
        availableStock: 5,
      },
      {
        id: "00000000-0000-0000-0000-000000000303",
        version: 2,
        equipmentId: EQUIPMENT_ID,
        warehouseId: WAREHOUSE_ID,
        rentalItemId: null,
        locationKind: "STOCK",
        quantity: 3,
        activeHeldQuantity: 0,
        availableStock: 3,
      },
    ],
    usages: [],
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  equipmentApi.getEquipmentItems.mockResolvedValue([equipment()])
  rentalItemsApi.listAssetRentalItems.mockImplementation(
    ({ page }: { page?: number }) =>
      Promise.resolve({
        content:
          page === 0
            ? [rentalItem(RENTAL_ITEM_ID_10, "БЫТ-010", "RENTED")]
            : [rentalItem(RENTAL_ITEM_ID_2, "БЫТ-002", "WAREHOUSE")],
        page: page ?? 0,
        size: 200,
        totalElements: 2,
        totalPages: 2,
      })
  )
})

describe("equipment rental usages projection", () => {
  it("joins every paged cabin balance by UUID and keeps real numbers, versions and statuses", async () => {
    const result = await getEquipmentItemsWithRentalUsages("asset-token", {
      warehouseId: WAREHOUSE_ID,
      search: "конвектор",
    })

    expect(equipmentApi.getEquipmentItems).toHaveBeenCalledWith("asset-token", {
      warehouseId: WAREHOUSE_ID,
      search: "конвектор",
    })
    expect(rentalItemsApi.listAssetRentalItems).toHaveBeenCalledTimes(2)
    expect(rentalItemsApi.listAssetRentalItems).toHaveBeenNthCalledWith(1, {
      accessToken: "asset-token",
      warehouseId: WAREHOUSE_ID,
      page: 0,
      size: 200,
    })
    expect(rentalItemsApi.listAssetRentalItems).toHaveBeenNthCalledWith(2, {
      accessToken: "asset-token",
      warehouseId: WAREHOUSE_ID,
      page: 1,
      size: 200,
    })
    expect(result[0]?.usages).toEqual([
      expect.objectContaining({
        id: "00000000-0000-0000-0000-000000000302",
        balanceVersion: 9,
        rentalItemId: RENTAL_ITEM_ID_2,
        rentalItemNumber: "БЫТ-002",
        rentalItemType: "БК-01",
        rentalItemStatus: "WAREHOUSE",
        warehouseId: WAREHOUSE_ID,
        locationKind: "CABIN_NON_RENTED",
        quantity: 5,
        availableQuantity: 5,
      }),
      expect.objectContaining({
        rentalItemId: RENTAL_ITEM_ID_10,
        rentalItemNumber: "БЫТ-010",
        rentalItemStatus: "RENTED",
        locationKind: "CABIN_RENTED",
        quantity: 4,
      }),
    ])
  })

  it("fails closed when a service balance references a cabin absent from the public projection", async () => {
    rentalItemsApi.listAssetRentalItems.mockResolvedValue({
      content: [],
      page: 0,
      size: 200,
      totalElements: 0,
      totalPages: 0,
    })

    await expect(
      getEquipmentItemsWithRentalUsages("asset-token", {
        warehouseId: WAREHOUSE_ID,
      })
    ).rejects.toThrow("остаток для неизвестной бытовки")
  })
})
