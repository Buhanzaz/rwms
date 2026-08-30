import {
  ArrowRightLeft,
  CalendarPlus,
  CircleAlert,
  Edit3,
  Plus,
  Trash2,
  MapPinPlus,
} from 'lucide-react';
import type { KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent } from 'react';
import type {
  Driver,
  DriverShift,
  LogisticsRequest,
  PlanMetrics,
  RouteCycle,
  RoutePlan,
  WarehouseWorkspace,
  SimulationDerivedState,
  Trailer,
  UUID,
  ValidationResult,
  Vehicle,
  Warehouse,
} from '../domain/types';
import type { RequestPlanningDetailsInput, WarehouseUpdateInput } from '../api/client';
import { Badge, Button, EmptyState, ErrorPanel } from '../components/ui';
import { useUiStore, type MapTool } from '../stores/ui-store';
import { formatDate, formatDistance, formatDuration, formatTime } from '../utils/format';
import { PlanPanel, type PlanMove } from '../features/planning/PlanPanel';
import { PlanningDayRequests } from '../features/planning/PlanningDayRequests';
import { SettingsEditor } from '../features/settings/SettingsEditor';

export type EntityKind = 'warehouse' | 'driver' | 'vehicle' | 'trailer' | 'shift' | 'request';
export type EditableEntity = Warehouse | Driver | Vehicle | Trailer | DriverShift | LogisticsRequest;

interface InspectorProps {
  workspace: WarehouseWorkspace;
  plan: RoutePlan | null;
  simulation: SimulationDerivedState | null;
  validation: ValidationResult | null;
  busy: boolean;
  onCreate: (kind: EntityKind) => void;
  onEdit: (kind: EntityKind, value: EditableEntity) => void;
  onDelete: (kind: Exclude<EntityKind, 'warehouse'>, id: UUID, label: string) => void;
  onGenerateWorkload: () => void;
  onDeleteGeneratedWorkload: () => void;
  onSetMapTool: (tool: MapTool) => void;
  onSelect: (kind: 'warehouse' | 'request' | 'driver' | 'vehicle' | 'shift' | 'cycle', id: UUID) => void;
  onMoveTask: (move: PlanMove) => void;
  onToggleCycleLock: (cycle: RouteCycle) => void;
  onSaveSettings: (input: WarehouseUpdateInput) => Promise<void>;
  onCreateTransfer: (sourceWarehouseId?: UUID, destinationWarehouseId?: UUID) => void;
  onConfirmPlan: () => void;
  onResetManualChanges: () => void;
  onSimulationOverride: (kind: 'delay' | 'unavailable', driverShiftId: UUID) => void;
  planningDate: string;
  onPlanningDateChange: (date: string) => void;
  onSaveRequestPlanning: (requestId: UUID, input: RequestPlanningDetailsInput) => Promise<void>;
  onSplitRequest: (requestId: UUID, quantities: number[]) => Promise<void>;
  inspectorWidth: number;
  onInspectorWidthChange: (width: number) => void;
}

function EntityCard({ title, subtitle, badges, onClick, onEdit, onDelete }: {
  title: string; subtitle: string; badges?: React.ReactNode; onClick: () => void; onEdit: () => void; onDelete?: () => void;
}) {
  return (
    <article className="entity-card">
      <button type="button" className="entity-card__select" onClick={onClick}>
        <span className="entity-card__row"><strong>{title}</strong><span>{badges}</span></span>
        <span className="entity-card__subtitle">{subtitle}</span>
      </button>
      <div className="toolbar-row" style={{ margin: '8px 0 0' }}>
        <Button size="sm" variant="ghost" onClick={onEdit}><Edit3 size={13} aria-hidden="true" />Изменить</Button>
        {onDelete ? <Button size="sm" variant="ghost" onClick={onDelete}><Trash2 size={13} aria-hidden="true" />Удалить</Button> : null}
      </div>
    </article>
  );
}

function Metrics({ metrics }: { metrics: PlanMetrics }) {
  const values: Array<[string, string]> = [
    ['Распределено', `${metrics.assigned_count}/${metrics.request_count} · ${metrics.assignment_percent.toFixed(0)}%`],
    ['Циклов', String(metrics.cycle_count)],
    ['Общий пробег', formatDistance(metrics.total_distance_meters)],
    ['Пустой пробег', `${formatDistance(metrics.empty_distance_meters)} · ${metrics.empty_distance_percent.toFixed(0)}%`],
    ['Движение', formatDuration(metrics.travel_seconds)],
    ['Обслуживание', formatDuration(metrics.service_seconds)],
    ['Ожидание', formatDuration(metrics.waiting_seconds)],
    ['Крюк', formatDuration(metrics.detour_seconds)],
    ['Пары доставок', String(metrics.paired_deliveries)],
    ['Пары вывозов', String(metrics.paired_pickups)],
    ['Средняя загрузка', metrics.average_load.toFixed(2)],
    ['Переработка', formatDuration(metrics.overtime_seconds)],
    ['Минимальный резерв', formatDuration(metrics.minimum_buffer_seconds)],
    ['Score', metrics.score.toFixed(1)],
  ];
  return <div className="metrics-grid">{values.map(([label, value]) => <div className="metric" key={label}><small>{label}</small><strong>{value}</strong></div>)}</div>;
}

function WarehouseSection({ props }: { props: InspectorProps }) {
  const warehouse = props.workspace.warehouse;
  return <>
    <h2 className="section-title">{warehouse.name}</h2><p className="section-subtitle">{warehouse.address} · {warehouse.city ?? 'город не указан'}</p>
    <div className="detail-grid">
      <div className="detail-item"><small>Часовой пояс</small><strong>{warehouse.timezone}</strong></div>
      <div className="detail-item"><small>Рабочий день</small><strong>{warehouse.working_day_start}–{warehouse.working_day_end}</strong></div>
      <div className="detail-item"><small>Координаты</small><strong>{warehouse.latitude.toFixed(5)}, {warehouse.longitude.toFixed(5)}</strong></div>
      <div className="detail-item"><small>RWMS</small><strong>подключён автоматически</strong></div>
    </div>
    <div className="divider" />
    <div className="warehouse-actions" aria-label="Действия со складом">
      <div className="warehouse-actions__row">
        <Button variant="primary" onClick={() => props.onCreateTransfer()}>
          <ArrowRightLeft size={14} aria-hidden="true" />
          Создать перемещение
        </Button>
        <Button variant="primary" onClick={() => props.onEdit('warehouse', warehouse)}><Edit3 size={14} aria-hidden="true" />Настроить склад</Button>
        <Button onClick={props.onGenerateWorkload} disabled={props.busy}><CalendarPlus size={14} aria-hidden="true" />Создать нагрузку</Button>
        <Button variant="danger" onClick={props.onDeleteGeneratedWorkload} disabled={props.busy}><Trash2 size={14} aria-hidden="true" />Удалить нагрузку</Button>
      </div>
    </div>
  </>;
}

function CatalogSection({ props, kind }: { props: InspectorProps; kind: 'driver' | 'vehicle' }) {
  const values = kind === 'driver' ? props.workspace.drivers : props.workspace.vehicles;
  const trailers = props.workspace.trailers;
  return <>
    <h2 className="section-title">{kind === 'driver' ? 'Водители' : 'Машины'}</h2><p className="section-subtitle">{kind === 'driver' ? 'Конкретный работник или общий пул квалифицированных водителей выбранного склада RWMS.' : 'Вместимость и физическая конфигурация проверяются до безопасного расчёта каждого участка.'}</p>
    <Button variant="primary" onClick={() => props.onCreate(kind)}><Plus size={14} />{kind === 'driver' ? 'Добавить' : 'Добавить машину'}</Button><div className="divider" />
    <div className="entity-list">{values.map((value) => {
      const driver = kind === 'driver' ? value as Driver : null;
      const vehicle = kind === 'vehicle' ? value as Vehicle : null;
      const physicalSummary = vehicle?.length_mm && vehicle.width_mm && vehicle.height_mm
        ? ` · ${vehicle.length_mm}×${vehicle.width_mm}×${vehicle.height_mm} мм`
        : vehicle ? ' · профиль не заполнен' : '';
      return <EntityCard key={value.id} title={value.name} subtitle={driver ? (driver.rwms_assignment_mode === 'WAREHOUSE_DRIVERS' ? 'Общий пул водителей RWMS' : 'Конкретный водитель RWMS') : `${vehicle?.registration_number} · вместимость ${vehicle?.capacity}${physicalSummary}`} badges={<Badge tone={value.active ? 'success' : 'neutral'}>{value.active ? 'активен' : 'выключен'}</Badge>} onClick={() => props.onSelect(kind, value.id)} onEdit={() => props.onEdit(kind, value)} onDelete={() => props.onDelete(kind, value.id, value.name)} />;
    })}</div>
    {kind === 'vehicle' && !values.length ? <EmptyState title="Машины не созданы" description="Добавьте машину, чтобы назначать её на смены и маршруты склада." /> : null}
    {kind === 'vehicle' ? <>
      <div className="divider" />
      <h3 className="section-title">Прицепы</h3><p className="section-subtitle">Прицеп не исчезает после разгрузки и остаётся частью автопоезда до отдельного отсоединения.</p>
      <Button variant="primary" onClick={() => props.onCreate('trailer')}><Plus size={14} />Добавить прицеп</Button><div className="divider" />
      {trailers === undefined ? <p className="field__hint">Загружаем каталог прицепов и эксплуатационные профили…</p> : <div className="entity-list">{trailers.map((trailer) => <EntityCard
        key={trailer.id}
        title={trailer.name}
        subtitle={`${trailer.registration_number || 'без номера'}${trailer.length_mm ? ` · длина ${trailer.length_mm} мм` : ' · физический профиль не заполнен'}`}
        badges={<Badge tone={trailer.active ? 'success' : 'neutral'}>{trailer.active ? 'активен' : 'выключен'}</Badge>}
        onClick={() => props.onEdit('trailer', trailer)}
        onEdit={() => props.onEdit('trailer', trailer)}
        onDelete={() => props.onDelete('trailer', trailer.id, trailer.name)}
      />)}</div>}
      {trailers?.length === 0 ? <EmptyState title="Прицепы не созданы" description="Добавьте совместимый прицеп, чтобы машина могла перевозить две бытовки." /> : null}
    </> : null}
  </>;
}

function ShiftsSection({ props }: { props: InspectorProps }) {
  return <>
    <h2 className="section-title">Смены</h2><p className="section-subtitle">Одна запись задаёт повторяющийся рабочий интервал водителя на период внутри месяца.</p>
    <Button variant="primary" disabled={!props.workspace.drivers.length || !props.workspace.vehicles.length} onClick={() => props.onCreate('shift')}><CalendarPlus size={14} />Добавить смену</Button><div className="divider" />
    <div className="entity-list">{props.workspace.shifts.map((shift) => {
      const driver = props.workspace.drivers.find((item) => item.id === shift.driver_id);
      const state = !shift.active ? 'неактивна' : shift.date_to < props.planningDate ? 'завершена' : shift.date_from > props.planningDate ? 'запланирована' : 'активна';
      return <EntityCard key={shift.id} title={driver?.name ?? 'Водитель'} subtitle={`${formatDate(shift.date_from)}–${formatDate(shift.date_to)} · ${shift.start_time.slice(0, 5)}–${shift.end_time.slice(0, 5)} · перерыв ${shift.break_minutes} мин`} badges={<Badge tone={state === 'активна' ? 'success' : state === 'запланирована' ? 'accent' : 'neutral'}>{state}</Badge>} onClick={() => props.onSelect('shift', shift.id)} onEdit={() => props.onEdit('shift', shift)} onDelete={() => props.onDelete('shift', shift.id, `смену ${driver?.name ?? ''}`)} />;
    })}</div>
    {!props.workspace.shifts.length ? <EmptyState title="Смены не добавлены" description="Добавьте смену после создания водителя и машины." /> : null}
  </>;
}

function DeliveriesSection({ props }: { props: InspectorProps }) {
  return <>
    <h2 className="section-title">Доставки и вывозы</h2>
    <div className="toolbar-row"><Button variant="primary" onClick={() => props.onSetMapTool('ADD_DELIVERY')}><MapPinPlus size={14} />Доставка</Button><Button onClick={() => props.onSetMapTool('ADD_PICKUP')}><MapPinPlus size={14} />Вывоз</Button></div>
    <div className="divider" />
    <PlanningDayRequests
      workspace={props.workspace}
      planningDate={props.planningDate}
      busy={props.busy}
      onPlanningDateChange={props.onPlanningDateChange}
      onSave={props.onSaveRequestPlanning}
      onSplit={props.onSplitRequest}
      onSelect={(id) => props.onSelect('request', id)}
    />
  </>;
}

function SimulationDrivers({ props }: { props: InspectorProps }) {
  if (!props.simulation) return null;
  return <section className="simulation-route-statuses" aria-label="Текущее состояние маршрутов"><h2 className="section-title">Машины сейчас</h2><p className="section-subtitle">План остаётся ниже целиком. Здесь показаны текущий этап, адрес назначения и расчётное время прибытия.</p><div className="entity-list">{props.simulation.vehicles.map((vehicle) => <article className="entity-card simulation-route-status" key={vehicle.driver_shift_id} data-testid={`simulation-route-${vehicle.driver_shift_id}`}><div className="entity-card__row"><button type="button" className="simulation-route-status__driver" onClick={() => props.onSelect('driver', vehicle.driver_shift_id)}><strong>{vehicle.driver_name}</strong><small>{vehicle.vehicle_name} · {vehicle.registration_number}</small></button><Badge tone={vehicle.status === 'DELAYED' ? 'danger' : vehicle.status === 'FINISHED' ? 'neutral' : 'success'}>{vehicle.status}</Badge></div><div className="simulation-route-status__destination"><small>Адрес назначения</small><strong>{vehicle.next_stop_label ?? (vehicle.status === 'FINISHED' ? 'Маршрут завершён' : 'Не определён')}</strong><span>ETA: {vehicle.eta ? formatTime(vehicle.eta, props.workspace.warehouse.timezone) : '—'} · загрузка {vehicle.load}</span></div><div className="toolbar-row" style={{ margin: '8px 0 0' }}><Button size="sm" onClick={() => props.onSimulationOverride('delay', vehicle.driver_shift_id)}>+ Задержка</Button><Button size="sm" variant="danger" onClick={() => props.onSimulationOverride('unavailable', vehicle.driver_shift_id)}>Недоступен</Button></div></article>)}</div>{props.simulation.warnings.map((warning) => <div className="error-panel" key={`${warning.code}-${warning.message}`}><strong>{warning.code}</strong><p>{warning.message}</p></div>)}</section>;
}

export function Inspector(props: InspectorProps) {
  const section = useUiStore((state) => state.section);
  const mode = useUiStore((state) => state.mode);
  const beginResize = (event: ReactPointerEvent<HTMLButtonElement>) => {
    event.preventDefault();
    const resize = (pointerEvent: PointerEvent) => props.onInspectorWidthChange(window.innerWidth - pointerEvent.clientX);
    const stop = () => {
      window.removeEventListener('pointermove', resize);
      window.removeEventListener('pointerup', stop);
      window.removeEventListener('pointercancel', stop);
    };
    window.addEventListener('pointermove', resize);
    window.addEventListener('pointerup', stop);
    window.addEventListener('pointercancel', stop);
  };
  const resizeWithKeyboard = (event: ReactKeyboardEvent<HTMLButtonElement>) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    if (event.key === 'ArrowLeft') props.onInspectorWidthChange(props.inspectorWidth + 24);
    else if (event.key === 'ArrowRight') props.onInspectorWidthChange(props.inspectorWidth - 24);
    else if (event.key === 'Home') props.onInspectorWidthChange(320);
    else props.onInspectorWidthChange(Math.floor(window.innerWidth / 2));
  };
  let content: React.ReactNode;
  switch (section) {
    case 'WAREHOUSE': content = <WarehouseSection props={props} />; break;
    case 'DRIVERS': content = <CatalogSection props={props} kind="driver" />; break;
    case 'VEHICLES': content = <CatalogSection props={props} kind="vehicle" />; break;
    case 'SHIFTS': content = <ShiftsSection props={props} />; break;
    case 'REQUESTS': content = <DeliveriesSection props={props} />; break;
    case 'PLAN_DAY': content = <>
      <div className="entity-card__row"><h2 className="section-title">План на {formatDate(props.planningDate)}</h2><span className="toolbar-row">{props.plan?.manually_changed && props.plan.status !== 'CONFIRMED' ? <Button size="sm" onClick={props.onResetManualChanges} disabled={props.busy}>Отменить изменения</Button> : null}{props.plan && props.plan.status !== 'CONFIRMED' ? <Button size="sm" variant="primary" onClick={props.onConfirmPlan} disabled={props.busy}>Утвердить</Button> : null}</span></div>
      {mode === 'SIMULATION' && props.simulation ? <><SimulationDrivers props={props} /><div className="divider" /></> : null}
      {props.plan ? <><Metrics metrics={props.plan.metrics} /><div className="divider" /><PlanPanel plan={props.plan} timeZone={props.workspace.warehouse.timezone} readOnly={props.plan.status === 'CONFIRMED'} onSelectCycle={(id) => props.onSelect('cycle', id)} onSelectDriverRoute={(id) => props.onSelect('driver', id)} onMove={props.onMoveTask} onToggleLock={props.onToggleCycleLock} onCreateTransfer={props.onCreateTransfer} onRescheduleUnassigned={(id) => { const request = props.workspace.requests.find((item) => item.id === id); if (request) props.onEdit('request', request); }} /></> : <EmptyState title="План дня не составлен" description="Заполните условия доставок и вывозов — план пересчитается автоматически." />}
    </>; break;
    case 'UNASSIGNED': content = props.plan?.unassigned.length ? <PlanPanel plan={props.plan} timeZone={props.workspace.warehouse.timezone} showUnassignedOnly readOnly={props.plan.status === 'CONFIRMED'} onSelectCycle={(id) => props.onSelect('cycle', id)} onSelectDriverRoute={(id) => props.onSelect('driver', id)} onMove={props.onMoveTask} onToggleLock={props.onToggleCycleLock} onCreateTransfer={props.onCreateTransfer} onRescheduleUnassigned={(id) => { const request = props.workspace.requests.find((item) => item.id === id); if (request) props.onEdit('request', request); }} /> : <EmptyState title="Нераспределённых заданий нет" description={props.plan ? 'Все задачи выбранного дня распределены.' : 'После расчёта плана здесь появятся задачи без назначенного маршрута.'} />; break;
    case 'SETTINGS': content = <SettingsEditor warehouse={props.workspace.warehouse} busy={props.busy} onSave={props.onSaveSettings} />; break;
  }
  return <aside className="inspector" aria-label="Инспектор">
    <button
      type="button"
      className="inspector__resize-handle"
      role="separator"
      aria-label="Изменить ширину инспектора"
      aria-orientation="vertical"
      aria-valuemin={320}
      aria-valuemax={Math.floor(window.innerWidth / 2)}
      aria-valuenow={Math.round(props.inspectorWidth)}
      onPointerDown={beginResize}
      onKeyDown={resizeWithKeyboard}
      onDoubleClick={() => props.onInspectorWidthChange(420)}
    />
    <header className="inspector__head"><strong>Инспектор</strong><Badge tone="accent">{props.workspace.warehouse.timezone}</Badge></header><div className="inspector__body">{props.validation && !props.validation.valid ? <><ErrorPanel title="План содержит ошибки" error={new Error(props.validation.errors.map((error) => `${error.code}: ${error.message}`).join('\n'))} /><div className="divider" /></> : null}{props.validation?.warnings.map((warning) => <div className="explanation" key={`${warning.code}-${warning.message}`}><CircleAlert size={12} /> {warning.message}</div>)}{content}</div>
  </aside>;
}
