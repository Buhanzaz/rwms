import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { z } from 'zod';

const warehouseSchema = z.object({
  id: z.string().uuid(),
  name: z.string(),
  city: z.string().nullable(),
});

const workloadSchema = z.object({
  warehouse_id: z.string().uuid(),
  start_date: z.string(),
  end_date: z.string(),
  created_requests: z.number().int(),
  created_deliveries: z.number().int(),
  created_pickups: z.number().int(),
  daily_counts: z.array(z.object({
    date: z.string(),
    deliveries: z.number().int(),
    pickups: z.number().int(),
  })),
  auto_plan_ids: z.array(z.string().uuid()).optional(),
});

const stopTypeSchema = z.enum(['DEPOT_LOAD', 'DELIVERY', 'PICKUP', 'DEPOT_UNLOAD', 'DEPOT_RETURN']);
const planSchema = z.object({
  id: z.string().uuid(),
  warehouse_id: z.string().uuid(),
  cycles: z.array(z.object({
    stops: z.array(z.object({
      task_id: z.string().uuid().nullable(),
      stop_type: stopTypeSchema,
      load_before: z.number().int(),
      load_after: z.number().int(),
    })),
  })),
});

async function availableWarehouses(request: APIRequestContext) {
  const response = await request.get('/api/warehouses');
  expect(response.ok()).toBe(true);
  return z.array(warehouseSchema).parse(await response.json());
}

async function selectWarehouse(page: Page, warehouse: z.infer<typeof warehouseSchema>) {
  const picker = page.getByRole('combobox', { name: 'Текущий склад' });
  await expect(picker).toBeVisible();
  await picker.click();
  const option = page.getByRole('option').filter({ hasText: warehouse.name });
  await expect(option).toBeVisible();
  await option.click();
  await expect(picker).toContainText(warehouse.name);
}

test('рабочая область привязана к складу и не содержит сценарного legacy UI', async ({ page, request }) => {
  const warehouses = await availableWarehouses(request);
  test.skip(warehouses.length === 0, 'Для browser smoke нужен хотя бы один подключённый склад RWMS');
  const warehouse = warehouses[0];

  await page.goto('/');
  await expect(page.getByTestId('map-stage')).toBeVisible();
  await selectWarehouse(page, warehouse);

  await expect(page.getByRole('button', { name: /Сценарий|Demo scenario|Демо/i })).toHaveCount(0);
  await expect(page.getByLabel('Текущий сценарий')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Сегодня' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Завтра' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Обмен с RWMS' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Сохранить план' })).toHaveCount(0);
  await expect(page.getByText('Valhalla · OpenStreetMap · грузовой граф')).toHaveCount(0);
  await expect(page.getByText(/Map mode · MapLibre|Grid mode/)).toHaveCount(0);

  const sidebar = page.getByLabel('Разделы логистического стенда');
  await sidebar.getByRole('button', { name: 'Склад' }).click();
  await expect(page.getByRole('heading', { name: warehouse.name })).toBeVisible();
  await expect(page.getByText('RWMS', { exact: true })).toBeVisible();
  await expect(page.getByText('подключён автоматически')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Тест на 3 дня' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Создать нагрузку' })).toBeVisible();

  await sidebar.getByRole('button', { name: /^Зоны/ }).click();
  await expect(page.getByRole('heading', { name: 'Особые зоны доставки' })).toBeVisible();
  await expect(page.getByText('Вершины', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Код', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Приоритет', { exact: true })).toHaveCount(0);

  await sidebar.getByRole('button', { name: /^Смены/ }).click();
  await expect(page.getByText('Одна запись задаёт повторяющийся рабочий интервал водителя на период внутри месяца.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Добавить смену' })).toBeVisible();

  await sidebar.getByRole('button', { name: /^Доставки/ }).click();
  await expect(page.getByRole('button', { name: 'Доставка', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Вывоз', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: /координат/i })).toHaveCount(0);
  await page.getByRole('button', { name: 'Доставка', exact: true }).click();
  await expect(page.getByText('Инструмент карты включён')).toBeVisible();

  const notificationButton = page.getByRole('button', { name: /Уведомления/ });
  await expect(notificationButton).toBeVisible();
  await notificationButton.click();
  await expect(page.getByLabel('История уведомлений')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Очистить всё' })).toBeEnabled();
  await page.getByRole('button', { name: 'Очистить всё' }).click();
  await expect(page.getByText('История пуста')).toBeVisible();
  await notificationButton.click();

  await sidebar.getByRole('button', { name: 'План дня' }).click();
  await expect(page.getByText('Здесь только итоговый план и его показатели. Время, прицеп и обязательность редактируются в разделе «Доставки».')).toBeVisible();
  await expect(page.getByLabel('Доставка/вывоз с')).toHaveCount(0);
  await expect(page.getByLabel('Машина с прицепом проедет к адресу')).toHaveCount(0);

  await page.getByRole('button', { name: 'Слои карты' }).click();
  const layerMenu = page.getByLabel('Видимость слоёв');
  await expect(layerMenu).toBeVisible();
  await expect(layerMenu.getByLabel('Изохроны склада')).not.toBeChecked();
  await expect(layerMenu.getByLabel('Изохроны задания')).not.toBeChecked();
  const overlaySections = page.locator('.map-overlay-stack > div');
  if (await page.getByLabel('Все участки построенного плана').isVisible().catch(() => false)) {
    await expect(overlaySections.nth(0)).toHaveAttribute('aria-label', 'Все участки построенного плана');
    await expect(overlaySections.nth(1)).toHaveAttribute('aria-label', 'Видимость слоёв');
  }

  await page.getByRole('button', { name: 'Проверить слот' }).click();
  const slotPanel = page.getByLabel('Проверка клиентского слота');
  await expect(slotPanel).toBeVisible();
  await expect(slotPanel.getByLabel('Изохроны склада')).not.toBeChecked();
  await expect(slotPanel.getByLabel('Изохроны задания')).toBeDisabled();
  await expect(slotPanel.getByLabel('Адрес нового клиента')).toHaveAttribute('placeholder', 'Начните вводить адрес');
  await slotPanel.getByRole('button', { name: 'Закрыть проверку слотов' }).click();

  await sidebar.getByRole('button', { name: 'Настройки' }).click();
  await expect(page.getByLabel('Показывать уведомление, секунд')).toHaveValue('8');
});

test('настраиваемая трёхдневная нагрузка автоматически создаёт складские планы', async ({ page, request }) => {
  test.skip(
    process.env.RWMS_E2E_DISPOSABLE_DATABASE !== 'true',
    'Мутационный smoke разрешён только на явно одноразовой базе',
  );
  const warehouses = await availableWarehouses(request);
  test.skip(warehouses.length === 0, 'Для генератора нужен подключённый склад RWMS');
  const warehouse = warehouses[0];
  const generatedDates: string[] = [];

  try {
    await page.goto('/');
    await selectWarehouse(page, warehouse);
    await page.getByLabel('Разделы логистического стенда').getByRole('button', { name: 'Склад' }).click();
    const generatedResponsePromise = page.waitForResponse((response) => (
      response.request().method() === 'POST'
      && response.url().endsWith('/api/warehouses/' + warehouse.id + '/generate-workload')
    ));
    await page.getByRole('button', { name: 'Создать нагрузку' }).click();
    await page.getByLabel('Дней').fill('3');
    await page.getByLabel('Вывозов в день').fill('2');
    await page.getByLabel('Альтернативных дат').fill('1');
    await page.getByRole('button', { name: 'Сгенерировать и заменить нагрузку' }).click();
    const generatedResponse = await generatedResponsePromise;
    expect(generatedResponse.ok()).toBe(true);
    const generated = workloadSchema.parse(await generatedResponse.json());
    generatedDates.push(...generated.daily_counts.map((entry) => entry.date));

    expect(generated.warehouse_id).toBe(warehouse.id);
    expect(generated.daily_counts).toHaveLength(3);
    expect(generated.created_deliveries).toBeGreaterThan(0);
    expect(generated.created_pickups).toBeGreaterThan(0);
    expect(generated.created_requests).toBe(generated.created_deliveries + generated.created_pickups);
    await expect(page.getByLabel('Дата планирования')).toHaveValue(generated.start_date);
    await expect(page.getByText(/Нагрузка (создана|заменена):/)).toBeVisible();

    for (const planId of generated.auto_plan_ids ?? []) {
      const planResponse = await request.get('/api/plans/' + planId);
      expect(planResponse.ok()).toBe(true);
      const plan = planSchema.parse(await planResponse.json());
      expect(plan.warehouse_id).toBe(warehouse.id);
      const taskIds = plan.cycles.flatMap((cycle) => cycle.stops.flatMap((stop) => (
        stop.task_id === null ? [] : [stop.task_id]
      )));
      expect(new Set(taskIds).size).toBe(taskIds.length);
      for (const cycle of plan.cycles) {
        expect(cycle.stops[0]?.stop_type).toBe('DEPOT_LOAD');
        expect(cycle.stops.at(-1)?.stop_type).toBe('DEPOT_RETURN');
        expect(cycle.stops.every((stop) => (
          stop.load_before >= 0
          && stop.load_before <= 2
          && stop.load_after >= 0
          && stop.load_after <= 2
        ))).toBe(true);
      }
    }
  } finally {
    for (const date of generatedDates) {
      const cleanup = await request.delete(
        '/api/warehouses/' + warehouse.id + '/generated-workload?date=' + encodeURIComponent(date),
      );
      expect(cleanup.ok() || cleanup.status() === 404).toBe(true);
    }
  }
});
