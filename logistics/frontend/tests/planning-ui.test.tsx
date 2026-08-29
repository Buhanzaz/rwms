import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EMPTY_METRICS } from '../src/domain/defaults';
import type { RoutePlan, SimulationDerivedState, WarehouseWorkspace } from '../src/domain/types';
import { PlanPanel } from '../src/features/planning/PlanPanel';
import { SimulationBar } from '../src/features/simulation/SimulationBar';
import { Inspector } from '../src/app/Inspector';
import { Sidebar } from '../src/app/Sidebar';
import { EmptyPositioningConfirmDialog } from '../src/components/EntityDialogs';
import { useUiStore } from '../src/stores/ui-store';
import { requestFixture, workspaceFixture as baseWorkspaceFixture } from './fixtures';

function planFixture(): RoutePlan {
  return {
    id: 'plan', warehouse_id: 'warehouse-1', date: '2026-08-25', version: 3, status: 'GENERATED', score: 42,
    created_at: '2026-08-24T00:00:00Z', updated_at: '2026-08-24T00:00:00Z', metrics: { ...EMPTY_METRICS, request_count: 5, assigned_count: 4, unassigned_count: 1, assignment_percent: 80, cycle_count: 1 },
    manually_changed: false,
    unassigned: [{
      task: { id: 'task-5', request_id: 'request-5', part_number: 1, quantity: 2, type: 'DELIVERY', latitude: 55.8, longitude: 37.7, zone_id: 'z4', zone_version: 1, service_minutes: 30, priority: 5, mandatory: true, status: 'UNASSIGNED' },
      reason_codes: ['TIME_WINDOW_CONFLICT'], reasons: ['временное окно 09:00–11:00 не помещается ни в одну смену'], closest_option: 'Можно назначить 30 августа 2026 г. в 18:19', recommendations: ['увеличить временное окно'],
    }],
    driver_routes: [{
      driver_shift_id: 'shift', shift_start_at: '2026-08-25T04:00:00Z', shift_end_at: '2026-08-25T17:00:00Z', driver_id: 'driver', driver_name: 'Водитель 1', vehicle_id: 'vehicle', vehicle_name: 'МАЗ', registration_number: 'А123БВ', metrics: { ...EMPTY_METRICS, shift_utilization_percent: 67 },
      cycles: [{
        id: 'cycle', route_plan_id: 'plan', driver_shift_id: 'shift', sequence: 1, planned_start: '2026-08-25T05:00:00Z', planned_finish: '2026-08-25T10:00:00Z', total_distance_meters: 146000,
        total_travel_seconds: 12300, total_service_seconds: 6000, empty_distance_meters: 25000, detour_seconds: 1320, score: 12.5, locked: false,
        explanation: ['окна доставок совместимы', 'дополнительный путь составляет 14 минут'], warnings: [], manually_changed: false,
        stops: [
          { id: 's0', route_cycle_id: 'cycle', sequence: 0, task_id: null, stop_type: 'DEPOT_LOAD', planned_arrival: '2026-08-25T05:00:00Z', planned_departure: '2026-08-25T05:30:00Z', service_seconds: 1800, quantity_delta: 2, load_before: 0, load_after: 2, latitude: 55.7, longitude: 37.6, label: 'Склад' },
          { id: 's1', route_cycle_id: 'cycle', sequence: 1, task_id: 'task-1', stop_type: 'DELIVERY', planned_arrival: '2026-08-25T06:00:00Z', planned_departure: '2026-08-25T06:20:00Z', service_seconds: 1200, quantity_delta: -1, load_before: 2, load_after: 1, latitude: 55.8, longitude: 37.6, label: 'Москва, Тверская улица, 10', zone_id: 'zone-1' },
          { id: 's2', route_cycle_id: 'cycle', sequence: 2, task_id: 'task-2', stop_type: 'DELIVERY', planned_arrival: '2026-08-25T07:00:00Z', planned_departure: '2026-08-25T07:20:00Z', service_seconds: 1200, quantity_delta: -1, load_before: 1, load_after: 0, latitude: 55.8, longitude: 37.7, label: 'Доставка 151', zone_id: 'zone-2' },
          { id: 's3', route_cycle_id: 'cycle', sequence: 3, task_id: 'task-3', stop_type: 'PICKUP', planned_arrival: '2026-08-25T08:00:00Z', planned_departure: '2026-08-25T08:20:00Z', service_seconds: 1200, quantity_delta: 1, load_before: 0, load_after: 1, latitude: 55.8, longitude: 37.7, label: 'Вывоз 98', zone_id: 'zone-2' },
          { id: 's4', route_cycle_id: 'cycle', sequence: 4, task_id: 'task-4', stop_type: 'PICKUP', planned_arrival: '2026-08-25T09:00:00Z', planned_departure: '2026-08-25T09:20:00Z', service_seconds: 1200, quantity_delta: 1, load_before: 1, load_after: 2, latitude: 55.8, longitude: 37.6, label: 'Вывоз 103', zone_id: 'zone-1' },
          { id: 's5', route_cycle_id: 'cycle', sequence: 5, task_id: null, stop_type: 'DEPOT_RETURN', planned_arrival: '2026-08-25T10:00:00Z', planned_departure: '2026-08-25T10:00:00Z', service_seconds: 0, quantity_delta: -2, load_before: 2, load_after: 0, latitude: 55.7, longitude: 37.6, label: 'Склад' },
        ], legs: [],
      }],
    }],
  };
}

function workspaceFixture(): WarehouseWorkspace {
  return baseWorkspaceFixture({ requests: [] });
}

function inspectorProps(plan: RoutePlan, simulation: SimulationDerivedState): ComponentProps<typeof Inspector> {
  return {
    workspace: workspaceFixture(),
    plan,
    simulation,
    validation: null,
    busy: false,
    onCreate: () => undefined,
    onEdit: () => undefined,
    onDelete: () => undefined,
    onGenerateWorkload: () => undefined,
    onDeleteGeneratedWorkload: () => undefined,
    onSetMapTool: () => undefined,
    onSelect: () => undefined,
    onMoveTask: () => undefined,
    onToggleCycleLock: () => undefined,
    onSaveSettings: () => Promise.resolve(),
    onCreateTransfer: () => undefined,
    onConfirmPlan: () => undefined,
    onResetManualChanges: () => undefined,
    onSimulationOverride: () => undefined,
    planningDate: '2026-08-25',
    onPlanningDateChange: () => undefined,
    onSaveRequestPlanning: () => Promise.resolve(),
    onSplitRequest: () => Promise.resolve(),
    inspectorWidth: 420,
    onInspectorWidthChange: () => undefined,
  };
}

afterEach(() => {
  useUiStore.setState({ mode: 'EDITOR', section: 'WAREHOUSE' });
});

describe('application shell', () => {
  it('keeps implementation-provider text out of the working sidebar', () => {
    render(<Sidebar workspace={workspaceFixture()} plan={null} />);

    expect(screen.queryByText('Valhalla · OpenStreetMap · грузовой граф')).not.toBeInTheDocument();
    expect(screen.queryByText(/OSRM/)).not.toBeInTheDocument();
  });

  it('keeps only the supported warehouse actions in one equal row', () => {
    const plan = planFixture();
    const simulation: SimulationDerivedState = { timestamp: '2026-08-25T05:45:00Z', vehicles: [], events: [], completed_stop_ids: [], active_stop_ids: [], affected_task_ids: [], warnings: [] };
    const props = inspectorProps(plan, simulation);
    props.onCreateTransfer = vi.fn();
    render(<Inspector {...props} />);

    const actions = screen.getByLabelText('Действия со складом');
    const rows = Array.from(actions.querySelectorAll(':scope > .warehouse-actions__row'));
    expect(rows).toHaveLength(1);
    expect(within(rows[0] as HTMLElement).getAllByRole('button')).toHaveLength(4);
    expect(within(actions).getByRole('button', { name: 'Создать перемещение' })).toBeVisible();
    expect(within(actions).queryByRole('link', { name: 'Создать перемещение' })).not.toBeInTheDocument();
    fireEvent.click(within(actions).getByRole('button', { name: 'Создать перемещение' }));
    expect(props.onCreateTransfer).toHaveBeenCalledOnce();
    expect(within(actions).getByRole('button', { name: 'Настроить склад' })).toBeVisible();
    expect(within(actions).getByRole('button', { name: 'Создать нагрузку' })).toBeVisible();
    expect(within(actions).getByRole('button', { name: 'Удалить нагрузку' })).toBeVisible();
    expect(within(actions).queryByRole('button', { name: 'Обновить из RWMS' })).not.toBeInTheDocument();
    expect(within(actions).queryByRole('button', { name: 'Подключить склад' })).not.toBeInTheDocument();
    expect(within(actions).queryByRole('button', { name: 'Тест на 3 дня' })).not.toBeInTheDocument();
  });

  it('shows consistent empty states for warehouse planning collections', () => {
    const simulation: SimulationDerivedState = { timestamp: '2026-08-25T05:45:00Z', vehicles: [], events: [], completed_stop_ids: [], active_stop_ids: [], affected_task_ids: [], warnings: [] };
    const props = inspectorProps(planFixture(), simulation);
    props.workspace = baseWorkspaceFixture({ zones: [], vehicles: [], trailers: [], shifts: [] });
    const view = render(<Inspector {...props} />);

    act(() => useUiStore.setState({ section: 'ZONES' }));
    expect(screen.getByText('Зоны не созданы')).toBeVisible();

    act(() => useUiStore.setState({ section: 'VEHICLES' }));
    expect(screen.getByText('Машины не созданы')).toBeVisible();
    expect(screen.getByText('Прицепы не созданы')).toBeVisible();
    const trailerDescription = screen.getByText(/Прицеп не исчезает после разгрузки/);
    const trailerButton = screen.getByRole('button', { name: 'Добавить прицеп' });
    expect(trailerDescription.compareDocumentPosition(trailerButton) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    act(() => useUiStore.setState({ section: 'SHIFTS' }));
    expect(screen.getByText('Смены не добавлены')).toBeVisible();

    act(() => useUiStore.setState({ section: 'PLAN_DAY' }));
    view.rerender(<Inspector {...props} plan={null} />);
    expect(screen.getByText('План дня не составлен')).toBeVisible();

    act(() => useUiStore.setState({ section: 'ROUTES' }));
    expect(screen.getByText('Маршруты не построены')).toBeVisible();

    act(() => useUiStore.setState({ section: 'UNASSIGNED' }));
    expect(screen.getByText('Нераспределённых заданий нет')).toBeVisible();
  });
});

describe('built plan UI', () => {
  it('shows driver, selects the driver route, and exposes exact load and unassigned details', async () => {
    const user = userEvent.setup();
    const plan = planFixture();
    const onSelectDriverRoute = vi.fn<(driverShiftId: string) => void>();
    const { rerender } = render(<PlanPanel plan={plan} timeZone="Europe/Moscow" onSelectCycle={() => undefined} onSelectDriverRoute={onSelectDriverRoute} onMove={() => undefined} onToggleLock={() => undefined} />);
    expect(screen.getByText('Водитель 1')).toBeVisible();
    expect(screen.getByText(/Нагрузка смены: 67%/)).toBeVisible();
    await user.click(screen.getByRole('button', { name: /Водитель 1/i }));
    expect(onSelectDriverRoute).toHaveBeenCalledWith('shift');
    expect(screen.getByLabelText('Цепочка загрузки цикла 1')).toHaveTextContent('2 → 1 → 0 → 1 → 2 → 0');
    expect(screen.getByText(/окна доставок совместимы/)).toBeVisible();
    rerender(<PlanPanel plan={plan} timeZone="Europe/Moscow" showUnassignedOnly onSelectCycle={() => undefined} onSelectDriverRoute={() => undefined} onMove={() => undefined} onToggleLock={() => undefined} />);
    expect(screen.getByText(/временное окно 09:00–11:00/)).toBeVisible();
    expect(screen.getByText('обязательно')).toBeVisible();
    expect(screen.getByText(/Можно назначить.*18:19/)).toBeVisible();
    expect(screen.getByText('увеличить временное окно')).toBeVisible();
  });

  it('shows one-off support warehouse service without presenting it as a reposition', () => {
    const plan = planFixture();
    const route = plan.driver_routes[0]!;
    const cycle = route.cycles[0]!;
    const context = {
      execution_mode: 'CROSS_WAREHOUSE_SERVICE' as const,
      service_warehouse_id: 'served-warehouse',
      resource_origin_warehouse_id: 'support-warehouse',
      resource_origin_warehouse_name: 'Опорный склад',
      support_warehouse_link_id: 'support-link',
      driver_id: 'support-driver',
      driver_worker_id: 'support-worker',
      driver_name: 'Петров Алексей',
      vehicle_id: 'support-vehicle',
      vehicle_name: 'МАЗ поддержки',
      vehicle_registration_number: 'А456ВС',
      available_at_served: '2026-08-25T09:00:00Z',
      latest_served_finish: '2026-08-25T14:00:00Z',
      inbound_travel_minutes: 180,
      return_travel_minutes: 190,
      inbound_distance_meters: 185_000,
      return_distance_meters: 195_000,
      positioning_distance_meters: 380_000,
      available_transfer_cabin_capacity: 2,
      trailer_available: true,
      outbound_positioning_empty: true,
      empty_positioning_reason_required: true,
      returns_to_origin: true,
      changes_operational_warehouse: false,
      reason_codes: ['NO_LOCAL_DRIVER', 'SUPPORT_DRIVER_AVAILABLE'],
    };
    route.driver_id = context.driver_id;
    route.driver_name = context.driver_name;
    route.vehicle_id = context.vehicle_id;
    route.vehicle_name = context.vehicle_name;
    route.registration_number = context.vehicle_registration_number;
    route.cross_warehouse_service = context;
    cycle.cross_warehouse_service = context;

    const onCreateTransfer = vi.fn();
    const view = render(<PlanPanel plan={plan} timeZone="Europe/Moscow" onSelectCycle={() => undefined} onSelectDriverRoute={() => undefined} onMove={() => undefined} onToggleLock={() => undefined} onCreateTransfer={onCreateTransfer} />);

    const support = screen.getByTestId('cross-warehouse-service-shift');
    expect(within(support).getByText('Привлечённый ресурс')).toBeVisible();
    expect(within(support).getByText(/Опорный склад/)).toBeVisible();
    expect(within(support).getByText(/Прибытие и доступность: 12:00/)).toBeVisible();
    expect(within(support).getByText(/возврат на исходный склад: да/)).toBeVisible();
    expect(within(support).getByText(/базирование не меняется/)).toBeVisible();
    expect(within(support).getByText(/Попутное перемещение/)).toBeVisible();
    expect(within(support).getByText(/доступно 2 бытовк.*с прицепом/)).toBeVisible();
    const addCabins = within(support).getByRole('button', { name: 'Добавить бытовки' });
    fireEvent.click(addCabins);
    expect(onCreateTransfer).toHaveBeenCalledWith('support-warehouse', 'served-warehouse');
    expect(within(support).queryByRole('link', { name: 'Добавить бытовки' })).not.toBeInTheDocument();
    expect(screen.getByText('Петров Алексей')).toBeVisible();
    expect(screen.getByText(/МАЗ поддержки · А456ВС/)).toBeVisible();

    const oneCabinContext = {
      ...context,
      available_transfer_cabin_capacity: 1,
      trailer_available: false,
      reason_codes: [...context.reason_codes, 'VEHICLE_CAPACITY_ONE_CABIN'],
    };
    route.cross_warehouse_service = oneCabinContext;
    cycle.cross_warehouse_service = oneCabinContext;
    view.rerender(<PlanPanel plan={plan} timeZone="Europe/Moscow" onSelectCycle={() => undefined} onSelectDriverRoute={() => undefined} onMove={() => undefined} onToggleLock={() => undefined} onCreateTransfer={onCreateTransfer} />);
    expect(
      within(screen.getByTestId('cross-warehouse-service-shift')).getByText(
        /доступно 1 бытовк.*автомобиль вмещает только одну бытовку/,
      ),
    ).toBeVisible();
  });

  it('requires a reason before the operator approves an empty positioning leg', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn<(reason: string) => Promise<void>>().mockResolvedValue(undefined);
    render(
      <EmptyPositioningConfirmDialog
        busy={false}
        onClose={() => undefined}
        onConfirm={onConfirm}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Утвердить пустой перегон' }));
    expect(screen.getByText('Укажите причину пустого перегона')).toBeVisible();
    await user.type(
      screen.getByRole('textbox', { name: 'Причина пустого перегона' }),
      'Нет подходящего попутного груза',
    );
    await user.click(screen.getByRole('button', { name: 'Утвердить пустой перегон' }));

    expect(onConfirm).toHaveBeenCalledWith('Нет подходящего попутного груза');
  });

  it('keeps the day view plan-only and exposes approval plus manual-change reset before approval', async () => {
    const user = userEvent.setup();
    const plan = planFixture();
    plan.manually_changed = true;
    const simulation: SimulationDerivedState = { timestamp: '2026-08-25T05:45:00Z', vehicles: [], events: [], completed_stop_ids: [], active_stop_ids: [], affected_task_ids: [], warnings: [] };
    const onConfirmPlan = vi.fn();
    const onResetManualChanges = vi.fn();
    useUiStore.setState({ section: 'PLAN_DAY' });

    render(<Inspector {...inspectorProps(plan, simulation)} onConfirmPlan={onConfirmPlan} onResetManualChanges={onResetManualChanges} />);

    expect(screen.queryByLabelText('Доставка/вывоз с')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Машина с прицепом проедет к адресу')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Отменить изменения' }));
    await user.click(screen.getByRole('button', { name: 'Утвердить' }));
    expect(onResetManualChanges).toHaveBeenCalledOnce();
    expect(onConfirmPlan).toHaveBeenCalledOnce();
  });

  it('shows each delivery once without the duplicate dated card list', () => {
    const plan = planFixture();
    const simulation: SimulationDerivedState = { timestamp: '2026-08-25T05:45:00Z', vehicles: [], events: [], completed_stop_ids: [], active_stop_ids: [], affected_task_ids: [], warnings: [] };
    const props = inspectorProps(plan, simulation);
    props.workspace = baseWorkspaceFixture({ requests: [requestFixture({ scheduled_date: '2026-08-25', date_options: [{ date: '2026-08-25', priority: 1, window_start: '09:00', window_end: '12:00', is_hard: true }] })] });
    useUiStore.setState({ section: 'REQUESTS' });

    render(<Inspector {...props} />);

    expect(screen.getAllByTestId('planning-request-request-1')).toHaveLength(1);
    expect(screen.queryByText(/Карточки заявок на/)).not.toBeInTheDocument();
  });

  it('resizes the inspector with its accessible separator', () => {
    const plan = planFixture();
    const simulation: SimulationDerivedState = { timestamp: '2026-08-25T05:45:00Z', vehicles: [], events: [], completed_stop_ids: [], active_stop_ids: [], affected_task_ids: [], warnings: [] };
    const onInspectorWidthChange = vi.fn();

    render(<Inspector {...inspectorProps(plan, simulation)} onInspectorWidthChange={onInspectorWidthChange} />);
    const separator = screen.getByRole('separator', { name: 'Изменить ширину инспектора' });
    fireEvent.keyDown(separator, { key: 'ArrowLeft' });
    expect(onInspectorWidthChange).toHaveBeenCalledWith(444);
  });

});

describe('simulation controls', () => {
  it('changes speed and allows deterministic slider seeking', async () => {
    const user = userEvent.setup();
    const plan = planFixture();
    const timestamp = new Date('2026-08-25T06:00:00Z').getTime();
    const state: SimulationDerivedState = { timestamp: new Date(timestamp).toISOString(), vehicles: [], events: [], completed_stop_ids: [], active_stop_ids: [], affected_task_ids: [], warnings: [] };
    const onTimestamp = vi.fn<(value: number) => void>();
    const onSpeed = vi.fn<(value: 1 | 5 | 10 | 20 | 60) => void>();
    render(<SimulationBar plan={plan} state={state} timestamp={timestamp} timeZone="Europe/Moscow" playing={false} speed={5} overrides={[]} onTimestamp={onTimestamp} onPlaying={() => undefined} onSpeed={onSpeed} />);
    await user.selectOptions(screen.getByLabelText('Скорость симуляции'), '20');
    expect(onSpeed).toHaveBeenCalledWith(20);
    const slider = screen.getByLabelText('Время симуляции');
    expect(slider).toHaveAttribute('min', String(new Date('2026-08-25T04:00:00Z').getTime()));
    fireEvent.change(slider, { target: { value: String(new Date('2026-08-25T08:00:00Z').getTime()) } });
    expect(onTimestamp).toHaveBeenCalledWith(new Date('2026-08-25T08:00:00Z').getTime());
  });

  it('does not count vehicles whose first window has not started as working', () => {
    const plan = planFixture();
    const timestamp = new Date('2026-08-25T06:00:00Z').getTime();
    const vehicle: SimulationDerivedState['vehicles'][number] = {
      driver_shift_id: 'waiting-shift',
      driver_name: 'Ожидающий водитель',
      vehicle_name: 'Машина',
      registration_number: 'А000АА',
      position: { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [37.6, 55.7] } },
      status: 'WAITING_SHIFT',
      load: 0,
      next_stop_label: 'Поздняя доставка',
      eta: '2026-08-25T12:00:00Z',
      active_cycle_id: null,
      active_leg_index: null,
      delayed_by_minutes: 0,
    };
    const state: SimulationDerivedState = {
      timestamp: new Date(timestamp).toISOString(),
      vehicles: [
        vehicle,
        { ...vehicle, driver_shift_id: 'active-shift', status: 'DRIVING' },
        { ...vehicle, driver_shift_id: 'finished-shift', status: 'FINISHED' },
      ],
      events: [],
      completed_stop_ids: [],
      active_stop_ids: [],
      affected_task_ids: [],
      warnings: [],
    };

    render(<SimulationBar plan={plan} state={state} timestamp={timestamp} timeZone="Europe/Moscow" playing={false} speed={5} overrides={[]} onTimestamp={() => undefined} onPlaying={() => undefined} onSpeed={() => undefined} />);

    expect(screen.getByText('Машин в работе: 1')).toBeVisible();
  });
});

describe('simulation plan inspector', () => {
  it('keeps the complete plan visible and shows the current destination on its driver status card', async () => {
    const user = userEvent.setup();
    const plan = planFixture();
    const simulation: SimulationDerivedState = {
      timestamp: '2026-08-25T05:45:00.000Z',
      vehicles: [{
        driver_shift_id: 'shift',
        driver_name: 'Водитель 1',
        vehicle_name: 'МАЗ',
        registration_number: 'А123БВ',
        position: { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [37.6, 55.8] } },
        status: 'DRIVING',
        load: 2,
        next_stop_label: 'Москва, Тверская улица, 10',
        eta: '2026-08-25T06:00:00.000Z',
        active_cycle_id: 'cycle',
        active_leg_index: 0,
        delayed_by_minutes: 0,
      }],
      events: [],
      completed_stop_ids: [],
      active_stop_ids: [],
      affected_task_ids: [],
      warnings: [],
    };
    const onSelect = vi.fn<ComponentProps<typeof Inspector>['onSelect']>();
    useUiStore.setState({ mode: 'SIMULATION', section: 'ROUTES' });

    render(<Inspector {...inspectorProps(plan, simulation)} onSelect={onSelect} />);

    const currentRoute = screen.getByTestId('simulation-route-shift');
    expect(within(currentRoute).getByText('Москва, Тверская улица, 10')).toBeVisible();
    expect(within(currentRoute).getByText(/ETA: 09:00/)).toBeVisible();
    expect(within(currentRoute).getByText('DRIVING')).toBeVisible();
    expect(screen.getByText('План · версия 3')).toBeVisible();
    expect(screen.getByText('Пары вывозов')).toBeVisible();
    expect(screen.getByTestId('cycle-cycle')).toBeVisible();
    expect(screen.getByLabelText('Цепочка загрузки цикла 1')).toHaveTextContent('2 → 1 → 0 → 1 → 2 → 0');

    await user.click(within(currentRoute).getByRole('button', { name: /Водитель 1/i }));
    expect(onSelect).toHaveBeenCalledWith('driver', 'shift');
  });
});
