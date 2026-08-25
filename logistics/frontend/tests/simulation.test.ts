import { describe, expect, it } from 'vitest';
import type { RoutePlan, SimulationOverride } from '../src/domain/types';
import { deriveSimulationState, interpolateLineString } from '../src/simulation/deriveSimulationState';
import { EMPTY_METRICS } from '../src/domain/defaults';
import { deriveSimulationRouteLayers } from '../src/simulation/route-layers';

function planFixture(): RoutePlan {
  return {
    id: 'plan-1', scenario_id: 'scenario-1', warehouse_id: 'warehouse-1', date: '2026-08-25', version: 1,
    status: 'GENERATED', score: 10, created_at: '2026-08-24T00:00:00Z', updated_at: '2026-08-24T00:00:00Z',
    metrics: { ...EMPTY_METRICS }, unassigned: [],
    driver_routes: [{
      driver_shift_id: 'shift-1', driver_id: 'driver-1', driver_name: 'Водитель 1', vehicle_id: 'vehicle-1', vehicle_name: 'МАЗ', registration_number: 'А123БВ', preferred_route_group: 'WEST', metrics: { ...EMPTY_METRICS },
      cycles: [{
        id: 'cycle-1', route_plan_id: 'plan-1', driver_shift_id: 'shift-1', sequence: 1,
        planned_start: '2026-08-25T05:00:00Z', planned_finish: '2026-08-25T07:30:00Z', total_distance_meters: 10000,
        total_travel_seconds: 3600, total_service_seconds: 5400, empty_distance_meters: 4000, detour_seconds: 0, score: 10, locked: false,
        explanation: ['Совместимые окна'], warnings: [],
        stops: [
          { id: 'stop-depot-start', route_cycle_id: 'cycle-1', sequence: 0, task_id: null, stop_type: 'DEPOT_LOAD', planned_arrival: '2026-08-25T05:00:00Z', planned_departure: '2026-08-25T05:30:00Z', service_seconds: 1800, quantity_delta: 2, load_before: 0, load_after: 2, latitude: 55.75, longitude: 37.6, label: 'Склад' },
          { id: 'stop-delivery', route_cycle_id: 'cycle-1', sequence: 1, task_id: 'task-1', stop_type: 'DELIVERY', planned_arrival: '2026-08-25T06:00:00Z', planned_departure: '2026-08-25T06:15:00Z', service_seconds: 900, quantity_delta: -1, load_before: 2, load_after: 1, latitude: 55.75, longitude: 37.63, label: 'Москва, Тверская улица, 10' },
          { id: 'stop-pickup', route_cycle_id: 'cycle-1', sequence: 2, task_id: 'task-2', stop_type: 'PICKUP', planned_arrival: '2026-08-25T06:45:00Z', planned_departure: '2026-08-25T07:00:00Z', service_seconds: 900, quantity_delta: 1, load_before: 1, load_after: 2, latitude: 55.75, longitude: 37.65, label: 'Вывоз 98' },
          { id: 'stop-depot-end', route_cycle_id: 'cycle-1', sequence: 3, task_id: null, stop_type: 'DEPOT_RETURN', planned_arrival: '2026-08-25T07:30:00Z', planned_departure: '2026-08-25T07:30:00Z', service_seconds: 0, quantity_delta: -2, load_before: 2, load_after: 0, latitude: 55.75, longitude: 37.6, label: 'Склад' },
        ],
        legs: [
          { id: 'leg-1', departure_at: '2026-08-25T05:30:00Z', arrival_at: '2026-08-25T06:00:00Z', distance_meters: 3000, travel_seconds: 1800, geometry: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[37.6, 55.75], [37.61, 55.75], [37.63, 55.75]] } } },
          { id: 'leg-2', departure_at: '2026-08-25T06:15:00Z', arrival_at: '2026-08-25T06:45:00Z', distance_meters: 2000, travel_seconds: 1800, geometry: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[37.63, 55.75], [37.65, 55.75]] } } },
          { id: 'leg-3', departure_at: '2026-08-25T07:00:00Z', arrival_at: '2026-08-25T07:30:00Z', distance_meters: 5000, travel_seconds: 1800, geometry: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[37.65, 55.75], [37.6, 55.75]] } } },
        ],
      }],
    }],
  };
}

describe('interpolateLineString', () => {
  it('interpolates by accumulated distance rather than coordinate index', () => {
    const position = interpolateLineString([[0, 0], [0.01, 0], [0.03, 0]], 0.5);
    expect(position[0]).toBeCloseTo(0.015, 4);
    expect(position[1]).toBeCloseTo(0, 6);
  });
});

describe('deriveSimulationState', () => {
  it('keeps load_before throughout service and switches at departure', () => {
    const plan = planFixture();
    const during = deriveSimulationState(plan, '2026-08-25T06:05:00Z', []);
    const departed = deriveSimulationState(plan, '2026-08-25T06:15:00Z', []);
    expect(during.vehicles[0]).toMatchObject({ status: 'DELIVERING', load: 2 });
    expect(departed.vehicles[0]).toMatchObject({ status: 'DELIVERING', load: 1 });
  });

  it('derives the same state after seeking forward and backward', () => {
    const plan = planFixture();
    const before = deriveSimulationState(plan, '2026-08-25T05:45:00Z', []);
    deriveSimulationState(plan, '2026-08-25T07:10:00Z', []);
    const afterSeekBack = deriveSimulationState(plan, '2026-08-25T05:45:00Z', []);
    expect(afterSeekBack).toEqual(before);
    expect(before.vehicles[0]).toMatchObject({
      status: 'DRIVING',
      next_stop_label: 'Москва, Тверская улица, 10',
      eta: '2026-08-25T06:00:00.000Z',
    });
  });

  it('applies delay without mutating the original plan', () => {
    const plan = planFixture();
    const original = structuredClone(plan);
    const override: SimulationOverride = { id: 'delay-1', kind: 'DELAY', driver_shift_id: 'shift-1', effective_at: '2026-08-25T05:45:00Z', delay_minutes: 20, reason: 'Пробка' };
    const state = deriveSimulationState(plan, '2026-08-25T06:05:00Z', [override]);
    expect(state.vehicles[0]).toMatchObject({ status: 'DRIVING', delayed_by_minutes: 20, eta: '2026-08-25T06:20:00.000Z' });
    expect(state.warnings[0]?.code).toBe('DELAY_APPLIED');
    expect(plan).toEqual(original);
  });

  it('freezes a vehicle when its driver becomes unavailable', () => {
    const plan = planFixture();
    const override: SimulationOverride = { id: 'unavailable-1', kind: 'DRIVER_UNAVAILABLE', driver_shift_id: 'shift-1', effective_at: '2026-08-25T06:10:00Z', delay_minutes: 0, reason: 'Болезнь' };
    const state = deriveSimulationState(plan, '2026-08-25T07:00:00Z', [override]);
    expect(state.vehicles[0]?.status).toBe('DELAYED');
    expect(state.affected_task_ids).toContain('task-2');
    expect(state.warnings[0]?.code).toBe('DRIVER_UNAVAILABLE');
    expect(state.completed_stop_ids).toEqual(['stop-depot-start']);
    expect(state.active_stop_ids).toEqual(['stop-delivery']);
    expect(state.events.map((event) => event.label)).not.toContain('Водитель 1: прибытие — Вывоз 98');
  });
});

describe('simulation route layers', () => {
  it('keeps traveled legs visible during service and after seeking backward', () => {
    const plan = planFixture();
    const duringService = deriveSimulationState(plan, '2026-08-25T06:05:00Z', []);
    const duringSecondLeg = deriveSimulationState(plan, '2026-08-25T06:30:00Z', []);
    deriveSimulationState(plan, '2026-08-25T07:30:00Z', []);
    const afterSeekBack = deriveSimulationState(plan, '2026-08-25T06:05:00Z', []);

    const serviceLayers = deriveSimulationRouteLayers(plan, duringService);
    const drivingLayers = deriveSimulationRouteLayers(plan, duringSecondLeg);
    expect(serviceLayers.traveled).toHaveLength(1);
    expect(serviceLayers.traveled[0]?.geometry.coordinates).toEqual([[37.6, 55.75], [37.61, 55.75], [37.63, 55.75]]);
    expect(serviceLayers.active).toHaveLength(0);
    expect(drivingLayers.traveled).toHaveLength(1);
    expect(drivingLayers.active).toHaveLength(1);
    expect(drivingLayers.active[0]?.geometry.coordinates).toEqual([[37.63, 55.75], [37.65, 55.75]]);
    expect(deriveSimulationRouteLayers(plan, afterSeekBack)).toEqual(
      serviceLayers,
    );
  });
});
