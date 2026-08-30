import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../src/app/App';
import { CatalogDialog, RequestDialog } from '../src/components/EntityDialogs';
import type { DriverInput, LogisticsRequestInput, VehicleInput } from '../src/api/client';
import { NotificationCenter, ThemeSwitch, Toasts } from '../src/components/ui';
import { useUiStore } from '../src/stores/ui-store';
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
  beforeEach(() => useUiStore.setState({ notifications: [], notificationDurationSeconds: 8 }));

  it('shows an actionable backend-unavailable state', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('network down'))));
    renderApp();
    expect(await screen.findByRole('alert')).toHaveTextContent('Backend недоступен');
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
    expect(warehouseSelector).toHaveTextContent('Склад СПб — Санкт-Петербург');
    expect(warehouseSelector).toHaveAttribute('aria-expanded', 'false');
    const warehouseHome = screen.getByRole('button', { name: 'Открыть склад' });
    expect(screen.queryByText('RWMS · Логистика')).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Текущий склад' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Добавить склад' })).not.toBeInTheDocument();

    await user.click(warehouseHome);
    expect(useUiStore.getState()).toMatchObject({ mode: 'PLAN_DAY', section: 'WAREHOUSE', selected: { kind: 'warehouse', id: warehouse.id } });
  });

  it('switches from a planning root to its representative warehouse in the group selector', async () => {
    const mainWarehouse = warehouseFixture({ id: 'warehouse-main', name: 'Опорный склад', city: 'Основной город' });
    const representativeWarehouse = warehouseFixture({
      id: 'warehouse-representative',
      external_warehouse_id: '22222222-2222-4222-8222-222222222222',
      name: 'Представительский склад',
      city: 'Региональный город',
      representative: true,
    });
    const otherRoot = warehouseFixture({
      id: 'warehouse-other-root',
      external_warehouse_id: '33333333-3333-4333-8333-333333333333',
      name: 'Другой основной склад',
      city: 'Другой город',
    });
    const rootWorkspace = workspaceFixture({
      warehouse: mainWarehouse,
      planning_root_warehouse_id: mainWarehouse.id,
      planning_group_warehouse_ids: [mainWarehouse.id, representativeWarehouse.id],
      warehouses: [otherRoot, representativeWarehouse, mainWarehouse],
      requests: [],
      plans: [],
    });
    const representativeWorkspace = { ...rootWorkspace, warehouse: representativeWarehouse };
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = requestUrl(input);
      let body: unknown = null;
      if (url.endsWith('/warehouses/available')) body = [];
      else if (url.endsWith('/warehouses')) body = [mainWarehouse, representativeWarehouse, otherRoot];
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
    expect(trigger).toHaveTextContent('Опорный склад — Основной город');
    await user.click(trigger);

    const listbox = screen.getByRole('listbox', { name: 'Склад логистической группы' });
    const options = within(listbox).getAllByRole('option');
    expect(options.map((option) => option.textContent)).toEqual([
      'Опорный склад — Основной город',
      '\u00a0\u00a0· Представительский склад — Региональный город',
      'Другой основной склад — Другой город',
    ]);
    expect(options[0]).toHaveAttribute('aria-selected', 'true');

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('listbox', { name: 'Склад логистической группы' })).not.toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    await user.click(trigger);
    await user.click(within(screen.getByRole('listbox', { name: 'Склад логистической группы' })).getByRole('option', { name: /Представительский склад/ }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'Склад логистической группы' })).toHaveTextContent('Представительский склад — Региональный город'));
    expect(fetchMock.mock.calls.map(([input]) => requestUrl(input))).toContainEqual(expect.stringContaining(`/warehouses/${representativeWarehouse.id}/workspace`));
    await user.click(screen.getByRole('button', { name: 'Склад логистической группы' }));
    expect(within(screen.getByRole('listbox', { name: 'Склад логистической группы' })).getByRole('option', { name: /Представительский склад/ })).toHaveAttribute('aria-selected', 'true');
    await user.click(screen.getByRole('button', { name: 'Дата планирования' }));
    expect(screen.queryByRole('listbox', { name: 'Склад логистической группы' })).not.toBeInTheDocument();
  });

  it('opens a new RWMS request from a direct representative warehouse on its planning date', async () => {
    const mainWarehouse = warehouseFixture({ id: 'warehouse-main', name: 'Опорный склад' });
    const representativeWarehouse = warehouseFixture({
      id: 'warehouse-representative',
      external_warehouse_id: '22222222-2222-4222-8222-222222222222',
      name: 'Представительский склад',
      city: 'Региональный город',
      representative: true,
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
    const workspace = workspaceFixture({
      warehouse: mainWarehouse,
      planning_root_warehouse_id: mainWarehouse.id,
      planning_group_warehouse_ids: [mainWarehouse.id, representativeWarehouse.id],
      warehouses: [mainWarehouse, representativeWarehouse],
      requests: [
        regionalRequest,
        requestFixture({ id: 'generated-request', warehouse_id: representativeWarehouse.id, source_system: 'WAREHOUSE_WORKLOAD_GENERATOR' }),
        requestFixture({ id: 'manual-request', warehouse_id: representativeWarehouse.id, source_system: null }),
      ],
      plans: [],
    });
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
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
    }));
    const user = userEvent.setup();
    renderApp();

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
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
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
    }));
    useUiStore.setState({ mode: 'PLAN_DAY', section: 'REQUESTS', selected: null });
    const user = userEvent.setup();
    renderApp();

    await user.click(await screen.findByRole('button', { name: /^Доставки/ }));
    await user.click(screen.getByRole('button', { name: /^Следующая дата: 31 августа 2026/ }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'Дата планирования' })).toHaveTextContent('31 августа 2026'));
    expect(useUiStore.getState().section).toBe('REQUESTS');
    expect(screen.getByRole('region', { name: 'Подготовка доставок и вывозов на день' })).toBeVisible();
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
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
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
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    });
    vi.stubGlobal('fetch', fetchMock);

    renderApp();

    expect(await screen.findByRole('button', { name: 'Склад логистической группы' })).toHaveTextContent('Представительский склад — Региональный город');
    await waitFor(() => {
      const urls = fetchMock.mock.calls.map(([input]) => requestUrl(input));
      expect(urls.some((url) => url.includes(`/warehouses/${mainWarehouse.id}/plans/ensure`))).toBe(true);
      expect(urls.some((url) => url.includes(`/warehouses/${mainWarehouse.id}/planning-days/`))).toBe(true);
      expect(urls.some((url) => url.includes(`/warehouses/${representativeWarehouse.id}/plans/ensure`))).toBe(false);
    });
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
  it('submits complete cargo dimensions with multiple date windows', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: LogisticsRequestInput) => Promise<void>>(() => Promise.resolve());
    render(<RequestDialog type="DELIVERY" point={{ latitude: 55.7, longitude: 37.6 }} defaultDate="2026-08-25" busy={false} onClose={() => undefined} onSubmit={submit} />);
    expect(screen.getByText(/изохроне склада/)).toBeVisible();
    await user.type(screen.getByLabelText('Название / номер'), '№142');
    await user.type(screen.getByLabelText('Длина бытовки, мм'), '6000');
    await user.type(screen.getByLabelText('Ширина бытовки, мм'), '2400');
    await user.type(screen.getByLabelText('Высота бытовки, мм'), '2400');
    await user.type(screen.getByLabelText('Масса бытовки, кг'), '2500');
    await user.click(screen.getByRole('button', { name: 'Дата' }));
    expect(screen.getAllByLabelText('Дата')).toHaveLength(2);
    fireEvent.change(screen.getAllByLabelText('Дата')[1]!, { target: { value: '2026-08-26' } });
    expect(screen.queryByLabelText('Широта')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Долгота')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Сохранить доставку' }));
    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    const submitted = submit.mock.calls[0]?.[0];
    expect(submitted).toMatchObject({ cargo_length_mm: 6000, cargo_width_mm: 2400, cargo_height_mm: 2400, cargo_weight_kg: 2500 });
    expect(submitted?.date_options).toHaveLength(2);
  });

  it('does not submit a partial cargo routing profile', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: LogisticsRequestInput) => Promise<void>>(() => Promise.resolve());
    render(<RequestDialog type="PICKUP" point={{ latitude: 55.7, longitude: 37.6 }} defaultDate="2026-08-25" busy={false} onClose={() => undefined} onSubmit={submit} />);
    await user.type(screen.getByLabelText('Название / номер'), 'Вывоз 98');
    await user.type(screen.getByLabelText('Длина бытовки, мм'), '6000');
    await user.click(screen.getByRole('button', { name: 'Сохранить вывоз' }));
    expect(await screen.findByText('Укажите все четыре параметра груза или оставьте все поля пустыми')).toBeVisible();
    expect(submit).not.toHaveBeenCalled();
  });
});

describe('vehicle editor', () => {
  it('starts a new vehicle with an empty name and omits browser-owned driver names', async () => {
    const user = userEvent.setup();
    const vehicleSubmit = vi.fn<(input: VehicleInput) => Promise<void>>(() => Promise.resolve());
    const vehicleView = render(<CatalogDialog kind="vehicle" busy={false} onClose={() => undefined} onSubmit={(input) => vehicleSubmit(input as VehicleInput)} />);

    expect(screen.getByLabelText('Название')).toHaveValue('');
    vehicleView.unmount();

    const driverSubmit = vi.fn<(input: DriverInput) => Promise<void>>(() => Promise.resolve());
    render(<CatalogDialog kind="driver" busy={false} onClose={() => undefined} onSubmit={(input) => driverSubmit(input as DriverInput)} />);
    expect(screen.getByLabelText('Назначение в RWMS')).toHaveValue('WAREHOUSE_DRIVERS');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(driverSubmit).toHaveBeenCalledOnce());
    expect(driverSubmit.mock.calls[0]?.[0]).toMatchObject({
      rwms_assignment_mode: 'WAREHOUSE_DRIVERS',
      external_worker_id: null,
    });
    expect(driverSubmit.mock.calls[0]?.[0]).not.toHaveProperty('name');
  });

  it('rejects capacity above the backend maximum with an inline error', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: VehicleInput) => Promise<void>>(() => Promise.resolve());
    render(<CatalogDialog kind="vehicle" busy={false} onClose={() => undefined} onSubmit={(input) => submit(input as VehicleInput)} />);
    await user.type(screen.getByLabelText('Название'), 'Машина 1');
    await user.type(screen.getByLabelText('Госномер'), 'А123БВ');
    const capacity = screen.getByLabelText('Вместимость');
    await user.clear(capacity);
    await user.type(capacity, '3');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));
    expect(await screen.findByText('Для MVP вместимость не может превышать 2')).toBeVisible();
    expect(submit).not.toHaveBeenCalled();
  });
});
