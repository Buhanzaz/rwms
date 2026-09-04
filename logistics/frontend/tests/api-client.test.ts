import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  api,
  getWarehouseWorkspace,
  setSimulatorAccessTokenProvider,
  startOptimizationEventStream,
  type OptimizationStreamEvent,
} from '../src/api/client';
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
  it('uses a fresh panel USER bearer for each operational request', async () => {
    const tokenProvider = vi.fn()
      .mockResolvedValueOnce('panel-token-1')
      .mockResolvedValueOnce('panel-token-2');
    setSimulatorAccessTokenProvider(tokenProvider);
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await api.listWarehouses();
    await api.listWarehouses();

    expect(tokenProvider).toHaveBeenCalledTimes(2);
    expect(new Headers((fetchMock.mock.calls[0]?.[1] as RequestInit).headers).get('Authorization'))
      .toBe('Bearer panel-token-1');
    expect(new Headers((fetchMock.mock.calls[1]?.[1] as RequestInit).headers).get('Authorization'))
      .toBe('Bearer panel-token-2');
  });

  it('fails explicitly before transport when the panel session is missing', async () => {
    setSimulatorAccessTokenProvider(() => Promise.resolve(null));
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const error = await api.listWarehouses().catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 401, code: 'AUTHENTICATION_REQUIRED' });
    expect((error as Error).message).toContain('Войдите в RWMS');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('keeps the health probe public', async () => {
    setSimulatorAccessTokenProvider(() => Promise.resolve(null));
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse({ status: 'ok' }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.health()).resolves.toEqual({ status: 'ok' });

    expect(new Headers((fetchMock.mock.calls[0]?.[1] as RequestInit).headers).has('Authorization')).toBe(false);
  });

  it('loads and normalizes a bounded planning-date workspace without refreshing RWMS', async () => {
    const workspace = workspaceFixture({
      requests: [{
        ...requestFixture({ delivery_price_rubles: 28_500, price_isochrone_minutes: 180 }),
        mandatory: undefined as never,
        tasks: [{ ...requestFixture().tasks![0]!, mandatory: undefined as never }],
      }],
    });
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(workspace));
    vi.stubGlobal('fetch', fetchMock);

    const result = await getWarehouseWorkspace('warehouse-1', { planningDate: '2026-08-30' });

    expect(fetchMock.mock.calls.map(([url]) => requestUrl(url))).toEqual([
      '/api/warehouses/warehouse-1/workspace?planning_date=2026-08-30&request_limit=250',
    ]);
    expect(result.warehouse.address).toContain('Шоссе Революции');
    expect(result.requests[0]).toMatchObject({ mandatory: false });
    expect(result.requests[0]).toMatchObject({ delivery_price_rubles: 28_500, price_isochrone_minutes: 180 });
    expect(result.requests[0]?.tasks?.[0]).toMatchObject({ mandatory: false });
  });

  it('never retries a failed projection read through the mutating refresh path', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse({
      title: 'Проекция временно недоступна',
      code: 'WORKSPACE_UNAVAILABLE',
    }, 503));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getWarehouseWorkspace('warehouse-1', { planningDate: '2026-08-30' })).rejects.toBeInstanceOf(ApiError);

    expect(fetchMock.mock.calls.map(([url]) => requestUrl(url))).toEqual([
      '/api/warehouses/warehouse-1/workspace?planning_date=2026-08-30&request_limit=250',
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
    }, 'driver-intent');
    await api.createShift('warehouse-1', {
      driver_id: 'driver-1',
      vehicle_id: 'vehicle-1',
      date_from: '2026-08-01',
      date_to: '2026-08-31',
      start_time: '08:00',
      end_time: '20:00',
      break_minutes: 60,
      active: true,
    }, 'shift-intent');
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
      split_allowed: true,
      notes: '',
      date_options: [{ date: '2026-08-30', priority: 1, window_start: '12:00', window_end: '15:00', is_hard: true }],
    }, 'request-intent');

    expect(fetchMock.mock.calls.map(([url]) => requestUrl(url))).toEqual([
      '/api/warehouses/warehouse-1/drivers',
      '/api/warehouses/warehouse-1/shifts',
      '/api/warehouses/warehouse-1/requests',
    ]);
    expect(bodyAt(fetchMock, 1)).toMatchObject({ date_from: '2026-08-01', date_to: '2026-08-31' });
    expect(bodyAt(fetchMock, 2)).toMatchObject({ type: 'DELIVERY', mandatory: true });
    expect(fetchMock.mock.calls.map(([, init]) => new Headers((init as RequestInit).headers).get('Idempotency-Key')))
      .toEqual(['driver-intent', 'shift-intent', 'request-intent']);
  });

  it('reuses one caller-owned idempotency key after an uncertain create result', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ title: 'Временно недоступно' }, 503))
      .mockResolvedValueOnce(jsonResponse({ id: 'driver-1' }, 201));
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      external_worker_id: null,
      rwms_assignment_mode: 'WAREHOUSE_DRIVERS' as const,
      active: true,
      notes: '',
    };

    await expect(api.createDriver('warehouse-1', input, 'stable-driver-intent')).rejects.toBeInstanceOf(ApiError);
    await api.createDriver('warehouse-1', input, 'stable-driver-intent');

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls.map(([, init]) => new Headers((init as RequestInit).headers).get('Idempotency-Key')))
      .toEqual(['stable-driver-intent', 'stable-driver-intent']);
  });

  it('sends the authoritative version fence with catalog updates', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse({ id: 'driver-1', version: 8 }));
    vi.stubGlobal('fetch', fetchMock);

    await api.updateDriver('driver-1', { active: false }, 7);

    expect(bodyAt(fetchMock, 0)).toEqual({ active: false, expected_version: 7 });
  });

  it('fences request actions, date-option updates, splits and every catalog delete', async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => Promise.resolve(
      init?.method === 'DELETE' ? new Response(null, { status: 204 }) : jsonResponse({}),
    ));
    vi.stubGlobal('fetch', fetchMock);
    const planningDetails = {
      date: '2026-08-30',
      window_start: '12:00',
      window_end: '15:00',
      is_hard: true,
      mandatory: true,
      trailer_access_allowed: false,
      include_driver_passport_in_notification: false,
      contact_name: 'Диспетчер',
      contact_phone: '+70000000000',
    };

    await api.updateRequestDateOption('option-1', { priority: 2 }, 13);
    await api.scheduleRequest('request-1', { date: '2026-08-30', add_if_missing: true }, 13);
    await api.saveRequestPlanningDetails('request-1', planningDetails, 13);
    await api.splitRequest('request-1', [1, 1], 13);
    await api.deleteDriver('driver-1', 3);
    await api.deleteVehicle('vehicle-1', 4);
    await api.deleteTrailer('trailer-1', 5);
    await api.deleteShift('shift-1', 6);
    await api.deleteRequest('request-1', 13);
    await api.deleteRequestDateOption('option-1', 13);

    expect(bodyAt(fetchMock, 0)).toEqual({ priority: 2, expected_version: 13 });
    expect(bodyAt(fetchMock, 1)).toEqual({ date: '2026-08-30', add_if_missing: true, expected_version: 13 });
    expect(bodyAt(fetchMock, 2)).toEqual({ ...planningDetails, expected_version: 13 });
    expect(bodyAt(fetchMock, 3)).toEqual({ expected_version: 13, part_quantities: [1, 1] });
    expect(fetchMock.mock.calls.slice(4).map(([url]) => requestUrl(url))).toEqual([
      '/api/drivers/driver-1?expected_version=3',
      '/api/vehicles/vehicle-1?expected_version=4',
      '/api/trailers/trailer-1?expected_version=5',
      '/api/shifts/shift-1?expected_version=6',
      '/api/requests/request-1?expected_version=13',
      '/api/request-date-options/option-1?expected_version=13',
    ]);
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

  it('does not let the browser choose the audit actor for a manual plan change', async () => {
    const workspace = workspaceFixture();
    const currentPlan = normalizeRoutePlan(rawPlan() as never, workspace);
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse({
      valid: true,
      version: 5,
      errors: [],
      warnings: [],
    }));
    vi.stubGlobal('fetch', fetchMock);

    await api.manualChange('plan-1', {
      expected_version: 4,
      change_type: 'MOVE_TASK',
      task_id: 'task-1',
      target_cycle_id: 'cycle-2',
      target_sequence: 1,
      reason: 'Ручное перемещение',
    }, workspace, currentPlan);

    expect(bodyAt(fetchMock, 0)).toEqual({
      expected_version: 4,
      change_type: 'MOVE_TASK',
      payload: {
        task_id: 'task-1',
        target_cycle_id: 'cycle-2',
        target_sequence: 1,
      },
      reason: 'Ручное перемещение',
    });
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

describe('existing delivery reschedule transport', () => {
  it('calculates by selected date and applies the exact owner fences with idempotency', async () => {
    setSimulatorAccessTokenProvider(() => Promise.resolve('panel-token'));
    const options = {
      request_id: 'request-1',
      request_version: 7,
      source_plan_id: '11111111-1111-4111-8111-111111111111',
      source_plan_version: 13,
      order_id: 'order-1',
      order_version: 11,
      session_id: 'session-1',
      session_version: 5,
      current_slot: {
        slot_id: 'slot-current',
        slot_version: 2,
        date: '2026-09-01',
        kind: 'FIXED_WINDOW',
        window_start: '12:00:00',
        window_end: '15:00:00',
        delivery_price_rubles: 22_000,
        expires_at: '2026-09-01T18:30:00+03:00',
      },
      options: [],
    };
    const result = {
      ...options,
      request_version: 8,
      order_version: 12,
      session_version: 6,
      scheduled_date: '2026-09-02',
      confirmed_slot: {
        slot_id: 'slot-1',
        slot_version: 3,
        date: '2026-09-02',
        kind: 'FIXED_WINDOW',
        window_start: '09:00:00',
        window_end: '12:00:00',
        delivery_price_rubles: 24_000,
        expires_at: '2026-09-01T18:30:00+03:00',
      },
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(options))
      .mockResolvedValueOnce(jsonResponse(result))
      .mockResolvedValueOnce(jsonResponse(result));
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();

    await api.getRequestRescheduleOptions('request-1', {
      expected_request_version: 6,
      date: '2026-09-02',
    }, controller.signal);
    await api.rescheduleRequest('request-1', {
      expected_request_version: 7,
      source_plan_id: '11111111-1111-4111-8111-111111111111',
      source_plan_version: 13,
      expected_order_version: 11,
      expected_session_version: 5,
      slot_id: 'slot-1',
      slot_version: 3,
    }, 'intent-1');
    await api.retryRequestReschedule('request-1', {
      hold_id: '22222222-2222-4222-8222-222222222222',
      expected_quarantine_count: 2,
    }, 'retry-intent-1');

    expect(fetchMock.mock.calls.map(([url]) => requestUrl(url))).toEqual([
      '/api/requests/request-1/reschedule-options',
      '/api/requests/request-1/reschedule',
      '/api/requests/request-1/reschedule-retry',
    ]);
    expect(bodyAt(fetchMock, 0)).toEqual({ expected_request_version: 6, date: '2026-09-02' });
    expect((fetchMock.mock.calls[0]?.[1] as RequestInit).signal).toBe(controller.signal);
    expect(bodyAt(fetchMock, 1)).toEqual({
      expected_request_version: 7,
      source_plan_id: '11111111-1111-4111-8111-111111111111',
      source_plan_version: 13,
      expected_order_version: 11,
      expected_session_version: 5,
      slot_id: 'slot-1',
      slot_version: 3,
    });
    expect(new Headers((fetchMock.mock.calls[1]?.[1] as RequestInit).headers).get('Idempotency-Key')).toBe('intent-1');
    expect((fetchMock.mock.calls[2]?.[1] as RequestInit).method).toBe('POST');
    expect(bodyAt(fetchMock, 2)).toEqual({
      hold_id: '22222222-2222-4222-8222-222222222222',
      expected_quarantine_count: 2,
    });
    expect(new Headers((fetchMock.mock.calls[2]?.[1] as RequestInit).headers).get('Idempotency-Key')).toBe('retry-intent-1');
  });
});

describe('transport errors', () => {
  it('preserves Problem Details status, code and detail', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      title: 'Склад RWMS не найден',
      detail: 'Выбранный склад отсутствует в справочнике RWMS',
      code: 'RWMS_WAREHOUSE_NOT_FOUND',
    }, 422)));

    const error = await api.updateWarehouse('warehouse-1', { loading_minutes: 30 }, 1)
      .catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 422, code: 'RWMS_WAREHOUSE_NOT_FOUND' });
    expect((error as Error).message).toContain('справочнике RWMS');
  });

  it('keeps FastAPI validation diagnostics without exposing them through the error message', async () => {
    setSimulatorAccessTokenProvider(() => Promise.resolve('panel-token'));
    const rawDetail = [{
      type: 'greater_than',
      loc: ['body', 'loading_minutes'],
      msg: 'Input should be greater than 0',
      input: -1,
    }];
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      title: 'Validation Error',
      detail: rawDetail,
      code: 'INVALID_REQUEST',
      trace_id: 'internal-trace',
    }, 422)));

    const error = await api.updateWarehouse('warehouse-1', { loading_minutes: -1 }, 1)
      .catch((caught: unknown) => caught) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 422, code: 'INVALID_REQUEST' });
    expect(error.problem?.detail).toEqual(rawDetail);
    expect(error.message).toContain('Проверьте введённые данные');
    expect(error.message).not.toMatch(/Validation|greater_than|loading_minutes|HTTP|trace/iu);
  });

  it('retains an upstream diagnostic only in Problem Details and returns actionable Russian copy', async () => {
    setSimulatorAccessTokenProvider(() => Promise.resolve('panel-token'));
    const rawDetail = 'RMS Logistics Service returned HTTP 400: {"internal":"trace"}';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      detail: rawDetail,
      code: 'RWMS_REQUEST_FAILED',
    }, 400)));

    const error = await api.updateWarehouse('warehouse-1', { loading_minutes: 30 }, 1)
      .catch((caught: unknown) => caught) as ApiError;

    expect(error.problem?.detail).toBe(rawDetail);
    expect(error.message).toContain('Проверьте введённые данные');
    expect(error.message).not.toMatch(/RMS Logistics Service|HTTP 400|internal|trace|\{/iu);
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

describe('optimization event transport', () => {
  it('streams custom and terminal events with a bearer header and no token in the URL', async () => {
    setSimulatorAccessTokenProvider(() => Promise.resolve('stream-token'));
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(
          'id: 7\nevent: phase_started\ndata: {"event_type":"phase_started","sequence":7',
        ));
        controller.enqueue(new TextEncoder().encode('}\n\nevent: run_terminal\ndata: {"status":"COMPLETED"}\n\n'));
        controller.close();
      },
    });
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(stream, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    const events: OptimizationStreamEvent[] = [];

    await new Promise<void>((resolve, reject) => {
      startOptimizationEventStream('run-1', {
        onEvent: (event) => {
          events.push(event);
          if (event.event === 'run_terminal') resolve();
        },
        onError: reject,
      });
    });

    expect(events).toEqual([
      {
        id: '7',
        event: 'phase_started',
        data: '{"event_type":"phase_started","sequence":7}',
      },
      {
        id: null,
        event: 'run_terminal',
        data: '{"status":"COMPLETED"}',
      },
    ]);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/optimization-runs/run-1/stream');
    expect(url).not.toContain('stream-token');
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer stream-token');
    expect(new Headers(init.headers).get('Accept')).toBe('text/event-stream');
  });

  it('reconnects an interrupted stream from the last persisted event id', async () => {
    vi.useFakeTimers();
    try {
      const tokenProvider = vi.fn()
        .mockResolvedValueOnce('stream-token-1')
        .mockResolvedValueOnce('stream-token-2');
      setSimulatorAccessTokenProvider(tokenProvider);
      const interrupted = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(new TextEncoder().encode('id: 11\nevent: trace\ndata: {"sequence":11}\n\n'));
          controller.close();
        },
      });
      const terminal = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(new TextEncoder().encode('event: run_terminal\ndata: {"status":"COMPLETED"}\n\n'));
          controller.close();
        },
      });
      const fetchMock = vi.fn()
        .mockResolvedValueOnce(new Response(interrupted, { status: 200 }))
        .mockResolvedValueOnce(new Response(terminal, { status: 200 }));
      vi.stubGlobal('fetch', fetchMock);
      const terminalReceived = new Promise<void>((resolve) => {
        startOptimizationEventStream('run-2', {
          onEvent: (event) => {
            if (event.event === 'run_terminal') resolve();
          },
        });
      });

      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
      await vi.advanceTimersByTimeAsync(1_000);
      await terminalReceived;

      expect(fetchMock).toHaveBeenCalledTimes(2);
      const [, reconnectInit] = fetchMock.mock.calls[1] as [string, RequestInit];
      const reconnectHeaders = new Headers(reconnectInit.headers);
      expect(reconnectHeaders.get('Authorization')).toBe('Bearer stream-token-2');
      expect(reconnectHeaders.get('Last-Event-ID')).toBe('11');
    } finally {
      vi.useRealTimers();
    }
  });
});
