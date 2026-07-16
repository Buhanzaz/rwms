import type { Page } from "@playwright/test"

const warehouses = [
  {
    id: "00000000-0000-0000-0000-000000000001",
    version: 0,
    code: "WH_00000000000000000000000000000001",
    name: "СПБ",
    city: "Санкт-Петербург",
    address: null,
    timeZone: "Europe/Moscow",
    active: true,
    sortOrder: null,
  },
  {
    id: "00000000-0000-0000-0000-000000000002",
    version: 0,
    code: "WH_00000000000000000000000000000002",
    name: "Москва",
    city: "Москва",
    address: null,
    timeZone: "Europe/Moscow",
    active: true,
    sortOrder: 10,
  },
] as const

/** Test-only browser route; production code still calls the gateway API. */
export async function installWarehouseRouteFixture(page: Page) {
  await page.route(
    /\/api\/warehouse\/v1\/warehouses(?:\?.*)?$/,
    async (route) => {
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
        body: JSON.stringify(warehouses),
      })
    }
  )
}
