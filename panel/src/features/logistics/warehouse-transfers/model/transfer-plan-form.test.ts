import { describe, expect, it } from "vitest"

import {
  autoAllocateCabinGroups,
  cabinGroupMismatches,
  calculateCabinFurnitureDelta,
  calculateTransferFurnitureTotals,
  emptyTransferCabinGroup,
} from "@/features/logistics/warehouse-transfers/model/transfer-plan-form"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const OTHER_WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const TYPE_ID = "33333333-3333-4333-8333-333333333333"
const DIMENSION_ID = "44444444-4444-4444-8444-444444444444"
const FINISHING_ID = "55555555-5555-4555-8555-555555555555"
const CHARACTERISTIC_ID = "66666666-6666-4666-8666-666666666666"
const BED_ID = "77777777-7777-4777-8777-777777777777"
const TABLE_ID = "88888888-8888-4888-8888-888888888888"

function cabin(
  id: string,
  overrides: Partial<RentalItemDto> = {}
): RentalItemDto {
  return {
    id,
    version: 3,
    warehouseId: WAREHOUSE_ID,
    number: id.slice(0, 4),
    rentalTypeId: TYPE_ID,
    dimensionId: DIMENSION_ID,
    finishingId: FINISHING_ID,
    type: "Каталожный тип",
    dimensions: "6 × 2,4",
    finishing: "Каталожная отделка",
    category: null,
    characteristics: [{ id: CHARACTERISTIC_ID, name: "Каталожная опция" }],
    linoleum: true,
    status: "FREE",
    comment: null,
    contents: null,
    contentsItems: [],
    shipmentDate: null,
    tenant: null,
    price: null,
    activeOrderReservation: null,
    passport: {},
    tags: [],
    ...overrides,
  }
}

function group(key: string) {
  return {
    ...emptyTransferCabinGroup(key),
    rentalTypeId: TYPE_ID,
    dimensionId: DIMENSION_ID,
    finishingId: FINISHING_ID,
    characteristicIds: [CHARACTERISTIC_ID],
    linoleum: true,
    quantity: 2,
    furniturePerCabin: [
      { furnitureCatalogItemId: BED_ID, quantityPerCabin: 4 },
      { furnitureCatalogItemId: TABLE_ID, quantityPerCabin: 1 },
    ],
  }
}

describe("transfer plan form calculations", () => {
  it("automatically selects distinct FREE exact-match cabins", () => {
    const first = cabin("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    const second = cabin("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    const reserved = cabin("cccccccc-cccc-4ccc-8ccc-cccccccccccc", {
      activeOrderReservation: {
        reservationId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
        orderId: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
        clientId: null,
        tenantSnapshot: null,
        reservedAt: "2026-09-01T08:00:00Z",
      },
    })

    const result = autoAllocateCabinGroups(
      [group("one"), { ...group("two"), quantity: 1 }],
      [reserved, first, second],
      WAREHOUSE_ID
    )

    expect(result[0]?.allocatedCabins).toEqual([
      { assetId: first.id, assetVersion: 3 },
      { assetId: second.id, assetVersion: 3 },
    ])
    expect(result[1]?.allocatedCabins).toEqual([])
  })

  it("explains warehouse, status, reservation, and catalog mismatches", () => {
    const mismatches = cabinGroupMismatches(
      cabin("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", {
        warehouseId: OTHER_WAREHOUSE_ID,
        status: "REPAIR",
        rentalTypeId: "99999999-9999-4999-8999-999999999999",
        activeOrderReservation: {
          reservationId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
          orderId: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
          clientId: null,
          tenantSnapshot: null,
          reservedAt: "2026-09-01T08:00:00Z",
        },
      }),
      group("one"),
      WAREHOUSE_ID
    )

    expect(mismatches.map((item) => item.code)).toEqual(
      expect.arrayContaining(["WAREHOUSE", "STATUS", "RESERVATION", "TYPE"])
    )
  })

  it("calculates per-group and loose furniture totals without double-counting", () => {
    expect(
      calculateTransferFurnitureTotals(
        [group("one")],
        [{ furnitureCatalogItemId: BED_ID, quantity: 2 }]
      )
    ).toEqual([
      {
        furnitureCatalogItemId: BED_ID,
        cabinRequirementQuantity: 8,
        looseQuantity: 2,
        totalQuantity: 10,
      },
      {
        furnitureCatalogItemId: TABLE_ID,
        cabinRequirementQuantity: 2,
        looseQuantity: 0,
        totalQuantity: 2,
      },
    ])
  })

  it("shows what must be added and removed for an allocated cabin", () => {
    const delta = calculateCabinFurnitureDelta(
      cabin("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", {
        contentsItems: [
          { equipmentId: BED_ID, name: "Кровать", quantity: 3 },
          { equipmentId: TABLE_ID, name: "Стол", quantity: 2 },
        ],
      }),
      group("one").furniturePerCabin
    )

    expect(delta).toEqual([
      {
        furnitureCatalogItemId: BED_ID,
        required: 4,
        actual: 3,
        delta: 1,
      },
      {
        furnitureCatalogItemId: TABLE_ID,
        required: 1,
        actual: 2,
        delta: -1,
      },
    ])
  })
})
