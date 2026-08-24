import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../src/api/client';
import { normalizeRoutePlan, normalizeScenario, type RawRoutePlan, type RawScenario } from '../src/api/mappers';
import type { ScenarioWorkspace, Zone } from '../src/domain/types';

const rawScenario: RawScenario = {
  id: 'scenario-id',
  name: 'Контрактный сценарий',
  description: '',
  timezone: 'Europe/Moscow',
  default_planning_date: '2026-08-25',
  created_at: '2026-08-22T00:00:00Z',
  updated_at: '2026-08-22T00:00:00Z',
  seed: 42,
  settings: {},
};

const rawPlan: RawRoutePlan = {
  id: 'plan-id',
  scenario_id: 'scenario-id',
  warehouse_id: 'warehouse-id',
  date: '2026-08-25',
  name: 'План A',
  version: 1,
  status: 'GENERATED',
  score: 0,
  metrics: {},
  validation_errors: [],
  validation_warnings: [],
  manually_changed: false,
  created_at: '2026-08-22T00:00:00Z',
  updated_at: '2026-08-22T00:00:00Z',
  cycles: [],
  unassigned_tasks: [],
};

const zone: Zone = {
  id: 'zone-id',
  scenario_id: 'scenario-id',
  name: 'Зона 1',
  code: 'Z1',
  route_group: 'WEST',
  geometry: { type: 'Polygon', coordinates: [[[37, 55], [38, 55], [38, 56], [37, 55]]] },
  version: 1,
  priority: 1,
  locked: false,
  created_at: '2026-08-22T00:00:00Z',
  updated_at: '2026-08-22T00:00:00Z',
};

function workspaceFixture(): ScenarioWorkspace {
  return {
    scenario: normalizeScenario(rawScenario),
    warehouses: [],
    zones: [zone],
    zone_relations: [],
    drivers: [],
    vehicles: [],
    shifts: [],
    requests: [],
  };
}

function jsonResponse(value: unknown): Response {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

function fetchMock(...responses: unknown[]) {
  const mock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>();
  responses.forEach((response) => mock.mockResolvedValueOnce(jsonResponse(response)));
  vi.stubGlobal('fetch', mock);
  return mock;
}

function errorFetch(status: number, value: unknown) {
  const mock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>();
  mock.mockResolvedValueOnce(new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', mock);
  return mock;
}

function requestBody(mock: ReturnType<typeof fetchMock>, index: number): Record<string, unknown> {
  const body = mock.mock.calls[index]?.[1]?.body;
  if (typeof body !== 'string') return {};
  return JSON.parse(body) as Record<string, unknown>;
}

afterEach(() => vi.unstubAllGlobals());

describe('actual backend transport contract', () => {
  it('uses body-free demo/export and wraps an imported document', async () => {
    const mock = fetchMock(rawScenario, { schema_version: 1 }, rawScenario);
    await api.generateDemo('scenario-id');
    await api.exportScenario('scenario-id', true);
    await api.importScenario({ schema_version: 1 });

    expect(mock.mock.calls[0]?.[0]).toBe('/api/scenarios/scenario-id/generate-demo');
    expect(mock.mock.calls[0]?.[1]?.body).toBeUndefined();
    expect(mock.mock.calls[1]?.[0]).toBe('/api/scenarios/scenario-id/export?include_plans=true');
    expect(mock.mock.calls[1]?.[1]?.body).toBeUndefined();
    expect(requestBody(mock, 2)).toEqual({ document: { schema_version: 1 } });
  });

  it('clones a plan with only the optional name', async () => {
    const mock = fetchMock(rawPlan);
    await api.clonePlan('plan-id', 'План B', workspaceFixture());
    expect(requestBody(mock, 0)).toEqual({ name: 'План B' });
  });

  it('keeps zone geometry bare and locking on its dedicated endpoint', async () => {
    const mock = fetchMock({ ...zone, name: 'Зона новая' }, { ...zone, locked: true });
    await api.updateZone('zone-id', { name: 'Зона новая', geometry: zone.geometry });
    await api.setZoneLocked('zone-id', true);

    expect(requestBody(mock, 0)).toEqual({ name: 'Зона новая', geometry: zone.geometry });
    expect(requestBody(mock, 0)).not.toHaveProperty('locked');
    expect(mock.mock.calls[1]?.[0]).toBe('/api/zones/zone-id/lock');
    expect(requestBody(mock, 1)).toEqual({ locked: true });
  });

  it('nests manual-change payload but leaves dedicated simulation bodies raw', async () => {
    const workspace = workspaceFixture();
    const currentPlan = normalizeRoutePlan(rawPlan, workspace);
    const mock = fetchMock(rawPlan, rawPlan, rawPlan);

    await api.manualChange('plan-id', {
      expected_version: 1,
      change_type: 'MOVE_TASK',
      task_id: 'task-id',
      target_cycle_id: 'cycle-id',
      target_sequence: 2,
      changed_by: 'local-admin',
      reason: 'Проверка контракта',
    }, workspace, currentPlan);
    await api.applyDelay('plan-id', {
      expected_version: 1,
      driver_shift_id: 'shift-id',
      effective_at: '2026-08-25T08:00:00Z',
      delay_minutes: 20,
      reason: 'Пробка',
      persist: false,
    }, workspace, currentPlan);
    await api.markDriverUnavailable('plan-id', {
      expected_version: 1,
      driver_shift_id: 'shift-id',
      effective_at: '2026-08-25T08:00:00Z',
      reason: 'Недоступен',
      persist: false,
    }, workspace, currentPlan);

    expect(requestBody(mock, 0)).toEqual({
      expected_version: 1,
      change_type: 'MOVE_TASK',
      changed_by: 'local-admin',
      reason: 'Проверка контракта',
      payload: { task_id: 'task-id', target_cycle_id: 'cycle-id', target_sequence: 2 },
    });
    expect(requestBody(mock, 1)).toEqual({
      expected_version: 1,
      driver_shift_id: 'shift-id',
      effective_at: '2026-08-25T08:00:00Z',
      delay_minutes: 20,
      reason: 'Пробка',
      persist: false,
    });
    expect(mock.mock.calls[2]?.[0]).toBe('/api/plans/plan-id/simulation/driver-unavailable');
    expect(requestBody(mock, 2)).toEqual({
      expected_version: 1,
      driver_shift_id: 'shift-id',
      effective_at: '2026-08-25T08:00:00Z',
      reason: 'Недоступен',
      persist: false,
    });
  });

  it('turns FastAPI validation arrays into a readable error instead of rendering objects', async () => {
    errorFetch(422, { detail: [{ loc: ['body', 'document'], msg: 'Field required', type: 'missing' }] });
    const failure = await api.importScenario({}).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).message).toBe('Field required');
  });

  it('sends explicit RWMS warehouse and driver linkage fields without renaming them', async () => {
    const warehouseId = '35b8738c-d405-4c42-ac2b-e9f4a26d7c19';
    const workerId = '2de75998-c1f9-4d0f-b5d0-59bcb75cf103';
    const mock = fetchMock({}, {});

    await api.updateWarehouse('warehouse-id', { external_warehouse_id: warehouseId });
    await api.updateDriver('driver-id', { external_worker_id: workerId });

    expect(mock.mock.calls[0]?.[0]).toBe('/api/warehouses/warehouse-id');
    expect(requestBody(mock, 0)).toEqual({ external_warehouse_id: warehouseId });
    expect(mock.mock.calls[1]?.[0]).toBe('/api/drivers/driver-id');
    expect(requestBody(mock, 1)).toEqual({ external_worker_id: workerId });
  });

  it('uses the selected date for RWMS sync and the exact plan version for apply', async () => {
    const mock = fetchMock(
      { imported: 2, updated: 1, skipped: 3, failures: [] },
      {
        applied: [{ orderId: '57ca2992-702f-4cc7-b478-6a2c64146241', documentId: '41b0e409-53d2-40af-a68c-d4de750005cc', replayed: false }],
        rejected: [{ orderId: '927a16a4-b40c-42a6-8f4d-d3b08eb0a1df', code: 'ORDER_VERSION_CONFLICT', message: 'order changed' }],
      },
      {
        plan_id: 'plan-id',
        plan_version: 7,
        tasks: [{
          task_id: 'task-id',
          request_id: 'request-id',
          order_id: '57ca2992-702f-4cc7-b478-6a2c64146241',
          document_id: '41b0e409-53d2-40af-a68c-d4de750005cc',
          driver_audience_mode: 'WAREHOUSE_DRIVERS',
          driver_worker_id: null,
          driver_name: 'Свободная доставка',
          task_state: 'SCHEDULED',
        }],
      },
    );

    const sync = await api.syncRwmsRequests('scenario-id', {
      warehouse_id: '35b8738c-d405-4c42-ac2b-e9f4a26d7c19',
      date_from: '2026-08-25',
      date_to: '2026-08-25',
    });
    const applied = await api.applyPlanToRwms('plan-id', 7);
    const statuses = await api.getPlanRwmsStatus('plan-id', 7);

    expect(mock.mock.calls[0]?.[0]).toBe('/api/scenarios/scenario-id/rwms/sync');
    expect(requestBody(mock, 0)).toEqual({
      warehouse_id: '35b8738c-d405-4c42-ac2b-e9f4a26d7c19',
      date_from: '2026-08-25',
      date_to: '2026-08-25',
    });
    expect(sync).toEqual({ imported: 2, updated: 1, skipped: 3, failures: [] });
    expect(mock.mock.calls[1]?.[0]).toBe('/api/plans/plan-id/rwms/apply');
    expect(requestBody(mock, 1)).toEqual({
      expected_version: 7,
      publish_unassigned_task_ids: [],
    });
    expect(applied).toEqual({
      applied: [{ order_id: '57ca2992-702f-4cc7-b478-6a2c64146241', document_id: '41b0e409-53d2-40af-a68c-d4de750005cc', replayed: false }],
      rejected: [{ order_id: '927a16a4-b40c-42a6-8f4d-d3b08eb0a1df', code: 'ORDER_VERSION_CONFLICT', message: 'order changed' }],
    });
    expect(mock.mock.calls[2]?.[0]).toBe('/api/plans/plan-id/rwms/status?expected_version=7');
    expect(mock.mock.calls[2]?.[1]?.body).toBeUndefined();
    expect(statuses.tasks[0]).toMatchObject({
      task_id: 'task-id',
      driver_audience_mode: 'WAREHOUSE_DRIVERS',
      driver_worker_id: null,
    });
  });
});
