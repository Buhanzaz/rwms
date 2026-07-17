import { expect, test } from "@playwright/test"

import { installWarehouseRouteFixture } from "./fixtures/warehouse-route-fixture"

const warehouseId = "00000000-0000-0000-0000-000000000001"

const completedSession = {
  id: "00000000-0000-0000-0000-000000000701",
  sessionRevision: 5,
  warehouseId,
  warehouseVersion: 2,
  warehouseTimeZone: "Europe/Moscow",
  businessDate: "2026-07-17",
  lifecycle: "COMPLETED",
  expectedCount: 1,
  findingCount: 1,
  inspectedCount: 1,
  startedAt: "2026-07-17T08:00:00Z",
  terminalAt: "2026-07-17T09:00:00Z",
  publicationState: "SUCCEEDED",
} as const

const statistics = {
  expectedCount: 1,
  inspectedCount: 1,
  missingCount: 0,
  readyCount: 1,
  withWorkCount: 0,
  addedCount: 0,
  unexpectedExistingCount: 0,
  conflictCount: 0,
  workLineCount: 0,
  materialLineCount: 0,
  workTotalMinor: 0,
  materialTotalMinor: 0,
  grandTotalMinor: 0,
  roundingAdjustmentMinor: 0,
  normativeMinutes: "0",
  durationSeconds: 3600,
  aggregateLines: [],
} as const

test.beforeEach(async ({ page }) => {
  await installWarehouseRouteFixture(page)
})

test("inventory production adapter renders server history responsively", async ({
  page,
}) => {
  const requestedPaths: string[] = []
  await page.route("**/api/inventory/v1/**", async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    requestedPaths.push(`${url.pathname}${url.search}`)

    if (
      request.method() === "GET" &&
      url.pathname === "/api/inventory/v1/sessions/active"
    ) {
      await route.fulfill({
        status: 404,
        contentType: "application/problem+json",
        body: JSON.stringify({ detail: "Активная сессия не найдена" }),
      })
      return
    }

    if (
      request.method() === "GET" &&
      url.pathname === "/api/inventory/v1/sessions"
    ) {
      const page = Number(url.searchParams.get("page") ?? "0")
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          content: [
            {
              ...completedSession,
              id:
                page === 0
                  ? completedSession.id
                  : "00000000-0000-0000-0000-000000000702",
            },
          ],
          page: { page, size: 50, totalElements: 2, totalPages: 2 },
        }),
      })
      return
    }

    if (
      request.method() === "GET" &&
      url.pathname === "/api/inventory/v1/statistics/summary"
    ) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ sessionCount: 1, statistics }),
      })
      return
    }

    await route.fulfill({ status: 404, body: "Unmatched inventory fixture" })
  })

  await page.goto("/inventory")
  const main = page.getByRole("main")
  await expect(
    main.getByText("Активной сессии для склада «СПБ» нет.")
  ).toBeVisible()

  await main.getByRole("button", { name: "История" }).click()
  await expect(page).toHaveURL(/\/inventory\/history$/)
  await expect(main.getByText("Проверено: 1", { exact: true })).toBeVisible()
  await expect(
    main.locator("button:visible").filter({ hasText: /^Открыть$/ })
  ).toHaveCount(1)
  await expect(main.getByText("Страница 1 из 2")).toBeVisible()
  await main.getByRole("button", { name: "Вперёд" }).click()
  await expect(main.getByText("Страница 2 из 2")).toBeVisible()

  expect(
    requestedPaths.some((path) =>
      path.startsWith(
        `/api/inventory/v1/sessions/active?warehouseId=${warehouseId}`
      )
    )
  ).toBe(true)
  expect(
    requestedPaths.some((path) =>
      path.startsWith(
        `/api/inventory/v1/sessions?warehouseId=${warehouseId}&page=1&size=50`
      )
    )
  ).toBe(true)
  expect(
    requestedPaths.some((path) =>
      path.startsWith(
        `/api/inventory/v1/sessions?warehouseId=${warehouseId}&page=0&size=50`
      )
    )
  ).toBe(true)
  expect(
    requestedPaths.some((path) =>
      path.startsWith(
        `/api/inventory/v1/statistics/summary?warehouseId=${warehouseId}`
      )
    )
  ).toBe(true)

  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
  }))
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport)
})

test("inventory stays fail-closed when the service rejects access", async ({
  page,
}) => {
  await page.route("**/api/inventory/v1/**", async (route) => {
    await route.fulfill({
      status: 401,
      contentType: "application/problem+json",
      body: JSON.stringify({ detail: "Токен отклонён inventory-service" }),
    })
  })

  await page.goto("/inventory")
  const main = page.getByRole("main")
  await expect(main.getByText("Инвентаризация недоступна")).toBeVisible()
  await expect(main.getByText("Токен отклонён inventory-service")).toBeVisible()
  await expect(
    main.getByRole("button", { name: "Начать инвентаризацию" })
  ).toHaveCount(0)
})

test("NOT_FOUND retries one stable create intent", async ({ page }) => {
  const inventoryId = "00000000-0000-4000-8000-000000000710"
  const finding = {
    id: "00000000-0000-4000-8000-000000000711",
    inventoryId,
    findingRevision: 0,
    origin: "ADDED_USED",
    inspection: "NOT_INSPECTED",
    reconciliation: "MATCHED",
    assetId: "00000000-0000-4000-8000-000000000712",
    assetVersion: 0,
    displayCanonicalNumber: "Б-77",
    identityMatchKey: "Б-77",
    passportObservation: { presence: "ABSENT", value: null },
    equipmentObservation: { presence: "ABSENT", value: null },
    mutationState: "IDLE",
    planFingerprintSha256: null,
    expectedSnapshot: null,
    frozenPlan: null,
    media: [],
    publication: null,
  }
  const session = {
    ...completedSession,
    id: inventoryId,
    lifecycle: "ACTIVE",
    terminalAt: null,
    publicationState: "NOT_REQUESTED",
    findingCount: 0,
    inspectedCount: 0,
    statistics: null,
    cancellation: null,
  }
  const createRequests: Array<{
    path: string
    idempotencyKey: string | undefined
  }> = []
  let createAttempt = 0

  await page.route("**/api/media/v1/assets?**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ items: [], next: null }),
    })
  })
  await page.route("**/api/inventory/v1/**", async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (
      request.method() === "GET" &&
      (url.pathname === "/api/inventory/v1/sessions/active" ||
        url.pathname === `/api/inventory/v1/sessions/${inventoryId}`)
    ) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(session),
      })
      return
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/inventory/v1/sessions/${inventoryId}/findings`
    ) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          content: createAttempt > 1 ? [finding] : [],
          page: {
            page: 0,
            size: 200,
            totalElements: createAttempt > 1 ? 1 : 0,
            totalPages: createAttempt > 1 ? 1 : 0,
          },
        }),
      })
      return
    }
    if (
      request.method() === "POST" &&
      url.pathname ===
        `/api/inventory/v1/sessions/${inventoryId}/number-resolutions`
    ) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          displayCanonicalNumber: "Б-77",
          identityMatchKey: "Б-77",
          outcome: "NOT_FOUND",
          finding: null,
        }),
      })
      return
    }
    if (
      request.method() === "POST" &&
      /\/findings\/[0-9a-f-]+\/assets$/.test(url.pathname)
    ) {
      createAttempt += 1
      createRequests.push({
        path: url.pathname,
        idempotencyKey: request.headers()["idempotency-key"],
      })
      await route.fulfill(
        createAttempt === 1
          ? {
              status: 503,
              contentType: "application/problem+json",
              body: JSON.stringify({
                detail: "asset-service временно недоступен",
              }),
            }
          : {
              status: 200,
              contentType: "application/json",
              body: JSON.stringify(finding),
            }
      )
      return
    }
    await route.fulfill({ status: 404, body: "Unmatched inventory fixture" })
  })

  await page.goto("/inventory")
  await expect(page).toHaveURL(new RegExp(`/inventory/${inventoryId}$`))
  const main = page.getByRole("main")
  await main.getByLabel("Номер").fill("Б-77")
  await main.getByRole("button", { name: "Проверить номер" }).click()
  await expect(main.getByText("Создать отсутствующую бытовку")).toBeVisible()

  await main.getByRole("button", { name: "Создать и прикрепить" }).click()
  await expect(
    main.getByText("asset-service временно недоступен")
  ).toBeVisible()
  await main.getByRole("button", { name: "Создать и прикрепить" }).click()
  await expect(main.getByText("Ревизия результата: 0")).toBeVisible()

  expect(createRequests).toHaveLength(2)
  expect(createRequests[0]).toEqual(createRequests[1])
  expect(createRequests[0].idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
})
