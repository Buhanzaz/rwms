import { CalendarCheck2, CalendarX2, Clock3, MapPin, PackageOpen, X } from 'lucide-react';
import maplibregl, { type Map as MapLibreMap } from 'maplibre-gl';
import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import type { LogisticsRequest, UUID, Zone } from '../domain/types';
import { formatDate } from '../utils/format';

interface RequestMapCardProps {
  request: LogisticsRequest;
  zone: Zone | null;
  planningDate: string;
  busy: boolean;
  onSchedule: (requestId: UUID, date: string, addIfMissing: boolean) => void;
  onUnschedule: (requestId: UUID) => void;
  onClose: () => void;
}

interface RequestMapPopupProps extends RequestMapCardProps {
  map: MapLibreMap;
}

const requestStatusLabels: Record<LogisticsRequest['status'], string> = {
  DRAFT: 'Черновик',
  READY: 'Готова к планированию',
  PLANNED: 'В плане',
  IN_PROGRESS: 'В работе',
  COMPLETED: 'Выполнена',
  CANCELLED: 'Отменена',
  UNASSIGNED: 'Не распределена',
};

function initialDate(request: LogisticsRequest, planningDate: string): string {
  if (request.scheduled_date) return request.scheduled_date;
  if (request.date_options.some((option) => option.date === planningDate)) return planningDate;
  return request.date_options[0]?.date ?? planningDate;
}

function formatWindow(start: string | null, end: string | null): string {
  return start && end ? `${start.slice(0, 5)}–${end.slice(0, 5)}` : 'весь день';
}

/** Request inspector with an explicit logistics date command. */
export function RequestMapCard({
  request,
  zone,
  planningDate,
  busy,
  onSchedule,
  onUnschedule,
  onClose,
}: RequestMapCardProps) {
  const [selectedDate, setSelectedDate] = useState(() => initialDate(request, planningDate));
  useEffect(() => setSelectedDate(initialDate(request, planningDate)), [planningDate, request]);

  const dateOptions = useMemo(
    () => [...request.date_options].sort((left, right) => left.date.localeCompare(right.date)),
    [request.date_options],
  );
  const selectedOption = dateOptions.find((option) => option.date === selectedDate) ?? null;
  const alreadyScheduled = request.scheduled_date === selectedDate;
  const zoneState = request.zone_status === 'OUTSIDE_ZONES'
    ? 'Вне логистических зон'
    : request.zone_status === 'STALE'
      ? `Зона ${zone?.code ?? '—'} требует пересчёта`
      : `Зона ${zone?.code ?? '—'} · версия ${request.zone_version ?? '—'}`;

  return (
    <section className="request-map-menu" aria-label="Заявка на карте" aria-busy={busy} data-testid="request-map-menu">
      <div className="request-map-menu__head">
        <strong>{request.type === 'DELIVERY' ? 'Д · доставка' : 'В · возврат'}</strong>
        <button type="button" aria-label="Закрыть карточку заявки" onClick={onClose}><X size={16} aria-hidden="true" /></button>
      </div>

      <h3>{request.name}</h3>
      <div className="request-map-menu__status">{requestStatusLabels[request.status]}</div>
      <dl className="request-map-menu__details">
        <div><dt><MapPin size={12} aria-hidden="true" />Адрес</dt><dd>{request.address_label}</dd></div>
        <div><dt><PackageOpen size={12} aria-hidden="true" />Объём</dt><dd>{request.quantity} бытов. · обслуживание {request.service_minutes} мин</dd></div>
        <div><dt><Clock3 size={12} aria-hidden="true" />Классификация</dt><dd>{zoneState}</dd></div>
      </dl>
      {request.notes ? <p className="request-map-menu__notes">{request.notes}</p> : null}

      <div className="request-map-menu__schedule-state">
        <CalendarCheck2 size={14} aria-hidden="true" />
        <span>{request.scheduled_date
          ? <>Выставлено на <strong>{formatDate(request.scheduled_date)}</strong></>
          : 'Дата логистики ещё не выбрана'}</span>
      </div>

      <div className="request-map-menu__dates" aria-label="Клиент может принять">
        <strong>Клиент может принять</strong>
        <div>
          {dateOptions.map((option) => (
            <button
              type="button"
              key={option.date}
              aria-pressed={selectedDate === option.date}
              className={option.date === request.scheduled_date ? 'request-map-menu__date--scheduled' : undefined}
              onClick={() => setSelectedDate(option.date)}
            >
              {formatDate(option.date)} · {formatWindow(option.window_start, option.window_end)}{option.is_hard ? ' · жёстко' : ''}
            </button>
          ))}
        </div>
      </div>

      <label className="request-map-menu__date-input">
        <span>Дата для логистики</span>
        <input
          type="date"
          name="scheduled-date"
          autoComplete="off"
          aria-label="Дата для логистики"
          value={selectedDate}
          onChange={(event) => setSelectedDate(event.target.value)}
        />
      </label>

      {selectedDate && !selectedOption ? (
        <p className="request-map-menu__agreement-note">
          Эта дата не входила в варианты клиента. Кнопка ниже явно добавит её как согласованную вручную.
        </p>
      ) : null}

      <div className="request-map-menu__actions">
        <button
          type="button"
          className="request-map-menu__primary"
          disabled={busy || !selectedDate || alreadyScheduled}
          onClick={() => onSchedule(request.id, selectedDate, selectedOption === null)}
        >
          <CalendarCheck2 size={13} aria-hidden="true" />
          {busy
            ? 'Выполняется…'
            : alreadyScheduled
            ? `Уже выставлено на ${formatDate(selectedDate)}`
            : !selectedDate
              ? 'Выберите дату'
            : selectedOption
              ? `Выставить на ${formatDate(selectedDate)}`
              : `Добавить дату и выставить на ${formatDate(selectedDate)}`}
        </button>
        {request.scheduled_date ? (
          <button type="button" disabled={busy} onClick={() => onUnschedule(request.id)}>
            <CalendarX2 size={13} aria-hidden="true" />{busy ? 'Выполняется…' : 'Снять с назначенной даты'}
          </button>
        ) : null}
      </div>
    </section>
  );
}

/** Renders a request inspector in a native MapLibre popup above its marker. */
export function RequestMapPopup({
  map,
  request,
  zone,
  planningDate,
  busy,
  onSchedule,
  onUnschedule,
  onClose,
}: RequestMapPopupProps) {
  const [portalRoot, setPortalRoot] = useState<HTMLElement | null>(null);

  useEffect(() => {
    const root = document.createElement('div');
    root.className = 'request-map-popup__portal';
    const popup = new maplibregl.Popup({
      anchor: 'bottom',
      className: 'request-map-popup',
      closeButton: false,
      closeOnClick: false,
      focusAfterOpen: false,
      maxWidth: '390px',
      offset: [0, -26],
    })
      .setLngLat([request.longitude, request.latitude])
      .setDOMContent(root)
      .addTo(map);

    setPortalRoot(root);
    return () => {
      setPortalRoot((current) => current === root ? null : current);
      popup.remove();
    };
  }, [map, request.id, request.latitude, request.longitude]);

  if (!portalRoot) return null;
  return createPortal(
    <RequestMapCard
      request={request}
      zone={zone}
      planningDate={planningDate}
      busy={busy}
      onSchedule={onSchedule}
      onUnschedule={onUnschedule}
      onClose={onClose}
    />,
    portalRoot,
  );
}
