import { expect, test } from "@playwright/test"

import { installWarehouseRouteFixture } from "./fixtures/warehouse-route-fixture"

test.beforeEach(async ({ page }) => {
  await installWarehouseRouteFixture(page)
})

test("maintenance workflows render with explicit development fixtures", async ({
  page,
}) => {
  const pageErrors: string[] = []
  page.on("pageerror", (error) => pageErrors.push(error.message))
  const main = page.getByRole("main")

  await page.goto("/estimates")
  await expect(
    main.getByRole("button", { name: "Создать смету" })
  ).toBeVisible()
  await expect(
    main.getByRole("tab", { name: "Требуют доработки" })
  ).toBeVisible()

  await page.goto("/repairs")
  await expect(
    main.getByRole("button", { name: "Создать задание" })
  ).toBeVisible()

  await page.goto("/task-board")
  await expect(main.getByLabel("Поиск по доске задач")).toBeVisible()
  await expect(main.getByText("Текущих: 0", { exact: true })).toBeVisible()

  await page.goto("/acceptance")
  await expect(
    main.getByRole("status").filter({
      hasText: "Нет ремонтов, ожидающих приёмки.",
    })
  ).toBeVisible()

  await page.goto("/write-offs")
  await expect(
    main.getByRole("status").filter({
      hasText: "Списанные бытовки отсутствуют.",
    })
  ).toBeVisible()

  await page.goto("/settings/estimates-repairs")
  await expect(main.getByText("Настройка смет", { exact: true })).toBeVisible()
  await expect(
    main.getByText("Настройка ремонтов", { exact: true })
  ).toBeVisible()

  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
  }))
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport)
  expect(pageErrors).toEqual([])
})
