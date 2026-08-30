import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api, getWarehouseWorkspace } from '../src/api/client';
import { normalizeRoutePlan } from '../src/api/mappers';
import { requestFixture, warehouseFixture, workspaceFixture } from './fixtures';

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function bodyAt(mock: ReturnType<typeof vi.fn>, index: number): unknown {
  const init = mock.mock.calls[index]?.[1] as RequestInit | undefined;
  return typeof init?.body === 'string' ? JSON.parse(init.body) as unknown : undefined;
}

function requestUrl(input: unknown): string {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.href;
  if (input instanceof Request) return input.url;
  throw new TypeError('Expected a request URL');
}

function rawPlan(overrides: Record<string, unknown> = {}) {
  return {
    id: 'plan-1',
    warehouse_id: 'warehouse-1',
    date: '2026-08-30',
    version: 4,
    status: 'GENERATED',
    score: 10,
    created_at: '2026-08-28T08:00:00Z',
    updated_at: '2026-08-28T08:00:00Z',
    cycles: [],
    unassigned_tasks: [],
    metrics: {},
    manually_changed: false,
    ...overrides,
  };
}

beforeEach(() => {
  vi.unstubAllGlobals();
});

describe('warehouse workspace transport', () => {
  it('loads and normalizes the workspace through the automatically refreshing endpoint', async () => {
    const workspace = workspaceFixture({
      requests: [{
        ...requestFixture({ delivery_price_rubles: 28_500, price_isochrone_minutes: 180 }),
        mandatory: undefined as never,
        tasks: [{ ...requestFixture().tasks![0]!, mandatory: undefined as never }],
      }],
    });
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(workspace));
    vi.stubGlobal('fetch', fetchMock);

    const result = await getWarehouseWorkspace('warehouse-1');

    expect(fetchMock.mock.calls.map(([url]) => requestUrl(url))).toEqual([
      '/api/warehouses/warehouse-1/workspace',
    ]);
    expect(result.warehouse.address).toContain('Шоссе Революции');
    expect(result.requests[0]).toMatchObject({ mandatory: false });
    expect(result.requests[0]).toMatchObject({ delivery_price_rubles: 28_500, price_isochrone_minutes: 180 });
    expect(result.requests[0]?.tasks?.[0]).toMatchObject({ mandatory: false });
  });

  it('keeps saved demand visible when automatic refresh reports a partial failure', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        title: 'Синхронизация неполная',
        code: 'RWMS_WORKSPACE_SYNC_INCOMPLETE',
        failures: [{ id: 'request-2' }, { id: 'request-3' }],
      }, 422))
      .mockResolvedValueOnce(jsonResponse(workspaceFixture()));
    vi.stubGlobal('fetch', fetchMock);

    const result = await getWarehouseWorkspace('warehouse-1');

    expect(result.requests).toHaveLength(1);
    expect(result.rwms_refresh_warning).toContain('2');
    expect(fetchMock.mock.calls.map(([url]) => requestUrl(url))).toEqual([
      '/api/warehouses/warehouse-1/workspace',
      '/api/warehouses/warehouse-1/workspace?refresh_rwms=false',
    ]);
  });

  it('lists canonical RWMS warehouses for automatic projection', async () => {
    const warehouse = warehouseFixture();
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse([{ warehouse_id: warehouse.external_warehouse_id, name: warehouse.name, address: warehouse.address, timezone: warehouse.timezone }]));
    vi.stubGlobal('fetch', fetchMock);

    const available = await api.listAvailableWarehouses();

    expect(available[0]?.address).toBe(warehouse.address);
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/warehouses/available');
  });
});

describe('warehouse-owned catalogs', () => {
  it('uses the active warehouse for drivers, shifts and requests', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ id: 'driver-1' }))
      .mockResolvedValueOnce(jsonResponse({ id: 'shift-1' }))
      .mockResolvedValueOnce(jsonResponse({ id: 'request-1' }));
    vi.stubGlobal('fetch', fetchMock);

    await api.createDriver('warehouse-1', {
      external_worker_id: null,
      rwms_assignment_mode: 'WAREHOUSE_DRIVERS',
      active: true,
      notes: '',
    });
    await api.createShift('warehouse-1', {
      driver_id: 'driver-1',
      vehicle_id: 'vehicle-1',
      date_from: '2026-08-01',
      date_to: '2026-08-31',
      start_time: '08:00',
      end_time: '20:00',
      break_minutes: 60,
      active: true,
    });
    await api.createRequest('warehouse-1', {
      type: 'DELIVERY',
      name: 'Обязательная доставка',
      address_label: 'Невский проспект, 1',
      latitude: 59.93,
      longitude: 30.32,
      quantity: 2,
      cargo_length_mm: null,
      cargo_width_mm: null,
      cargo_height_mm: null,
      cargo_weight_kg: null,
      service_minutes: 30,
      priority: 10,
      mandatory: true,
      status: 'READY',
      split_allowed: true,
      notes: '',
      date_options: [{ date: '2026-08-30', priority: 1, window_start: '12:00', window_end: '15:00', is_hard: true }],
    });

    expect(fetchMock.mock.calls.map(([url]) => requestUrl(url))).toEqual([
      '/api/warehouses/warehouse-1/drivers',
      '/api/warehouses/warehouse-1/shifts',
      '/api/warehouses/warehouse-1/requests',
    ]);
    expect(bodyAt(fetchMock, 1)).toMatchObject({ date_from: '2026-08-01', date_to: '2026-08-31' });
    expect(bodyAt(fetchMock, 2)).toMatchObject({ type: 'DELIVERY', mandatory: true });
  });
});

describe('planning lifecycle', () => {
  it('hands a request to a contractor without internal route or vehicle fields', async () => {
    const assigned = requestFixture({
      assignment_type: 'CONTRACTOR_HANDOFF',
      assigned_contractor_worker_id: 'contractor-1',
      assigned_contractor_name: 'Иван Петров',
    });
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(assigned));
    vi.stubGlobal('fetch', fetchMock);

    const result = await api.assignRequestToContractor(assigned.id, 'contractor-1');

    expect(result.assignment_type).toBe('CONTRACTOR_HANDOFF');
    expect(fetchMock.mock.calls[0]?.[0]).toBe(`/api/requests/${assigned.id}/contractor-assignment`);
    expect(bodyAt(fetchMock, 0)).toEqual({ contractor_worker_id: 'contractor-1' });
  });

  it('dispatches a contractor on the date selected in the header', async () => {
    const response = {
      contractor_worker_id: 'contractor-1',
      contractor_name: 'Иван Петров',
      planning_date: '2026-08-31',
      mode: 'MANUAL',
      assigned_request_ids: ['request-1', 'request-2'],
      assigned_count: 2,
    };
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(response));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.dispatchContractor(
      'warehouse-1',
      'contractor-1',
      '2026-08-31',
      'MANUAL',
      ['request-1', 'request-2'],
    )).resolves.toEqual(response);

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/warehouses/warehouse-1/contractor-dispatches');
    expect(bodyAt(fetchMock, 0)).toEqual({
      contractor_worker_id: 'contractor-1',
      planning_date: '2026-08-31',
      mode: 'MANUAL',
      request_ids: ['request-1', 'request-2'],
    });
  });

  it('uses warehouse endpoints for generated load and day closing', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ warehouse_id: 'warehouse-1', created_requests: 8 }))
      .mockResolvedValueOnce(jsonResponse({ warehouse_id: 'warehouse-1', date: '2026-08-30', accepting_requests: false }));
    vi.stubGlobal('fetch', fetchMock);

    await api.generateWorkload('warehouse-1', {
      start_date: '2026-08-30',
      days: 3,
      deliveries_per_day: 4,
      pickups_per_day: 4,
      alternative_dates_count: 1,
      cargo_length_mm: 6000,
      cargo_width_mm: 2400,
      cargo_height_mm: 2400,
      cargo_weight_kg: 1200,
      seed: 42,
    });
    await api.closePlanningDay('warehouse-1', '2026-08-30');

    expect(fetchMock.mock.calls.map(([url]) => requestUrl(url))).toEqual([
      '/api/warehouses/warehouse-1/generate-workload',
      '/api/warehouses/warehouse-1/planning-days/2026-08-30/close',
    ]);
  });

  it('approves a plan and resets pre-approval manual changes with version fencing', async () => {
    const workspace = workspaceFixture();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(rawPlan({ status: 'CONFIRMED', version: 5 })))
      .mockResolvedValueOnce(jsonResponse(rawPlan({ version: 6, manually_changed: false })));
    vi.stubGlobal('fetch', fetchMock);

    const approved = await api.confirmPlan('plan-1', 4, true, workspace);
    const reset = await api.resetManualChanges('plan-1', 5, workspace);

    expect(approved.status).toBe('CONFIRMED');
    expect(reset.manually_changed).toBe(false);
    expect(fetchMock.mock.calls.map(([url]) => requestUrl(url))).toEqual([
      '/api/plans/plan-1/confirm',
      '/api/plans/plan-1/manual-changes/reset',
    ]);
    expect(bodyAt(fetchMock, 0)).toEqual({ expected_version: 4, accept_warnings: true });
    expect(bodyAt(fetchMock, 1)).toEqual({ expected_version: 5 });
  });

  it('sends an explicit empty-positioning reason when a support route is approved', async () => {
    const workspace = workspaceFixture();
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(rawPlan({
      status: 'CONFIRMED',
      version: 5,
    })));
    vi.stubGlobal('fetch', fetchMock);

    await api.confirmPlan(
      'plan-1',
      4,
      true,
      workspace,
      'Нет подходящего попутного груза',
    );

    expect(bodyAt(fetchMock, 0)).toEqual({
      expected_version: 4,
      accept_warnings: true,
      empty_positioning_reason: 'Нет подходящего попутного груза',
    });
  });

  it('renders a machine-readable nearest option as a local, readable date', () => {
    const workspace = workspaceFixture();
    const plan = normalizeRoutePlan(rawPlan({
      unassigned_tasks: [{
        task_id: 'task-1',
        reason_codes: ['TIME_WINDOW_CONFLICT'],
        descriptions_ru: ['Окно не помещается в смену'],
        nearest_option: { possible_at: '2026-08-30T18:19:55+03:00' },
        recommendation_ru: 'Расширьте окно',
      }],
    }) as never, workspace);

    expect(plan.unassigned[0]?.closest_option).toContain('Можно назначить');
    expect(plan.unassigned[0]?.closest_option).toContain('18:19');
    expect(plan.unassigned[0]?.closest_option).not.toContain('possible_at');
  });

  it('labels the route depot from the plan owner when a representative warehouse is open', () => {
    const mainWarehouse = warehouseFixture({ id: 'warehouse-main', name: 'СПБ' });
    const representativeWarehouse = warehouseFixture({
      id: 'warehouse-representative',
      name: 'В. Новгород',
      city: 'Великий Новгород',
      representative: true,
    });
    const workspace = workspaceFixture({
      warehouse: representativeWarehouse,
      warehouses: [mainWarehouse, representativeWarehouse],
      planning_root_warehouse_id: mainWarehouse.id,
      planning_group_warehouse_ids: [mainWarehouse.id, representativeWarehouse.id],
    });
    const plan = normalizeRoutePlan(rawPlan({
      warehouse_id: mainWarehouse.id,
      cycles: [{
        id: 'cycle-main-depot',
        driver_shift_id: 'shift-main',
        sequence: 1,
        planned_start: '2026-08-30T06:00:00Z',
        planned_finish: '2026-08-30T06:30:00Z',
        total_distance_meters: 0,
        total_travel_seconds: 0,
        total_service_seconds: 1_800,
        empty_distance_meters: 0,
        detour_seconds: 0,
        score: 0,
        locked: false,
        manually_changed: false,
        metrics: {},
        stops: [{
          id: 'stop-main-depot',
          sequence: 0,
          task_id: null,
          stop_type: 'DEPOT_LOAD',
          planned_arrival: '2026-08-30T06:00:00Z',
          planned_departure: '2026-08-30T06:30:00Z',
          service_seconds: 1_800,
          quantity_delta: 0,
          load_before: 0,
          load_after: 0,
          latitude: mainWarehouse.latitude,
          longitude: mainWarehouse.longitude,
          warnings: [],
          locked: false,
        }],
        segments: [],
        explanations: [],
      }],
    }) as never, workspace);

    expect(plan.driver_routes[0]?.cycles[0]?.stops[0]).toMatchObject({
      label: 'СПБ',
      latitude: mainWarehouse.latitude,
      longitude: mainWarehouse.longitude,
    });
    expect(plan.driver_routes[0]?.cycles[0]?.stops[0]?.label).not.toBe('В. Новгород');
  });

  it('keeps support-warehouse execution facts and exact resource names in the day plan', () => {
    const supportWarehouse = warehouseFixture({
      id: 'support-local',
      external_warehouse_id: '22222222-2222-4222-8222-222222222222',
      name: 'Опорный склад',
    });
    const workspace = workspaceFixture({
      warehouses: [warehouseFixture(), supportWarehouse],
      drivers: [],
      vehicles: [],
      shifts: [],
    });
    const plan = normalizeRoutePlan(rawPlan({
      cycles: [{
        id: 'cycle-support',
        driver_shift_id: 'shift-support',
        sequence: 1,
        planned_start: '2026-08-30T09:00:00Z',
        planned_finish: '2026-08-30T12:00:00Z',
        total_distance_meters: 100_000,
        total_travel_seconds: 7_200,
        total_service_seconds: 1_800,
        empty_distance_meters: 0,
        detour_seconds: 0,
        score: 1,
        locked: false,
        manually_changed: false,
        metrics: {
          execution_mode: 'CROSS_WAREHOUSE_SERVICE',
          service_warehouse_id: workspace.warehouse.external_warehouse_id,
          resource_origin_warehouse_id: supportWarehouse.external_warehouse_id,
          support_warehouse_link_id: '33333333-3333-4333-8333-333333333333',
          driver_id: '44444444-4444-4444-8444-444444444444',
          driver_worker_id: '55555555-5555-4555-8555-555555555555',
          driver_name: 'Петров Алексей',
          vehicle_id: '66666666-6666-4666-8666-666666666666',
          vehicle_name: 'МАЗ поддержки',
          vehicle_registration_number: 'А456ВС',
          available_at_served: '2026-08-30T09:00:00Z',
          latest_served_finish: '2026-08-30T15:00:00Z',
          inbound_travel_minutes: 180,
          return_travel_minutes: 190,
          inbound_distance_meters: 185_000,
          return_distance_meters: 195_000,
          positioning_distance_meters: 380_000,
          positioning_outbound_geometry: {
            type: 'LineString',
            coordinates: [[30, 59], [30.5, 58.7], [31, 58]],
          },
          positioning_return_geometry: {
            type: 'LineString',
            coordinates: [[31, 58], [30.4, 58.8], [30, 59]],
          },
          available_transfer_cabin_capacity: 2,
          trailer_available: true,
          outbound_positioning_empty: true,
          empty_positioning_reason_required: true,
          returns_to_origin: true,
          changes_operational_warehouse: false,
          reason_codes: ['NO_LOCAL_DRIVER', 'SUPPORT_DRIVER_AVAILABLE'],
        },
        stops: [],
        segments: [],
        explanations: [{ summary_ru: 'Выбран опорный склад' }],
      }],
    }) as never, workspace);

    expect(plan.driver_routes[0]).toMatchObject({
      driver_name: 'Петров Алексей',
      vehicle_name: 'МАЗ поддержки',
      registration_number: 'А456ВС',
      cross_warehouse_service: {
        resource_origin_warehouse_name: 'Опорный склад',
        returns_to_origin: true,
        changes_operational_warehouse: false,
      },
    });
    expect(plan.driver_routes[0]?.cycles[0]?.cross_warehouse_service?.reason_codes).toEqual([
      'NO_LOCAL_DRIVER',
      'SUPPORT_DRIVER_AVAILABLE',
    ]);
    expect(
      plan.driver_routes[0]?.cycles[0]?.cross_warehouse_service
        ?.positioning_outbound_geometry?.geometry.coordinates,
    ).toHaveLength(3);
    expect(
      plan.driver_routes[0]?.cycles[0]?.cross_warehouse_service
        ?.positioning_return_geometry?.geometry.coordinates,
    ).toHaveLength(3);
  });
});

describe('transport errors', () => {
  it('preserves Problem Details status, code and detail', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      title: 'Склад RWMS не найден',
      detail: 'Выбранный склад отсутствует в справочнике RWMS',
      code: 'RWMS_WAREHOUSE_NOT_FOUND',
    }, 422)));

    const error = await api.updateWarehouse('warehouse-1', { loading_minutes: 30 })
      .catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 422, code: 'RWMS_WAREHOUSE_NOT_FOUND' });
    expect((error as Error).message).toContain('справочнике RWMS');
  });

  it('ensures the automatic plan through the active warehouse endpoint', async () => {
    const workspace = workspaceFixture();
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(rawPlan()));
    vi.stubGlobal('fetch', fetchMock);

    const plan = await api.ensureAutomaticPlan('warehouse-1', '2026-08-30', workspace);

    expect(plan?.warehouse_id).toBe('warehouse-1');
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/warehouses/warehouse-1/plans/ensure?date=2026-08-30');
    expect((fetchMock.mock.calls[0]?.[1] as RequestInit).method).toBe('POST');
  });
});
