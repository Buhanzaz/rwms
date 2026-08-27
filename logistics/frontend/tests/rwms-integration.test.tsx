import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, type DriverInput, type RwmsSyncResult, type VehicleInput, type WarehouseInput } from '../src/api/client';
import { CatalogDialog, WarehouseDialog } from '../src/components/EntityDialogs';
import { EMPTY_METRICS } from '../src/domain/defaults';
import type { LogisticsRequest, RoutePlan, Warehouse } from '../src/domain/types';
import { RwmsIntegrationDialog } from '../src/features/rwms/RwmsIntegrationDialog';

const externalWarehouseId = '35b8738c-d405-4c42-ac2b-e9f4a26d7c19';
const externalWorkerId = '2de75998-c1f9-4d0f-b5d0-59bcb75cf103';

const linkedWarehouse: Warehouse = {
  id: 'warehouse-id',
  scenario_id: 'scenario-id',
  external_warehouse_id: externalWarehouseId,
  name: 'Основной склад',
  latitude: 55.75,
  longitude: 37.61,
  loading_minutes: 30,
  unloading_minutes: 20,
  turnaround_minutes: 15,
  working_day_start: '08:00',
  working_day_end: '20:00',
};

const generatedPlan: RoutePlan = {
  id: 'plan-id',
  scenario_id: 'scenario-id',
  warehouse_id: 'warehouse-id',
  date: '2026-08-25',
  version: 7,
  status: 'GENERATED',
  score: 42,
  created_at: '2026-08-23T08:00:00Z',
  updated_at: '2026-08-23T08:00:00Z',
  driver_routes: [],
  unassigned: [],
  metrics: { ...EMPTY_METRICS },
};

function sourceRequest(
  id: string,
  sourceSystem: string | null,
  name = 'Доставка из RWMS',
): LogisticsRequest {
  return {
    id,
    scenario_id: 'scenario-id',
    source_system: sourceSystem,
    external_id: sourceSystem === 'RWMS' ? `external-${id}` : null,
    type: 'DELIVERY',
    name,
    address_label: 'Москва',
    latitude: 55.75,
    longitude: 37.61,
    quantity: 1,
    service_minutes: 30,
    priority: 0,
    status: 'READY',
    zone_id: null,
    zone_version: null,
    split_allowed: false,
    notes: '',
    created_at: '2026-08-23T08:00:00Z',
    updated_at: '2026-08-23T08:00:00Z',
    scheduled_date: null,
    date_options: [],
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((fulfill) => { resolve = fulfill; });
  return { promise, resolve };
}

function integrationDialog(overrides: Partial<ComponentProps<typeof RwmsIntegrationDialog>> = {}) {
  const onSync = vi.fn<ComponentProps<typeof RwmsIntegrationDialog>['onSync']>(() => Promise.resolve({
    imported: 0,
    updated: 0,
    skipped: 0,
    failures: [],
  }));
  const onApply = vi.fn<ComponentProps<typeof RwmsIntegrationDialog>['onApply']>(() => Promise.resolve({
    applied: [],
    rejected: [],
  }));
  const onStatus = vi.fn<NonNullable<ComponentProps<typeof RwmsIntegrationDialog>['onStatus']>>(() => Promise.resolve({
    plan_id: generatedPlan.id,
    plan_version: generatedPlan.version,
    tasks: [],
  }));
  const props: ComponentProps<typeof RwmsIntegrationDialog> = {
    scenarioId: 'scenario-id',
    planningDate: '2026-08-25',
    warehouses: [linkedWarehouse],
    plan: generatedPlan,
    busy: false,
    onClose: () => undefined,
    onSync,
    onApply,
    onStatus,
    ...overrides,
  };
  return { props, onSync, onApply, onStatus };
}

describe('RWMS entity linkage', () => {
  it('submits the external RWMS warehouse UUID from the warehouse form', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: WarehouseInput) => Promise<void>>(() => Promise.resolve());
    render(<WarehouseDialog busy={false} onClose={() => undefined} onSubmit={submit} />);

    await user.type(screen.getByLabelText('UUID склада в RWMS'), externalWarehouseId);
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({ external_warehouse_id: externalWarehouseId });
  });

  it('submits the external RWMS worker UUID only for a driver', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: DriverInput | VehicleInput) => Promise<void>>(() => Promise.resolve());
    render(<CatalogDialog kind="driver" busy={false} onClose={() => undefined} onSubmit={submit} />);

    await user.type(screen.getByLabelText('Название'), 'Водитель RWMS');
    await user.type(screen.getByLabelText('UUID сотрудника в RWMS'), externalWorkerId);
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({
      name: 'Водитель RWMS',
      external_worker_id: externalWorkerId,
    });
  });
});

describe('RWMS operator actions', () => {
  it('shows synchronization progress, counts, and every per-order failure', async () => {
    const user = userEvent.setup();
    const pending = deferred<RwmsSyncResult>();
    const onSync = vi.fn<ComponentProps<typeof RwmsIntegrationDialog>['onSync']>(() => pending.promise);
    const { props } = integrationDialog({ onSync });
    render(<RwmsIntegrationDialog {...props} />);

    await user.click(screen.getByRole('button', { name: 'Синхронизировать заявки' }));
    expect(onSync).toHaveBeenCalledWith(externalWarehouseId, '2026-08-25');
    expect(screen.getByText('Синхронизация…')).toBeVisible();

    pending.resolve({
      imported: 3,
      updated: 2,
      skipped: 1,
      failures: [{
        order_id: '57ca2992-702f-4cc7-b478-6a2c64146241',
        code: 'COORDINATES_REQUIRED',
        message: 'Для заказа нет координат',
      }],
    });

    const result = await screen.findByLabelText('Результат синхронизации RWMS');
    expect(within(result).getByText(/добавлено/)).toHaveTextContent('3 добавлено');
    expect(within(result).getByText(/обновлено/)).toHaveTextContent('2 обновлено');
    expect(within(result).getByText(/без изменений/)).toHaveTextContent('1 без изменений');
    expect(within(result).getByText(/ошибок/)).toHaveTextContent('1 ошибок');
    expect(within(result).getByText('COORDINATES_REQUIRED')).toBeVisible();
    expect(within(result).getByText('Для заказа нет координат')).toBeVisible();
  });

  it('keeps RWMS_SYNC_DISABLED visible instead of reporting a false success', async () => {
    const user = userEvent.setup();
    const onSync = vi.fn<ComponentProps<typeof RwmsIntegrationDialog>['onSync']>(() => Promise.reject(
      new ApiError(503, { code: 'RWMS_SYNC_DISABLED', detail: 'Интеграция отключена' }, 'disabled'),
    ));
    const { props } = integrationDialog({ onSync });
    render(<RwmsIntegrationDialog {...props} />);

    await user.click(screen.getByRole('button', { name: 'Синхронизировать заявки' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('RWMS_SYNC_DISABLED: Интеграция отключена');
    expect(screen.queryByLabelText('Результат синхронизации RWMS')).not.toBeInTheDocument();
  });

  it('applies the exact displayed plan version and shows applied and rejected assignments', async () => {
    const user = userEvent.setup();
    const onApply = vi.fn<ComponentProps<typeof RwmsIntegrationDialog>['onApply']>(() => Promise.resolve({
      applied: [{
        order_id: '57ca2992-702f-4cc7-b478-6a2c64146241',
        document_id: '41b0e409-53d2-40af-a68c-d4de750005cc',
        replayed: false,
      }],
      rejected: [{
        order_id: '927a16a4-b40c-42a6-8f4d-d3b08eb0a1df',
        code: 'ORDER_VERSION_CONFLICT',
        message: 'Заказ уже изменён',
      }],
    }));
    const { props } = integrationDialog({ onApply });
    render(<RwmsIntegrationDialog {...props} />);

    expect(screen.getByText('План 2026-08-25, версия 7. Backend проверит эту версию перед отправкой.')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Передать план в RWMS' }));

    expect(onApply).toHaveBeenCalledWith('plan-id', 7, []);
    const result = await screen.findByLabelText('Результат отправки плана в RWMS');
    expect(within(result).getByText('ORDER_VERSION_CONFLICT')).toBeVisible();
    expect(within(result).getByText('Заказ уже изменён')).toBeVisible();
    expect(within(result).getByText('применено', { selector: '.badge' })).toBeVisible();
  });

  it('publishes only explicitly selected RWMS deliveries and hides scenario-local work', async () => {
    const user = userEvent.setup();
    const onApply = vi.fn<ComponentProps<typeof RwmsIntegrationDialog>['onApply']>(() => Promise.resolve({
      applied: [],
      rejected: [],
    }));
    const sharedTaskId = 'ef483cae-25f4-4c6a-86cf-e50c6fb4bb3c';
    const plan: RoutePlan = {
      ...generatedPlan,
      unassigned: [{
        task: {
          id: sharedTaskId,
          request_id: 'request-id',
          part_number: 1,
          quantity: 2,
          type: 'DELIVERY',
          latitude: 55.75,
          longitude: 37.61,
          zone_id: null,
          zone_version: null,
          service_minutes: 30,
          priority: 0,
          status: 'READY',
        },
        request: sourceRequest('request-id', 'RWMS'),
        reason_codes: ['NO_SHIFT_CAPACITY'],
        reasons: ['не хватило смены'],
        recommendations: ['Опубликовать свободным водителям'],
      }, {
        task: {
          id: 'generated-task-id',
          request_id: 'generated-request-id',
          part_number: 2,
          quantity: 1,
          type: 'DELIVERY',
          latitude: 55.76,
          longitude: 37.62,
          zone_id: null,
          zone_version: null,
          service_minutes: 30,
          priority: 0,
          status: 'READY',
        },
        request: sourceRequest('generated-request-id', 'SIMULATOR_GENERATOR', 'Сгенерированная доставка'),
        reason_codes: ['NO_SHIFT_CAPACITY'],
        reasons: ['не хватило смены'],
        recommendations: ['Оставить в сценарии'],
      }, {
        task: {
          id: 'manual-task-id',
          request_id: 'manual-request-id',
          part_number: 3,
          quantity: 1,
          type: 'DELIVERY',
          latitude: 55.77,
          longitude: 37.63,
          zone_id: null,
          zone_version: null,
          service_minutes: 30,
          priority: 0,
          status: 'READY',
        },
        request: sourceRequest('manual-request-id', null, 'Ручная доставка'),
        reason_codes: ['NO_SHIFT_CAPACITY'],
        reasons: ['не хватило смены'],
        recommendations: ['Оставить в сценарии'],
      }],
    };
    const { props } = integrationDialog({ onApply, plan });
    render(<RwmsIntegrationDialog {...props} />);

    await user.click(screen.getByRole('checkbox', { name: /Доставка из RWMS · 2 шт. · не хватило смены/ }));
    expect(screen.queryByRole('checkbox', { name: /Сгенерированная доставка/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /Ручная доставка/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Передать план в RWMS' }));

    expect(onApply).toHaveBeenCalledWith('plan-id', 7, [sharedTaskId]);
  });

  it('loads and refreshes publication statuses while preventing duplicate publication', async () => {
    const user = userEvent.setup();
    const unpublishedTaskId = 'ef483cae-25f4-4c6a-86cf-e50c6fb4bb3c';
    const publishedTaskId = '8f529c9d-6be0-413f-bb19-e1f649ba315d';
    const claimedTaskId = '215a13b2-73ca-4e70-8369-15b8af163859';
    const plan: RoutePlan = {
      ...generatedPlan,
      unassigned: [unpublishedTaskId, publishedTaskId, claimedTaskId].map((taskId, index) => ({
        task: {
          id: taskId,
          request_id: `request-${index + 1}`,
          part_number: index + 1,
          quantity: 1,
          type: 'DELIVERY' as const,
          latitude: 55.75,
          longitude: 37.61,
          zone_id: null,
          zone_version: null,
          service_minutes: 30,
          priority: 0,
          status: 'READY' as const,
        },
        request: sourceRequest(`request-${index + 1}`, 'RWMS', `Задача ${index + 1}`),
        reason_codes: ['NO_SHIFT_CAPACITY'],
        reasons: ['не хватило смены'],
        recommendations: ['Опубликовать свободным водителям'],
      })),
    };
    const onStatus = vi.fn<NonNullable<ComponentProps<typeof RwmsIntegrationDialog>['onStatus']>>(() => Promise.resolve({
      plan_id: plan.id,
      plan_version: plan.version,
      tasks: [{
        task_id: publishedTaskId,
        request_id: 'request-2',
        order_id: 'ad581f61-4c8c-4809-9916-f433eedcb3ba',
        document_id: '7b560f9f-7690-4c54-af58-138840182411',
        driver_audience_mode: 'WAREHOUSE_DRIVERS',
        driver_worker_id: null,
        driver_name: 'Свободная доставка',
        task_state: 'SCHEDULED',
      }, {
        task_id: claimedTaskId,
        request_id: 'request-3',
        order_id: '3e5e9013-8e93-4c4e-9eaa-727e4ecf557d',
        document_id: '1d12025b-c2ab-4200-be63-a3226352fd3e',
        driver_audience_mode: 'ASSIGNED_DRIVER',
        driver_worker_id: externalWorkerId,
        driver_name: 'Иван Петров',
        task_state: 'SCHEDULED',
      }],
    }));
    const onApply = vi.fn<ComponentProps<typeof RwmsIntegrationDialog>['onApply']>(() => Promise.resolve({
      applied: [],
      rejected: [],
    }));
    const { props } = integrationDialog({ plan, onStatus, onApply });
    render(<RwmsIntegrationDialog {...props} />);

    await waitFor(() => expect(onStatus).toHaveBeenCalledWith('plan-id', 7));
    const unpublished = await screen.findByRole('checkbox', { name: /Задача 1.*не опубликовано/ });
    const published = screen.getByRole('checkbox', { name: /Задача 2.*опубликовано свободным водителям/ });
    const claimed = screen.getByRole('checkbox', { name: /Задача 3.*забрал Иван Петров/ });
    expect(unpublished).toBeEnabled();
    expect(published).toBeDisabled();
    expect(claimed).toBeDisabled();

    await user.click(unpublished);
    await user.click(screen.getByRole('button', { name: 'Передать план в RWMS' }));
    expect(onApply).toHaveBeenCalledWith('plan-id', 7, [unpublishedTaskId]);
    await waitFor(() => expect(onStatus).toHaveBeenCalledTimes(2));

    await user.click(screen.getByRole('button', { name: 'Обновить статусы' }));
    await waitFor(() => expect(onStatus).toHaveBeenCalledTimes(3));
  });

  it('keeps a status refresh failure visible inline', async () => {
    const taskId = 'ef483cae-25f4-4c6a-86cf-e50c6fb4bb3c';
    const plan: RoutePlan = {
      ...generatedPlan,
      unassigned: [{
        task: {
          id: taskId,
          request_id: 'request-id',
          part_number: 1,
          quantity: 1,
          type: 'DELIVERY',
          latitude: 55.75,
          longitude: 37.61,
          zone_id: null,
          zone_version: null,
          service_minutes: 30,
          priority: 0,
          status: 'READY',
        },
        request: sourceRequest('request-id', 'RWMS'),
        reason_codes: ['NO_SHIFT_CAPACITY'],
        reasons: ['не хватило смены'],
        recommendations: ['Опубликовать свободным водителям'],
      }],
    };
    const onStatus = vi.fn<NonNullable<ComponentProps<typeof RwmsIntegrationDialog>['onStatus']>>(() => Promise.reject(
      new ApiError(502, { code: 'RWMS_REQUEST_FAILED', detail: 'RWMS недоступен' }, 'failed'),
    ));
    const { props } = integrationDialog({ onStatus, plan });
    render(<RwmsIntegrationDialog {...props} />);

    const error = await screen.findByRole('alert');
    expect(error).toHaveTextContent('Статусы RWMS не обновлены');
    expect(error).toHaveTextContent('RWMS_REQUEST_FAILED: RWMS недоступен');
    expect(screen.getByRole('checkbox', { name: /не удалось проверить/ })).toBeDisabled();
  });
});
