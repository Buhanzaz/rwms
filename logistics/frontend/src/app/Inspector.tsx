import {
  ArrowRightLeft,
  CalendarPlus,
  CircleAlert,
  Edit3,
  GripVertical,
  Plus,
  Trash2,
  MapPinPlus,
  X,
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
import type {
  ContractorDispatchMode,
  ContractorDispatchResult,
  RequestPlanningDetailsInput,
} from '../api/client';
import { Badge, Button, EmptyState, ErrorPanel } from '../components/ui';
import { useUiStore, type MapTool } from '../stores/ui-store';
import { formatDate, formatDistance, formatDuration, formatTime, nextDate } from '../utils/format';
import { PlanPanel, type PlanMove } from '../features/planning/PlanPanel';
import { PlanningDayRequests } from '../features/planning/PlanningDayRequests';
import { OperationsJournalDisclosure } from '../features/operations/OperationsJournal';
import { ContractorDriversPanel } from '../features/contractors/ContractorDriversPanel';
import { OperationsPanel } from '../features/operations/OperationsPanel';
import { validationMessageRu } from '../utils/user-facing-error';
import {
  warehouseAttachedName,
  warehouseDisplayName,
  warehouseLocation,
  warehouseShortName,
} from '../domain/warehouse-presentation';

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
  onDelete: (kind: Exclude<EntityKind, 'warehouse'>, id: UUID, label: string, version: number) => void;
  onGenerateWorkload: () => void;
  onDeleteGeneratedWorkload: () => void;
  onSetMapTool: (tool: MapTool) => void;
  onSelect: (kind: 'warehouse' | 'request' | 'driver' | 'vehicle' | 'shift' | 'cycle', id: UUID) => void;
  onMoveTask: (move: PlanMove) => void;
  onToggleCycleLock: (cycle: RouteCycle) => void;
  onCreateTransfer: (sourceWarehouseId?: UUID, destinationWarehouseId?: UUID) => void;
  onAssignContractor: (requestId: UUID) => void;
  onRescheduleUnassigned: (requestId: UUID) => void;
  onDispatchContractor: (contractorWorkerId: UUID, mode: ContractorDispatchMode, requestIds: UUID[]) => Promise<ContractorDispatchResult | void>;
  onConfirmPlan: () => void;
  onResetManualChanges: () => void;
  onSimulationOverride: (kind: 'delay' | 'unavailable', driverShiftId: UUID) => void;
  planningDate: string;
  onPlanningDateChange: (date: string) => void;
  onSaveRequestPlanning: (requestId: UUID, input: RequestPlanningDetailsInput) => Promise<void>;
  onSplitRequest: (requestId: UUID, quantities: number[]) => Promise<void>;
  loadingMoreRequests: boolean;
  onLoadMoreRequests: () => Promise<void>;
  inspectorWidth: number;
  onInspectorWidthChange: (width: number) => void;
  onClose?: () => void;
}

function EntityCard({ title, subtitle, badges, onClick, onEdit, onDelete }: {
  title: string; subtitle: string; badges?: React.ReactNode; onClick: () => void; onEdit?: () => void; onDelete?: () => void;
}) {
  return (
    <article className="entity-card">
      <button type="button" className="entity-card__select" onClick={onClick}>
        <span className="entity-card__row"><strong>{title}</strong><span>{badges}</span></span>
        <span className="entity-card__subtitle">{subtitle}</span>
      </button>
      {onEdit || onDelete ? <div className="toolbar-row" style={{ margin: '8px 0 0' }}>
        {onEdit ? <Button size="sm" variant="ghost" onClick={onEdit}><Edit3 size={13} aria-hidden="true" />Изменить</Button> : null}
        {onDelete ? <Button size="sm" variant="ghost" onClick={onDelete}><Trash2 size={13} aria-hidden="true" />Удалить</Button> : null}
      </div> : null}
    </article>
  );
}

function Metrics({ metrics }: { metrics: PlanMetrics }) {
  const values: Array<[string, string]> = [
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
  ];
  return <>
    <div className="metrics-grid metrics-grid--summary" aria-label="Итог плана">
      <div className="metric"><small>Назначено</small><strong>{metrics.assigned_count}/{metrics.request_count}</strong></div>
      <div className="metric"><small>Без маршрута</small><strong>{metrics.unassigned_count}</strong></div>
      <div className="metric"><small>Пробег</small><strong>{formatDistance(metrics.total_distance_meters)}</strong></div>
    </div>
    <details className="plan-details"><summary>Подробная статистика</summary><div className="metrics-grid">{values.map(([label, value]) => <div className="metric" key={label}><small>{label}</small><strong>{value}</strong></div>)}</div></details>
  </>;
}

function WarehouseSection({ props }: { props: InspectorProps }) {
  const warehouse = props.workspace.warehouse;
  const mainWarehouse = props.workspace.warehouses.find((candidate) => (
    candidate.id === (props.workspace.planning_root_warehouse_id ?? warehouse.id)
  ));
  return <>
    <h2 className="section-title">{warehouseDisplayName(warehouse, mainWarehouse)}</h2><p className="section-subtitle">{warehouseLocation(warehouse)}</p>
    <div className="detail-grid">
      <div className="detail-item"><small>Часовой пояс</small><strong>{warehouse.timezone}</strong></div>
      <div className="detail-item"><small>Рабочий день</small><strong>{warehouse.working_day_start}–{warehouse.working_day_end}</strong></div>
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

/** Orders warehouse-owned resources by selected warehouse, then its visible siblings. */
function groupedResources<T extends { warehouse_id: UUID }>(
  workspace: WarehouseWorkspace,
  values: readonly T[],
): Array<{ warehouse: Warehouse; role: string; values: T[] }> {
  const warehouses = workspace.warehouses.some((candidate) => candidate.id === workspace.warehouse.id)
    ? workspace.warehouses
    : [workspace.warehouse, ...workspace.warehouses];
  const rootId = workspace.planning_root_warehouse_id ?? workspace.warehouse.id;
  const mainWarehouse = warehouses.find((warehouse) => warehouse.id === rootId);
  const orderedIds = [
    workspace.warehouse.id,
    ...(rootId === workspace.warehouse.id ? [] : [rootId]),
    ...(workspace.planning_group_warehouse_ids ?? []).filter((id) => id !== workspace.warehouse.id && id !== rootId),
    ...values.map((value) => value.warehouse_id).filter((id) => id !== workspace.warehouse.id && id !== rootId),
  ].filter((id, index, ids) => ids.indexOf(id) === index);
  return orderedIds.flatMap((warehouseId) => {
    const warehouse = warehouses.find((candidate) => candidate.id === warehouseId);
    if (!warehouse) return [];
    const ownedValues = values.filter((value) => value.warehouse_id === warehouseId);
    const selected = warehouseId === workspace.warehouse.id;
    const role = warehouseDisplayName(warehouse, mainWarehouse);
    return selected || warehouseId === rootId || ownedValues.length ? [{ warehouse, role, values: ownedValues }] : [];
  });
}

function CatalogSection({ props, kind }: { props: InspectorProps; kind: 'driver' | 'vehicle' }) {
  const values = kind === 'driver' ? props.workspace.drivers : props.workspace.vehicles;
  const trailers = props.workspace.trailers;
  const groups = groupedResources<Driver | Vehicle>(props.workspace, values);
  const mainWarehouse = props.workspace.warehouses.find((warehouse) => (
    warehouse.id === (props.workspace.planning_root_warehouse_id ?? props.workspace.warehouse.id)
  ));
  return <>
    <h2 className="section-title">{kind === 'driver' ? `Водители — ${warehouseShortName(props.workspace.warehouse)}` : `Автотранспорт — ${warehouseShortName(props.workspace.warehouse)}`}</h2>
    {kind === 'vehicle' ? <><Button variant="primary" onClick={() => props.onCreate(kind)}><Plus size={14} />Транспортное средство</Button><div className="divider" /></> : null}
    {groups.map((group) => <section key={group.warehouse.id} aria-label={`${group.role}: ${group.warehouse.name}`}>
      <h3 className="section-title">{kind === 'driver' ? `Водители, прикреплённые к ${warehouseAttachedName(group.warehouse, mainWarehouse)}` : group.role}</h3>
      <div className="entity-list">{group.values.map((value) => {
        const driver = kind === 'driver' ? value as Driver : null;
        const vehicle = kind === 'vehicle' ? value as Vehicle : null;
        const physicalSummary = vehicle?.length_mm && vehicle.width_mm && vehicle.height_mm
          ? ` · габариты ${(vehicle.length_mm / 1_000).toFixed(1)} × ${(vehicle.width_mm / 1_000).toFixed(1)} × ${(vehicle.height_mm / 1_000).toFixed(1)} м`
          : '';
        const payloadSummary = vehicle?.payload_capacity_kg
          ? ` · грузоподъёмность ${(vehicle.payload_capacity_kg / 1_000).toFixed(1)} т`
          : '';
        const resourceSummary = driver
          ? warehouseAttachedName(group.warehouse, mainWarehouse)
          : `${vehicle?.manufacturer ? `${vehicle.manufacturer} · ` : ''}${vehicle?.registration_number} · вместимость ${vehicle?.capacity}${payloadSummary}${physicalSummary}`;
        return <EntityCard key={value.id} title={value.name} subtitle={resourceSummary} badges={<Badge tone={value.active ? 'success' : 'neutral'}>{value.active ? 'активен' : 'выключен'}</Badge>} onClick={() => props.onSelect(kind, value.id)} {...(kind === 'vehicle' ? {
          onEdit: () => props.onEdit('vehicle', value),
          onDelete: () => props.onDelete('vehicle', value.id, value.name, value.version),
        } : {})} />;
      })}</div>
      {!group.values.length ? <p className="field__hint">На этом складе пока нет {kind === 'driver' ? 'водителей' : 'транспортных средств'}.</p> : null}
    </section>)}
    {kind === 'vehicle' && !values.length ? <EmptyState title="Транспорт не добавлен" description="Добавьте транспортное средство, чтобы назначать его на смены и рейсы склада." /> : null}
    {kind === 'vehicle' ? <>
      <div className="divider" />
      <h3 className="section-title">Прицепы</h3>
      <Button variant="primary" onClick={() => props.onCreate('trailer')}><Plus size={14} />Добавить прицеп</Button><div className="divider" />
      {trailers === undefined ? <p className="field__hint">Загружаем каталог прицепов…</p> : groupedResources(props.workspace, trailers).map((group) => <section key={group.warehouse.id} aria-label={`${group.role}: прицепы ${group.warehouse.name}`}>
        <h3 className="section-title">{group.role}</h3>
        <div className="entity-list">{group.values.map((trailer) => <EntityCard
          key={trailer.id}
          title={trailer.name}
          subtitle={trailer.registration_number || 'без номера'}
          badges={<Badge tone={trailer.active ? 'success' : 'neutral'}>{trailer.active ? 'активен' : 'выключен'}</Badge>}
          onClick={() => props.onEdit('trailer', trailer)}
          onEdit={() => props.onEdit('trailer', trailer)}
          onDelete={() => props.onDelete('trailer', trailer.id, trailer.name, trailer.version)}
        />)}</div>
        {!group.values.length ? <p className="field__hint">На этом складе пока нет локальных прицепов.</p> : null}
      </section>)}
      {trailers?.length === 0 ? <EmptyState title="Прицепы не добавлены" description="Добавьте прицеп в отдельный каталог." /> : null}
    </> : null}
  </>;
}

function ShiftsSection({ props }: { props: InspectorProps }) {
  const localDrivers = props.workspace.drivers.filter((driver) => driver.warehouse_id === props.workspace.warehouse.id);
  const localVehicles = props.workspace.vehicles.filter((vehicle) => vehicle.warehouse_id === props.workspace.warehouse.id);
  const shiftVisibility = useUiStore((state) => state.shiftVisibility);
  const setShiftVisibility = useUiStore((state) => state.setShiftVisibility);
  const shiftState = (shift: DriverShift) => {
    const endDate = shift.end_time < shift.start_time ? nextDate(shift.date_to, 1) : shift.date_to;
    if (!shift.active) return 'ARCHIVED' as const;
    return endDate < props.planningDate ? 'COMPLETED' as const : 'ACTIVE' as const;
  };
  const visibleShifts = props.workspace.shifts.filter((shift) => shiftState(shift) === shiftVisibility);
  const groups = groupedResources(props.workspace, visibleShifts);
  return <>
    <h2 className="section-title">Смены — {warehouseShortName(props.workspace.warehouse)}</h2>
    <Button variant="primary" disabled={!localDrivers.length || !localVehicles.length} onClick={() => props.onCreate('shift')}><CalendarPlus size={14} />Добавить смену</Button><div className="divider" />
    <div className="segmented shift-visibility-filter" aria-label="Фильтр смен">
      {([['ACTIVE', 'Активные'], ['COMPLETED', 'Завершённые'], ['ARCHIVED', 'Архив']] as const).map(([value, label]) => (
        <button type="button" key={value} aria-pressed={shiftVisibility === value} onClick={() => setShiftVisibility(value)}>{label}</button>
      ))}
    </div>
    {groups.map((group) => <section key={group.warehouse.id} aria-label={`${group.role}: смены ${group.warehouse.name}`}>
      <h3 className="section-title">{group.role}</h3>
      <div className="entity-list">{group.values.map((shift) => {
        const driver = props.workspace.drivers.find((item) => item.id === shift.driver_id);
        const overnight = shift.end_time < shift.start_time;
        const actualEndDate = overnight ? nextDate(shift.date_to, 1) : shift.date_to;
        const state = shiftState(shift) === 'ARCHIVED' ? 'в архиве' : shiftState(shift) === 'COMPLETED' ? 'завершена' : shift.date_from > props.planningDate ? 'запланирована' : 'активна';
        const actualRange = `${formatDate(shift.date_from)} ${shift.start_time.slice(0, 5)} — ${formatDate(actualEndDate)} ${shift.end_time.slice(0, 5)}`;
        return <EntityCard key={shift.id} title={driver?.name ?? 'Водитель'} subtitle={`${group.warehouse.name} · ${actualRange}${overnight ? ' · окончание на следующий день' : ''} · перерыв ${shift.break_minutes} мин`} badges={<Badge tone={state === 'активна' ? 'success' : state === 'запланирована' ? 'accent' : 'neutral'}>{state}</Badge>} onClick={() => props.onSelect('shift', shift.id)} onEdit={() => props.onEdit('shift', shift)} onDelete={() => props.onDelete('shift', shift.id, `смену ${driver?.name ?? ''}`, shift.version)} />;
      })}</div>
      {!group.values.length ? <p className="field__hint">На этом складе пока нет локальных смен.</p> : null}
    </section>)}
    {!props.workspace.shifts.length ? <EmptyState title="Смены не добавлены" description="Добавьте смену для водителя и транспортного средства." /> : null}
    {props.workspace.shifts.length && !visibleShifts.length ? <EmptyState title="Смен по выбранному фильтру нет" description="Переключите фильтр, чтобы увидеть остальные смены." /> : null}
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
      loadingMore={props.loadingMoreRequests}
      onLoadMore={props.onLoadMoreRequests}
      onPlanningDateChange={props.onPlanningDateChange}
      onSave={props.onSaveRequestPlanning}
      onSplit={props.onSplitRequest}
      onSelect={(id) => props.onSelect('request', id)}
    />
  </>;
}

function SimulationDrivers({ props }: { props: InspectorProps }) {
  if (!props.simulation) return null;
  return <section className="simulation-route-statuses" aria-label="Текущее состояние маршрутов"><h2 className="section-title">Машины сейчас</h2><p className="section-subtitle">План остаётся ниже целиком. Здесь показаны текущий этап, адрес назначения и расчётное время прибытия.</p><div className="entity-list">{props.simulation.vehicles.map((vehicle) => <article className="entity-card simulation-route-status" key={vehicle.driver_shift_id} data-testid={`simulation-route-${vehicle.driver_shift_id}`}><div className="entity-card__row"><button type="button" className="simulation-route-status__driver" onClick={() => props.onSelect('driver', vehicle.driver_shift_id)}><strong>{vehicle.driver_name}</strong><small>{vehicle.vehicle_name} · {vehicle.registration_number}</small></button><Badge tone={vehicle.status === 'DELAYED' ? 'danger' : vehicle.status === 'FINISHED' ? 'neutral' : 'success'}>{vehicle.status}</Badge></div><div className="simulation-route-status__destination"><small>Адрес назначения</small><strong>{vehicle.next_stop_label ?? (vehicle.status === 'FINISHED' ? 'Маршрут завершён' : 'Не определён')}</strong><span>ETA: {vehicle.eta ? formatTime(vehicle.eta, props.workspace.warehouse.timezone) : '—'} · загрузка {vehicle.load}</span></div><div className="toolbar-row" style={{ margin: '8px 0 0' }}><Button size="sm" onClick={() => props.onSimulationOverride('delay', vehicle.driver_shift_id)}>+ Задержка</Button><Button size="sm" variant="danger" onClick={() => props.onSimulationOverride('unavailable', vehicle.driver_shift_id)}>Недоступен</Button></div></article>)}</div>{props.simulation.warnings.map((warning) => <div className="error-panel" key={`${warning.code}-${warning.message}`}><strong>План требует внимания</strong><p>{validationMessageRu(warning.code, warning.message)}</p></div>)}</section>;
}

export function Inspector(props: InspectorProps) {
  const section = useUiStore((state) => state.section);
  const mode = useUiStore((state) => state.mode);
  const selected = useUiStore((state) => state.selected);
  const selectedRequestId = selected?.kind === 'request' ? selected.id : null;
  const unassignedRequestIds = new Set(props.plan?.unassigned.map((item) => item.task.request_id) ?? []);
  const manualContractorRequests = props.workspace.requests.filter((request) => (
    request.warehouse_id === props.workspace.warehouse.id
    && request.scheduled_date === props.planningDate
    && request.assigned_contractor_worker_id == null
    && (props.plan ? unassignedRequestIds.has(request.id) : request.status === 'READY' || request.status === 'UNASSIGNED')
  ));
  const contractorRouteRequests = props.workspace.requests.filter((request) => (
    request.warehouse_id === props.workspace.warehouse.id
    && request.scheduled_date === props.planningDate
    && request.assigned_contractor_worker_id != null
  ));
  const beginResize = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (event.button !== 0) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
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
    case 'CONTRACTORS': content = <ContractorDriversPanel key={props.workspace.warehouse.external_warehouse_id} warehouseId={props.workspace.warehouse.external_warehouse_id} warehouseName={warehouseShortName(props.workspace.warehouse)} planningDate={props.planningDate} manualRequests={manualContractorRequests} routeRequests={contractorRouteRequests} routeRequestsComplete={props.workspace.request_next_cursor == null} busy={props.busy} onDispatch={props.onDispatchContractor} />; break;
    case 'VEHICLES': content = <CatalogSection props={props} kind="vehicle" />; break;
    case 'SHIFTS': content = <ShiftsSection props={props} />; break;
    case 'REQUESTS': content = <DeliveriesSection props={props} />; break;
    case 'PLAN_DAY': content = <>
      <div className="entity-card__row"><h2 className="section-title">План на {formatDate(props.planningDate)}</h2><span className="toolbar-row">{props.plan?.manually_changed && props.plan.status !== 'CONFIRMED' ? <Button size="sm" onClick={props.onResetManualChanges} disabled={props.busy}>Отменить изменения</Button> : null}{props.plan && props.plan.status !== 'CONFIRMED' ? <Button size="sm" variant="primary" onClick={props.onConfirmPlan} disabled={props.busy}>Утвердить</Button> : null}</span></div>
      {mode === 'PLAN_DAY' ? <>
        <OperationsPanel
          workspace={props.workspace}
          plan={props.plan}
          planningDate={props.planningDate}
          busy={props.busy}
          onSelectRequest={(id) => props.onSelect('request', id)}
        />
        <div className="divider" />
      </> : null}
      {mode === 'PLAN_DAY' ? <OperationsJournalDisclosure
        warehouseId={props.workspace.planning_root_warehouse_id ?? props.workspace.warehouse.id}
        planningDate={props.planningDate}
        timeZone={props.workspace.warehouses.find((warehouse) => warehouse.id === (props.workspace.planning_root_warehouse_id ?? props.workspace.warehouse.id))?.timezone ?? props.workspace.warehouse.timezone}
        onSelectRequest={(id) => props.onSelect('request', id)}
      /> : null}
      {mode === 'SIMULATION' && props.simulation ? <><SimulationDrivers props={props} /><div className="divider" /></> : null}
      {props.plan ? <><Metrics metrics={props.plan.metrics} /><div className="divider" /><PlanPanel plan={props.plan} requests={props.workspace.requests} timeZone={props.workspace.warehouse.timezone} readOnly={props.plan.status === 'CONFIRMED'} selectedRequestId={selectedRequestId} onSelectCycle={(id) => props.onSelect('cycle', id)} onSelectDriverRoute={(id) => props.onSelect('driver', id)} onSelectRequest={(id) => props.onSelect('request', id)} onMove={props.onMoveTask} onToggleLock={props.onToggleCycleLock} onCreateTransfer={props.onCreateTransfer} onAssignContractor={props.onAssignContractor} onRescheduleUnassigned={props.onRescheduleUnassigned} /></> : <EmptyState title="План дня не составлен" description="Заполните условия доставок и вывозов — план пересчитается автоматически." />}
    </>; break;
    case 'UNASSIGNED': content = props.plan?.unassigned.length ? <PlanPanel plan={props.plan} requests={props.workspace.requests} timeZone={props.workspace.warehouse.timezone} showUnassignedOnly readOnly={props.plan.status === 'CONFIRMED'} selectedRequestId={selectedRequestId} onSelectCycle={(id) => props.onSelect('cycle', id)} onSelectDriverRoute={(id) => props.onSelect('driver', id)} onSelectRequest={(id) => props.onSelect('request', id)} onMove={props.onMoveTask} onToggleLock={props.onToggleCycleLock} onCreateTransfer={props.onCreateTransfer} onAssignContractor={props.onAssignContractor} onRescheduleUnassigned={props.onRescheduleUnassigned} /> : <EmptyState title="Нераспределённых заданий нет" description={props.plan ? 'Все задачи выбранного дня распределены.' : 'После расчёта плана здесь появятся задачи без назначенного маршрута.'} />; break;
  }
  return <aside id="logistics-inspector" className="inspector" aria-label="Панель логистики">
    {props.onClose ? <div className="inspector__chrome"><span>Логистика</span><Button size="sm" variant="ghost" aria-label="Скрыть панель логистики" onClick={props.onClose}><X size={16} aria-hidden="true" /></Button></div> : null}
    <button
      type="button"
      className="inspector__resize-handle"
      role="separator"
      aria-label="Изменить ширину инспектора"
      aria-orientation="vertical"
      aria-valuemin={320}
      aria-valuemax={Math.floor(window.innerWidth / 2)}
      aria-valuenow={Math.round(props.inspectorWidth)}
      title="Потяните, чтобы изменить ширину. Двойной щелчок — исходный размер."
      onPointerDown={beginResize}
      onPointerMove={(event) => {
        if (event.currentTarget.hasPointerCapture(event.pointerId)) {
          props.onInspectorWidthChange(window.innerWidth - event.clientX - 12);
        }
      }}
      onPointerUp={(event) => {
        if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
      }}
      onKeyDown={resizeWithKeyboard}
      onDoubleClick={() => props.onInspectorWidthChange(420)}
    >
      <GripVertical size={16} aria-hidden="true" />
    </button>
    <div className="inspector__body">{props.validation && !props.validation.valid ? <><ErrorPanel title="План содержит ошибки" error={new Error(props.validation.errors.map((error) => validationMessageRu(error.code, error.message)).join('\n'))} /><div className="divider" /></> : null}{props.validation?.warnings.map((warning) => <div className="explanation" key={`${warning.code}-${warning.message}`}><CircleAlert size={12} /> {validationMessageRu(warning.code, warning.message)}</div>)}{content}</div>
  </aside>;
}
