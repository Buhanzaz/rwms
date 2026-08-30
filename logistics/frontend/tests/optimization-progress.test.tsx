import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../src/app/App';
import { useUiStore } from '../src/stores/ui-store';
import { warehouseFixture, workspaceFixture } from './fixtures';

vi.mock('../src/map/MapCanvas', () => ({
  MapCanvas: () => <div data-testid="common-map">common map</div>,
}));

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
    date: '2026-08-30',
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

function installRouter(options: { partialWorkspaceOnce?: boolean; acceptingRequests?: boolean } = {}) {
  let partialPending = options.partialWorkspaceOnce ?? false;
  let accepting = options.acceptingRequests ?? true;
  let ensureCalls = 0;
  const warehouse = warehouseFixture();
  const workspace = workspaceFixture();
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = requestUrl(input);
    const method = init?.method ?? 'GET';
    if (url === '/api/warehouses') return response([warehouse]);
    if (url === '/api/warehouses/available') return response([]);
    if (url === '/api/warehouses/warehouse-1/workspace' && partialPending) {
      partialPending = false;
      return response({ code: 'RWMS_WORKSPACE_SYNC_INCOMPLETE', detail: 'Одна заявка не обновлена', failures: [{ id: 'request-2' }] }, 422);
    }
    if (url === '/api/warehouses/warehouse-1/workspace' || url === '/api/warehouses/warehouse-1/workspace?refresh_rwms=false') return response(workspace);
    if (url === '/api/warehouses/warehouse-1/plans/ensure?date=2026-08-30' && method === 'POST') {
      ensureCalls += 1;
      return response(rawPlan({ version: ensureCalls }));
    }
    if (url === '/api/plans/plan-1' && method === 'GET') return response(rawPlan({ version: ensureCalls || 1 }));
    if (url === '/api/requests/request-1/planning-details' && method === 'POST') {
      const payload = JSON.parse(typeof init?.body === 'string' ? init.body : '{}') as { mandatory: boolean };
      workspace.requests[0] = { ...workspace.requests[0]!, mandatory: payload.mandatory };
      return response(workspace.requests[0]);
    }
    if (url === '/api/warehouses/warehouse-1/planning-days/2026-08-30' && method === 'GET') {
      return response({ warehouse_id: 'warehouse-1', date: '2026-08-30', accepting_requests: accepting, closed_at: null, closed_by: null, plan_id: 'plan-1' });
    }
    if (url === '/api/warehouses/warehouse-1/planning-days/2026-08-30/close' && method === 'POST') {
      accepting = false;
      return response({ warehouse_id: 'warehouse-1', date: '2026-08-30', accepting_requests: false, closed_at: '2026-08-28T10:00:00Z', closed_by: 'manager', plan_id: 'plan-1' });
    }
    throw new Error(`Unexpected request ${method} ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><App /></QueryClientProvider>);
}

beforeEach(() => {
  useUiStore.setState({ mode: 'PLAN_DAY', section: 'WAREHOUSE', notifications: [], selected: null });
});

describe('warehouse automatic planning', () => {
  it('loads one common map and automatically requests the selected warehouse plan', async () => {
    const fetchMock = installRouter();
    renderApp();

    expect(
      await screen.findByRole('combobox', { name: 'Главный склад группы' }),
    ).toHaveDisplayValue('Склад СПб · Санкт-Петербург');
    expect(screen.getByTestId('common-map')).toBeVisible();
    await waitFor(() => expect(fetchMock.mock.calls.some(([url, init]) =>
      url === '/api/warehouses/warehouse-1/plans/ensure?date=2026-08-30'
      && init?.method === 'POST')).toBe(true));
    expect(screen.queryByRole('button', { name: 'Сегодня' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Завтра' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Обмен с RWMS/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Сохранить план/ })).not.toBeInTheDocument();
  });

  it('falls back to persisted workspace state and visibly reports an incomplete automatic refresh', async () => {
    const fetchMock = installRouter({ partialWorkspaceOnce: true });
    renderApp();

    expect(await screen.findByRole('alert')).toHaveTextContent('RWMS не обновил доставки и вывозы: 1');
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/warehouses/warehouse-1/workspace?refresh_rwms=false')).toBe(true);
    expect(screen.getByTestId('common-map')).toBeVisible();
  });

  it('offers an explicit route refresh after mandatory delivery changes and rebuilds only on demand', async () => {
    const user = userEvent.setup();
    const fetchMock = installRouter();
    renderApp();

    await screen.findByRole('combobox', { name: 'Главный склад группы' });
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) =>
      url === '/api/warehouses/warehouse-1/plans/ensure?date=2026-08-30').length).toBe(1));
    await user.click(screen.getByRole('button', { name: /^Доставки/ }));
    await user.click(await screen.findByLabelText('Обязательная доставка'));
    await user.click(screen.getByRole('button', { name: 'Сохранить условия' }));

    const refreshButton = await screen.findByRole('button', { name: 'Обновить маршруты' });
    expect(fetchMock.mock.calls.filter(([url]) =>
      url === '/api/warehouses/warehouse-1/plans/ensure?date=2026-08-30').length).toBe(1);
    await user.click(refreshButton);
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) =>
      url === '/api/warehouses/warehouse-1/plans/ensure?date=2026-08-30').length).toBe(2));
    expect(screen.queryByRole('button', { name: 'Обновить маршруты' })).not.toBeInTheDocument();
  });
});
