import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ShiftInput, ZoneInput } from '../src/api/client';
import { Inspector } from '../src/app/Inspector';
import { ShiftDialog, ZoneDialog } from '../src/components/EntityDialogs';
import { DEFAULT_PLANNING_SETTINGS } from '../src/domain/defaults';
import type {
  Driver,
  LogisticsRequest,
  ScenarioWorkspace,
  Vehicle,
  Zone,
} from '../src/domain/types';
import { useUiStore } from '../src/stores/ui-store';

const polygon: Zone['geometry'] = {
  type: 'Polygon',
  coordinates: [[
    [37.5, 55.7],
    [37.7, 55.7],
    [37.7, 55.9],
    [37.5, 55.9],
    [37.5, 55.7],
  ]],
};

const zone: Zone = {
  id: 'zone-z1',
  scenario_id: 'scenario-id',
  name: 'Западная зона',
  code: 'Z1',
  route_group: 'WEST',
  geometry: polygon,
  version: 7,
  priority: 10,
  locked: true,
  stale_request_count: 1,
  created_at: '2026-08-20T08:00:00Z',
  updated_at: '2026-08-22T08:00:00Z',
};

const drivers: Driver[] = [
  {
    id: 'driver-1',
    scenario_id: 'scenario-id',
    name: 'Водитель 1',
    preferred_route_group: 'WEST',
    active: true,
    notes: '',
  },
  {
    id: 'driver-2',
    scenario_id: 'scenario-id',
    name: 'Водитель 2',
    preferred_route_group: 'EAST',
    active: false,
    notes: 'резерв',
  },
];

const vehicle: Vehicle = {
  id: 'vehicle-1',
  scenario_id: 'scenario-id',
  name: 'МАЗ 1',
  registration_number: 'А123БВ',
  capacity: 2,
  active: true,
  average_speed_city: 35,
  average_speed_region: 65,
  notes: '',
};

function requestFixture(overrides: Partial<LogisticsRequest>): LogisticsRequest {
  return {
    id: 'request-id',
    scenario_id: 'scenario-id',
    type: 'DELIVERY',
    name: 'Заявка',
    address_label: 'Тестовый адрес',
    latitude: 55.8,
    longitude: 37.6,
    quantity: 1,
    service_minutes: 30,
    priority: 2,
    status: 'READY',
    zone_id: 'zone-z1',
    zone_version: 7,
    split_allowed: true,
    notes: '',
    created_at: '2026-08-20T08:00:00Z',
    updated_at: '2026-08-22T08:00:00Z',
    date_options: [{ date: '2026-08-25', priority: 1, window_start: '09:00', window_end: '11:00', is_hard: true }],
    zone_status: 'CURRENT',
    ...overrides,
  };
}

function workspaceFixture(): ScenarioWorkspace {
  return {
    scenario: {
      id: 'scenario-id',
      name: 'Тестовая логистика',
      description: 'Стенд',
      timezone: 'Europe/Moscow',
      default_planning_date: '2026-08-25',
      created_at: '2026-08-20T08:00:00Z',
      updated_at: '2026-08-22T08:00:00Z',
      settings: { ...DEFAULT_PLANNING_SETTINGS },
      seed: 42,
    },
    warehouses: [],
    zones: [zone],
    zone_relations: [],
    drivers,
    vehicles: [vehicle],
    shifts: [],
    requests: [],
  };
}

function inspectorProps(workspace: ScenarioWorkspace): ComponentProps<typeof Inspector> {
  return {
    workspace,
    plan: null,
    simulation: null,
    validation: null,
    busy: false,
    onCreate: () => undefined,
    onEdit: () => undefined,
    onDelete: () => undefined,
    onGenerateDemo: () => undefined,
    onGenerateMultiDayDemo: () => undefined,
    onCloneScenario: () => undefined,
    onDeleteScenario: () => undefined,
    onExport: () => undefined,
    onImport: () => undefined,
    onReclassify: () => undefined,
    onZoneRelation: () => undefined,
    onSetMapTool: () => undefined,
    onSelect: () => undefined,
    onMoveTask: () => undefined,
    onToggleCycleLock: () => undefined,
    onSaveSettings: () => Promise.resolve(),
    onClonePlan: () => undefined,
    onSimulationOverride: () => undefined,
    planningDate: '2026-08-25',
    onPlanningDateChange: () => undefined,
    onAgreeRequestDate: () => undefined,
  };
}

afterEach(() => {
  useUiStore.setState({ section: 'SCENARIO' });
});

describe('zone editor', () => {
  it('submits a newly drawn bare GeoJSON geometry and explicit lock state', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: ZoneInput) => Promise<void>>(() => Promise.resolve());
    render(<ZoneDialog geometry={polygon} busy={false} onClose={() => undefined} onSubmit={submit} />);

    await user.type(screen.getByLabelText('Название'), 'Северная зона');
    await user.type(screen.getByLabelText('Код'), 'N1');
    await user.clear(screen.getByLabelText('Группа маршрута'));
    await user.type(screen.getByLabelText('Группа маршрута'), 'NORTH_CUSTOM');
    await user.clear(screen.getByLabelText('Приоритет'));
    await user.type(screen.getByLabelText('Приоритет'), '4');
    await user.click(screen.getByLabelText('Заблокировать редактирование геометрии'));
    await user.click(screen.getByRole('button', { name: 'Сохранить зону' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit).toHaveBeenCalledWith({
      name: 'Северная зона',
      code: 'N1',
      route_group: 'NORTH_CUSTOM',
      priority: 4,
      locked: true,
      geometry: polygon,
    });
    expect(submit.mock.calls[0]?.[0].geometry).not.toHaveProperty('geometry');
  });

  it('shows the persisted version and requires an explicit unlock before editing', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: ZoneInput) => Promise<void>>(() => Promise.resolve());
    render(<ZoneDialog zone={zone} geometry={polygon} busy={false} onClose={() => undefined} onSubmit={submit} />);

    expect(screen.getByRole('dialog', { name: 'Зона Z1 · версия 7' })).toBeVisible();
    expect(screen.getByText(/Зона заблокирована/)).toBeVisible();
    expect(screen.getByLabelText('Название')).toHaveAttribute('readonly');

    await user.click(screen.getByLabelText('Заблокировать редактирование геометрии'));
    expect(screen.getByLabelText('Название')).not.toHaveAttribute('readonly');
    await user.clear(screen.getByLabelText('Название'));
    await user.type(screen.getByLabelText('Название'), 'Западная зона — новая');
    await user.click(screen.getByRole('button', { name: 'Сохранить зону' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({ name: 'Западная зона — новая', locked: false, geometry: polygon });
  });
});

describe('server-owned zone classification', () => {
  it('distinguishes stale classification from a point outside every zone', () => {
    const workspace = workspaceFixture();
    workspace.requests = [
      requestFixture({ id: 'request-stale', name: 'Доставка 142', zone_version: 1, zone_status: 'STALE' }),
      requestFixture({
        id: 'request-outside',
        type: 'PICKUP',
        name: 'Вывоз 181',
        zone_id: null,
        zone_version: null,
        zone_status: 'OUTSIDE_ZONES',
      }),
    ];
    useUiStore.setState({ section: 'REQUESTS' });

    render(<Inspector {...inspectorProps(workspace)} />);

    expect(screen.getByText('D · Доставка 142')).toBeVisible();
    expect(screen.getByText(/зона Z1 v1/)).toBeVisible();
    expect(screen.getByText('STALE')).toHaveClass('badge--warning');
    expect(screen.getByText('P · Вывоз 181')).toBeVisible();
    expect(screen.getByText(/зона OUTSIDE_ZONES v—/)).toBeVisible();
    expect(screen.getByText('OUTSIDE_ZONES')).toHaveClass('badge--danger');
  });
});

describe('request date board', () => {
  it('filters the list by planning date and offers a non-destructive agreed date', async () => {
    const user = userEvent.setup();
    const workspace = workspaceFixture();
    workspace.requests = [
      requestFixture({ id: 'today-request', name: 'Сегодня', date_options: [{ date: '2026-08-25', priority: 20, window_start: null, window_end: null, is_hard: false }] }),
      requestFixture({ id: 'tomorrow-request', name: 'Завтра', date_options: [{ date: '2026-08-26', priority: 20, window_start: null, window_end: null, is_hard: false }] }),
    ];
    const onPlanningDateChange = vi.fn<ComponentProps<typeof Inspector>['onPlanningDateChange']>();
    const onAgreeRequestDate = vi.fn<ComponentProps<typeof Inspector>['onAgreeRequestDate']>();
    useUiStore.setState({ section: 'REQUESTS' });

    render(<Inspector {...inspectorProps(workspace)} onPlanningDateChange={onPlanningDateChange} onAgreeRequestDate={onAgreeRequestDate} />);

    expect(screen.getByText('D · Сегодня')).toBeVisible();
    expect(screen.queryByText('D · Завтра')).not.toBeInTheDocument();
    await user.click(within(screen.getByLabelText('Заявки по допустимым датам')).getByRole('button', { name: /26 августа/i }));
    expect(onPlanningDateChange).toHaveBeenCalledWith('2026-08-26');
    await user.click(screen.getByRole('button', { name: /Согласовать 25 августа/i }));
    expect(onAgreeRequestDate).toHaveBeenCalledWith('today-request', '2026-08-25');
  });
});

describe('drivers and shifts', () => {
  it('shows driver activity and treats the route group as a preference', async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn<ComponentProps<typeof Inspector>['onCreate']>();
    const workspace = workspaceFixture();
    useUiStore.setState({ section: 'DRIVERS' });

    render(<Inspector {...inspectorProps(workspace)} onCreate={onCreate} />);

    expect(screen.getByText('Водитель 1')).toBeVisible();
    expect(screen.getByText('Группа WEST')).toBeVisible();
    expect(screen.getByText('Водитель 2')).toBeVisible();
    expect(screen.getByText('Группа EAST')).toBeVisible();
    expect(screen.getByText('выключен')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Добавить' }));
    expect(onCreate).toHaveBeenCalledWith('driver');
  });

  it('creates a shift with scenario-local wall time converted to offset-aware ISO', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: ShiftInput) => Promise<void>>(() => Promise.resolve());
    const workspace = workspaceFixture();
    render(
      <ShiftDialog
        scenario={workspace.scenario}
        drivers={workspace.drivers}
        vehicles={workspace.vehicles}
        busy={false}
        onClose={() => undefined}
        onSubmit={submit}
      />,
    );

    await user.selectOptions(screen.getByLabelText('Водитель'), 'driver-2');
    fireEvent.change(screen.getByLabelText('Дата'), { target: { value: '2026-08-26' } });
    fireEvent.change(screen.getByLabelText('Начало'), { target: { value: '09:15' } });
    fireEvent.change(screen.getByLabelText('Окончание'), { target: { value: '18:45' } });
    await user.clear(screen.getByLabelText('Перерыв, мин'));
    await user.type(screen.getByLabelText('Перерыв, мин'), '45');
    await user.type(screen.getByLabelText('Предпочтительная группа смены'), 'EAST');
    await user.click(screen.getByRole('button', { name: 'Сохранить смену' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit).toHaveBeenCalledWith({
      driver_id: 'driver-2',
      vehicle_id: 'vehicle-1',
      date: '2026-08-26',
      start_at: '2026-08-26T06:15:00.000Z',
      end_at: '2026-08-26T15:45:00.000Z',
      break_minutes: 45,
      preferred_route_group: 'EAST',
      active: true,
    });
  });
});

describe('plan validation presentation', () => {
  it('renders blocking errors separately from confirmable warnings', () => {
    const workspace = workspaceFixture();
    useUiStore.setState({ section: 'SCENARIO' });

    render(
      <Inspector
        {...inspectorProps(workspace)}
        validation={{
          valid: false,
          version: 3,
          errors: [{ code: 'CAPACITY_EXCEEDED', message: 'Загрузка 3 превышает вместимость 2' }],
          warnings: [{ code: 'HIGH_DETOUR', message: 'Крюк составляет 31 минуту' }],
        }}
      />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('План содержит ошибки');
    expect(screen.getByRole('alert')).toHaveTextContent('CAPACITY_EXCEEDED: Загрузка 3 превышает вместимость 2');
    expect(screen.getByText('Крюк составляет 31 минуту')).toBeVisible();
  });
});
