import type { Page, Route } from "@playwright/test"

const warehouseId = "00000000-0000-0000-0000-000000000001"
const rentalItemId = "00000000-0000-0000-0000-0000000000a1"
const equipmentId = "00000000-0000-0000-0000-0000000000e1"

const rentalItem = {
  id: rentalItemId,
  version: 3,
  warehouseId,
  number: "Б-101",
  status: "FREE",
  rentalType: "Офис",
  dimensions: "6 × 2,4 м",
  finishing: null,
  category: "Стандарт",
  characteristics: null,
  linoleum: true,
  generalComment: "Локальный комментарий asset-service",
  passport: { insulation: "100 мм" },
  tags: ["офис"],
  contents: [
    {
      equipmentId,
      equipmentCode: "CHAIR-01",
      quantity: 2,
      locationKind: "CABIN_NON_RENTED",
    },
  ],
  createdAt: "2026-07-16T09:00:00Z",
  updatedAt: "2026-07-16T10:00:00Z",
} as const

const catalogItem = {
  id: equipmentId,
  version: 2,
  code: "CHAIR-01",
  name: "Стул",
  category: "FURNITURE",
  active: true,
  comment: "Сервисный комментарий",
  createdAt: "2026-07-16T09:00:00Z",
  updatedAt: "2026-07-16T10:00:00Z",
} as const

const warehouseEquipment = {
  equipment: catalogItem,
  totals: {
    equipmentId,
    warehouseId,
    totalQuantity: 10,
    stockQuantity: 8,
    nonRentedCabinQuantity: 2,
    rentedCabinQuantity: 0,
    writtenOffQuantity: 0,
    lostQuantity: 0,
    activeHeldQuantity: 1,
    availableStock: 7,
    balances: [
      {
        id: "00000000-0000-0000-0000-0000000000b1",
        version: 1,
        equipmentId,
        warehouseId,
        rentalItemId: null,
        locationKind: "STOCK",
        quantity: 8,
        activeHeldQuantity: 1,
        availableStock: 7,
      },
      {
        id: "00000000-0000-0000-0000-0000000000b2",
        version: 1,
        equipmentId,
        warehouseId,
        rentalItemId,
        locationKind: "CABIN_NON_RENTED",
        quantity: 2,
        activeHeldQuantity: 0,
        availableStock: 2,
      },
    ],
  },
} as const

async function json(route: Route, body: unknown) {
  if (route.request().method() !== "GET") {
    await route.fulfill({
      status: 405,
      contentType: "application/json",
      body: JSON.stringify({ detail: "Fixture accepts GET only" }),
    })
    return
  }

  await route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(body),
  })
}

/** Test-only HTTP fixture. Production routes still call asset-service through the gateway. */
export async function installAssetRouteFixture(page: Page) {
  await page.route(/\/api\/asset\/v1\/rental-items(?:\?.*)?$/, (route) =>
    json(route, {
      content: [rentalItem],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
    })
  )
  await page.route(
    /\/api\/asset\/v1\/rental-items\/00000000-0000-0000-0000-0000000000a1\/manual-notes(?:\?.*)?$/,
    (route) =>
      json(route, [
        {
          id: "00000000-0000-0000-0000-0000000000d1",
          rentalItemId,
          text: "Только service-local заметка",
          createdAt: "2026-07-16T11:00:00Z",
        },
      ])
  )
  await page.route(
    /\/api\/asset\/v1\/rental-items\/00000000-0000-0000-0000-0000000000a1(?:\?.*)?$/,
    (route) => json(route, rentalItem)
  )
  await page.route(/\/api\/asset\/v1\/equipment\/dispositions(?:\?.*)?$/, (route) =>
    json(route, [
      {
        movement: {
          id: "00000000-0000-0000-0000-0000000000m1",
          version: 0,
          equipmentId,
          sourceBalanceId: "00000000-0000-0000-0000-0000000000b1",
          targetBalanceId: "00000000-0000-0000-0000-0000000000b3",
          quantity: 1,
          kind: "WRITE_OFF",
          occurredAt: "2026-07-16T12:00:00Z",
        },
        equipmentCode: catalogItem.code,
        equipmentName: catalogItem.name,
      },
    ])
  )
  await page.route(/\/api\/asset\/v1\/equipment\/catalog(?:\?.*)?$/, (route) =>
    json(route, [catalogItem])
  )
  await page.route(/\/api\/asset\/v1\/equipment(?:\?.*)?$/, (route) =>
    json(route, [warehouseEquipment])
  )
  await page.route(/\/api\/asset\/v1\/classifiers(?:\?.*)?$/, (route) =>
    json(route, [
      {
        id: "00000000-0000-0000-0000-0000000000c1",
        version: 0,
        type: "CATEGORY",
        parentId: null,
        code: "OFFICE",
        name: "Офис",
        active: true,
        sortOrder: 0,
      },
    ])
  )
}
