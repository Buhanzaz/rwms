import { MapPin, Route, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { AddressSuggestion, GeocodedAddress } from '../../api/client';
import type { Warehouse } from '../../domain/types';
import { Button, CheckboxField, Spinner } from '../../components/ui';
import { useUiStore } from '../../stores/ui-store';
import type {
  SlotAvailabilityInput,
  SlotAvailabilityOption,
  SlotAvailabilityResponse,
  SlotPlanningLayers,
  SlotPlanningMapPresentation,
} from './types';

const FIXED_SLOTS = [
  { start: '09:00', end: '12:00' },
  { start: '12:00', end: '15:00' },
  { start: '15:00', end: '18:00' },
] as const;

const DEFAULT_LAYERS: SlotPlanningLayers = {
  routeBefore: false,
  routeAfter: true,
  pickupCandidates: true,
};

const REASON_LABELS: Record<string, string> = {
  DELIVERY_WINDOW_MISSED: 'Нельзя начать разгрузку внутри выбранного окна',
  SHIFT_END_EXCEEDED: 'Водитель не успеет вернуться и закончить смену до 20:00',
  VEHICLE_CAPACITY_EXCEEDED: 'Превышена вместимость машины или прицепа',
  NO_COMPATIBLE_VEHICLE: 'Нет машины с подходящей грузовой конфигурацией',
  NO_FREE_DRIVER: 'Нет свободного водителя с допустимым планом дня',
  TRUCK_ROUTE_NOT_FOUND: 'Грузовой маршрут до адреса не найден',
  NO_FREE_TRIP_CAPACITY: 'В существующих ходках нет свободного места',
  WAREHOUSE_TURNAROUND_TOO_LONG: 'Не хватает времени на складскую выгрузку и новую загрузку',
  NEXT_TRIP_AT_RISK: 'Вставка создаёт риск опоздания следующей ходки',
  LOCKED_STOP_CONFLICT: 'Вставка конфликтует с зафиксированным участком плана',
  SLOT_ALREADY_HELD: 'Последний вариант временно удерживает другой клиент',
  PICKUP_DEFERRED: 'Вывоз перенесён, чтобы не рисковать доставками',
  SITE_CAPACITY_EXCEEDED: 'Объект не может принять столько бытовок за один заезд',
  TRAILER_ACCESS_REQUIRED: 'Для заказа нужен прицеп, но он не проедет к адресу',
  AVAILABILITY_NOT_CONFIRMED: 'Сервис не подтвердил выполнимость этого слота',
  AVAILABILITY_NOT_RETURNED: 'Сервис не вернул расчёт для этого окна',
};

const STOP_LABELS: Record<string, string> = {
  WAREHOUSE_LOAD: 'Загрузка на складе',
  DEPOT_LOAD: 'Загрузка на складе',
  DELIVERY: 'Доставка',
  PICKUP: 'Вывоз',
  WAREHOUSE_UNLOAD: 'Выгрузка на складе',
  DEPOT_UNLOAD: 'Выгрузка на складе',
  WAREHOUSE_FINISH: 'Завершение на складе',
  DEPOT_RETURN: 'Возвращение на склад',
  TRAVEL: 'Движение',
  WAIT: 'Ожидание',
};

function slotFailureLabel(code: string): string {
  return REASON_LABELS[code] ?? `Причина: ${code}`;
}

function timeLabel(value: string | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (!Number.isNaN(date.getTime())) return date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  return value.slice(0, 5);
}

function distanceLabel(meters: number | undefined): string {
  if (meters === undefined) return '—';
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)} км` : `${Math.round(meters)} м`;
}

function missingSlot(start: string, end: string): SlotAvailabilityOption {
  return {
    start,
    end,
    status: 'UNAVAILABLE',
    candidate_count: 0,
    reasons: ['AVAILABILITY_NOT_RETURNED'],
    explanation: [],
  };
}

function fixedSlotOptions(response: SlotAvailabilityResponse | null): SlotAvailabilityOption[] {
  return FIXED_SLOTS.map(({ start, end }) => response?.slots.find((slot) => slot.start.slice(0, 5) === start && slot.end.slice(0, 5) === end)
    ?? missingSlot(start, end));
}

/** Dependencies and controlled map point for the dispatcher slot-check panel. */
interface SlotAvailabilityPanelProps {
  warehouseId: string;
  warehouses: Warehouse[];
  planningDate: string;
  point: { latitude: number; longitude: number } | null;
  onPointChange: (point: { latitude: number; longitude: number } | null) => void;
  onClose: () => void;
  onPresentationChange: (presentation: SlotPlanningMapPresentation) => void;
  calculate: (input: SlotAvailabilityInput, signal: AbortSignal) => Promise<SlotAvailabilityResponse>;
  suggestAddresses: (text: string, point: { latitude: number; longitude: number } | null, signal: AbortSignal) => Promise<AddressSuggestion[]>;
  resolveAddressSuggestion: (uri: string, signal: AbortSignal) => Promise<GeocodedAddress>;
  reverseGeocode: (latitude: number, longitude: number, signal: AbortSignal) => Promise<GeocodedAddress>;
  debounceMilliseconds?: number;
}

/** Dispatcher-side form and explanation UI; all feasibility decisions remain server-owned. */
export function SlotAvailabilityPanel({
  warehouseId: activeWarehouseId,
  warehouses,
  planningDate,
  point,
  onPointChange,
  onClose,
  onPresentationChange,
  calculate,
  suggestAddresses,
  resolveAddressSuggestion,
  reverseGeocode,
  debounceMilliseconds = 450,
}: SlotAvailabilityPanelProps) {
  const [warehouseId, setWarehouseId] = useState(activeWarehouseId);
  const [date, setDate] = useState(planningDate);
  const [address, setAddress] = useState('');
  const [cabinCount, setCabinCount] = useState(1);
  const [siteCapacity, setSiteCapacity] = useState<1 | 2>(1);
  const [response, setResponse] = useState<SlotAvailabilityResponse | null>(null);
  const [selectedSlotKey, setSelectedSlotKey] = useState<string | null>(null);
  const [layers, setLayers] = useState(DEFAULT_LAYERS);
  const [status, setStatus] = useState<'idle' | 'loading' | 'loaded' | 'error'>('idle');
  const [error, setError] = useState<string | null>(null);
  const [suggestions, setSuggestions] = useState<AddressSuggestion[]>([]);
  const [suggestionQueryEnabled, setSuggestionQueryEnabled] = useState(false);
  const [addressLookupStatus, setAddressLookupStatus] = useState<'idle' | 'suggesting' | 'resolving' | 'reversing' | 'error'>('idle');
  const [addressLookupError, setAddressLookupError] = useState<string | null>(null);
  const resolvedPointRef = useRef<string | null>(null);
  const mapLayers = useUiStore((state) => state.layers);
  const toggleMapLayer = useUiStore((state) => state.toggleLayer);

  useEffect(() => setDate(planningDate), [planningDate]);
  useEffect(() => setWarehouseId(activeWarehouseId), [activeWarehouseId]);
  useEffect(() => {
    if (warehouses.some((warehouse) => warehouse.id === warehouseId)) return;
    setWarehouseId(warehouses[0]?.id ?? '');
  }, [warehouseId, warehouses]);

  const suggestionBias = useMemo(() => {
    const selectedWarehouse = warehouses.find((warehouse) => warehouse.id === warehouseId);
    return selectedWarehouse
      ? { latitude: selectedWarehouse.latitude, longitude: selectedWarehouse.longitude }
      : null;
  }, [warehouseId, warehouses]);

  useEffect(() => {
    const text = address.trim();
    if (!suggestionQueryEnabled || text.length < 3) {
      setSuggestions([]);
      setAddressLookupStatus((current) => current === 'suggesting' ? 'idle' : current);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setAddressLookupStatus('suggesting');
      setAddressLookupError(null);
      void suggestAddresses(text, suggestionBias, controller.signal).then((value) => {
        if (controller.signal.aborted) return;
        setSuggestions(value);
        setAddressLookupStatus('idle');
      }).catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        setSuggestions([]);
        setAddressLookupStatus('error');
        setAddressLookupError(caught instanceof Error ? caught.message : 'Не удалось найти адрес');
      });
    }, 250);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [address, suggestionBias, suggestionQueryEnabled, suggestAddresses]);

  useEffect(() => {
    if (!point) return;
    const pointKey = `${point.latitude}:${point.longitude}`;
    if (resolvedPointRef.current === pointKey) {
      resolvedPointRef.current = null;
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setAddressLookupStatus('reversing');
      setAddressLookupError(null);
      void reverseGeocode(point.latitude, point.longitude, controller.signal).then((value) => {
        if (controller.signal.aborted) return;
        setAddress(value.address);
        setSuggestionQueryEnabled(false);
        setSuggestions([]);
        setAddressLookupStatus('idle');
      }).catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        setAddressLookupStatus('error');
        setAddressLookupError(caught instanceof Error ? caught.message : 'Адрес для точки не найден');
      });
    }, 250);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [point, reverseGeocode]);

  const validPoint = point && Number.isFinite(point.latitude) && Number.isFinite(point.longitude)
    && point.latitude >= -90 && point.latitude <= 90 && point.longitude >= -180 && point.longitude <= 180;
  const validCabinCount = Number.isInteger(cabinCount) && cabinCount > 0;
  const input = useMemo<SlotAvailabilityInput | null>(() => {
    if (!warehouseId || !date || !validPoint || !validCabinCount || address.trim().length < 3) return null;
    return {
      warehouse_id: warehouseId,
      date,
      address: address.trim(),
      latitude: point.latitude,
      longitude: point.longitude,
      cabin_count: cabinCount,
      site_cabin_capacity: siteCapacity,
    };
  }, [address, cabinCount, date, point, siteCapacity, validCabinCount, validPoint, warehouseId]);

  useEffect(() => {
    setSelectedSlotKey(null);
    if (!input) {
      setResponse(null);
      setStatus('idle');
      setError(null);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setStatus('loading');
      setError(null);
      void calculate(input, controller.signal).then((value) => {
        if (controller.signal.aborted) return;
        setResponse(value);
        setStatus('loaded');
      }).catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        setResponse(null);
        setStatus('error');
        setError(caught instanceof Error ? caught.message : 'Не удалось рассчитать слоты');
      });
    }, debounceMilliseconds);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [calculate, debounceMilliseconds, input]);

  const slots = useMemo(() => fixedSlotOptions(response), [response]);
  const selectedSlot = slots.find((slot) => `${slot.start}-${slot.end}` === selectedSlotKey && slot.status === 'AVAILABLE') ?? null;

  useEffect(() => {
    onPresentationChange({ active: true, warehouseId, point, selectedSlot, layers });
  }, [layers, onPresentationChange, point, selectedSlot, warehouseId]);

  const changePointCoordinate = (key: 'latitude' | 'longitude', raw: string) => {
    const value = Number(raw);
    if (!Number.isFinite(value)) return;
    onPointChange({
      latitude: key === 'latitude' ? value : point?.latitude ?? 0,
      longitude: key === 'longitude' ? value : point?.longitude ?? 0,
    });
  };
  const toggleLayer = (key: keyof SlotPlanningLayers) => setLayers((current) => ({ ...current, [key]: !current[key] }));
  const changeAddress = (value: string) => {
    setAddress(value);
    setSuggestionQueryEnabled(true);
    setSuggestions([]);
    setAddressLookupError(null);
    if (point) onPointChange(null);
  };
  const selectSuggestion = async (suggestion: AddressSuggestion) => {
    const controller = new AbortController();
    setAddressLookupStatus('resolving');
    setAddressLookupError(null);
    try {
      const value = await resolveAddressSuggestion(suggestion.uri, controller.signal);
      const pointKey = `${value.latitude}:${value.longitude}`;
      resolvedPointRef.current = pointKey;
      setAddress(value.address);
      setSuggestionQueryEnabled(false);
      setSuggestions([]);
      setAddressLookupStatus('idle');
      onPointChange({ latitude: value.latitude, longitude: value.longitude });
    } catch (caught: unknown) {
      setAddressLookupStatus('error');
      setAddressLookupError(caught instanceof Error ? caught.message : 'Не удалось определить координаты адреса');
    }
  };

  return (
    <aside className="slot-planner" aria-label="Проверка клиентского слота">
      <header className="slot-planner__header">
        <span><Route size={17} /><strong>Проверка нового заказа</strong></span>
        <button type="button" aria-label="Закрыть проверку слотов" onClick={onClose}><X size={17} /></button>
      </header>
      <div className="slot-planner__body">
        <p className="slot-planner__hint">Выберите найденный адрес или поставьте точку на карте. Доступность определяет полная симуляция дня; изохроны включаются отдельно в слоях карты.</p>
        <div className="slot-planner__form">
          <label className="field span-2"><span className="field__label">Склад</span><select className="input" aria-label="Склад для расчёта" value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)}>{warehouses.map((warehouse) => <option key={warehouse.id} value={warehouse.id}>{warehouse.name}</option>)}</select></label>
          <label className="field"><span className="field__label">Дата</span><input className="input" aria-label="Дата нового заказа" type="date" value={date} onChange={(event) => setDate(event.target.value)} /></label>
          <label className="field"><span className="field__label">Бытовок</span><input className="input" aria-label="Количество бытовок" type="number" min="1" step="1" value={cabinCount} onChange={(event) => setCabinCount(Number.isFinite(event.currentTarget.valueAsNumber) ? event.currentTarget.valueAsNumber : 0)} /></label>
          <div className="field span-2 address-search"><label><span className="field__label">Адрес клиента</span><input className="input" aria-label="Адрес нового клиента" autoComplete="off" aria-expanded={suggestions.length > 0} aria-controls="slot-address-suggestions" value={address} onChange={(event) => changeAddress(event.target.value)} placeholder="Начните вводить адрес" /></label>
            {suggestions.length > 0 ? <div id="slot-address-suggestions" className="address-search__suggestions" role="listbox" aria-label="Подсказки адреса">{suggestions.map((suggestion) => <button type="button" role="option" aria-selected="false" key={suggestion.id} onClick={() => void selectSuggestion(suggestion)}><strong>{suggestion.title}</strong>{suggestion.subtitle || suggestion.address ? <small>{suggestion.subtitle ?? suggestion.address}</small> : null}</button>)}</div> : null}
            {addressLookupStatus === 'suggesting' ? <small className="field__hint">Ищем адреса…</small> : null}
            {addressLookupStatus === 'resolving' ? <small className="field__hint">Определяем точку адреса…</small> : null}
            {addressLookupStatus === 'reversing' ? <small className="field__hint">Определяем адрес выбранной точки…</small> : null}
            {addressLookupError ? <small className="address-search__error" role="alert">{addressLookupError}. Можно ввести адрес вручную и поставить точку на карте.</small> : null}
          </div>
          <label className="field span-2"><span className="field__label">Сколько бытовок объект принимает за один заезд</span><select className="input" aria-label="Вместимость объекта за один заезд" value={siteCapacity} onChange={(event) => setSiteCapacity(Number(event.target.value) as 1 | 2)}><option value={1}>1 — машина без прицепа</option><option value={2}>2 — машина с прицепом может проехать</option></select><small className="field__hint">Это ограничение передаётся планировщику и влияет на допустимую конфигурацию грузового маршрута.</small></label>
          <label className="field"><span className="field__label">Широта</span><input className="input" aria-label="Широта нового клиента" type="number" step="0.000001" value={point?.latitude ?? ''} onChange={(event) => changePointCoordinate('latitude', event.target.value)} /></label>
          <label className="field"><span className="field__label">Долгота</span><input className="input" aria-label="Долгота нового клиента" type="number" step="0.000001" value={point?.longitude ?? ''} onChange={(event) => changePointCoordinate('longitude', event.target.value)} /></label>
        </div>
        <div className="slot-planner__point"><MapPin size={15} />{point ? `Точка: ${point.latitude.toFixed(6)}, ${point.longitude.toFixed(6)}` : 'Нажмите на карту, чтобы поставить точку клиента'}</div>

        <section className="slot-price" aria-label="Стоимость доставки">
          <small>Стоимость доставки · изохрона или особая цена</small>
          {response?.delivery_price_rubles !== undefined
            ? <strong>{response.delivery_price_rubles.toLocaleString('ru-RU')} ₽{response.price_zone_name ? <span> · {response.price_zone_name}</span> : response.price_isochrone_minutes ? <span> · до {response.price_isochrone_minutes / 60} ч</span> : null}</strong>
            : <strong>Время и стоимость подтвердит логист</strong>}
          <p>{response?.price_zone_name
            ? 'Применена особая ценовая зона.'
            : response?.price_isochrone_minutes
              ? `Цена рассчитана по времени пути от склада: до ${response.price_isochrone_minutes / 60} ч.`
              : 'Гарантированный маршрут с подтверждённым ресурсом пока не найден.'}{response?.trailer_access_allowed === false ? ' Для этого адреса проезд с прицепом запрещён.' : ''}</p>
        </section>

        {status === 'loading' ? <div className="slot-planner__status"><Spinner label="Идёт расчёт свободных слотов" /></div> : null}
        {status === 'error' ? <div className="slot-planner__error" role="alert">{error}</div> : null}
        {status === 'idle' ? <div className="slot-planner__status">Для расчёта нужны адрес и точка на карте.</div> : null}

        <section className="slot-cards" aria-label="Доступные клиентские слоты">
          {slots.map((slot) => {
            const available = status === 'loaded' && slot.status === 'AVAILABLE';
            const candidate = available ? slot.best_candidate : undefined;
            const key = `${slot.start}-${slot.end}`;
            return <button
              type="button"
              key={key}
              className={`slot-card slot-card--${available ? 'available' : 'unavailable'}${selectedSlotKey === key ? ' slot-card--selected' : ''}`}
              aria-pressed={selectedSlotKey === key}
              disabled={!available}
              onClick={() => setSelectedSlotKey(key)}
            >
              <span className="slot-card__head"><strong>{slot.start.slice(0, 5)}–{slot.end.slice(0, 5)}</strong><b>{available ? 'Доступен' : 'Недоступен'}</b></span>
              <span className="slot-card__metrics">
                <span>Прибытие <strong>{timeLabel(candidate?.estimated_service_start ?? candidate?.estimated_arrival)}</strong></span>
                <span>Доп. дорога <strong>{candidate?.incremental_travel_minutes !== undefined ? `${Math.round(candidate.incremental_travel_minutes)} мин` : '—'}</strong></span>
                <span>Запас <strong>{candidate?.minimum_slack_minutes !== undefined ? `${Math.round(candidate.minimum_slack_minutes)} мин` : '—'}</strong></span>
                <span>Вариантов <strong>{available ? slot.candidate_count : 0}</strong></span>
              </span>
              {!available ? <span className="slot-card__reasons">{slot.reasons.map((reason) => <small key={reason}>{slotFailureLabel(reason)}</small>)}</span> : null}
            </button>;
          })}
        </section>

        <section className="slot-layers" aria-label="Слои проверки нового заказа">
          <strong>Слои карты</strong>
          <CheckboxField label="Изохроны склада" checked={mapLayers.warehouseIsochrones} onChange={() => toggleMapLayer('warehouseIsochrones')} />
          <CheckboxField label="Изохроны задания" checked={mapLayers.taskIsochrones} disabled={!point} onChange={() => toggleMapLayer('taskIsochrones')} />
          <CheckboxField label="Маршрут до добавления" checked={layers.routeBefore} onChange={() => toggleLayer('routeBefore')} />
          <CheckboxField label="Маршрут после добавления" checked={layers.routeAfter} onChange={() => toggleLayer('routeAfter')} />
          <CheckboxField label="Вывозы-кандидаты" checked={layers.pickupCandidates} onChange={() => toggleLayer('pickupCandidates')} />
        </section>

        {selectedSlot?.best_candidate ? <section className="slot-candidate" aria-label="Лучший вариант маршрута">
          <h3>Лучший вариант · версия плана {response?.plan_version}</h3>
          <div className="slot-candidate__facts">
            <span>Возврат на склад<strong>{timeLabel(selectedSlot.best_candidate.warehouse_return_time)}</strong></span>
            <span>Ожидание<strong>{selectedSlot.best_candidate.waiting_minutes ?? 0} мин</strong></span>
            <span>Вывозов<strong>{selectedSlot.best_candidate.pickup_count ?? 0}</strong></span>
            <span>Доп. расстояние<strong>{distanceLabel(selectedSlot.best_candidate.incremental_distance)}</strong></span>
          </div>
          {selectedSlot.explanation.length ? <ul className="slot-candidate__explanation">{selectedSlot.explanation.map((item) => <li key={item}>{item}</li>)}</ul> : null}
          <div className="slot-timeline" role="region" aria-label="Временная шкала маршрута">
            {selectedSlot.best_candidate.timeline.length ? selectedSlot.best_candidate.timeline.map((stop, index) => <article className={`slot-timeline__stop slot-timeline__stop--${stop.type.toLowerCase()}`} key={stop.stop_id ?? `${stop.type}-${index}`}>
              <span>{timeLabel(stop.arrival_at ?? stop.service_start)}–{timeLabel(stop.departure_at ?? stop.service_end)}</span>
              <div><strong>{STOP_LABELS[stop.type] ?? stop.type}</strong><small>{stop.label}</small></div>
              <b>Груз {stop.load_before ?? '—'} → {stop.load_after ?? '—'}</b>
              {stop.waiting_minutes ? <em>ожидание {stop.waiting_minutes} мин</em> : null}
            </article>) : <p>Backend не вернул временную шкалу для этого варианта.</p>}
          </div>
        </section> : null}
      </div>
      <footer><Button onClick={() => onPointChange(null)}>Сбросить точку</Button></footer>
    </aside>
  );
}
