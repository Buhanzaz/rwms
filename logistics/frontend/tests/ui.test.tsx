import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../src/app/App';
import { CatalogDialog, RequestDialog } from '../src/components/EntityDialogs';
import type { LogisticsRequestInput, VehicleInput } from '../src/api/client';
import { NotificationCenter, ThemeSwitch, Toasts } from '../src/components/ui';
import { useUiStore } from '../src/stores/ui-store';
import { dateInTimeZone, formatDate, nextDate } from '../src/utils/format';
import { requestFixture, warehouseFixture, workspaceFixture } from './fixtures';

vi.mock('../src/map/MapCanvas', () => ({
  MapCanvas: () => <div data-testid="logistics-map" />,
}));

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><App /></QueryClientProvider>);
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input;
  return input instanceof URL ? input.href : input.url;
}

describe('application states', () => {
  beforeEach(() => {
    window.localStorage.clear();
    useUiStore.setState({
      mode: 'PLAN_DAY',
      section: 'WAREHOUSE',
      mapTool: 'SELECT',
      shiftVisibility: 'ACTIVE',
      selected: null,
      mapClickDraft: null,
      notifications: [],
      notificationDurationSeconds: 8,
    });
  });

  it('shows an actionable backend-unavailable state', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('network down'))));
    renderApp();
    expect(await screen.findByRole('alert')).toHaveTextContent('Сервис недоступен');
    expect(screen.getByRole('button', { name: 'Повторить' })).toBeEnabled();
  });

  it('shows automatic RWMS projection status without a manual connection action', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = requestUrl(input);
      const body = url.endsWith('/warehouses/available')
        ? [{ warehouse_id: '11111111-1111-4111-8111-111111111111', warehouse_version: 1, name: 'Склад СПб', city: 'Санкт-Петербург', address: null, latitude: null, longitude: null, timezone: 'Europe/Moscow', representative: false, routing_ready: false }]
        : [];
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    }));
    renderApp();
    expect(await screen.findByText('Склады RWMS синхронизируются')).toBeVisible();
    expect(screen.getByLabelText('Склады RWMS')).toHaveTextContent('Склад СПбНет координат в RWMS');
    expect(screen.queryByRole('button', { name: 'Добавить склад' })).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Выбрать склад RWMS' })).not.toBeInTheDocument();
  });

  it('uses compact actionable warehouse controls without the old product label', async () => {
    const warehouse = warehouseFixture();
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = requestUrl(input);
      let body: unknown = null;
      if (url.endsWith('/warehouses/available')) body = [{
        warehouse_id: warehouse.external_warehouse_id,
        warehouse_version: warehouse.external_warehouse_version,
        name: warehouse.name,
        city: warehouse.city,
        address: warehouse.address,
        latitude: warehouse.latitude,
        longitude: warehouse.longitude,
        timezone: warehouse.timezone,
        representative: warehouse.representative,
        routing_ready: true,
        local_warehouse_id: warehouse.id,
      }];
      else if (url.endsWith('/warehouses')) body = [warehouse];
      else if (url.includes(`/warehouses/${warehouse.id}/workspace`)) body = {
        warehouse,
        warehouses: [warehouse],
        drivers: [],
        vehicles: [],
        trailers: [],
        shifts: [],
        requests: [],
        plans: [],
      };
      else if (url.includes('/plans/ensure?')) body = null;
      else if (url.includes('/planning-days/')) body = {
        warehouse_id: warehouse.id,
        date: warehouse.default_planning_date,
        accepting_requests: true,
        closed_at: null,
        closed_by: null,
        plan_id: null,
      };
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    }));
    useUiStore.setState({ mode: 'PLAN_DAY', section: 'SETTINGS', selected: null });
    const user = userEvent.setup();
    renderApp();

    const warehouseSelector = await screen.findByRole('button', { name: 'Склад логистической группы' });
    expect(warehouseSelector).toHaveTextContent('Склад СПб');
    expect(warehouseSelector).toHaveTextContent('Europe/Moscow');
    expect(warehouseSelector).toHaveAttribute('aria-expanded', 'false');
    const warehouseHome = screen.getByRole('button', { name: 'Blockbox — Логистика: открыть склад' });
    expect(within(warehouseHome).getByText('BLOCKBOX')).toBeVisible();
    expect(within(warehouseHome).getByText('Логистика')).toBeVisible();
    expect(screen.queryByText('RWMS · Логистика')).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Текущий склад' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Добавить склад' })).not.toBeInTheDocument();

    await user.click(warehouseHome);
    expect(useUiStore.getState()).toMatchObject({ mode: 'PLAN_DAY', section: 'WAREHOUSE', selected: { kind: 'warehouse', id: warehouse.id } });
  });

  it('explains that a committed settings change is retrying its slot projection', async () => {
    const warehouse = warehouseFixture({
      capacity_generation: 12,
      capacity_published_generation: 11,
      capacity_publish_status: 'FAILED',
      capacity_publish_attempts: 1,
      capacity_publish_error_code: 'RWMS_CAPACITY_UNAVAILABLE',
      capacity_publish_next_attempt_at: '2026-08-31T04:00:05Z',
    });
    const workspace = workspaceFixture({ warehouse, warehouses: [warehouse], requests: [], plans: [] });
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = requestUrl(input);
      let body: unknown = null;
      if (url.endsWith('/warehouses/available')) body = [];
      else if (url.endsWith('/warehouses')) body = [warehouse];
      else if (url.includes(`/warehouses/${warehouse.id}/workspace`)) body = workspace;
      else if (url.includes('/planning-days/')) body = {
        warehouse_id: warehouse.id,
        date: warehouse.default_planning_date,
        accepting_requests: true,
        closed_at: null,
        closed_by: null,
        plan_id: null,
      };
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    }));

    renderApp();

    const warning = await screen.findByRole('alert');
    expect(warning).toHaveTextContent('Слоты обновляются');
    expect(warning).toHaveTextContent('Локальные настройки сохранены');
    expect(warning).toHaveTextContent('повторно сохранять форму не нужно');
    expect(warning).not.toHaveTextContent('RWMS_CAPACITY_UNAVAILABLE');
  });

  it('switches from a planning root to its representative warehouse in the group selector', async () => {
    const mainWarehouse = warehouseFixture({ id: 'warehouse-main', name: 'Опорный склад', city: 'Основной город' });
    const representativeWarehouse = warehouseFixture({
      id: 'warehouse-representative',
      external_warehouse_id: '22222222-2222-4222-8222-222222222222',
      name: 'Представительский склад',
      city: 'Региональный город',
      timezone: 'Asia/Novosibirsk',
      representative: true,
    });
    const otherRoot = warehouseFixture({
      id: 'warehouse-other-root',
      external_warehouse_id: '33333333-3333-4333-8333-333333333333',
      name: 'Другой основной склад',
      city: 'Другой город',
    });
    const otherRepresentative = warehouseFixture({
      id: 'warehouse-other-representative',
      external_warehouse_id: '44444444-4444-4444-8444-444444444444',
      name: 'Склад Тверь',
      city: 'Тверь',
      representative: true,
    });
    const rootWorkspace = workspaceFixture({
      warehouse: mainWarehouse,
      planning_root_warehouse_id: mainWarehouse.id,
      planning_group_warehouse_ids: [mainWarehouse.id, representativeWarehouse.id],
      warehouses: [otherRoot, otherRepresentative, representativeWarehouse, mainWarehouse],
      requests: [],
      plans: [],
    });
    const representativeWorkspace = { ...rootWorkspace, warehouse: representativeWarehouse };
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = requestUrl(input);
      let body: unknown = null;
      if (url.endsWith('/warehouses/available')) body = [];
      else if (url.endsWith('/warehouses')) body = [mainWarehouse, representativeWarehouse, otherRoot, otherRepresentative];
      else if (url.includes(`/warehouses/${mainWarehouse.id}/workspace`)) body = rootWorkspace;
      else if (url.includes(`/warehouses/${representativeWarehouse.id}/workspace`)) body = representativeWorkspace;
      else if (url.includes(`/warehouses/${mainWarehouse.id}/plans/ensure`)) body = null;
      else if (url.includes(`/warehouses/${mainWarehouse.id}/planning-days/`)) body = {
        warehouse_id: mainWarehouse.id,
        date: mainWarehouse.default_planning_date,
        accepting_requests: true,
        closed_at: null,
        closed_by: null,
        plan_id: null,
      };
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    });
    vi.stubGlobal('fetch', fetchMock);
    useUiStore.setState({ mode: 'PLAN_DAY', section: 'WAREHOUSE', selected: null });
    const user = userEvent.setup();
    renderApp();

    const trigger = await screen.findByRole('button', { name: 'Склад логистической группы' });
    expect(trigger).toHaveTextContent('Склад Основной город');
    await user.click(trigger);

    const listbox = screen.getByRole('listbox', { name: 'Склад логистической группы' });
    const options = within(listbox).getAllByRole('option');
    expect(options.map((option) => option.textContent)).toEqual([
      'Склад Основной город',
      'Представительский склад Региональный город / Основной город',
      'Склад Другой город',
      'Представительский склад Тверь',
    ]);
    expect(options[0]).toHaveAttribute('aria-selected', 'true');

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('listbox', { name: 'Склад логистической группы' })).not.toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    await user.click(trigger);
    await user.click(within(screen.getByRole('listbox', { name: 'Склад логистической группы' })).getByRole('option', { name: /Представительский склад Региональный город/ }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'Склад логистической группы' })).toHaveTextContent('Представительский склад Региональный город / Основной город'));
    expect(screen.getByRole('button', { name: 'Склад логистической группы' })).toHaveTextContent('Asia/Novosibirsk');
    expect(fetchMock.mock.calls.map(([input]) => requestUrl(input))).toContainEqual(expect.stringContaining(`/warehouses/${representativeWarehouse.id}/workspace`));
    await user.click(screen.getByRole('button', { name: 'Склад логистической группы' }));
    expect(within(screen.getByRole('listbox', { name: 'Склад логистической группы' })).getByRole('option', { name: /Представительский склад Региональный город/ })).toHaveAttribute('aria-selected', 'true');
    expect(within(screen.getByRole('listbox', { name: 'Склад логистической группы' })).getByRole('option', { name: 'Представительский склад Тверь' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Дата планирования' }));
    expect(screen.queryByRole('listbox', { name: 'Склад логистической группы' })).not.toBeInTheDocument();
    expect(window.localStorage.getItem('rwms:logistics:last-warehouse')).toBe(representativeWarehouse.id);
  });

  it('restores the saved representative warehouse and its planning date', async () => {
    const mainWarehouse = warehouseFixture({ id: 'warehouse-main', name: 'Опорный склад' });
    const representativeWarehouse = warehouseFixture({
      id: 'warehouse-representative',
      name: 'Склад Великий Новгород',
      city: 'Великий Новгород',
      representative: true,
    });
    const workspace = workspaceFixture({
      warehouse: representativeWarehouse,
      warehouses: [mainWarehouse, representativeWarehouse],
      planning_root_warehouse_id: mainWarehouse.id,
      planning_group_warehouse_ids: [mainWarehouse.id, representativeWarehouse.id],
      requests: [],
      plans: [],
    });
    window.localStorage.setItem('rwms:logistics:last-warehouse', representativeWarehouse.id);
    window.localStorage.setItem(`rwms:logistics:planning-date:${representativeWarehouse.id}`, '2026-09-02');
    useUiStore.setState({ mode: 'PLAN_DAY', section: 'SHIFTS', shiftVisibility: 'ARCHIVED', selected: null });
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = requestUrl(input);
      let body: unknown = null;
      if (url.endsWith('/warehouses/available')) body = [];
      else if (url.endsWith('/warehouses')) body = [mainWarehouse, representativeWarehouse];
      else if (url.includes(`/warehouses/${representativeWarehouse.id}/workspace`)) body = { ...workspace, planning_date: '2026-09-02' };
      else if (url.includes(`/warehouses/${mainWarehouse.id}/plans/ensure`)) body = null;
      else if (url.includes(`/warehouses/${mainWarehouse.id}/planning-days/2026-09-02`)) body = {
        warehouse_id: mainWarehouse.id,
        date: '2026-09-02',
        accepting_requests: true,
        closed_at: null,
        closed_by: null,
        plan_id: null,
      };
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    }));

    renderApp();

    expect(await screen.findByRole('button', { name: 'Склад логистической группы' })).toHaveTextContent('Представительский склад Великий Новгород / Санкт-Петербург');
    expect(screen.getByRole('button', { name: 'Дата планирования' })).toHaveTextContent('2 сентября 2026');
    expect(screen.getByRole('button', { name: 'Архив' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('Смен по выбранному фильтру нет')).toBeVisible();
  });

  it('baselines existing regional requests and opens only a request received afterwards', async () => {
    const mainWarehouse = warehouseFixture({ id: 'warehouse-main', name: 'Опорный склад' });
    const representativeWarehouse = warehouseFixture({
      id: 'warehouse-representative',
      external_warehouse_id: '22222222-2222-4222-8222-222222222222',
      name: 'Представительский склад',
      city: 'Региональный город',
      representative: true,
    });
    const existingRegionalRequest = requestFixture({
      id: 'existing-regional-request',
      warehouse_id: representativeWarehouse.id,
      source_system: 'RWMS',
      name: 'Существующий заказ',
    });
    const regionalRequest = requestFixture({
      id: 'regional-request',
      warehouse_id: representativeWarehouse.id,
      source_system: 'RWMS',
      name: 'Заказ 427',
      scheduled_date: '2026-09-02',
      date_options: [{
        date: '2026-09-02',
        priority: 1,
        window_start: null,
        window_end: null,
        is_hard: false,
      }],
    });
    let workspace = workspaceFixture({
      warehouse: mainWarehouse,
      planning_root_warehouse_id: mainWarehouse.id,
      planning_group_warehouse_ids: [mainWarehouse.id, representativeWarehouse.id],
      warehouses: [mainWarehouse, representativeWarehouse],
      requests: [
        existingRegionalRequest,
        requestFixture({ id: 'generated-request', warehouse_id: representativeWarehouse.id, source_system: 'WAREHOUSE_WORKLOAD_GENERATOR' }),
        requestFixture({ id: 'manual-request', warehouse_id: representativeWarehouse.id, source_system: null }),
      ],
      plans: [],
    });
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = requestUrl(input);
      let body: unknown = null;
      if (url.endsWith('/warehouses/available')) body = [];
      else if (url.endsWith('/warehouses')) body = [mainWarehouse];
      else if (url.includes(`/warehouses/${mainWarehouse.id}/workspace`)) body = workspace;
      else if (url.includes('/planning-days/')) body = {
        warehouse_id: mainWarehouse.id,
        date: url.split('/').at(-1),
        accepting_requests: true,
        closed_at: null,
        closed_by: null,
        plan_id: null,
      };
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    });
    vi.stubGlobal('fetch', fetchMock);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const user = userEvent.setup();
    render(<QueryClientProvider client={queryClient}><App /></QueryClientProvider>);

    await screen.findByRole('button', { name: 'Склад логистической группы' });
    expect(useUiStore.getState().notifications).toEqual([]);

    workspace = {
      ...workspace,
      requests: [...workspace.requests, regionalRequest],
    };
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ['workspace', mainWarehouse.id] });
    });

    expect(await screen.findByText('Новая заявка · Представительский склад')).toBeVisible();
    expect(useUiStore.getState().notifications).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'Уведомления: 1 новых' }));
    await user.click(screen.getByRole('button', { name: 'Открыть заявку: Новая заявка · Представительский склад' }));

    expect(screen.queryByRole('region', { name: 'История уведомлений' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Дата планирования' })).toHaveTextContent('2 сентября 2026');
    expect(useUiStore.getState()).toMatchObject({
      mode: 'PLAN_DAY',
      section: 'REQUESTS',
      selected: { kind: 'request', id: regionalRequest.id },
    });
  });

  it('keeps deliveries and pickups open when their date arrows change the planning day', async () => {
    const warehouse = warehouseFixture();
    const workspace = workspaceFixture({
      warehouse,
      requests: [requestFixture()],
      plans: [],
    });
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = requestUrl(input);
      let body: unknown = null;
      if (url.endsWith('/warehouses/available')) body = [];
      else if (url.endsWith('/warehouses')) body = [warehouse];
      else if (url.includes(`/warehouses/${warehouse.id}/workspace`)) body = workspace;
      else if (url.includes('/planning-days/')) body = {
        warehouse_id: warehouse.id,
        date: url.split('/').at(-1),
        accepting_requests: true,
        closed_at: null,
        closed_by: null,
        plan_id: null,
      };
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    });
    vi.stubGlobal('fetch', fetchMock);
    useUiStore.setState({ mode: 'PLAN_DAY', section: 'REQUESTS', selected: null });
    const user = userEvent.setup();
    renderApp();

    await user.click(await screen.findByRole('button', { name: /^Доставки/ }));
    const currentWarehouseDate = dateInTimeZone(new Date(), warehouse.timezone);
    const followingWarehouseDate = nextDate(currentWarehouseDate, 1);
    await user.click(screen.getByRole('button', {
      name: `Следующая дата: ${formatDate(followingWarehouseDate)}`,
    }));

    await waitFor(() => expect(fetchMock.mock.calls.map(([input]) => requestUrl(input)))
      .toContainEqual(expect.stringContaining(`/planning-days/${followingWarehouseDate}`)));
    expect(fetchMock.mock.calls.map(([input]) => requestUrl(input))).toContainEqual(expect.stringContaining(
      `/workspace?planning_date=${followingWarehouseDate}&request_limit=250`,
    ));
    expect(useUiStore.getState().section).toBe('REQUESTS');
    expect(screen.getByRole('region', { name: 'Подготовка доставок и вывозов на день' })).toBeVisible();
  });

  it('loads the next bounded request page explicitly without changing the inspector section', async () => {
    const warehouse = warehouseFixture();
    const planningDate = dateInTimeZone(new Date(), warehouse.timezone);
    const onDate = (id: string, name: string) => requestFixture({
      id,
      name,
      scheduled_date: planningDate,
      date_options: [{ date: planningDate, priority: 1, window_start: '12:00', window_end: '15:00', is_hard: true }],
    });
    const firstPage = workspaceFixture({
      warehouse,
      planning_date: planningDate,
      requests: [onDate('request-first', 'Доставка первая')],
      request_total: 2,
      request_next_cursor: 'request-cursor',
      plans: [],
    });
    const secondPage = workspaceFixture({
      warehouse,
      planning_date: planningDate,
      requests: [onDate('request-second', 'Доставка вторая')],
      request_total: 2,
      request_next_cursor: null,
      plans: [],
    });
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = requestUrl(input);
      let body: unknown = null;
      if (url.endsWith('/warehouses/available')) body = [];
      else if (url.endsWith('/warehouses')) body = [warehouse];
      else if (url.includes(`/warehouses/${warehouse.id}/workspace`)) body = url.includes('request_cursor=request-cursor') ? secondPage : firstPage;
      else if (url.includes('/planning-days/')) body = { warehouse_id: warehouse.id, date: planningDate, accepting_requests: true, closed_at: null, closed_by: null, plan_id: null };
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    });
    vi.stubGlobal('fetch', fetchMock);
    useUiStore.setState({ mode: 'PLAN_DAY', section: 'REQUESTS', selected: null });
    const user = userEvent.setup();

    renderApp();

    expect(await screen.findByText('· №первая')).toBeVisible();
    expect(screen.getByText('Показано заявок: 1 из 2')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Загрузить ещё заявки' }));

    expect(await screen.findByText('· №вторая')).toBeVisible();
    expect(screen.getByText('Показано заявок: 2 из 2')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Загрузить ещё заявки' })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.map(([input]) => requestUrl(input))).toContainEqual(expect.stringContaining(
      `planning_date=${planningDate}&request_limit=250&request_cursor=request-cursor`,
    ));
    expect(useUiStore.getState().section).toBe('REQUESTS');
  });

  it('clears accumulated request pages after a mutation refresh and ignores a late page response', async () => {
    const warehouse = warehouseFixture();
    const planningDate = dateInTimeZone(new Date(), warehouse.timezone);
    const onDate = (id: string, name: string) => requestFixture({
      id,
      name,
      scheduled_date: planningDate,
      date_options: [{ date: planningDate, priority: 1, window_start: '12:00', window_end: '15:00', is_hard: true }],
    });
    const firstPage = workspaceFixture({
      warehouse,
      planning_date: planningDate,
      requests: [onDate('request-first', 'Доставка первая')],
      request_total: 3,
      request_next_cursor: 'request-cursor',
      plans: [],
    });
    const secondRequest = onDate('request-second', 'Доставка вторая');
    const secondPage = workspaceFixture({
      warehouse,
      planning_date: planningDate,
      requests: [secondRequest],
      request_total: 3,
      request_next_cursor: 'late-cursor',
      plans: [],
    });
    const latePage = workspaceFixture({
      warehouse,
      planning_date: planningDate,
      requests: [onDate('request-late', 'Устаревшая доставка')],
      request_total: 3,
      request_next_cursor: null,
      plans: [],
    });
    let resolveLatePage!: (response: Response) => void;
    const latePageResponse = new Promise<Response>((resolve) => { resolveLatePage = resolve; });
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>((input, init) => {
      const url = requestUrl(input);
      let body: unknown = null;
      if (url.endsWith('/warehouses/available')) body = [];
      else if (url.endsWith('/warehouses')) body = [warehouse];
      else if (url.includes('request_cursor=late-cursor')) return latePageResponse;
      else if (url.includes('request_cursor=request-cursor')) body = secondPage;
      else if (url.includes(`/warehouses/${warehouse.id}/workspace`)) body = firstPage;
      else if (url === '/api/requests/request-second/planning-details' && init?.method === 'POST') body = { ...secondRequest, version: 2, mandatory: true };
      else if (url.endsWith(`/warehouses/${warehouse.id}/planning-days/${planningDate}/operations`)) body = {
        warehouse_id: warehouse.id,
        day: planningDate,
        mode: 'DELIVERIES_AND_PICKUPS',
        mode_version: 0,
        pending_action_count: 0,
        events: [],
        notices: [],
        actions: [],
        decisions: [],
        proposals: [],
        truncated_collections: [],
      };
      else if (url.includes('/planning-days/')) body = { warehouse_id: warehouse.id, date: planningDate, accepting_requests: true, closed_at: null, closed_by: null, plan_id: null };
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    });
    vi.stubGlobal('fetch', fetchMock);
    useUiStore.setState({ mode: 'PLAN_DAY', section: 'REQUESTS', selected: null });
    const user = userEvent.setup();

    renderApp();
    expect(await screen.findByText('· №первая')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Загрузить ещё заявки' }));
    const secondCard = await screen.findByTestId('planning-request-request-second');
    await user.click(screen.getByRole('button', { name: 'Загрузить ещё заявки' }));
    await waitFor(() => expect(fetchMock.mock.calls.map(([input]) => requestUrl(input)))
      .toContainEqual(expect.stringContaining('request_cursor=late-cursor')));
    await user.click(within(secondCard).getByLabelText('Обязательная доставка'));
    await user.click(within(secondCard).getByRole('button', { name: 'Сохранить условия' }));

    expect(await screen.findByText('Условия доставки сохранены')).toBeVisible();
    expect(useUiStore.getState().section).toBe('REQUESTS');
    expect(screen.getByRole('region', { name: 'Подготовка доставок и вывозов на день' })).toBeVisible();
    await user.click(screen.getByTitle('Доставки'));
    expect(await screen.findByTestId('planning-request-request-first')).toBeVisible();
    expect(screen.queryByTestId('planning-request-request-second')).not.toBeInTheDocument();
    await act(async () => {
      resolveLatePage(new Response(JSON.stringify(latePage), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      await new Promise((resolve) => { setTimeout(resolve, 0); });
    });
    expect(screen.queryByTestId('planning-request-request-late')).not.toBeInTheDocument();
    expect(screen.getByTestId('planning-request-request-first')).toBeVisible();
  });

  it('does not offer staff-driver creation from logistics', async () => {
    const warehouse = warehouseFixture({ id: 'warehouse-local', name: 'Региональный склад', representative: true });
    const root = warehouseFixture({ id: 'warehouse-root', name: 'Опорный склад' });
    const workspace = workspaceFixture({
      warehouse,
      warehouses: [root, warehouse],
      planning_root_warehouse_id: root.id,
      planning_group_warehouse_ids: [root.id, warehouse.id],
      drivers: [],
      shifts: [],
      requests: [],
      plans: [],
    });
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>((input) => {
      const url = requestUrl(input);
      let body: unknown = null;
      if (url.endsWith('/warehouses/available')) body = [];
      else if (url.endsWith('/warehouses')) body = [warehouse, root];
      else if (url.includes(`/warehouses/${warehouse.id}/workspace`)) body = workspace;
      else if (url.includes('/planning-days/')) body = { warehouse_id: warehouse.id, date: workspace.planning_date, accepting_requests: true, closed_at: null, closed_by: null, plan_id: null };
      else if (url.endsWith(`/warehouses/${warehouse.id}/available-drivers`)) body = [];
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    });
    vi.stubGlobal('fetch', fetchMock);
    window.localStorage.setItem('rwms:logistics:last-warehouse', warehouse.id);
    useUiStore.setState({ mode: 'PLAN_DAY', section: 'DRIVERS', selected: null });
    renderApp();
    expect(await screen.findByRole('heading', { name: 'Водители — Региональный склад' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Добавить' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Изменить' })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([input, init]) => requestUrl(input).endsWith(`/warehouses/${warehouse.id}/drivers`) && init?.method === 'POST')).toBe(false);
    expect(fetchMock.mock.calls.some(([input]) => requestUrl(input).endsWith(`/warehouses/${warehouse.id}/available-drivers`))).toBe(false);
  });

  it('builds and closes the common day through the planning root when a representative is open', async () => {
    const mainWarehouse = warehouseFixture({ id: 'warehouse-main', name: 'Опорный склад' });
    const representativeWarehouse = warehouseFixture({
      id: 'warehouse-representative',
      external_warehouse_id: '22222222-2222-4222-8222-222222222222',
      name: 'Представительский склад',
      city: 'Региональный город',
      representative: true,
    });
    const workspace = workspaceFixture({
      warehouse: representativeWarehouse,
      planning_root_warehouse_id: mainWarehouse.id,
      planning_group_warehouse_ids: [mainWarehouse.id, representativeWarehouse.id],
      warehouses: [mainWarehouse, representativeWarehouse],
      requests: [],
      plans: [],
    });
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = requestUrl(input);
      let body: unknown = null;
      if (url.endsWith('/warehouses/available')) body = [];
      else if (url.endsWith('/warehouses')) body = [representativeWarehouse];
      else if (url.includes(`/warehouses/${representativeWarehouse.id}/workspace`)) body = workspace;
      else if (url.includes(`/warehouses/${mainWarehouse.id}/plans/ensure`)) body = null;
      else if (url.includes(`/warehouses/${mainWarehouse.id}/planning-days/`)) body = {
        warehouse_id: mainWarehouse.id,
        date: representativeWarehouse.default_planning_date,
        accepting_requests: true,
        closed_at: null,
        closed_by: null,
        plan_id: null,
      };
      else if (url.includes(`/warehouses/${mainWarehouse.id}/generated-workload?`) && init?.method === 'DELETE') body = {
        warehouse_id: mainWarehouse.id,
        date: representativeWarehouse.default_planning_date,
        deleted_requests: 3,
        deleted_plans: 1,
        capacity_projection_status: 'PUBLISHED',
      };
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    });
    vi.stubGlobal('fetch', fetchMock);
    useUiStore.setState({ mode: 'PLAN_DAY', section: 'WAREHOUSE', selected: null });
    const user = userEvent.setup();

    renderApp();

    expect(await screen.findByRole('button', { name: 'Склад логистической группы' })).toHaveTextContent('Представительский склад Региональный город / Санкт-Петербург');
    await waitFor(() => {
      const urls = fetchMock.mock.calls.map(([input]) => requestUrl(input));
      expect(urls.some((url) => url.includes(`/warehouses/${mainWarehouse.id}/plans/ensure`))).toBe(true);
      expect(urls.some((url) => url.includes(`/warehouses/${mainWarehouse.id}/planning-days/`))).toBe(true);
      expect(urls.some((url) => url.includes(`/warehouses/${representativeWarehouse.id}/plans/ensure`))).toBe(false);
    });
    await user.click(screen.getByRole('button', { name: 'Удалить нагрузку' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Удалить нагрузку' }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([input, init]) => (
      requestUrl(input).includes(`/warehouses/${mainWarehouse.id}/generated-workload?`)
      && init?.method === 'DELETE'
    ))).toBe(true));
    expect(fetchMock.mock.calls.some(([input, init]) => (
      requestUrl(input).includes(`/warehouses/${representativeWarehouse.id}/generated-workload?`)
      && init?.method === 'DELETE'
    ))).toBe(false);
  });
});

describe('notification center', () => {
  beforeEach(() => useUiStore.setState({ notifications: [], notificationDurationSeconds: 8 }));

  it('keeps hidden toasts in bell history and clears the full history', async () => {
    const user = userEvent.setup();
    useUiStore.getState().toast({ tone: 'success', title: 'План готов', detail: '3 рейса' });
    const id = useUiStore.getState().notifications[0]!.id;
    useUiStore.getState().dismissToast(id);
    render(<><Toasts /><NotificationCenter /></>);

    expect(screen.queryByText('План готов')).not.toBeInTheDocument();
    const notificationsButton = screen.getByRole('button', { name: 'Уведомления: 1 новых' });
    expect(notificationsButton.querySelector('.notification-center__badge')).toHaveTextContent('1');
    await user.click(notificationsButton);
    expect(screen.getByRole('region', { name: 'История уведомлений' })).toHaveTextContent('План готов3 рейса');
    await user.click(screen.getByRole('button', { name: 'Очистить всё' }));
    expect(useUiStore.getState().notifications).toEqual([]);
  });

  it('hides a toast after the configured eight seconds without deleting its history entry', () => {
    vi.useFakeTimers();
    try {
      render(<Toasts />);
      act(() => useUiStore.getState().toast({ tone: 'info', title: 'Новая доставка' }));
      expect(screen.getByText('Новая доставка')).toBeVisible();
      act(() => { vi.advanceTimersByTime(7_999); });
      expect(screen.getByText('Новая доставка')).toBeVisible();
      act(() => { vi.advanceTimersByTime(1); });
      expect(screen.queryByText('Новая доставка')).not.toBeInTheDocument();
      expect(useUiStore.getState().notifications[0]).toMatchObject({ title: 'Новая доставка', visible: false });
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('theme switch', () => {
  it('uses one icon button to switch themes and persists the choice', async () => {
    const user = userEvent.setup();
    useUiStore.getState().setTheme('light');
    render(<ThemeSwitch />);

    const theme = screen.getByRole('button', { name: 'Включить тёмную тему' });
    expect(theme).toHaveAttribute('aria-pressed', 'false');
    expect(theme.querySelector('.lucide-sun')).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute('data-theme', 'light');

    await user.click(theme);

    const darkTheme = screen.getByRole('button', { name: 'Включить светлую тему' });
    expect(darkTheme).toHaveAttribute('aria-pressed', 'true');
    expect(darkTheme.querySelector('.lucide-moon')).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
    expect(window.localStorage.getItem('rwms-logistics-theme')).toBe('dark');
  });
});

describe('request editor', () => {
  it('keeps cargo fields out of logistics and creates a request without inventing its dimensions', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: LogisticsRequestInput) => Promise<void>>(() => Promise.resolve());
    render(<RequestDialog type="DELIVERY" point={{ latitude: 55.7, longitude: 37.6 }} defaultDate="2026-08-25" busy={false} onClose={() => undefined} onSubmit={submit} />);
    expect(screen.getByText(/изохроне склада/)).toBeVisible();
    await user.type(screen.getByLabelText('Название / номер'), '№142');
    expect(screen.getByText('Параметры из заказа клиента')).toBeVisible();
    expect(screen.getByText('Ожидаем параметры бытовки из заказа')).toBeVisible();
    expect(screen.queryByLabelText('Длина бытовки, мм')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Масса бытовки, кг')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Широта')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Долгота')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Сохранить доставку' }));
    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    const submitted = submit.mock.calls[0]?.[0];
    expect(submitted).toMatchObject({ cargo_length_mm: null, cargo_width_mm: null, cargo_height_mm: null, cargo_weight_kg: null });
    expect(submitted?.date_options).toHaveLength(1);
  });

  it('preserves the cargo profile provided by a client order', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: LogisticsRequestInput) => Promise<void>>(() => Promise.resolve());
    const request = requestFixture({
      cargo_length_mm: 6000,
      cargo_width_mm: 2400,
      cargo_height_mm: 2400,
      cargo_weight_kg: 2500,
    });
    render(<RequestDialog request={request} type="DELIVERY" defaultDate="2026-08-25" busy={false} onClose={() => undefined} onSubmit={submit} />);
    expect(screen.getByText('6.0 × 2.4 × 2.4 м · 2.5 т')).toBeVisible();
    await user.clear(screen.getByLabelText('Название / номер'));
    await user.type(screen.getByLabelText('Название / номер'), 'Заказ 98');
    await user.click(screen.getByRole('button', { name: 'Сохранить доставку' }));
    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({
      cargo_length_mm: 6000,
      cargo_width_mm: 2400,
      cargo_height_mm: 2400,
      cargo_weight_kg: 2500,
    });
  });
});

describe('vehicle editor', () => {
  it('starts a new vehicle with an empty name', () => {
    const vehicleSubmit = vi.fn<(input: VehicleInput) => Promise<void>>(() => Promise.resolve());
    render(<CatalogDialog busy={false} onClose={() => undefined} onSubmit={(input) => vehicleSubmit(input as VehicleInput)} />);

    expect(screen.getByLabelText('Название')).toHaveValue('');
  });

  it('rejects capacity above the backend maximum with an inline error', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: VehicleInput) => Promise<void>>(() => Promise.resolve());
    render(<CatalogDialog busy={false} onClose={() => undefined} onSubmit={(input) => submit(input as VehicleInput)} />);
    await user.type(screen.getByLabelText('Название'), 'Машина 1');
    await user.type(screen.getByLabelText('Госномер'), 'А123БВ');
    const capacity = screen.getByLabelText('Вместимость, бытовок');
    await user.clear(capacity);
    await user.type(capacity, '3');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));
    expect(await screen.findByText('Для MVP вместимость не может превышать 2')).toBeVisible();
    expect(submit).not.toHaveBeenCalled();
  });
});
