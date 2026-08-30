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
import { GripVertical, Lock, LockOpen, TriangleAlert } from 'lucide-react';
import type { RouteCycle, RoutePlan, RouteStop, UnassignedTask, UUID } from '../../domain/types';
import { Badge, Button, EmptyState } from '../../components/ui';
import { formatDistance, formatDuration, formatTime, shortId } from '../../utils/format';

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

function stopTypeLabel(stop: RouteStop): string {
  if (stop.stop_type === 'DELIVERY') return 'Доставка';
  if (stop.stop_type === 'PICKUP') return 'Вывоз';
  return 'Склад';
}

function SortableStop({ stop, cycleId, locked, readOnly, timeZone }: { stop: RouteStop; cycleId: UUID; locked: boolean; readOnly: boolean; timeZone: string }) {
  const draggable = Boolean(stop.task_id) && !locked && !stop.locked && !readOnly;
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
      <span className={`stop-row__type stop-row__type--${stop.stop_type.toLowerCase()}`} title={stop.stop_type}>{stopTypeLabel(stop)}</span>
      <span>{stop.label ?? stop.stop_type}</span>
      <span className="stop-row__load">{stop.load_before}→{stop.load_after}</span>
    </div>
  );
}

function CycleCard({ cycle, timeZone, readOnly, onSelect, onToggleLock }: {
  cycle: RouteCycle;
  timeZone: string;
  readOnly: boolean;
  onSelect: (id: UUID) => void;
  onToggleLock: (cycle: RouteCycle) => void;
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: `cycle-${cycle.id}`,
    disabled: cycle.locked || readOnly,
    data: { cycleId: cycle.id, sequence: cycle.stops.length } satisfies TargetData,
  });
  const taskIds = cycle.stops.flatMap((stop) => stop.task_id ? [stop.task_id] : []);
  return (
    <article className="cycle-card" ref={setNodeRef} style={isOver ? { borderColor: '#5ee2b2' } : undefined} data-testid={`cycle-${cycle.id}`}>
      <header className="cycle-card__head">
        <button type="button" className="button button--ghost button--sm" onClick={() => onSelect(cycle.id)}>
          <strong>Цикл {cycle.sequence}</strong> · {formatTime(cycle.planned_start, timeZone)}–{formatTime(cycle.planned_finish, timeZone)}
        </button>
        <Button size="sm" variant="ghost" disabled={readOnly} onClick={() => onToggleLock(cycle)} aria-label={cycle.locked ? `Разблокировать цикл ${cycle.sequence}` : `Заблокировать цикл ${cycle.sequence}`}>
          {cycle.locked ? <Lock size={13} /> : <LockOpen size={13} />}
        </Button>
      </header>
      <SortableContext items={taskIds} strategy={verticalListSortingStrategy}>
        <div className="stop-list">
          {cycle.stops.map((stop) => <SortableStop key={stop.id} stop={stop} cycleId={cycle.id} locked={cycle.locked} readOnly={readOnly} timeZone={timeZone} />)}
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

function DraggableUnassigned({ item, readOnly, onReschedule }: { item: UnassignedTask; readOnly: boolean; onReschedule: (requestId: UUID) => void }) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useSortable({
    id: `unassigned-${item.task.id}`,
    disabled: readOnly,
    data: { taskId: item.task.id, sourceCycleId: null, sourceSequence: 0 } satisfies DragData,
  });
  return (
    <article ref={setNodeRef} style={{ transform: CSS.Transform.toString(transform), opacity: isDragging ? .5 : 1 }} className="unassigned-card" {...attributes} {...listeners}>
      <div className="entity-card__row"><strong>№{shortId(item.request?.id ?? item.task.request_id)}</strong><span>{item.task.mandatory ? <Badge tone="danger">обязательно</Badge> : null}{!readOnly ? <GripVertical size={15} /> : null}</span></div>
      <p>{item.request?.type === 'PICKUP' ? 'Вывоз' : 'Доставка'}, {item.task.quantity} бытов.</p>
      <ul>{item.reasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>
      {item.closest_option ? <p>Ближайший вариант: {item.closest_option}</p> : null}
      {item.recommendations.length ? <div className="recommendation">{item.recommendations.join(' · ')}</div> : null}
      <Button type="button" size="sm" disabled={readOnly} onClick={(event) => { event.stopPropagation(); onReschedule(item.request?.id ?? item.task.request_id); }}>Перенести на другой день</Button>
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

export function PlanPanel({ plan, timeZone, showUnassignedOnly = false, readOnly = false, onSelectCycle, onSelectDriverRoute, onMove, onToggleLock, onCreateTransfer = () => undefined, onRescheduleUnassigned = () => undefined }: {
  plan: RoutePlan | null;
  timeZone: string;
  showUnassignedOnly?: boolean;
  readOnly?: boolean;
  onSelectCycle: (id: UUID) => void;
  onSelectDriverRoute: (driverShiftId: UUID) => void;
  onMove: (move: PlanMove) => void;
  onToggleLock: (cycle: RouteCycle) => void;
  onCreateTransfer?: (sourceWarehouseId: UUID, destinationWarehouseId: UUID) => void;
  onRescheduleUnassigned?: (requestId: UUID) => void;
}) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  if (!plan) return <EmptyState title="Автоплан пока не готов" description="Заполните для доставок и вывозов время, количество и проезд с прицепом. После этого рейсы появятся автоматически." />;
  const onDragEnd = (event: DragEndEvent) => {
    if (readOnly) return;
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
          <p className="section-subtitle">Причины сформированы планировщиком. Измените условия или перенесите задачу на подходящий день.</p>
          <SortableContext items={plan.unassigned.map((item) => `unassigned-${item.task.id}`)} strategy={verticalListSortingStrategy}>
            <div className="entity-list">
              {plan.unassigned.map((item) => <DraggableUnassigned item={item} readOnly={readOnly} onReschedule={onRescheduleUnassigned} key={item.task.id} />)}
              {!plan.unassigned.length ? <EmptyState title="Все задачи распределены" description="Для выбранной даты необработанных задач нет." /> : null}
            </div>
          </SortableContext>
        </>
      ) : (
        <>
          {plan.driver_routes.map((route) => (
            <section className="route-driver" key={route.driver_shift_id} data-testid="driver-route">
              <header className="route-driver__head">
                <button type="button" className="route-driver__select" onClick={() => onSelectDriverRoute(route.driver_shift_id)}>
                  <strong>{route.driver_name}</strong>
                  <p>{route.vehicle_name} · {route.registration_number}</p>
                  {route.cycles.length ? <p className="route-driver__workload">Старт со склада {formatTime(route.cycles[0]!.planned_start, timeZone)} · возврат на склад {formatTime(route.cycles.at(-1)!.planned_finish, timeZone)}</p> : null}
                </button>
                <Badge tone={plan.status === 'CONFIRMED' ? 'success' : 'accent'}>{plan.status === 'CONFIRMED' ? 'утверждён' : `${route.cycles.length} рейс.`}</Badge>
              </header>
              {route.cross_warehouse_service ? (
                <div className="parallel-start-summary" data-testid={`cross-warehouse-service-${route.driver_shift_id}`}>
                  <strong><Badge tone="warning">Привлечённый ресурс</Badge> {route.cross_warehouse_service.resource_origin_warehouse_name}</strong>
                  <span>
                    Прибытие и доступность: {formatTime(route.cross_warehouse_service.available_at_served, timeZone)} ·
                    возврат на исходный склад: {route.cross_warehouse_service.returns_to_origin ? 'да' : 'нет'} ·
                    базирование {route.cross_warehouse_service.changes_operational_warehouse ? 'изменится' : 'не меняется'}
                  </span>
                  <span>
                    Позиционирование: {formatDistance(route.cross_warehouse_service.positioning_distance_meters)} ·
                    дорога туда {formatDuration(route.cross_warehouse_service.inbound_travel_minutes * 60)} ·
                    обратно {formatDuration(route.cross_warehouse_service.return_travel_minutes * 60)}
                  </span>
                  {route.cross_warehouse_service.outbound_positioning_empty ? (
                    <span>
                      <strong>Попутное перемещение:</strong> участок до представительского склада пока пустой ·
                      доступно {route.cross_warehouse_service.available_transfer_cabin_capacity} бытовк.
                      {route.cross_warehouse_service.available_transfer_cabin_capacity > 1
                        ? ' с прицепом'
                        : route.cross_warehouse_service.reason_codes.includes('TRAILER_REQUIRED')
                          ? ' · для второй бытовки требуется прицеп'
                          : route.cross_warehouse_service.reason_codes.includes('VEHICLE_CAPACITY_ONE_CABIN')
                            ? ' · автомобиль вмещает только одну бытовку'
                            : ''}
                      {' · '}
                      <button
                        type="button"
                        className="button-link"
                        onClick={() => onCreateTransfer(
                          route.cross_warehouse_service!.resource_origin_warehouse_id,
                          route.cross_warehouse_service!.service_warehouse_id,
                        )}
                      >
                        Добавить бытовки
                      </button>
                    </span>
                  ) : null}
                </div>
              ) : null}
              {route.cycles.map((cycle) => <CycleCard key={cycle.id} cycle={cycle} timeZone={timeZone} readOnly={readOnly} onSelect={onSelectCycle} onToggleLock={onToggleLock} />)}
            </section>
          ))}
          {!plan.driver_routes.length ? <EmptyState icon={<TriangleAlert />} title="Нет маршрутов" description="Планировщик не смог создать ни одного допустимого цикла. Проверьте нераспределённые задачи." /> : null}
        </>
      )}
    </DndContext>
  );
}
