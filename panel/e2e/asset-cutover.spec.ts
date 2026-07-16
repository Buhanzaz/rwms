import { expect, test } from "@playwright/test"

import { installAssetRouteFixture } from "./fixtures/asset-route-fixture"
import { installWarehouseRouteFixture } from "./fixtures/warehouse-route-fixture"

test.beforeEach(async ({ page }) => {
  await installWarehouseRouteFixture(page)
  await installAssetRouteFixture(page)
})

test("asset HTTP cutover renders registry, passport, equipment, dispositions and settings", async ({
  page,
}) => {
  await page.goto("/warehouse")
  await expect(page.getByText("Б-101", { exact: true })).toBeVisible()
  await expect(page.getByText("2 поз.")).toBeVisible()

  await page.getByRole("link", { name: "Открыть" }).click()
  await expect(
    page.locator('[data-slot="card-title"]').filter({ hasText: /^Б-101$/ })
  ).toBeVisible()
  await page.getByRole("tab", { name: "Наполнение" }).click()
  await expect(page.getByText("CHAIR-01")).toBeVisible()
  await page.getByRole("tab", { name: "Комментарии и заметки" }).click()
  await expect(page.getByText("Только service-local заметка")).toBeVisible()
  await page.getByRole("tab", { name: "Фото" }).click()
  await expect(
    page.getByText("asset-panel не подменяет фотографии browser storage")
  ).toBeVisible()

  await page.goto("/equipment")
  await expect(
    page
      .getByRole("main")
      .getByText("Дополнительное оборудование", { exact: true })
  ).toBeVisible()
  await expect(page.getByText("Доступно")).toBeVisible()
  await expect(page.getByText("7", { exact: true })).toBeVisible()

  await page.goto("/write-offs/equipment")
  await expect(
    page
      .getByRole("main")
      .getByText("Списание дополнительного оборудования", { exact: true })
  ).toBeVisible()
  await expect(page.getByText("CHAIR-01")).toBeVisible()

  await page.goto("/settings/assets")
  await expect(
    page.getByRole("main").getByText("Каталог оборудования", { exact: true })
  ).toBeVisible()
  await expect(
    page.getByRole("main").getByText("Классификаторы", { exact: true })
  ).toBeVisible()
  await expect(page.getByText("Офис", { exact: true })).toBeVisible()

  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
  }))
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport)
})
