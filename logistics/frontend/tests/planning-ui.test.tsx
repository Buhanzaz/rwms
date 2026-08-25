import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_PLANNING_SETTINGS, EMPTY_METRICS } from '../src/domain/defaults';
import type { RoutePlan, ScenarioWorkspace, SimulationDerivedState } from '../src/domain/types';
import { PlanPanel } from '../src/features/planning/PlanPanel';
import { SimulationBar } from '../src/features/simulation/SimulationBar';
import { Inspector } from '../src/app/Inspector';
import { Sidebar } from '../src/app/Sidebar';
import { useUiStore } from '../src/stores/ui-store';

function planFixture(): RoutePlan {
  return {
    id: 'plan', scenario_id: 'scenario', warehouse_id: 'warehouse', date: '2026-08-25', version: 3, status: 'GENERATED', score: 42,
    created_at: '2026-08-24T00:00:00Z', updated_at: '2026-08-24T00:00:00Z', metrics: { ...EMPTY_METRICS, request_count: 5, assigned_count: 4, unassigned_count: 1, assignment_percent: 80, cycle_count: 1 },
    unassigned: [{
      task: { id: 'task-5', request_id: 'request-5', part_number: 1, quantity: 2, type: 'DELIVERY', latitude: 55.8, longitude: 37.7, zone_id: 'z4', zone_version: 1, service_minutes: 30, priority: 5, status: 'UNASSIGNED' },
      reason_codes: ['TIME_WINDOW_CONFLICT'], reasons: ['временное окно 09:00–11:00 не помещается ни в одну смену'], closest_option: '12:15', recommendations: ['увеличить временное окно'],
    }],
    driver_routes: [{
      driver_shift_id: 'shift', driver_id: 'driver', driver_name: 'Водитель 1', vehicle_id: 'vehicle', vehicle_name: 'МАЗ', registration_number: 'А123БВ', preferred_route_group: 'WEST', metrics: { ...EMPTY_METRICS, shift_utilization_percent: 67 },
      cycles: [{
        id: 'cycle', route_plan_id: 'plan', driver_shift_id: 'shift', sequence: 1, planned_start: '2026-08-25T05:00:00Z', planned_finish: '2026-08-25T10:00:00Z', total_distance_meters: 146000,
        total_travel_seconds: 12300, total_service_seconds: 6000, empty_distance_meters: 25000, detour_seconds: 1320, score: 12.5, locked: false,
        explanation: ['зоны Z1 и Z2 являются соседними', 'дополнительный путь составляет 14 минут'], warnings: [],
        stops: [
          { id: 's0', route_cycle_id: 'cycle', sequence: 0, task_id: null, stop_type: 'DEPOT_LOAD', planned_arrival: '2026-08-25T05:00:00Z', planned_departure: '2026-08-25T05:30:00Z', service_seconds: 1800, quantity_delta: 2, load_before: 0, load_after: 2, latitude: 55.7, longitude: 37.6, label: 'Склад' },
          { id: 's1', route_cycle_id: 'cycle', sequence: 1, task_id: 'task-1', stop_type: 'DELIVERY', planned_arrival: '2026-08-25T06:00:00Z', planned_departure: '2026-08-25T06:20:00Z', service_seconds: 1200, quantity_delta: -1, load_before: 2, load_after: 1, latitude: 55.8, longitude: 37.6, label: 'Москва, Тверская улица, 10', zone_code: 'Z1' },
          { id: 's2', route_cycle_id: 'cycle', sequence: 2, task_id: 'task-2', stop_type: 'DELIVERY', planned_arrival: '2026-08-25T07:00:00Z', planned_departure: '2026-08-25T07:20:00Z', service_seconds: 1200, quantity_delta: -1, load_before: 1, load_after: 0, latitude: 55.8, longitude: 37.7, label: 'Доставка 151', zone_code: 'Z2' },
          { id: 's3', route_cycle_id: 'cycle', sequence: 3, task_id: 'task-3', stop_type: 'PICKUP', planned_arrival: '2026-08-25T08:00:00Z', planned_departure: '2026-08-25T08:20:00Z', service_seconds: 1200, quantity_delta: 1, load_before: 0, load_after: 1, latitude: 55.8, longitude: 37.7, label: 'Вывоз 98', zone_code: 'Z2' },
          { id: 's4', route_cycle_id: 'cycle', sequence: 4, task_id: 'task-4', stop_type: 'PICKUP', planned_arrival: '2026-08-25T09:00:00Z', planned_departure: '2026-08-25T09:20:00Z', service_seconds: 1200, quantity_delta: 1, load_before: 1, load_after: 2, latitude: 55.8, longitude: 37.6, label: 'Вывоз 103', zone_code: 'Z1' },
          { id: 's5', route_cycle_id: 'cycle', sequence: 5, task_id: null, stop_type: 'DEPOT_RETURN', planned_arrival: '2026-08-25T10:00:00Z', planned_departure: '2026-08-25T10:00:00Z', service_seconds: 0, quantity_delta: -2, load_before: 2, load_after: 0, latitude: 55.7, longitude: 37.6, label: 'Склад' },
        ], legs: [],
      }],
    }],
  };
}

function workspaceFixture(): ScenarioWorkspace {
  return {
    scenario: {
      id: 'scenario',
      name: 'Тестовая логистика',
      description: '',
      timezone: 'Europe/Moscow',
      default_planning_date: '2026-08-25',
      created_at: '2026-08-24T00:00:00Z',
      updated_at: '2026-08-24T00:00:00Z',
      settings: { ...DEFAULT_PLANNING_SETTINGS },
    },
    warehouses: [],
    zones: [],
    zone_relations: [],
    drivers: [],
    vehicles: [],
    shifts: [],
    requests: [],
  };
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
  };
}

afterEach(() => {
  useUiStore.setState({ mode: 'EDITOR', section: 'SCENARIO' });
});

describe('application shell', () => {
  it('identifies the active truck routing stack', () => {
    render(<Sidebar workspace={workspaceFixture()} plan={null} />);

    expect(screen.getByText('Valhalla · OpenStreetMap · грузовой граф')).toBeVisible();
    expect(screen.queryByText(/OSRM/)).not.toBeInTheDocument();
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
    expect(screen.getByText(/зоны Z1 и Z2 являются соседними/)).toBeVisible();
    rerender(<PlanPanel plan={plan} timeZone="Europe/Moscow" showUnassignedOnly onSelectCycle={() => undefined} onSelectDriverRoute={() => undefined} onMove={() => undefined} onToggleLock={() => undefined} />);
    expect(screen.getByText(/временное окно 09:00–11:00/)).toBeVisible();
    expect(screen.getByText('увеличить временное окно')).toBeVisible();
  });

  it('renders the selected plan leg truck profile inside the route inspector', () => {
    const plan = planFixture();
    const cycle = plan.driver_routes[0]!.cycles[0]!;
    cycle.legs = [{
      id: 'leg-profile', from_stop_id: 's0', to_stop_id: 's1', departure_at: '2026-08-25T05:30:00Z', arrival_at: '2026-08-25T06:00:00Z',
      distance_meters: 10000, travel_seconds: 1800,
      geometry: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[37.6, 55.7], [37.6, 55.8]] } },
      routing_provider: 'valhalla', osm_data_version: '2026-08-24', routed_at: '2026-08-25T05:20:00Z',
      routing_profile_snapshot: {
        vehicleId: 'vehicle', trailerId: null, trailerAttached: false, isHgv: true, cargoCount: 1,
        cargoPlacements: [{ cargoId: 'cargo-1', position: 'TRUCK_PLATFORM', lengthMm: 6000, widthMm: 2400, heightMm: 2400, weightKg: 2500 }],
        configurationType: 'CARGO_ON_TRUCK', effectiveHeightMeters: 3.95, effectiveWidthMeters: 2.5,
        effectiveLengthMeters: 9, actualWeightTons: 18.2, maxAxleLoadTons: 7.8, axleCount: 3,
      },
    }];
    const simulation: SimulationDerivedState = { timestamp: '2026-08-25T05:45:00Z', vehicles: [], events: [], completed_stop_ids: [], active_stop_ids: [], affected_task_ids: [], warnings: [] };
    useUiStore.setState({ section: 'ROUTES' });

    render(<Inspector {...inspectorProps(plan, simulation)} />);

    expect(screen.getByRole('heading', { name: 'Диагностика грузовых маршрутов' })).toBeVisible();
    expect(screen.getByText('груз на машине')).toBeVisible();
    expect(screen.getByText('9.00 м')).toBeVisible();
    expect(screen.getByText('valhalla')).toBeVisible();
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
    fireEvent.change(slider, { target: { value: String(new Date('2026-08-25T08:00:00Z').getTime()) } });
    expect(onTimestamp).toHaveBeenCalledWith(new Date('2026-08-25T08:00:00Z').getTime());
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
