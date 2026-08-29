import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App, WarehousePicker } from '../src/app/App';
import { CatalogDialog, RequestDialog } from '../src/components/EntityDialogs';
import type { DriverInput, LogisticsRequestInput, VehicleInput } from '../src/api/client';
import { NotificationCenter, ThemeSwitch, Toasts } from '../src/components/ui';
import { useUiStore } from '../src/stores/ui-store';
import { warehouseFixture } from './fixtures';

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

  it('opens RWMS warehouse connection from an empty backend', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = requestUrl(input);
      const body = url.endsWith('/warehouses/available')
        ? [{ warehouse_id: '11111111-1111-4111-8111-111111111111', name: 'Склад СПб', address: 'СПб, Шоссе Революции, 1', timezone: 'Europe/Moscow' }]
        : [];
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    }));
    const user = userEvent.setup();
    renderApp();
    expect(await screen.findByText('Склады не созданы')).toBeVisible();
    await user.click((await screen.findAllByRole('button', { name: /Добавить склад/ }))[0]!);
    expect(screen.getByRole('dialog', { name: 'Выбрать склад RWMS' })).toBeVisible();
    const warehouseSelect = screen.getByLabelText('Склад RWMS');
    expect(warehouseSelect).toHaveTextContent('Склад СПб');
    await user.selectOptions(warehouseSelect, '11111111-1111-4111-8111-111111111111');
    expect(warehouseSelect).toHaveDisplayValue(/Склад СПб/);
  });

  it('does not require a polygon before adding the first RWMS warehouse', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => Promise.resolve(new Response(
      requestUrl(input).endsWith('/warehouses/available')
        ? JSON.stringify([{ warehouse_id: '11111111-1111-4111-8111-111111111111', name: 'Склад СПб', address: 'СПб, Шоссе Революции, 1', timezone: 'Europe/Moscow' }])
        : '[]',
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ))));
    const user = userEvent.setup();
    renderApp();

    expect(await screen.findByText('Склады не созданы')).toBeVisible();
    expect(screen.queryByTestId('bootstrap-zone-map')).not.toBeInTheDocument();
    await user.click(screen.getAllByRole('button', { name: 'Добавить склад' })[0]!);
    await user.selectOptions(screen.getByLabelText('Склад RWMS'), '11111111-1111-4111-8111-111111111111');
    expect(within(screen.getByRole('dialog', { name: 'Выбрать склад RWMS' })).getByRole('button', { name: 'Добавить склад' })).toBeEnabled();
    expect(screen.getByText(/Особые зоны можно добавить после создания склада/)).toBeVisible();
  });

  it('uses compact actionable warehouse controls without the old product label', async () => {
    const warehouse = warehouseFixture();
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = requestUrl(input);
      let body: unknown = null;
      if (url.endsWith('/warehouses/available')) body = [];
      else if (url.endsWith('/warehouses')) body = [warehouse];
      else if (url.includes(`/warehouses/${warehouse.id}/workspace`)) body = {
        warehouse,
        warehouses: [warehouse],
        zones: [],
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
    useUiStore.setState({ mode: 'EDITOR', section: 'SETTINGS', selected: null });
    const user = userEvent.setup();
    renderApp();

    const picker = await screen.findByRole('combobox', { name: 'Текущий склад' });
    const addWarehouse = screen.getByRole('button', { name: 'Добавить склад' });
    const warehouseHome = screen.getByRole('button', { name: 'Открыть склад' });
    expect(screen.queryByText('RWMS · Логистика')).not.toBeInTheDocument();
    expect(addWarehouse.nextElementSibling).toContainElement(picker);

    await user.click(warehouseHome);
    expect(useUiStore.getState()).toMatchObject({ mode: 'EDITOR', section: 'WAREHOUSE', selected: { kind: 'warehouse', id: warehouse.id } });
    await user.click(addWarehouse);
    expect(screen.getByRole('dialog', { name: 'Выбрать склад RWMS' })).toBeVisible();
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

describe('warehouse picker', () => {
  it('opens an accessible calendar-style list and switches only after an explicit choice', async () => {
    const user = userEvent.setup();
    const warehouses = [
      warehouseFixture(),
      warehouseFixture({ id: 'warehouse-2', name: 'Склад Великий Новгород', city: 'Великий Новгород' }),
    ];
    const onChange = vi.fn();
    render(<WarehousePicker warehouses={warehouses} value="warehouse-1" onChange={onChange} />);

    const picker = screen.getByRole('combobox', { name: 'Текущий склад' });
    expect(picker).toHaveAttribute('aria-expanded', 'false');
    expect(picker).toHaveTextContent('Склад СПбСанкт-Петербург');
    await user.click(picker);

    expect(screen.getByRole('listbox', { name: 'Склады' })).toBeVisible();
    const novgorod = screen.getByRole('option', { name: /Склад Великий Новгород/ });
    expect(novgorod).toHaveAttribute('aria-selected', 'false');
    await user.click(novgorod);

    expect(onChange).toHaveBeenCalledWith('warehouse-2');
    expect(screen.queryByRole('listbox', { name: 'Склады' })).not.toBeInTheDocument();
  });
});

describe('request editor', () => {
  it('keeps the server zone read-only and submits complete cargo dimensions with multiple date windows', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: LogisticsRequestInput) => Promise<void>>(() => Promise.resolve());
    render(<RequestDialog type="DELIVERY" point={{ latitude: 55.7, longitude: 37.6 }} defaultDate="2026-08-25" busy={false} onClose={() => undefined} onSubmit={submit} />);
    expect(screen.getByText(/Backend проверит запреты.*изохроне склада/)).toBeVisible();
    expect(screen.queryByLabelText(/zone_id/i)).not.toBeInTheDocument();
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
    expect(submitted).not.toHaveProperty('zone_id');
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
