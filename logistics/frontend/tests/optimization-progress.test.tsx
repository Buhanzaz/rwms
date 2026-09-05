import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../src/app/App';
import { useUiStore } from '../src/stores/ui-store';
import { dateInTimeZone } from '../src/utils/format';
import { requestFixture, warehouseFixture, workspaceFixture } from './fixtures';

vi.mock('../src/map/MapCanvas', () => ({
  MapCanvas: () => <div data-testid="common-map">common map</div>,
}));

const TEST_PLANNING_DATE = dateInTimeZone(new Date(), 'Europe/Moscow');

function response(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input;
  return input instanceof URL ? input.href : input.url;
}

function rawPlan(overrides: Record<string, unknown> = {}) {
  return {
    id: 'plan-1',
    warehouse_id: 'warehouse-1',
    date: TEST_PLANNING_DATE,
    version: 1,
    status: 'GENERATED',
    score: 0,
    created_at: '2026-08-28T08:00:00Z',
    updated_at: '2026-08-28T08:00:00Z',
    cycles: [],
    unassigned_tasks: [],
    metrics: {},
    manually_changed: false,
    ...overrides,
  };
}

function installRouter(options: { acceptingRequests?: boolean; plan?: Record<string, unknown>; ensureStatus?: number; staleTasks?: boolean; runStatus?: string } = {}) {
  let accepting = options.acceptingRequests ?? true;
  let ensureCalls = 0;
  const warehouse = warehouseFixture();
  const workspace = workspaceFixture({
    requests: [requestFixture({
      scheduled_date: TEST_PLANNING_DATE,
      date_options: [{
        date: TEST_PLANNING_DATE,
        priority: 1,
        window_start: '12:00',
        window_end: '15:00',
        is_hard: true,
      }],
    })],
  });
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = requestUrl(input);
    const method = init?.method ?? 'GET';
    if (url === '/api/warehouses') return response([warehouse]);
    if (url === '/api/warehouses/available') return response([]);
    if (url === `/api/warehouses/warehouse-1/workspace?planning_date=${TEST_PLANNING_DATE}&request_limit=250`) {
      return response(options.staleTasks && ensureCalls === 0
        ? { ...workspace, requests: workspace.requests.map((item) => ({ ...item, tasks: [] })) }
        : workspace);
    }
    if (url === `/api/warehouses/warehouse-1/plans/ensure?date=${TEST_PLANNING_DATE}` && method === 'POST') {
      ensureCalls += 1;
      return response(rawPlan({ version: ensureCalls, ...options.plan }), options.ensureStatus);
    }
    if (url === '/api/plans/plan-1' && method === 'GET') return response(rawPlan({ version: ensureCalls || 1, ...options.plan }));
    if (url === '/api/warehouses/warehouse-1/generate-workload' && method === 'POST') return response({
      start_date: TEST_PLANNING_DATE, end_date: TEST_PLANNING_DATE, created_requests: 1,
      created_deliveries: 1, created_pickups: 0, replaced_requests: 0, deleted_plans: 0,
      auto_plan_ids: ['plan-1'], auto_plan_run_ids: ['run-1'],
    });
    if (url === '/api/optimization-runs/run-1' && method === 'GET') return response({
      id: 'run-1', warehouse_id: 'warehouse-1', plan_id: 'plan-1', status: options.runStatus ?? 'COMPLETED',
      started_at: '2026-08-28T08:00:00Z', finished_at: '2026-08-28T08:00:05Z',
      seed: 1, settings_snapshot: {}, initial_score: 0, final_score: 0, error_message: null,
      stopped_by_limit: options.runStatus === 'TIMED_OUT', cancel_requested: false,
    });
    if (url === '/api/optimization-runs/run-1/stream') return new Response('', { headers: { 'Content-Type': 'text/event-stream' } });
    if (url === '/api/requests/request-1/planning-details' && method === 'POST') {
      const payload = JSON.parse(typeof init?.body === 'string' ? init.body : '{}') as { mandatory: boolean };
      workspace.requests[0] = { ...workspace.requests[0]!, mandatory: payload.mandatory };
      return response(workspace.requests[0]);
    }
    if (url === `/api/warehouses/warehouse-1/planning-days/${TEST_PLANNING_DATE}` && method === 'GET') {
      return response({ warehouse_id: 'warehouse-1', date: TEST_PLANNING_DATE, accepting_requests: accepting, closed_at: null, closed_by: null, plan_id: 'plan-1' });
    }
    if (url === `/api/warehouses/warehouse-1/planning-days/${TEST_PLANNING_DATE}/close` && method === 'POST') {
      accepting = false;
      return response({ warehouse_id: 'warehouse-1', date: TEST_PLANNING_DATE, accepting_requests: false, closed_at: '2026-08-28T10:00:00Z', closed_by: 'manager', plan_id: 'plan-1' });
    }
    throw new Error(`Unexpected request ${method} ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return { ...render(<QueryClientProvider client={client}><App /></QueryClientProvider>), client };
}

beforeEach(() => {
  useUiStore.setState({ mode: 'PLAN_DAY', section: 'WAREHOUSE', notifications: [], selected: null });
});

describe('warehouse automatic planning', () => {
  it('preserves the selected section and dismissed terminal warning when a terminal run plan is refreshed', async () => {
    const user = userEvent.setup();
    const plan = { version: 1, unassigned_tasks: [{ task_id: 'task-1', reason_codes: ['OPTIMIZATION_TIME_LIMIT'], descriptions_ru: [] }] };
    const fetchMock = installRouter({ plan, runStatus: 'TIMED_OUT' });
    const { client } = renderApp();
    await user.click(await screen.findByRole('button', { name: 'Создать нагрузку' }));
    await user.click(screen.getByRole('button', { name: 'Сгенерировать и заменить нагрузку' }));
    await screen.findByText(/Проверка маршрутов не завершена; рейсы не сохранены/);
    expect(useUiStore.getState().section).toBe('PLAN_DAY');

    await user.click(screen.getByRole('button', { name: /^Доставки/ }));
    const notification = useUiStore.getState().notifications.find((item) => item.title === 'Лимит времени достигнут')!;
    act(() => useUiStore.getState().dismissToast(notification.id));
    const notifications = useUiStore.getState().notifications;
    const previousReads = fetchMock.mock.calls.filter(([url]) => url === '/api/plans/plan-1').length;
    plan.version = 2;
    await act(async () => { await client.invalidateQueries({ queryKey: ['plan'] }); });

    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/plans/plan-1').length).toBeGreaterThan(previousReads);
    expect(useUiStore.getState().section).toBe('REQUESTS');
    expect(useUiStore.getState().notifications).toEqual(notifications);
    expect(screen.queryByText('Лимит времени достигнут')).not.toBeInTheDocument();
  });

  it('shows an incomplete search after a timeout and recovers tasks created after the workspace read', async () => {
    const fetchMock = installRouter({
      staleTasks: true,
      plan: { unassigned_tasks: [{ task_id: 'task-1', reason_codes: ['OPTIMIZATION_TIME_LIMIT'], descriptions_ru: [], recommendation_ru: null }] },
    });
    renderApp();

    expect(await screen.findByText('Лимит времени достигнут')).toBeVisible();
    expect(screen.getByText(/Проверка маршрутов не завершена\. Не распределено задач: 1/)).toBeVisible();
    expect(screen.queryByText('Допустимые маршруты не найдены')).not.toBeInTheDocument();
    expect(screen.queryByText('Автоплан не рассчитан')).not.toBeInTheDocument();
    expect(screen.queryByText(/План готов:/)).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([url]) => requestUrl(url).includes('/plans/ensure?'))).toHaveLength(1);
  });

  it('preserves the error notification when automatic planning really fails', async () => {
    installRouter({ ensureStatus: 503 });
    renderApp();

    expect(await screen.findByText('Автоплан не рассчитан')).toBeVisible();
    expect(screen.queryByText('Лимит времени достигнут')).not.toBeInTheDocument();
    expect(screen.queryByText(/План готов:/)).not.toBeInTheDocument();
  });

  it('reports saved trips in a partial result without announcing completed optimization', async () => {
    installRouter({ plan: {
      unassigned_tasks: [{ task_id: 'task-1', reason_codes: ['OPTIMIZATION_TIME_LIMIT'], descriptions_ru: [] }],
      cycles: [{
        id: 'cycle-1', driver_shift_id: 'shift-1', sequence: 1,
        planned_start: `${TEST_PLANNING_DATE}T08:00:00Z`, planned_finish: `${TEST_PLANNING_DATE}T09:00:00Z`,
        total_distance_meters: 10_000, total_travel_seconds: 3_600, total_service_seconds: 0,
        empty_distance_meters: 0, detour_seconds: 0, score: 1, locked: false, manually_changed: false,
        metrics: {}, stops: [], segments: [], explanations: [],
      }],
    } });
    renderApp();

    expect(await screen.findByText('Лимит времени достигнут')).toBeVisible();
    expect(screen.getByText(/Сохранено рейсов: 1\. Не распределено задач: 1/)).toBeVisible();
    expect(screen.queryByText(/План готов:/)).not.toBeInTheDocument();
    expect(screen.queryByText('Допустимые маршруты не найдены')).not.toBeInTheDocument();
  });

  it('keeps a proven resource shortage distinct from a timed out search', async () => {
    installRouter({ plan: { unassigned_tasks: [{ task_id: 'task-1', reason_codes: ['NO_ACTIVE_DRIVER'], descriptions_ru: [] }] } });
    renderApp();

    expect(await screen.findByText('Допустимые маршруты не найдены')).toBeVisible();
    expect(screen.queryByText('Лимит времени достигнут')).not.toBeInTheDocument();
  });

  it('loads one common map and automatically requests the selected warehouse plan', async () => {
    const fetchMock = installRouter();
    renderApp();

    expect(
      await screen.findByRole('button', { name: 'Склад логистической группы' }),
    ).toHaveTextContent('Склад СПб');
    expect(screen.getByTestId('common-map')).toBeVisible();
    await waitFor(() => expect(fetchMock.mock.calls.some(([url, init]) =>
      url === `/api/warehouses/warehouse-1/plans/ensure?date=${TEST_PLANNING_DATE}`
      && init?.method === 'POST')).toBe(true));
    expect(screen.queryByRole('button', { name: 'Сегодня' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Завтра' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Обмен с RWMS/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Сохранить план/ })).not.toBeInTheDocument();
  });

  it('loads the dated projection with one pure bounded GET and no refresh command', async () => {
    const fetchMock = installRouter();
    renderApp();

    await screen.findByRole('button', { name: 'Склад логистической группы' });
    const workspaceCalls = fetchMock.mock.calls.filter(([url]) => requestUrl(url).includes('/workspace?'));
    expect(workspaceCalls).toHaveLength(1);
    expect(workspaceCalls[0]?.[0]).toBe(`/api/warehouses/warehouse-1/workspace?planning_date=${TEST_PLANNING_DATE}&request_limit=250`);
    expect(workspaceCalls[0]?.[1]?.method ?? 'GET').toBe('GET');
    expect(fetchMock.mock.calls.some(([url]) => requestUrl(url).includes('/rwms/refresh'))).toBe(false);
    expect(screen.getByTestId('common-map')).toBeVisible();
  });

  it('offers an explicit route refresh after mandatory delivery changes and rebuilds only on demand', async () => {
    const user = userEvent.setup();
    const fetchMock = installRouter();
    renderApp();

    await screen.findByRole('button', { name: 'Склад логистической группы' });
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) =>
      url === `/api/warehouses/warehouse-1/plans/ensure?date=${TEST_PLANNING_DATE}`).length).toBe(1));
    await user.click(screen.getByRole('button', { name: /^Доставки/ }));
    await user.click(await screen.findByLabelText('Обязательная доставка'));
    await user.click(screen.getByRole('button', { name: 'Сохранить условия' }));

    const refreshButton = await screen.findByRole('button', { name: 'Обновить маршруты' });
    const planningDetailsCall = fetchMock.mock.calls.find(([url]) => url === '/api/requests/request-1/planning-details');
    const planningDetailsBody = planningDetailsCall?.[1]?.body;
    expect(JSON.parse(typeof planningDetailsBody === 'string' ? planningDetailsBody : '{}')).toMatchObject({ expected_version: 1 });
    expect(fetchMock.mock.calls.filter(([url]) =>
      url === `/api/warehouses/warehouse-1/plans/ensure?date=${TEST_PLANNING_DATE}`).length).toBe(1);
    await user.click(refreshButton);
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) =>
      url === `/api/warehouses/warehouse-1/plans/ensure?date=${TEST_PLANNING_DATE}`).length).toBe(2));
    expect(screen.queryByRole('button', { name: 'Обновить маршруты' })).not.toBeInTheDocument();
  });
});
