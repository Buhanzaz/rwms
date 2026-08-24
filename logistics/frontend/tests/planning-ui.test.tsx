import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { EMPTY_METRICS } from '../src/domain/defaults';
import type { RoutePlan, SimulationDerivedState } from '../src/domain/types';
import { PlanPanel } from '../src/features/planning/PlanPanel';
import { SimulationBar } from '../src/features/simulation/SimulationBar';

function planFixture(): RoutePlan {
  return {
    id: 'plan', scenario_id: 'scenario', warehouse_id: 'warehouse', date: '2026-08-25', version: 3, status: 'GENERATED', score: 42,
    created_at: '2026-08-24T00:00:00Z', updated_at: '2026-08-24T00:00:00Z', metrics: { ...EMPTY_METRICS, request_count: 5, assigned_count: 4, unassigned_count: 1, assignment_percent: 80, cycle_count: 1 },
    unassigned: [{
      task: { id: 'task-5', request_id: 'request-5', part_number: 1, quantity: 2, type: 'DELIVERY', latitude: 55.8, longitude: 37.7, zone_id: 'z4', zone_version: 1, service_minutes: 30, priority: 5, status: 'UNASSIGNED' },
      reason_codes: ['TIME_WINDOW_CONFLICT'], reasons: ['временное окно 09:00–11:00 не помещается ни в одну смену'], closest_option: '12:15', recommendations: ['увеличить временное окно'],
    }],
    driver_routes: [{
      driver_shift_id: 'shift', driver_id: 'driver', driver_name: 'Водитель 1', vehicle_id: 'vehicle', vehicle_name: 'МАЗ', registration_number: 'А123БВ', preferred_route_group: 'WEST', metrics: { ...EMPTY_METRICS },
      cycles: [{
        id: 'cycle', route_plan_id: 'plan', driver_shift_id: 'shift', sequence: 1, planned_start: '2026-08-25T05:00:00Z', planned_finish: '2026-08-25T10:00:00Z', total_distance_meters: 146000,
        total_travel_seconds: 12300, total_service_seconds: 6000, empty_distance_meters: 25000, detour_seconds: 1320, score: 12.5, locked: false,
        explanation: ['зоны Z1 и Z2 являются соседними', 'дополнительный путь составляет 14 минут'], warnings: [],
        stops: [
          { id: 's0', route_cycle_id: 'cycle', sequence: 0, task_id: null, stop_type: 'DEPOT_LOAD', planned_arrival: '2026-08-25T05:00:00Z', planned_departure: '2026-08-25T05:30:00Z', service_seconds: 1800, quantity_delta: 2, load_before: 0, load_after: 2, latitude: 55.7, longitude: 37.6, label: 'Склад' },
          { id: 's1', route_cycle_id: 'cycle', sequence: 1, task_id: 'task-1', stop_type: 'DELIVERY', planned_arrival: '2026-08-25T06:00:00Z', planned_departure: '2026-08-25T06:20:00Z', service_seconds: 1200, quantity_delta: -1, load_before: 2, load_after: 1, latitude: 55.8, longitude: 37.6, label: 'Доставка 142', zone_code: 'Z1' },
          { id: 's2', route_cycle_id: 'cycle', sequence: 2, task_id: 'task-2', stop_type: 'DELIVERY', planned_arrival: '2026-08-25T07:00:00Z', planned_departure: '2026-08-25T07:20:00Z', service_seconds: 1200, quantity_delta: -1, load_before: 1, load_after: 0, latitude: 55.8, longitude: 37.7, label: 'Доставка 151', zone_code: 'Z2' },
          { id: 's3', route_cycle_id: 'cycle', sequence: 3, task_id: 'task-3', stop_type: 'PICKUP', planned_arrival: '2026-08-25T08:00:00Z', planned_departure: '2026-08-25T08:20:00Z', service_seconds: 1200, quantity_delta: 1, load_before: 0, load_after: 1, latitude: 55.8, longitude: 37.7, label: 'Вывоз 98', zone_code: 'Z2' },
          { id: 's4', route_cycle_id: 'cycle', sequence: 4, task_id: 'task-4', stop_type: 'PICKUP', planned_arrival: '2026-08-25T09:00:00Z', planned_departure: '2026-08-25T09:20:00Z', service_seconds: 1200, quantity_delta: 1, load_before: 1, load_after: 2, latitude: 55.8, longitude: 37.6, label: 'Вывоз 103', zone_code: 'Z1' },
          { id: 's5', route_cycle_id: 'cycle', sequence: 5, task_id: null, stop_type: 'DEPOT_RETURN', planned_arrival: '2026-08-25T10:00:00Z', planned_departure: '2026-08-25T10:00:00Z', service_seconds: 0, quantity_delta: -2, load_before: 2, load_after: 0, latitude: 55.7, longitude: 37.6, label: 'Склад' },
        ], legs: [],
      }],
    }],
  };
}

describe('built plan UI', () => {
  it('shows driver, selects the driver route, and exposes exact load and unassigned details', async () => {
    const user = userEvent.setup();
    const plan = planFixture();
    const onSelectDriverRoute = vi.fn<(driverShiftId: string) => void>();
    const { rerender } = render(<PlanPanel plan={plan} timeZone="Europe/Moscow" onSelectCycle={() => undefined} onSelectDriverRoute={onSelectDriverRoute} onMove={() => undefined} onToggleLock={() => undefined} />);
    expect(screen.getByText('Водитель 1')).toBeVisible();
    await user.click(screen.getByRole('button', { name: /Водитель 1/i }));
    expect(onSelectDriverRoute).toHaveBeenCalledWith('shift');
    expect(screen.getByLabelText('Цепочка загрузки цикла 1')).toHaveTextContent('2 → 1 → 0 → 1 → 2 → 0');
    expect(screen.getByText(/зоны Z1 и Z2 являются соседними/)).toBeVisible();
    rerender(<PlanPanel plan={plan} timeZone="Europe/Moscow" showUnassignedOnly onSelectCycle={() => undefined} onSelectDriverRoute={() => undefined} onMove={() => undefined} onToggleLock={() => undefined} />);
    expect(screen.getByText(/временное окно 09:00–11:00/)).toBeVisible();
    expect(screen.getByText('увеличить временное окно')).toBeVisible();
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
