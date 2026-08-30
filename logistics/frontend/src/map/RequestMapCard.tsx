import { CalendarCheck2, CalendarX2, Clock3, MapPin, PackageOpen, Route, UserRound, X } from 'lucide-react';
import maplibregl, { type Map as MapLibreMap } from 'maplibre-gl';
import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import type { LogisticsRequest, PlanningTask, RouteCycle, RoutePlan, UUID } from '../domain/types';
import type { PlanMove } from '../features/planning/PlanPanel';
import { formatDate, formatTime } from '../utils/format';
import { DatePicker } from '../components/DatePicker';

interface RequestMapCardProps {
  request: LogisticsRequest;
  plan?: RoutePlan | null;
  planningDate: string;
  busy: boolean;
  onSchedule: (requestId: UUID, date: string, addIfMissing: boolean) => void;
  onUnschedule: (requestId: UUID) => void;
  onMoveTask?: (move: PlanMove) => void;
  onClose: () => void;
}

interface RequestMapPopupProps extends RequestMapCardProps {
  map: MapLibreMap;
}

const requestStatusLabels: Record<LogisticsRequest['status'], string> = {
  DRAFT: 'Черновик',
  READY: 'Готово',
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
  return start && end ? `${start.slice(0, 5)}–${end.slice(0, 5)}` : 'В течение дня';
}

interface TaskAssignment {
  task: PlanningTask;
  sourceCycleId: UUID | null;
  cycle: RouteCycle | null;
  driverName: string | null;
}

/** Request inspector with an explicit logistics date command. */
export function RequestMapCard({
  request,
  plan = null,
  planningDate,
  busy,
  onSchedule,
  onUnschedule,
  onMoveTask,
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
  const routeOptions = useMemo(
    () => plan?.driver_routes.flatMap((route) => route.cycles.map((cycle) => ({ route, cycle }))) ?? [],
    [plan],
  );
  const assignments = useMemo<TaskAssignment[]>(() => {
    const knownTasks = request.tasks?.length
      ? request.tasks
      : plan?.unassigned.filter((item) => item.task.request_id === request.id).map((item) => item.task) ?? [];
    return knownTasks.map((task) => {
      for (const route of plan?.driver_routes ?? []) {
        for (const cycle of route.cycles) {
          if (cycle.stops.some((stop) => stop.task_id === task.id)) {
            return { task, sourceCycleId: cycle.id, cycle, driverName: route.driver_name };
          }
        }
      }
      return { task, sourceCycleId: null, cycle: null, driverName: null };
    });
  }, [plan, request.id, request.tasks]);
  const planReadOnly = !plan || plan.status === 'CONFIRMED';

  const moveAssignment = (assignment: TaskAssignment, targetCycleId: UUID) => {
    if (!onMoveTask || !targetCycleId || targetCycleId === assignment.sourceCycleId) return;
    const target = routeOptions.find((option) => option.cycle.id === targetCycleId);
    if (!target) return;
    const targetTaskStops = target.cycle.stops.filter((stop) => stop.task_id);
    const targetSequence = assignment.task.type === 'DELIVERY'
      ? targetTaskStops.filter((stop) => stop.stop_type === 'DELIVERY').length + 1
      : targetTaskStops.length + 1;
    onMoveTask({
      taskId: assignment.task.id,
      sourceCycleId: assignment.sourceCycleId,
      targetCycleId,
      targetSequence,
      kind: 'MOVE_TASK',
    });
  };

  return (
    <section className="request-map-menu" aria-label={request.type === 'DELIVERY' ? 'Доставка на карте' : 'Вывоз на карте'} aria-busy={busy} data-testid="request-map-menu">
      <div className="request-map-menu__head">
        <strong className={`request-kind request-kind--${request.type.toLowerCase()}`}>{request.type === 'DELIVERY' ? 'Доставка' : 'Вывоз'}</strong>
        <button type="button" aria-label="Закрыть карточку доставки или вывоза" onClick={onClose}><X size={16} aria-hidden="true" /></button>
      </div>

      <h3>{request.name}</h3>
      <div className="request-map-menu__status">{requestStatusLabels[request.status]}</div>
      <dl className="request-map-menu__details">
        <div><dt><MapPin size={12} aria-hidden="true" />Адрес</dt><dd>{request.address_label}</dd></div>
        <div><dt><PackageOpen size={12} aria-hidden="true" />Объём</dt><dd>{request.quantity} бытов. · обслуживание {request.service_minutes} мин</dd></div>
        <div><dt><Clock3 size={12} aria-hidden="true" />Тариф</dt><dd>рассчитывается по времени пути в изохроне склада</dd></div>
      </dl>
      {request.notes ? <p className="request-map-menu__notes">{request.notes}</p> : null}

      {assignments.length ? (
        <section className="request-map-menu__assignment" aria-label="Назначение водителя и рейса">
          <div className="request-map-menu__assignment-title"><UserRound size={13} aria-hidden="true" /><strong>Водитель и рейс</strong></div>
          {assignments.map((assignment) => (
            <label key={assignment.task.id}>
              <span>{assignments.length > 1 ? `Часть ${assignment.task.part_number} · ${assignment.task.quantity} бытов.` : assignment.driverName ?? 'Пока не распределено'}</span>
              <select
                aria-label={`Рейс для ${assignments.length > 1 ? `части ${assignment.task.part_number}` : request.name}`}
                value={assignment.sourceCycleId ?? ''}
                disabled={busy || planReadOnly || assignment.task.locked || assignment.cycle?.locked || !onMoveTask}
                onChange={(event) => moveAssignment(assignment, event.target.value)}
              >
                {!assignment.sourceCycleId ? <option value="">Не распределено</option> : null}
                {routeOptions.map(({ route, cycle }) => (
                  <option key={cycle.id} value={cycle.id} disabled={cycle.locked}>
                    {route.driver_name} · цикл {cycle.sequence} · {formatTime(cycle.planned_start)}–{formatTime(cycle.planned_finish)}
                  </option>
                ))}
              </select>
            </label>
          ))}
          {planReadOnly ? <small><Route size={11} aria-hidden="true" />Назначение можно менять до утверждения плана</small> : null}
        </section>
      ) : null}

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

      <div className="request-map-menu__date-input">
        <span>Дата для логистики</span>
        <DatePicker label="Дата для логистики" value={selectedDate} onChange={setSelectedDate} disabled={busy} />
      </div>

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
  plan = null,
  planningDate,
  busy,
  onSchedule,
  onUnschedule,
  onMoveTask,
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
      plan={plan}
      planningDate={planningDate}
      busy={busy}
      onSchedule={onSchedule}
      onUnschedule={onUnschedule}
      {...(onMoveTask ? { onMoveTask } : {})}
      onClose={onClose}
    />,
    portalRoot,
  );
}
