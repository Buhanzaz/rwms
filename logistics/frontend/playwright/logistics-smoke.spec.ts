import { expect, test } from '@playwright/test';
import { z } from 'zod';

const optimizationRunSchema = z.object({ plan_id: z.string().nullable() });
const planScheduleSchema = z.object({
  cycles: z.array(z.object({
    segments: z.array(z.object({ departure_at: z.string(), arrival_at: z.string() })),
  })),
});

let createdScenarioId: string | undefined;

test.afterEach(async ({ request }) => {
  if (!createdScenarioId) return;
  const cleanup = await request.delete(`/api/scenarios/${createdScenarioId}`);
  expect(cleanup.ok()).toBe(true);
  createdScenarioId = undefined;
});

test('создание, demo-план, симуляция, задержка и экспорт', async ({ page, request }) => {
  await page.goto('/');

  await expect(page.getByTestId('map-stage')).toBeVisible();
  const sidebar = page.getByLabel('Разделы логистического стенда');
  await sidebar.getByRole('button', { name: /Сценарий/ }).click();
  await page.getByRole('button', { name: /Новый сценарий/ }).click();
  const scenarioName = `E2E логистика ${Date.now()}`;
  await page.getByLabel('Название').fill(scenarioName);
  await page.getByLabel('Описание').fill('Воспроизводимый Playwright smoke scenario');
  await page.getByRole('dialog').getByRole('button', { name: 'Сохранить' }).click();
  const scenarioSelect = page.getByLabel('Текущий сценарий');
  await expect(scenarioSelect.locator('option:checked')).toHaveText(scenarioName);
  createdScenarioId = await scenarioSelect.inputValue();

  await page.getByRole('button', { name: /Demo scenario/ }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Создать demo' }).click();
  await expect(page.getByText('Demo scenario готов')).toBeVisible();
  await expect(page.getByRole('button', { name: /Склад 1/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /Зоны 4/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /Смены 3/ })).toBeVisible();

  // Demo is the deterministic equivalent of the manual setup: one depot,
  // four polygon zones and their links, three drivers/vehicles/shifts,
  // paired deliveries and paired pickups.
  await sidebar.getByRole('button', { name: /Склад/ }).click();
  await expect(page.getByText('Основной склад')).toBeVisible();
  await sidebar.getByRole('button', { name: /^Зоны/ }).click();
  await expect(page.locator('.entity-card')).toHaveCount(4);
  await sidebar.getByRole('button', { name: /Связи зон/ }).click();
  await expect(page.locator('.relation-cell--allowed')).toHaveCount(8);
  await sidebar.getByRole('button', { name: /Водители/ }).click();
  await expect(page.locator('.entity-card')).toHaveCount(3);
  await sidebar.getByRole('button', { name: /Машины/ }).click();
  await expect(page.locator('.entity-card')).toHaveCount(3);
  await sidebar.getByRole('button', { name: /Смены/ }).click();
  await expect(page.locator('.entity-card')).toHaveCount(3);
  await sidebar.getByRole('button', { name: /Заявки/ }).click();
  await expect(page.getByText(/D ·/).first()).toBeVisible();
  await expect(page.getByText(/P ·/).first()).toBeVisible();

  const generationResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST' && response.url().endsWith(`/api/scenarios/${createdScenarioId}/plans/generate`),
  );
  await page.getByRole('button', { name: /Построить маршруты/ }).click();
  const generationResponse = await generationResponsePromise;
  const { plan_id: planId } = optimizationRunSchema.parse(await generationResponse.json());
  if (!planId) throw new Error('Optimization run did not return a route plan id');
  const progress = page.getByTestId('optimization-progress');
  if (await progress.isVisible().catch(() => false)) await expect(progress).toBeHidden({ timeout: 30_000 });
  await sidebar.getByRole('button', { name: /Маршруты/ }).click();
  await expect(page.getByTestId('driver-route').first()).toBeVisible();
  const canonicalCycle = page
    .locator('[data-testid^="cycle-"]')
    .filter({ hasText: 'Загрузка: 2 → 1 → 0 → 1 → 2 → 0' })
    .first();
  await expect(canonicalCycle).toBeVisible();
  await expect(canonicalCycle.locator('.stop-row__type')).toHaveText(['С', 'D', 'D', 'P', 'P', 'С']);

  // Exercise the real dnd-kit keyboard sensor: swap the two delivery stops,
  // then require the backend-validated plan version returned by the mutation.
  const firstDelivery = canonicalCycle.locator('.stop-row[role="button"]').nth(1);
  await firstDelivery.focus();
  await firstDelivery.press('Space');
  await firstDelivery.press('ArrowDown');
  await firstDelivery.press('Space');
  await expect(page.getByText('Изменение проверено и применено')).toBeVisible();
  await expect(page.getByText('План · версия 2')).toBeVisible();

  await page.getByRole('button', { name: 'Проверить' }).click();
  await expect(page.getByText('Проверка плана завершена')).toBeVisible();
  await page.getByRole('button', { name: 'Симуляция' }).click();
  await page.getByRole('button', { name: 'Запустить симуляцию' }).click();
  await page.getByRole('button', { name: 'Пауза' }).click();
  await page.getByLabel('Скорость симуляции').selectOption('20');
  const slider = page.getByLabel('Время симуляции');
  const min = Number(await slider.getAttribute('min'));
  const max = Number(await slider.getAttribute('max'));
  const planResponse = await request.get(`/api/plans/${planId}`);
  expect(planResponse.ok()).toBe(true);
  const planSchedule = planScheduleSchema.parse(await planResponse.json());
  const movingLeg = planSchedule.cycles
    .flatMap((cycle) => cycle.segments)
    .map((leg) => ({ ...leg, duration: Date.parse(leg.arrival_at) - Date.parse(leg.departure_at) }))
    .filter((leg) => leg.duration > 2_000)
    .sort((left, right) => right.duration - left.duration)[0];
  if (!movingLeg) throw new Error('Generated route plan has no travel leg');
  const travelMidpoint = Math.round(
    ((Date.parse(movingLeg.departure_at) + Date.parse(movingLeg.arrival_at)) / 2) / 1000,
  ) * 1000;
  expect(travelMidpoint).toBeGreaterThan(min);
  expect(travelMidpoint).toBeLessThan(max);
  await slider.evaluate((element, timestamp) => {
    const input = element as HTMLInputElement;
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, String(timestamp));
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }, travelMidpoint);
  await expect(slider).toHaveValue(String(travelMidpoint));
  await expect(page.getByTestId('simulation-current-time')).not.toHaveText('08:00:00');
  await expect(page.locator('.map-marker--truck').first()).toBeVisible();
  const movingTruck = page.locator(
    '.map-marker--truck[aria-label*="DRIVING"], .map-marker--truck[aria-label*="RETURNING"]',
  ).first();
  await expect(movingTruck).toBeVisible();
  await expect.poll(async () => {
    const truckBox = await movingTruck.boundingBox();
    const warehouseBox = await page.locator('.map-marker--warehouse').first().boundingBox();
    if (!truckBox || !warehouseBox) return false;
    const truckCenter = [truckBox.x + truckBox.width / 2, truckBox.y + truckBox.height / 2];
    const warehouseCenter = [warehouseBox.x + warehouseBox.width / 2, warehouseBox.y + warehouseBox.height / 2];
    return Math.hypot(truckCenter[0] - warehouseCenter[0], truckCenter[1] - warehouseCenter[1]) > 5;
  }).toBe(true);

  await page.getByRole('button', { name: '+ Задержка' }).first().click();
  await page.getByLabel('Задержка, мин').fill('30');
  await page.getByRole('dialog').getByRole('button', { name: 'Применить к симуляции' }).click();
  await expect(page.getByText('Задержка применена к симуляции')).toBeVisible();
  await expect(page.getByText(/расписание сдвинуто на 30 мин/)).toBeVisible();

  await page.getByRole('button', { name: 'Редактор' }).click();
  await sidebar.getByRole('button', { name: /Сценарий/ }).click();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: /Экспорт JSON/ }).click();
  expect((await download).suggestedFilename()).toMatch(/^logistics-.*\.json$/);

});
