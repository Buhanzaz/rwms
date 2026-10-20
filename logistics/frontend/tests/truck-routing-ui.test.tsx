import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, type TrailerInput } from '../src/api/client';
import {
  CatalogDialog,
  type VehicleEditorInput,
} from '../src/components/EntityDialogs';
import type {
  RouteCycle,
  WarehouseWorkspace,
  Trailer,
} from '../src/domain/types';
import { TrailerDialog } from '../src/features/trailers/TrailerDialog';
import { workspaceFixture } from './fixtures';

const trailer: Trailer = {
  id: 'trailer-1',
  version: 1,
  warehouse_id: 'warehouse-1',
  name: 'Прицеп 1',
  registration_number: 'ТР1234',
  active: true,
  tare_weight_t: 4,
  max_gross_weight_t: 10,
  axle_load_t: 7,
  payload_capacity_t: 5,
  length_m: 8,
  width_m: 2.5,
  height_m: 2,
  platform_length_m: 6,
  platform_height_from_ground_m: 1,
  max_platform_payload_t: 5,
  axle_count: 2,
  max_cargo_length_m: 6.5,
  max_cargo_width_m: 2.55,
  max_cargo_height_m: 3,
  max_cargo_weight_t: 4,
  notes: '',
};

function setNumber(label: string, value: number): void {
  fireEvent.change(screen.getByLabelText(label), { target: { value: String(value) } });
}

afterEach(() => vi.unstubAllGlobals());

describe('truck vehicle editor', () => {
  it('keeps the vehicle form business-oriented and sends canonical metric units to the API', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: VehicleEditorInput) => Promise<void>>(() => Promise.resolve());
    render(<CatalogDialog busy={false} onClose={() => undefined} onSubmit={submit} />);

    await user.type(screen.getByLabelText('Название'), 'МАЗ 1');
    await user.type(screen.getByLabelText('Марка авто'), 'МАЗ');
    await user.type(screen.getByLabelText('Госномер'), 'А123БВ');
    setNumber('Фактическая масса, т', 4.2);
    setNumber('Разрешённая масса, т', 9.5);
    setNumber('Нагрузка на ось, т', 3.5);
    setNumber('Грузоподъёмность, т', 8);
    setNumber('Длина, м', 9);
    setNumber('Ширина, м', 2.5);
    setNumber('Высота, м', 3);
    setNumber('Длина сцепки, м', 0.9);
    setNumber('Запас по массе, т', 0.25);

    expect(screen.queryByLabelText('Основной прицеп')).not.toBeInTheDocument();
    expect(screen.queryByTestId('one-cargo-preview')).not.toBeInTheDocument();
    expect(screen.getByText('Дополнительные настройки')).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Сохранить' }));
    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    const input = submit.mock.calls[0]?.[0];
    expect(input).toMatchObject({
      name: 'МАЗ 1',
      manufacturer: 'МАЗ',
      registration_number: 'А123БВ',
      capacity: 2,
      tare_weight_t: 4.2,
      max_gross_weight_t: 9.5,
      axle_load_t: 3.5,
      payload_capacity_t: 8,
      length_m: 9,
      width_m: 2.5,
      height_m: 3,
      has_trailer: false,
      coupling_length_m: 0.9,
      weight_safety_margin_t: 0.25,
      default_trailer_id: null,
    });
    expect(input).not.toHaveProperty('payload_capacity_kg');
    expect(input).not.toHaveProperty('length_mm');
    expect(input?.load_profiles).toEqual([]);
  });

  it('preserves hidden routing data when an existing vehicle is renamed', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: VehicleEditorInput) => Promise<void>>(() => Promise.resolve());
    const vehicle = {
      ...workspaceFixture().vehicles[0]!,
      manufacturer: 'КАМАЗ',
      payload_capacity_t: 6.5,
      length_m: 7_200 / 1_000,
      axle_count: 3,
      has_trailer: true,
      default_trailer_id: 'trailer-1',
      platform_length_m: 6,
      max_cargo_length_m: 6.5,
      coupling_length_m: 0.9,
      height_safety_margin_m: 0.1,
      weight_safety_margin_t: 0.25,
      load_profiles: [{ configuration_type: 'TWO_CARGO_SPLIT' as const, max_actual_axle_load_t: 8.7 }],
    };
    render(<CatalogDialog value={vehicle} busy={false} onClose={() => undefined} onSubmit={submit} />);

    await user.clear(screen.getByLabelText('Название'));
    await user.type(screen.getByLabelText('Название'), 'КАМАЗ 65115');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({
      name: 'КАМАЗ 65115',
      tare_weight_t: 4.2,
      max_gross_weight_t: 9.5,
      axle_load_t: 3.5,
      payload_capacity_t: 6.5,
      length_m: 7.2,
      width_m: 2.5,
      height_m: 3.1,
      has_trailer: true,
      default_trailer_id: 'trailer-1',
      axle_count: 3,
      platform_length_m: 6,
      max_cargo_length_m: 6.5,
      coupling_length_m: 0.9,
      height_safety_margin_m: 0.1,
      weight_safety_margin_t: 0.25,
      load_profiles: [{ configuration_type: 'TWO_CARGO_SPLIT', max_actual_axle_load_t: 8.7 }],
    });
  });

  it('converts an edited vehicle between operator units and canonical API numbers while preserving routing data', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: VehicleEditorInput) => Promise<void>>(() => Promise.resolve());
    const vehicle = {
      ...workspaceFixture().vehicles[0]!,
      has_trailer: true,
      default_trailer_id: 'trailer-1',
      platform_length_m: 6,
      max_cargo_length_m: 6.5,
      max_cargo_weight_t: 2.5,
      coupling_length_m: 0.9,
      height_safety_margin_m: 0.1,
      width_safety_margin_m: 0.05,
      weight_safety_margin_t: 0.25,
      load_profiles: [{ configuration_type: 'EMPTY_TRUCK' as const, max_actual_axle_load_t: 7.5 }],
    };
    render(<CatalogDialog value={vehicle} busy={false} onClose={() => undefined} onSubmit={submit} />);

    expect(screen.getByLabelText('Длина платформы машины, м')).toHaveValue(6);
    expect(screen.getByLabelText('Запас по массе, т')).toHaveValue(0.25);
    setNumber('Фактическая масса, т', 4.125);
    setNumber('Грузоподъёмность, т', 6.75);
    setNumber('Длина, м', 9.25);
    setNumber('Длина платформы машины, м', 6.4);
    setNumber('Максимальная масса груза на машине, т', 2.25);
    setNumber('Запас по высоте, м', 0.2);

    await user.click(screen.getByRole('button', { name: 'Сохранить' }));
    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    const input = submit.mock.calls[0]?.[0];
    expect(input).toMatchObject({
      tare_weight_t: 4.125,
      max_gross_weight_t: 9.5,
      axle_load_t: 3.5,
      payload_capacity_t: 6.75,
      length_m: 9.25,
      width_m: 2.5,
      height_m: 3.1,
      has_trailer: true,
      default_trailer_id: 'trailer-1',
      platform_length_m: 6.4,
      max_cargo_length_m: 6.5,
      max_cargo_weight_t: 2.25,
      coupling_length_m: 0.9,
      height_safety_margin_m: 0.2,
      width_safety_margin_m: 0.05,
      weight_safety_margin_t: 0.25,
      load_profiles: [{ configuration_type: 'EMPTY_TRUCK', max_actual_axle_load_t: 7.5 }],
    });
    expect(input).not.toHaveProperty('payload_capacity_kg');
    expect(input).not.toHaveProperty('tare_weight_kg');
    expect(input).not.toHaveProperty('length_mm');
  });

  it('round-trips the Есть прицеп selection for vehicles without and with a trailer', async () => {
    const user = userEvent.setup();
    const withoutTrailer = {
      ...workspaceFixture().vehicles[0]!,
      has_trailer: false,
      default_trailer_id: null,
    };
    const firstSubmit = vi.fn<(input: VehicleEditorInput) => Promise<void>>(() => Promise.resolve());
    const first = render(<CatalogDialog value={withoutTrailer} busy={false} onClose={() => undefined} onSubmit={firstSubmit} />);

    expect(screen.getByLabelText('Есть прицеп')).toHaveValue('false');
    await user.selectOptions(screen.getByLabelText('Есть прицеп'), 'true');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));
    await waitFor(() => expect(firstSubmit).toHaveBeenCalledOnce());
    expect(firstSubmit.mock.calls[0]?.[0]).toMatchObject({ has_trailer: true, default_trailer_id: null });
    first.unmount();

    const withTrailer = {
      ...workspaceFixture().vehicles[0]!,
      has_trailer: true,
      default_trailer_id: 'trailer-9',
    };
    const secondSubmit = vi.fn<(input: VehicleEditorInput) => Promise<void>>(() => Promise.resolve());
    render(<CatalogDialog value={withTrailer} busy={false} onClose={() => undefined} onSubmit={secondSubmit} />);

    expect(screen.getByLabelText('Есть прицеп')).toHaveValue('true');
    await user.selectOptions(screen.getByLabelText('Есть прицеп'), 'false');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));
    await waitFor(() => expect(secondSubmit).toHaveBeenCalledOnce());
    expect(secondSubmit.mock.calls[0]?.[0]).toMatchObject({ has_trailer: false, default_trailer_id: null });
  });

  it('creates a simple trailer record without manufacturing routing properties', async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(input: TrailerInput) => Promise<void>>(() => Promise.resolve());
    render(<TrailerDialog busy={false} onClose={() => undefined} onSubmit={submit} />);
    await user.type(screen.getByLabelText('Марка'), 'Резервный прицеп');
    await user.type(screen.getByLabelText('Госномер'), 'ТР5678');
    await user.click(screen.getByRole('button', { name: 'Сохранить прицеп' }));
    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0]?.[0]).toMatchObject({ name: 'Резервный прицеп', registration_number: 'ТР5678', tare_weight_t: null, length_m: null });
  });

  it('saves the vehicle and all axle profiles through one atomic API command', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>();
    fetchMock
      .mockResolvedValueOnce(new Response(JSON.stringify(trailer), { status: 201, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: 'vehicle-1', warehouse_id: 'warehouse-1', name: 'МАЗ', registration_number: 'А123БВ' }), { status: 201, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);
    const trailerInput = Object.fromEntries(
      Object.entries(trailer).filter(([key]) => key !== 'id' && key !== 'warehouse_id'),
    ) as unknown as TrailerInput;

    await api.createTrailer('warehouse-1', trailerInput, 'trailer-intent');
    await api.createVehicleConfiguration('warehouse-1', {
      vehicle: {
        name: 'МАЗ', registration_number: 'А123БВ', capacity: 2, active: true,
        average_speed_city: 30, average_speed_region: 60, notes: '',
        tare_weight_t: 4.2, max_gross_weight_t: 9.5, axle_load_t: 3.5, payload_capacity_t: 6.75,
        length_m: 9.25, width_m: 2.5, height_m: 3.1, has_trailer: true,
      },
      load_profiles: [{ configuration_type: 'TWO_CARGO_SPLIT', max_actual_axle_load_t: 8.7 }],
    }, 'vehicle-intent');

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/warehouses/warehouse-1/trailers');
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get('Idempotency-Key')).toBe('trailer-intent');
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1]?.body as string)).toMatchObject({ name: 'Прицеп 1', length_m: 8 });
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/warehouses/warehouse-1/vehicle-configurations');
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get('Idempotency-Key')).toBe('vehicle-intent');
    expect(JSON.parse(fetchMock.mock.calls[1]?.[1]?.body as string)).toMatchObject({
      vehicle: {
        name: 'МАЗ',
        registration_number: 'А123БВ',
        payload_capacity_t: 6.75,
        length_m: 9.25,
        has_trailer: true,
      },
      load_profiles: [{ configuration_type: 'TWO_CARGO_SPLIT', max_actual_axle_load_t: 8.7 }],
    });
  });
});

function cycleFixture(): RouteCycle {
  return {
    id: 'cycle-1', route_plan_id: 'plan-1', driver_shift_id: 'shift-1', sequence: 1,
    planned_start: '2026-08-25T05:00:00Z', planned_finish: '2026-08-25T08:00:00Z',
    total_distance_meters: 10000, total_travel_seconds: 1200, total_service_seconds: 600,
    empty_distance_meters: 0, detour_seconds: 0, score: 1, locked: false, manually_changed: false, explanation: [], warnings: [],
    stops: [
      { id: 'stop-1', route_cycle_id: 'cycle-1', sequence: 0, task_id: null, stop_type: 'DEPOT_LOAD', planned_arrival: '2026-08-25T05:00:00Z', planned_departure: '2026-08-25T05:10:00Z', service_seconds: 600, quantity_delta: 2, load_before: 0, load_after: 2, latitude: 55.7, longitude: 37.6, label: 'Склад' },
      { id: 'stop-2', route_cycle_id: 'cycle-1', sequence: 1, task_id: 'task-1', stop_type: 'DELIVERY', planned_arrival: '2026-08-25T06:00:00Z', planned_departure: '2026-08-25T06:20:00Z', service_seconds: 1200, quantity_delta: -1, load_before: 2, load_after: 1, latitude: 55.8, longitude: 37.7, label: 'Клиент' },
      { id: 'stop-3', route_cycle_id: 'cycle-1', sequence: 2, task_id: null, stop_type: 'DEPOT_RETURN', planned_arrival: '2026-08-25T08:00:00Z', planned_departure: '2026-08-25T08:00:00Z', service_seconds: 0, quantity_delta: -1, load_before: 1, load_after: 0, latitude: 55.7, longitude: 37.6, label: 'Склад' },
    ],
    legs: [
      {
        id: 'leg-safe', from_stop_id: 'stop-1', to_stop_id: 'stop-2', departure_at: '2026-08-25T05:10:00Z', arrival_at: '2026-08-25T06:00:00Z', distance_meters: 7000, travel_seconds: 3000,
        geometry: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[37.6, 55.7], [37.7, 55.8]] } },
        routing_provider: 'valhalla', osm_data_version: '2026-08-24', routed_at: '2026-08-25T04:59:00Z',
        routing_profile_snapshot: {
          vehicleId: 'vehicle-1', trailerId: 'trailer-1', trailerAttached: true, isHgv: true, cargoCount: 2,
          cargoPlacements: [
            { cargoId: 'cargo-1', position: 'TRUCK_PLATFORM', lengthMm: 6000, widthMm: 2400, heightMm: 2400, weightKg: 2500 },
            { cargoId: 'cargo-2', position: 'TRAILER_PLATFORM', lengthMm: 6000, widthMm: 2400, heightMm: 2400, weightKg: 2500 },
          ],
          configurationType: 'TWO_CARGO_SPLIT', effectiveHeightMeters: 4.02, effectiveWidthMeters: 2.5,
          effectiveLengthMeters: 18.6, actualWeightTons: 24.8, maxAxleLoadTons: 8.4, axleCount: 5,
        },
      },
      {
        id: 'leg-unprofiled', from_stop_id: 'stop-2', to_stop_id: 'stop-3', departure_at: '2026-08-25T06:20:00Z', arrival_at: '2026-08-25T08:00:00Z', distance_meters: 3000, travel_seconds: 6000,
        geometry: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[37.7, 55.8], [37.6, 55.7]] } },
        routing_profile_snapshot: null,
      },
    ],
  };
}

describe('route profile normalization', () => {
  function rawPlanWithSnapshot(routingProfileSnapshot: unknown): object {
    const cycle = cycleFixture();
    const segment = cycle.legs[0]!;
    return {
      id: 'plan-1', warehouse_id: 'warehouse-1', date: '2026-08-25', name: 'План', version: 1,
      status: 'GENERATED', score: 1, metrics: {}, validation_errors: [], validation_warnings: [], manually_changed: false,
      created_at: '2026-08-25T04:00:00Z', updated_at: '2026-08-25T04:00:00Z', unassigned_tasks: [],
      cycles: [{
        id: cycle.id, driver_shift_id: cycle.driver_shift_id, sequence: 1, planned_start: cycle.planned_start, planned_finish: cycle.planned_finish,
        total_distance_meters: cycle.total_distance_meters, total_travel_seconds: cycle.total_travel_seconds, total_service_seconds: cycle.total_service_seconds,
        empty_distance_meters: 0, detour_seconds: 0, score: 1, locked: false, manually_changed: false, metrics: {}, explanations: [],
        stops: cycle.stops.slice(0, 2).map((stop) => ({ ...stop, warnings: [], locked: false })),
        segments: [{
          id: segment.id, sequence: 0, from_stop_id: segment.from_stop_id, to_stop_id: segment.to_stop_id,
          departure_at: segment.departure_at, arrival_at: segment.arrival_at, distance_meters: segment.distance_meters,
          travel_seconds: segment.travel_seconds, geometry: segment.geometry.geometry,
          routing_profile_snapshot: routingProfileSnapshot, routing_provider: 'valhalla', osm_data_version: '2026-08-24', routed_at: segment.routed_at,
        }],
      }],
    };
  }

  async function normalize(rawPlan: object) {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify(rawPlan), { status: 200, headers: { 'Content-Type': 'application/json' } }))));
    const workspace: WarehouseWorkspace = workspaceFixture({ drivers: [], vehicles: [], shifts: [], requests: [] });
    return api.getPlan('plan-1', workspace);
  }

  it('preserves a canonical backend profile snapshot when normalizing a route plan', async () => {
    const canonicalSnapshot = {
      ...cycleFixture().legs[0]!.routing_profile_snapshot!,
      cargoPlacements: [
        { cargoId: 'cargo-1', position: 'TRUCK_PLATFORM', lengthM: 6, widthM: 2.4, heightM: 2.4, weightT: 2.5 },
        { cargoId: 'cargo-2', position: 'TRAILER_PLATFORM', lengthM: 6, widthM: 2.4, heightM: 2.4, weightT: 2.5 },
      ],
    };
    const plan = await normalize(rawPlanWithSnapshot(canonicalSnapshot));

    expect(plan.driver_routes[0]?.cycles[0]?.legs[0]?.routing_profile_snapshot?.effectiveLengthMeters).toBe(18.6);
    expect(plan.driver_routes[0]?.cycles[0]?.legs[0]?.routing_profile_snapshot?.cargoPlacements[0]).toEqual({
      cargoId: 'cargo-1',
      position: 'TRUCK_PLATFORM',
      lengthMm: 6_000,
      widthMm: 2_400,
      heightMm: 2_400,
      weightKg: 2_500,
    });
    expect(plan.driver_routes[0]?.cycles[0]?.legs[0]?.routing_provider).toBe('valhalla');
  });

  it('normalizes persisted pre-canonical millimetre snapshots without dropping them', async () => {
    const legacySnapshot = cycleFixture().legs[0]!.routing_profile_snapshot;
    const plan = await normalize(rawPlanWithSnapshot(legacySnapshot));

    expect(plan.driver_routes[0]?.cycles[0]?.legs[0]?.routing_profile_snapshot?.cargoPlacements[0]).toEqual({
      cargoId: 'cargo-1',
      position: 'TRUCK_PLATFORM',
      lengthMm: 6_000,
      widthMm: 2_400,
      heightMm: 2_400,
      weightKg: 2_500,
    });
  });
});
