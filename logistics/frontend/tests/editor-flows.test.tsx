import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ShiftInput, ZoneInput } from '../src/api/client';
import { Inspector } from '../src/app/Inspector';
import { RelationDialog, ShiftDialog, ZoneDialog } from '../src/components/EntityDialogs';
import { DEFAULT_PLANNING_SETTINGS } from '../src/domain/defaults';
import type {
  Driver,
  LogisticsRequest,
  ScenarioWorkspace,
  Trailer,
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
  delivery_price: 120,
  pickup_price: 80,
  geometry: polygon,
  version: 7,
  priority: 10,
  locked: true,
  stale_request_count: 1,
  created_at: '2026-08-20T08:00:00Z',
  updated_at: '2026-08-22T08:00:00Z',
};

const eastZone: Zone = {
  ...zone,
  id: 'zone-z2',
  name: 'Восточная зона',
  code: 'Z2',
  route_group: 'EAST',
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

const trailer: Trailer = {
  id: 'trailer-1', scenario_id: 'scenario-id', name: 'Прицеп 1', registration_number: 'ТР1234', active: true,
  tare_weight_kg: 4000, max_gross_weight_kg: 10000, length_mm: 8000, width_mm: 2500, height_mm: 2000,
  platform_length_mm: 6000, platform_width_mm: 2500, platform_height_from_ground_mm: 1000,
  max_platform_payload_kg: 5000, payload_capacity_kg: 5000, axle_count: 2, max_axle_load_kg: 7000,
  max_cargo_length_mm: 6500, max_cargo_width_mm: 2550, max_cargo_height_mm: 3000, max_cargo_weight_kg: 4000,
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
    scheduled_date: null,
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
    trailers: [trailer],
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
    onGenerateWorkload: () => undefined,
    onDeleteGeneratedWorkload: () => undefined,
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
    onScheduleRequestDate: () => undefined,
    onUnscheduleRequest: () => undefined,
    onSaveRequestPlanning: () => Promise.resolve(),
    onSplitRequest: () => Promise.resolve(),
  };
}

afterEach(() => {
  useUiStore.setState({ section: 'SCENARIO' });
});

describe('scenario workload actions', () => {
  it('exposes deletion of generated load as a separate scenario action', async () => {
    const user = userEvent.setup();
    const onDeleteGeneratedWorkload = vi.fn<ComponentProps<typeof Inspector>['onDeleteGeneratedWorkload']>();

    render(<Inspector {...inspectorProps(workspaceFixture())} onDeleteGeneratedWorkload={onDeleteGeneratedWorkload} />);

    await user.click(screen.getByRole('button', { name: 'Удалить нагрузку' }));
    expect(onDeleteGeneratedWorkload).toHaveBeenCalledOnce();
  });
});

describe('truck resource catalog', () => {
  it('exposes create, edit, and delete actions for trailers next to vehicles', async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn<ComponentProps<typeof Inspector>['onCreate']>();
    const onEdit = vi.fn<ComponentProps<typeof Inspector>['onEdit']>();
    const onDelete = vi.fn<ComponentProps<typeof Inspector>['onDelete']>();
    useUiStore.setState({ section: 'VEHICLES' });

    render(<Inspector {...inspectorProps(workspaceFixture())} onCreate={onCreate} onEdit={onEdit} onDelete={onDelete} />);
    await user.click(screen.getByRole('button', { name: 'Добавить прицеп' }));
    expect(onCreate).toHaveBeenCalledWith('trailer');

    const trailerCard = screen.getByText('Прицеп 1').closest('article');
    expect(trailerCard).not.toBeNull();
    await user.click(within(trailerCard!).getByRole('button', { name: 'Изменить' }));
    expect(onEdit).toHaveBeenCalledWith('trailer', trailer);
    await user.click(within(trailerCard!).getByRole('button', { name: 'Удалить' }));
    expect(onDelete).toHaveBeenCalledWith('trailer', trailer.id, trailer.name);
  });
});

describe('zone editor', () => {
  it('offers a dedicated inner-cutout map tool', async () => {
    const user = userEvent.setup();
    const onSetMapTool = vi.fn<ComponentProps<typeof Inspector>['onSetMapTool']>();
    useUiStore.setState({ section: 'ZONES' });

    render(<Inspector {...inspectorProps(workspaceFixture())} onSetMapTool={onSetMapTool} />);

    await user.click(screen.getByRole('button', { name: 'Сделать вырез' }));
    expect(onSetMapTool).toHaveBeenCalledWith('CUT_ZONE');
  });

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
      delivery_price: 0,
      pickup_price: 0,
      priority: 4,
      locked: true,
      geometry: polygon,
    });
    expect(submit.mock.calls[0]?.[0].geometry).not.toHaveProperty('geometry');
  });

  it('prefills and submits the separate zone created inside a cutout', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: ZoneInput) => Promise<void>>(() => Promise.resolve());
    render(
      <ZoneDialog
        geometry={polygon}
        initialValues={{ name: 'Западная зона · внутренняя 1', code: 'Z1-IN1', route_group: 'WEST', delivery_price: 120, pickup_price: 80, priority: 11, locked: false }}
        title="Новая зона внутри Z1"
        description="Отмена не изменит геометрию."
        submitLabel="Вырезать и создать зону"
        busy={false}
        onClose={() => undefined}
        onSubmit={submit}
      />,
    );

    expect(screen.getByRole('dialog', { name: 'Новая зона внутри Z1' })).toBeVisible();
    expect(screen.getByLabelText('Название')).toHaveValue('Западная зона · внутренняя 1');
    expect(screen.getByLabelText('Код')).toHaveValue('Z1-IN1');
    expect(screen.getByLabelText('Тариф доставки, ₽')).toHaveValue(120);
    expect(screen.getByLabelText('Тариф вывоза, ₽')).toHaveValue(80);
    expect(screen.getByLabelText('Приоритет')).toHaveValue(11);
    await user.click(screen.getByRole('button', { name: 'Вырезать и создать зону' }));

    await waitFor(() => expect(submit).toHaveBeenCalledWith({
      name: 'Западная зона · внутренняя 1',
      code: 'Z1-IN1',
      route_group: 'WEST',
      delivery_price: 120,
      pickup_price: 80,
      priority: 11,
      locked: false,
      geometry: polygon,
    }));
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
    await user.clear(screen.getByLabelText('Тариф доставки, ₽'));
    await user.type(screen.getByLabelText('Тариф доставки, ₽'), '150');
    await user.clear(screen.getByLabelText('Тариф вывоза, ₽'));
    await user.type(screen.getByLabelText('Тариф вывоза, ₽'), '95');
    await user.click(screen.getByRole('button', { name: 'Сохранить зону' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({ name: 'Западная зона — новая', locked: false, delivery_price: 150, pickup_price: 95, geometry: polygon });
  });
});

describe('zone relations', () => {
  it('uses the same full-cycle detour warning thresholds as scenario planning', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<ComponentProps<typeof RelationDialog>['onSubmit']>(() => Promise.resolve());

    render(
      <RelationDialog
        fromZone={zone}
        toZone={eastZone}
        busy={false}
        onClose={() => undefined}
        onSubmit={submit}
      />,
    );

    expect(screen.getByLabelText('Порог крюка, мин')).toHaveValue(35);
    expect(screen.getByLabelText('Порог доли крюка')).toHaveValue(1.5);
    await user.click(screen.getByRole('button', { name: 'Сохранить связь' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({
      from_zone_id: zone.id,
      to_zone_id: eastZone.id,
      max_detour_minutes: 35,
      max_detour_ratio: 1.5,
    }));
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

    expect(screen.getByText('Д · Доставка 142')).toBeVisible();
    expect(screen.getByText(/зона Z1 v1/)).toBeVisible();
    expect(screen.getByText('STALE')).toHaveClass('badge--warning');
    expect(screen.getByText('В · Вывоз 181')).toBeVisible();
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
    const onScheduleRequestDate = vi.fn<ComponentProps<typeof Inspector>['onScheduleRequestDate']>();
    useUiStore.setState({ section: 'REQUESTS' });

    render(<Inspector {...inspectorProps(workspace)} onPlanningDateChange={onPlanningDateChange} onScheduleRequestDate={onScheduleRequestDate} />);

    expect(screen.getByText('Д · Сегодня')).toBeVisible();
    expect(screen.queryByText('Д · Завтра')).not.toBeInTheDocument();
    await user.click(within(screen.getByLabelText('Заявки по допустимым датам')).getByRole('button', { name: /26 августа/i }));
    expect(onPlanningDateChange).toHaveBeenCalledWith('2026-08-26');
    await user.click(screen.getByRole('button', { name: /Выставить 25 августа/i }));
    expect(onScheduleRequestDate).toHaveBeenCalledWith('today-request', '2026-08-25', false);
  });

  it('shows an explicitly scheduled request only on its logistics date', () => {
    const workspace = workspaceFixture();
    workspace.requests = [requestFixture({
      id: 'scheduled-request',
      name: 'Выбранная дата',
      scheduled_date: '2026-08-26',
      date_options: [
        { date: '2026-08-25', priority: 20, window_start: null, window_end: null, is_hard: false },
        { date: '2026-08-26', priority: 10, window_start: null, window_end: null, is_hard: false },
      ],
    })];
    useUiStore.setState({ section: 'REQUESTS' });

    const { rerender } = render(<Inspector {...inspectorProps(workspace)} />);
    expect(screen.queryByText('Д · Выбранная дата')).not.toBeInTheDocument();

    rerender(<Inspector {...inspectorProps(workspace)} planningDate="2026-08-26" />);
    expect(screen.getByText('Д · Выбранная дата')).toBeVisible();
    expect(screen.getByText(/Выставлено на 26 августа 2026/)).toBeVisible();
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
