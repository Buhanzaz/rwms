import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { GeocodedAddress } from '../src/api/client';
import { SlotAvailabilityPanel } from '../src/features/slot-availability/SlotAvailabilityPanel';
import type { SlotAvailabilityResponse, SlotPlanningMapPresentation } from '../src/features/slot-availability/types';
import { useUiStore } from '../src/stores/ui-store';
import { warehouseFixture } from './fixtures';

const warehouse = warehouseFixture({ id: 'warehouse-spb', name: 'СПБ', latitude: 59.9, longitude: 30.3 });

const calculated: SlotAvailabilityResponse = {
  date: '2026-08-29',
  plan_version: 17,
  delivery_price_rubles: 12500,
  price_isochrone_minutes: 300,
  trailer_access_allowed: true,
  slots: [
    {
      start: '09:00', end: '12:00', status: 'AVAILABLE', candidate_count: 2, reasons: [],
      explanation: ['Новая доставка помещается перед доставкой 12:00–15:00'],
      best_candidate: {
        estimated_service_start: '2026-08-29T09:20:00+03:00',
        warehouse_return_time: '2026-08-29T15:50:00+03:00',
        minimum_slack_minutes: 35,
        incremental_travel_minutes: 22,
        incremental_distance: 18400,
        waiting_minutes: 15,
        pickup_count: 1,
        affected_stops: ['delivery-25'],
        timeline: [
          { type: 'WAREHOUSE_LOAD', label: 'СПБ', arrival_at: '2026-08-29T08:00:00+03:00', departure_at: '2026-08-29T08:30:00+03:00', load_before: 0, load_after: 2 },
          { type: 'DELIVERY', label: 'Новый клиент', service_start: '2026-08-29T09:20:00+03:00', service_end: '2026-08-29T10:20:00+03:00', load_before: 2, load_after: 1 },
          { type: 'WAIT', label: 'Ожидание окна', arrival_at: '2026-08-29T11:05:00+03:00', departure_at: '2026-08-29T12:00:00+03:00', waiting_minutes: 55, load_before: 1, load_after: 1 },
          { type: 'PICKUP', label: 'Вывоз P1', service_start: '2026-08-29T13:30:00+03:00', service_end: '2026-08-29T14:30:00+03:00', load_before: 0, load_after: 1 },
        ],
        route_before_geojson: { type: 'LineString', coordinates: [[30.3, 59.9], [30.4, 59.8]] },
        route_after_geojson: { type: 'LineString', coordinates: [[30.3, 59.9], [30.35, 59.85], [30.4, 59.8]] },
      },
    },
    { start: '12:00', end: '15:00', status: 'UNAVAILABLE', candidate_count: 0, reasons: ['NEXT_TRIP_AT_RISK'], explanation: [] },
    { start: '15:00', end: '18:00', status: 'UNAVAILABLE', candidate_count: 0, reasons: ['TRUCK_ROUTE_NOT_FOUND'], explanation: [] },
  ],
};

function panel(calculate = vi.fn().mockResolvedValue(calculated), debounceMilliseconds = 0) {
  const presentations: SlotPlanningMapPresentation[] = [];
  const suggestAddresses = vi.fn().mockResolvedValue([]);
  const resolveAddressSuggestion = vi.fn().mockResolvedValue({ address: 'СПб, адрес 1', latitude: 59.93428, longitude: 30.335099 });
  const reverseGeocode = vi.fn().mockResolvedValue({ address: 'СПб, адрес 1', latitude: 59.93428, longitude: 30.335099 });
  render(<SlotAvailabilityPanel
    warehouseId="warehouse-spb"
    warehouses={[warehouse]}
    planningDate="2026-08-29"
    point={{ latitude: 59.93428, longitude: 30.335099 }}
    onPointChange={vi.fn()}
    onClose={vi.fn()}
    onPresentationChange={(value) => presentations.push(value)}
    calculate={calculate}
    suggestAddresses={suggestAddresses}
    resolveAddressSuggestion={resolveAddressSuggestion}
    reverseGeocode={reverseGeocode}
    debounceMilliseconds={debounceMilliseconds}
  />);
  return { calculate, presentations, suggestAddresses, resolveAddressSuggestion, reverseGeocode };
}

function deferredAddress() {
  let resolve!: (address: GeocodedAddress) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<GeocodedAddress>((complete, fail) => { resolve = complete; reject = fail; });
  return { promise, resolve, reject };
}

function addressRacePanel() {
  const first = deferredAddress();
  const second = deferredAddress();
  const resolveAddressSuggestion = vi.fn<(uri: string, signal: AbortSignal) => Promise<GeocodedAddress>>()
    .mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
  const onPointChange = vi.fn();
  const calculate = vi.fn().mockResolvedValue(calculated);
  const suggestAddresses = vi.fn().mockResolvedValue([
    { id: 'a', title: 'Адрес A', uri: 'address:a' },
    { id: 'b', title: 'Адрес B', uri: 'address:b' },
  ]);
  const reverseGeocode = vi.fn().mockResolvedValue({ address: 'Точка на карте', latitude: 60, longitude: 31 });
  function ControlledPanel() {
    const [point, setPoint] = useState<{ latitude: number; longitude: number } | null>(null);
    return <>
      <button onClick={() => setPoint({ latitude: 60, longitude: 31 })}>Выбрать точку на карте</button>
      <SlotAvailabilityPanel
        warehouseId={warehouse.id} warehouses={[warehouse]} planningDate="2026-08-29" point={point}
        onPointChange={(value) => { onPointChange(value); setPoint(value); }}
        onClose={vi.fn()} onPresentationChange={vi.fn()} calculate={calculate}
        suggestAddresses={suggestAddresses} resolveAddressSuggestion={resolveAddressSuggestion}
        reverseGeocode={reverseGeocode} debounceMilliseconds={0}
      />
    </>;
  }
  return { ...render(<ControlledPanel />), first, second, onPointChange, calculate, resolveAddressSuggestion, reverseGeocode };
}

afterEach(() => {
  vi.useRealTimers();
  useUiStore.setState((state) => ({ layers: { ...state.layers, warehouseIsochrones: false, taskIsochrones: false } }));
});

describe('dispatcher slot availability panel', () => {
  it('debounces the complete request and carries site capacity separately from cabin count', async () => {
    vi.useFakeTimers();
    const { calculate } = panel(vi.fn().mockResolvedValue(calculated), 400);
    fireEvent.change(screen.getByLabelText('Количество бытовок'), { target: { value: '2' } });
    fireEvent.change(screen.getByLabelText('Вместимость объекта за один заезд'), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('Адрес нового клиента'), { target: { value: 'СПб, адрес 1' } });

    act(() => { vi.advanceTimersByTime(399); });
    expect(calculate).not.toHaveBeenCalled();
    await act(async () => { vi.advanceTimersByTime(1); await Promise.resolve(); });
    expect(calculate).toHaveBeenCalledOnce();
    expect(calculate.mock.calls[0]?.[0]).toMatchObject({
      cabin_count: 2,
      site_cabin_capacity: 1,
      latitude: 59.93428,
      longitude: 30.335099,
    });
  });

  it('passes an arbitrary positive order size independently from one-cabin site capacity', async () => {
    const { calculate } = panel();
    fireEvent.change(screen.getByLabelText('Количество бытовок'), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText('Вместимость объекта за один заезд'), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('Адрес нового клиента'), { target: { value: 'СПб, адрес 1' } });

    await waitFor(() => expect(calculate).toHaveBeenCalledOnce());
    expect(calculate.mock.calls[0]?.[0]).toMatchObject({
      cabin_count: 3,
      site_cabin_capacity: 1,
    });
  });

  it('renders exactly three server-controlled cards and structured Russian failure reasons', async () => {
    panel();
    expect(screen.getByText('Поиск слота для нового заказа')).toBeVisible();
    expect(screen.queryByText('Проверка нового заказа')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Адрес нового клиента'), { target: { value: 'СПб, адрес 1' } });
    const region = screen.getByRole('region', { name: 'Доступные клиентские слоты' });
    await within(region).findByText('Вставка создаёт риск опоздания следующей ходки');
    expect(within(region).getAllByRole('button')).toHaveLength(3);
    expect(within(region).queryByText('Нельзя начать разгрузку внутри выбранного окна')).not.toBeInTheDocument();
    expect(within(region).getByText('Грузовой маршрут до адреса не найден')).toBeInTheDocument();
    expect(within(region).getAllByText('2').length).toBeGreaterThan(0);
  });

  it('never exposes backend reason codes and translates trailer access failures', async () => {
    const unavailable = {
      ...calculated,
      slots: calculated.slots.map((slot, index) => index === 0
        ? { ...slot, status: 'UNAVAILABLE' as const, candidate_count: 0, best_candidate: undefined, reasons: ['TRAILER_ACCESS_NOT_ALLOWED', 'UNRECOGNIZED_REASON'] }
        : slot),
    };
    panel(vi.fn().mockResolvedValue(unavailable));
    fireEvent.change(screen.getByLabelText('Адрес нового клиента'), { target: { value: 'СПб, адрес 1' } });

    const region = screen.getByRole('region', { name: 'Доступные клиентские слоты' });
    expect(await within(region).findByText('Проезд к адресу с прицепом не разрешён')).toBeVisible();
    expect(within(region).getByText('Слот недоступен из-за ограничений маршрута')).toBeVisible();
    expect(within(region).queryByText(/TRAILER_ACCESS_NOT_ALLOWED|UNRECOGNIZED_REASON/u)).not.toBeInTheDocument();
  });

  it('shows the dynamic isochrone price and selected route timeline with load changes', async () => {
    panel();
    fireEvent.change(screen.getByLabelText('Адрес нового клиента'), { target: { value: 'СПб, адрес 1' } });
    const available = await screen.findByRole('button', { name: /09:00–12:00.*Доступен/s });
    fireEvent.click(available);

    const price = screen.getByRole('region', { name: 'Стоимость доставки' });
    expect(within(price).getByText('Стоимость доставки')).toBeVisible();
    expect(within(price).queryByText(/изохрона склада/iu)).not.toBeInTheDocument();
    expect(within(price).getByText(/12\s?500 ₽/)).toBeInTheDocument();
    expect(within(price).getByText(/Цена рассчитана по времени пути от склада: до 5 ч/)).toBeInTheDocument();
    const timeline = screen.getByRole('region', { name: 'Временная шкала маршрута' });
    expect(within(timeline).getByText('Груз 0 → 2')).toBeInTheDocument();
    expect(within(timeline).getByText('Груз 2 → 1')).toBeInTheDocument();
    expect(within(timeline).getByText('Вывоз')).toBeInTheDocument();
    expect(within(timeline).getByText('ожидание 55 мин')).toBeInTheDocument();
  });

  it('publishes independent map layer switches without making a feasibility decision in UI', async () => {
    const { presentations } = panel();
    fireEvent.click(screen.getByLabelText('Маршрут до добавления'));
    fireEvent.click(screen.getByLabelText('Вывозы-кандидаты'));

    await waitFor(() => expect(presentations.at(-1)?.layers).toMatchObject({ routeBefore: true, pickupCandidates: false }));
    expect(presentations.at(-1)?.selectedSlot).toBeNull();
  });

  it('exposes warehouse and selected-task isochrones as explicit map layers', async () => {
    const userWarehouseLayer = screen.queryByLabelText('Изохроны склада');
    expect(userWarehouseLayer).not.toBeInTheDocument();
    panel();

    const warehouseLayer = screen.getByLabelText('Изохроны склада');
    const taskLayer = screen.getByLabelText('Изохроны задания');
    expect(warehouseLayer).not.toBeChecked();
    expect(taskLayer).not.toBeChecked();
    expect(taskLayer).toBeEnabled();
    fireEvent.click(warehouseLayer);
    fireEvent.click(taskLayer);

    await waitFor(() => expect(useUiStore.getState().layers).toMatchObject({ warehouseIsochrones: true, taskIsochrones: true }));
  });

  it('fills the address when coordinates are placed on the map', async () => {
    const { reverseGeocode } = panel();

    await waitFor(() => expect(reverseGeocode).toHaveBeenCalledWith(59.93428, 30.335099, expect.any(AbortSignal)));
    expect(screen.getByLabelText('Адрес нового клиента')).toHaveValue('СПб, адрес 1');
  });

  it('resolves a navigator-like suggestion into both address and coordinates', async () => {
    const onPointChange = vi.fn();
    const suggestAddresses = vi.fn().mockResolvedValue([{ id: 'suggestion-1', title: 'Невский проспект, 1', subtitle: 'Санкт-Петербург', uri: 'ymapsbm1://geo?1' }]);
    const resolveAddressSuggestion = vi.fn().mockResolvedValue({ address: 'Санкт-Петербург, Невский проспект, 1', latitude: 59.935, longitude: 30.325 });
    render(<SlotAvailabilityPanel
      warehouseId="warehouse-spb"
      warehouses={[warehouse]}
      planningDate="2026-08-29"
      point={null}
      onPointChange={onPointChange}
      onClose={vi.fn()}
      onPresentationChange={vi.fn()}
      calculate={vi.fn().mockResolvedValue(calculated)}
      suggestAddresses={suggestAddresses}
      resolveAddressSuggestion={resolveAddressSuggestion}
      reverseGeocode={vi.fn()}
      debounceMilliseconds={0}
    />);

    expect(screen.getByLabelText('Изохроны задания')).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Адрес нового клиента'), { target: { value: 'Невский' } });
    fireEvent.click(await screen.findByRole('option', { name: /Невский проспект, 1/ }));

    await waitFor(() => expect(onPointChange).toHaveBeenCalledWith({ latitude: 59.935, longitude: 30.325 }));
    expect(screen.getByLabelText('Адрес нового клиента')).toHaveValue('Санкт-Петербург, Невский проспект, 1');
  });

  it('keeps the second selected address when the first resolution finishes last', async () => {
    const { first, second, resolveAddressSuggestion, onPointChange, calculate } = addressRacePanel();
    fireEvent.change(screen.getByLabelText('Адрес нового клиента'), { target: { value: 'Адрес' } });
    fireEvent.click(await screen.findByRole('option', { name: 'Адрес A' }));
    fireEvent.click(screen.getByRole('option', { name: 'Адрес B' }));
    expect(resolveAddressSuggestion.mock.calls[0]?.[1].aborted).toBe(true);

    await act(async () => { second.resolve({ address: 'Адрес B', latitude: 60, longitude: 31 }); await second.promise; });
    await waitFor(() => expect(calculate).toHaveBeenCalledWith(expect.objectContaining({ address: 'Адрес B', latitude: 60, longitude: 31 }), expect.any(AbortSignal)));
    await act(async () => { first.resolve({ address: 'Адрес A', latitude: 59, longitude: 30 }); await first.promise; });

    expect(screen.getByLabelText('Адрес нового клиента')).toHaveValue('Адрес B');
    expect(onPointChange).toHaveBeenCalledExactlyOnceWith({ latitude: 60, longitude: 31 });
    expect(calculate.mock.calls.every(([input]) => (input as { address: string }).address === 'Адрес B')).toBe(true);
  });

  it.each(['text', 'coordinates', 'map'] as const)('ignores a pending resolution after changing %s', async (change) => {
    const { first, resolveAddressSuggestion, onPointChange, reverseGeocode } = addressRacePanel();
    fireEvent.change(screen.getByLabelText('Адрес нового клиента'), { target: { value: 'Адрес' } });
    fireEvent.click(await screen.findByRole('option', { name: 'Адрес A' }));
    if (change === 'text') {
      fireEvent.change(screen.getByLabelText('Адрес нового клиента'), { target: { value: 'Новый ручной адрес' } });
    } else if (change === 'coordinates') {
      fireEvent.change(screen.getByLabelText('Широта нового клиента'), { target: { value: '60' } });
    } else {
      fireEvent.click(screen.getByRole('button', { name: 'Выбрать точку на карте' }));
    }
    expect(resolveAddressSuggestion.mock.calls[0]?.[1].aborted).toBe(true);
    await act(async () => { first.resolve({ address: 'Адрес A', latitude: 59, longitude: 30 }); await first.promise; });
    expect(onPointChange).not.toHaveBeenCalledWith({ latitude: 59, longitude: 30 });
    if (change === 'text') {
      expect(screen.getByLabelText('Адрес нового клиента')).toHaveValue('Новый ручной адрес');
      expect(reverseGeocode).not.toHaveBeenCalled();
    } else {
      await waitFor(() => expect(screen.getByLabelText('Адрес нового клиента')).toHaveValue('Точка на карте'));
      expect(screen.getByLabelText('Широта нового клиента')).toHaveValue(60);
    }
  });

  it('does not show a stale resolution error after a newer address succeeds', async () => {
    const { first, second } = addressRacePanel();
    fireEvent.change(screen.getByLabelText('Адрес нового клиента'), { target: { value: 'Адрес' } });
    fireEvent.click(await screen.findByRole('option', { name: 'Адрес A' }));
    fireEvent.click(screen.getByRole('option', { name: 'Адрес B' }));
    await act(async () => { second.resolve({ address: 'Адрес B', latitude: 60, longitude: 31 }); await second.promise; });
    await act(async () => { first.reject(new Error('Запоздавшая ошибка адреса A')); await first.promise.catch(() => undefined); });
    expect(screen.getByLabelText('Адрес нового клиента')).toHaveValue('Адрес B');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows a failure of the current address resolution without calculating a slot', async () => {
    const { first, calculate, onPointChange } = addressRacePanel();
    fireEvent.change(screen.getByLabelText('Адрес нового клиента'), { target: { value: 'Адрес' } });
    fireEvent.click(await screen.findByRole('option', { name: 'Адрес A' }));
    await act(async () => { first.reject(new Error('Геокодер недоступен')); await first.promise.catch(() => undefined); });
    expect(screen.getByRole('alert')).toHaveTextContent('Геокодер недоступен');
    expect(calculate).not.toHaveBeenCalled();
    expect(onPointChange).not.toHaveBeenCalled();
  });

  it('aborts address resolution on unmount and ignores its later success', async () => {
    const { first, unmount, resolveAddressSuggestion, onPointChange, calculate } = addressRacePanel();
    fireEvent.change(screen.getByLabelText('Адрес нового клиента'), { target: { value: 'Адрес' } });
    fireEvent.click(await screen.findByRole('option', { name: 'Адрес A' }));
    unmount();
    expect(resolveAddressSuggestion.mock.calls[0]?.[1].aborted).toBe(true);
    await act(async () => { first.resolve({ address: 'Адрес A', latitude: 59, longitude: 30 }); await first.promise; });
    expect(onPointChange).not.toHaveBeenCalled();
    expect(calculate).not.toHaveBeenCalled();
  });
});
