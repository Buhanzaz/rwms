import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical, Lock, LockOpen, MoveRight, TriangleAlert } from 'lucide-react';
import type { RouteCycle, RoutePlan, RouteStop, UnassignedTask, UUID } from '../../domain/types';
import { Badge, Button, EmptyState } from '../../components/ui';
import { formatDistance, formatDuration, formatTime, shortId } from '../../utils/format';

const integerFormatter = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 });

interface DragData {
  taskId: UUID;
  sourceCycleId: UUID | null;
  sourceSequence: number;
}

interface TargetData {
  cycleId: UUID;
  sequence: number;
}

function isDragData(value: unknown): value is DragData {
  if (!value || typeof value !== 'object') return false;
  const data = value as Record<string, unknown>;
  return typeof data.taskId === 'string' && (typeof data.sourceCycleId === 'string' || data.sourceCycleId === null) && typeof data.sourceSequence === 'number';
}

function isTargetData(value: unknown): value is TargetData {
  if (!value || typeof value !== 'object') return false;
  const data = value as Record<string, unknown>;
  return typeof data.cycleId === 'string' && typeof data.sequence === 'number';
}

function stopSymbol(stop: RouteStop): string {
  if (stop.stop_type === 'DELIVERY') return 'D';
  if (stop.stop_type === 'PICKUP') return 'V';
  return 'С';
}

function SortableStop({ stop, cycleId, locked, timeZone }: { stop: RouteStop; cycleId: UUID; locked: boolean; timeZone: string }) {
  const draggable = Boolean(stop.task_id) && !locked && !stop.locked;
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: stop.task_id ?? stop.id,
    disabled: !draggable,
    data: { taskId: stop.task_id ?? stop.id, sourceCycleId: cycleId, sourceSequence: stop.sequence } satisfies DragData,
  });
  return (
    <div
      ref={setNodeRef}
      className={`stop-row ${isDragging ? 'stop-row--dragging' : ''}`}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      data-testid={`stop-${stop.id}`}
      {...attributes}
      {...listeners}
    >
      <time>{formatTime(stop.planned_arrival, timeZone)}</time>
      <span className="stop-row__type" title={stop.stop_type}>{stopSymbol(stop)}</span>
      <span>{stop.label ?? stop.stop_type}{stop.zone_code ? ` · ${stop.zone_code}` : ''}</span>
      <span className="stop-row__load">{stop.load_before}→{stop.load_after}</span>
    </div>
  );
}

function CycleCard({ cycle, timeZone, onSelect, onToggleLock }: {
  cycle: RouteCycle;
  timeZone: string;
  onSelect: (id: UUID) => void;
  onToggleLock: (cycle: RouteCycle) => void;
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: `cycle-${cycle.id}`,
    disabled: cycle.locked,
    data: { cycleId: cycle.id, sequence: cycle.stops.length } satisfies TargetData,
  });
  const taskIds = cycle.stops.flatMap((stop) => stop.task_id ? [stop.task_id] : []);
  return (
    <article className="cycle-card" ref={setNodeRef} style={isOver ? { borderColor: '#5ee2b2' } : undefined} data-testid={`cycle-${cycle.id}`}>
      <header className="cycle-card__head">
        <button type="button" className="button button--ghost button--sm" onClick={() => onSelect(cycle.id)}>
          <strong>Цикл {cycle.sequence}</strong> · {formatTime(cycle.planned_start, timeZone)}–{formatTime(cycle.planned_finish, timeZone)}
        </button>
        <Button size="sm" variant="ghost" onClick={() => onToggleLock(cycle)} aria-label={cycle.locked ? `Разблокировать цикл ${cycle.sequence}` : `Заблокировать цикл ${cycle.sequence}`}>
          {cycle.locked ? <Lock size={13} /> : <LockOpen size={13} />}
        </Button>
      </header>
      <SortableContext items={taskIds} strategy={verticalListSortingStrategy}>
        <div className="stop-list">
          {cycle.stops.map((stop) => <SortableStop key={stop.id} stop={stop} cycleId={cycle.id} locked={cycle.locked} timeZone={timeZone} />)}
        </div>
      </SortableContext>
      <div className="load-chain" aria-label={`Цепочка загрузки цикла ${cycle.sequence}`}>
        Загрузка: {cycle.stops.map((stop) => stop.load_after).filter((value, index, values) => index === 0 || value !== values[index - 1]).join(' → ')}
      </div>
      <div className="entity-card__row" style={{ padding: '0 9px 8px' }}>
        <span><small>{formatDistance(cycle.total_distance_meters)} · {formatDuration(cycle.total_travel_seconds)}</small></span>
        <span><Badge tone="accent">score {cycle.score.toFixed(1)}</Badge>{cycle.manually_changed ? <Badge tone="warning">ручной</Badge> : null}</span>
      </div>
      {cycle.explanation.length ? <div className="explanation"><strong>Почему так:</strong>{cycle.explanation.map((line, index) => <div key={`${line}-${index}`}>• {line}</div>)}</div> : null}
      {cycle.warnings.map((warning) => <div className="explanation" key={`${warning.code}-${warning.message}`} style={{ borderColor: '#fbbf24' }}>⚠ {warning.message}</div>)}
    </article>
  );
}

function DraggableUnassigned({ item }: { item: UnassignedTask }) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useSortable({
    id: `unassigned-${item.task.id}`,
    data: { taskId: item.task.id, sourceCycleId: null, sourceSequence: 0 } satisfies DragData,
  });
  return (
    <article ref={setNodeRef} style={{ transform: CSS.Transform.toString(transform), opacity: isDragging ? .5 : 1 }} className="unassigned-card" {...attributes} {...listeners}>
      <div className="entity-card__row"><strong>№{shortId(item.request?.id ?? item.task.request_id)}</strong><GripVertical size={15} /></div>
      <p>{item.request?.type === 'PICKUP' ? 'Вывоз' : 'Доставка'}, {item.task.quantity} бытов.</p>
      <ul>{item.reasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>
      {item.closest_option ? <p>Ближайший вариант: {item.closest_option}</p> : null}
      {item.recommendations.length ? <div className="recommendation">{item.recommendations.join(' · ')}</div> : null}
      <small><MoveRight size={11} /> Перетащите в допустимый цикл</small>
    </article>
  );
}

export interface PlanMove {
  taskId: UUID;
  sourceCycleId: UUID | null;
  targetCycleId: UUID;
  targetSequence: number;
  kind: 'MOVE_TASK' | 'REORDER_TASK';
}

export function PlanPanel({ plan, timeZone, showUnassignedOnly = false, onSelectCycle, onSelectDriverRoute, onMove, onToggleLock }: {
  plan: RoutePlan | null;
  timeZone: string;
  showUnassignedOnly?: boolean;
  onSelectCycle: (id: UUID) => void;
  onSelectDriverRoute: (driverShiftId: UUID) => void;
  onMove: (move: PlanMove) => void;
  onToggleLock: (cycle: RouteCycle) => void;
}) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  if (!plan) return <EmptyState title="План ещё не построен" description="Выберите дату и нажмите «Построить маршруты»." />;
  const firstCycles = plan.driver_routes.flatMap((route) => route.cycles[0] ? [route.cycles[0]] : []);
  const earliestStart = firstCycles.reduce<string | null>((earliest, cycle) => !earliest || cycle.planned_start < earliest ? cycle.planned_start : earliest, null);
  const parallelStarts = earliestStart ? firstCycles.filter((cycle) => cycle.planned_start === earliestStart).length : 0;

  const onDragEnd = (event: DragEndEvent) => {
    if (!isDragData(event.active.data.current) || !event.over) return;
    let target = event.over.data.current;
    if (!isTargetData(target)) {
      const stopId = String(event.over.id).replace('unassigned-', '');
      for (const route of plan.driver_routes) {
        for (const cycle of route.cycles) {
          const stop = cycle.stops.find((candidate) => candidate.task_id === stopId);
          if (stop) target = { cycleId: cycle.id, sequence: stop.sequence } satisfies TargetData;
        }
      }
    }
    if (!isTargetData(target)) return;
    const source = event.active.data.current;
    onMove({
      taskId: source.taskId,
      sourceCycleId: source.sourceCycleId,
      targetCycleId: target.cycleId,
      targetSequence: target.sequence,
      kind: source.sourceCycleId === target.cycleId ? 'REORDER_TASK' : 'MOVE_TASK',
    });
  };

  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
      {showUnassignedOnly ? (
        <>
          <h2 className="section-title">Нераспределённые</h2>
          <p className="section-subtitle">Причины сформированы планировщиком. Перетаскивание запускает серверную валидацию.</p>
          <SortableContext items={plan.unassigned.map((item) => `unassigned-${item.task.id}`)} strategy={verticalListSortingStrategy}>
            <div className="entity-list">
              {plan.unassigned.map((item) => <DraggableUnassigned item={item} key={item.task.id} />)}
              {!plan.unassigned.length ? <EmptyState title="Все задачи распределены" description="Для выбранной даты необработанных задач нет." /> : null}
            </div>
          </SortableContext>
        </>
      ) : (
        <>
          <h2 className="section-title">План · версия {plan.version}</h2>
          <p className="section-subtitle">Перетаскивайте транспортные части между циклами. Ошибочные изменения backend отклонит.</p>
          {earliestStart ? <div className="parallel-start-summary"><strong>Параллельный старт</strong><span>{parallelStarts} из {firstCycles.length} первых рейсов начинаются в {formatTime(earliestStart, timeZone)}. Остальные могут стартовать позже только из-за окна или занятости своей смены.</span></div> : null}
          {plan.driver_routes.map((route) => (
            <section className="route-driver" key={route.driver_shift_id} data-testid="driver-route">
              <header className="route-driver__head">
                <button type="button" className="route-driver__select" onClick={() => onSelectDriverRoute(route.driver_shift_id)}>
                  <strong>{route.driver_name}</strong>
                  <p>{route.vehicle_name} · {route.registration_number}</p>
                  <p className="route-driver__workload">Нагрузка смены: {integerFormatter.format(route.metrics.shift_utilization_percent)}% · циклов: {integerFormatter.format(route.cycles.length)}</p>
                </button>
                <Badge tone="accent">{route.preferred_route_group}</Badge>
              </header>
              {route.cycles.map((cycle) => <CycleCard key={cycle.id} cycle={cycle} timeZone={timeZone} onSelect={onSelectCycle} onToggleLock={onToggleLock} />)}
            </section>
          ))}
          {!plan.driver_routes.length ? <EmptyState icon={<TriangleAlert />} title="Нет маршрутов" description="Планировщик не смог создать ни одного допустимого цикла. Проверьте нераспределённые задачи." /> : null}
        </>
      )}
    </DndContext>
  );
}
