import { expect, test } from "@playwright/test"

import { installWarehouseRouteFixture } from "./fixtures/warehouse-route-fixture"

test.beforeEach(async ({ page }) => {
  await installWarehouseRouteFixture(page)
})

test("system administrator reaches the warehouse settings through the HTTP route fixture", async ({
  page,
}, testInfo) => {
  await page.goto("/settings/warehouses")

  await expect(page.getByRole("heading", { name: "Склады" })).toBeVisible()
  await expect(
    page.getByRole("button", { name: "Создать склад" })
  ).toBeVisible()

  if (testInfo.project.name === "mobile") {
    await expect(
      page.getByRole("paragraph").filter({ hasText: "СПБ" })
    ).toBeVisible()
    await expect(
      page.getByText("Санкт-Петербург · Europe/Moscow · порядок —")
    ).toBeVisible()
  } else {
    await expect(page.getByRole("cell", { name: "СПБ" })).toBeVisible()
    await expect(page.getByRole("columnheader", { name: "Код" })).toBeVisible()
  }
})
