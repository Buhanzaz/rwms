import {
  CalendarRange,
  CalendarPlus,
  CircleAlert,
  Copy,
  Download,
  Edit3,
  GitFork,
  Lock,
  LockOpen,
  MapPinPlus,
  Plus,
  RefreshCw,
  Scissors,
  Trash2,
  Upload,
} from 'lucide-react';
import type {
  Driver,
  DriverShift,
  LogisticsRequest,
  PlanMetrics,
  PlanningSettings,
  RouteCycle,
  RoutePlan,
  ScenarioWorkspace,
  SimulationDerivedState,
  UUID,
  ValidationResult,
  Vehicle,
  Warehouse,
  Zone,
} from '../domain/types';
import { Badge, Button, EmptyState, ErrorPanel } from '../components/ui';
import { isRequestVisibleOnDate, requestPlanningDates } from '../domain/request-dates';
import { useUiStore, type MapTool } from '../stores/ui-store';
import { formatDate, formatDistance, formatDuration, formatTime, nextDate } from '../utils/format';
import { PlanPanel, type PlanMove } from '../features/planning/PlanPanel';
import { SettingsEditor } from '../features/settings/SettingsEditor';

export type EntityKind = 'scenario' | 'warehouse' | 'zone' | 'driver' | 'vehicle' | 'shift' | 'request';
export type EditableEntity = ScenarioWorkspace['scenario'] | Warehouse | Zone | Driver | Vehicle | DriverShift | LogisticsRequest;

interface InspectorProps {
  workspace: ScenarioWorkspace;
  plan: RoutePlan | null;
  simulation: SimulationDerivedState | null;
  validation: ValidationResult | null;
  busy: boolean;
  onCreate: (kind: EntityKind) => void;
  onEdit: (kind: EntityKind, value: EditableEntity) => void;
  onDelete: (kind: Exclude<EntityKind, 'scenario' | 'warehouse'>, id: UUID, label: string) => void;
  onGenerateDemo: () => void;
  onGenerateMultiDayDemo: () => void;
  onCloneScenario: () => void;
  onDeleteScenario: () => void;
  onExport: () => void;
  onImport: () => void;
  onReclassify: () => void;
  onZoneRelation: (fromId: UUID, toId: UUID) => void;
  onSetMapTool: (tool: MapTool) => void;
  onSelect: (kind: 'warehouse' | 'zone' | 'request' | 'driver' | 'vehicle' | 'shift' | 'cycle', id: UUID) => void;
  onMoveTask: (move: PlanMove) => void;
  onToggleCycleLock: (cycle: RouteCycle) => void;
  onSaveSettings: (settings: PlanningSettings) => Promise<void>;
  onClonePlan: () => void;
  onSimulationOverride: (kind: 'delay' | 'unavailable', driverShiftId: UUID) => void;
  planningDate: string;
  onPlanningDateChange: (date: string) => void;
  onScheduleRequestDate: (requestId: UUID, date: string, addIfMissing: boolean) => void;
  onUnscheduleRequest: (requestId: UUID) => void;
}

function EntityCard({ title, subtitle, badges, onClick, onEdit, onDelete }: {
  title: string; subtitle: string; badges?: React.ReactNode; onClick: () => void; onEdit: () => void; onDelete?: () => void;
}) {
  return (
    <article className="entity-card" onClick={onClick}>
      <div className="entity-card__row"><strong>{title}</strong><span>{badges}</span></div>
      <p>{subtitle}</p>
      <div className="toolbar-row" style={{ margin: '8px 0 0' }}>
        <Button size="sm" variant="ghost" onClick={(event) => { event.stopPropagation(); onEdit(); }}><Edit3 size={13} />Изменить</Button>
        {onDelete ? <Button size="sm" variant="ghost" onClick={(event) => { event.stopPropagation(); onDelete(); }}><Trash2 size={13} />Удалить</Button> : null}
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
    ['Вывозы на возврате', String(metrics.paired_pickups)],
    ['Средняя загрузка', metrics.average_load.toFixed(2)],
    ['Переработка', formatDuration(metrics.overtime_seconds)],
    ['Минимальный резерв', formatDuration(metrics.minimum_buffer_seconds)],
    ['Score', metrics.score.toFixed(1)],
  ];
  return <div className="metrics-grid">{values.map(([label, value]) => <div className="metric" key={label}><small>{label}</small><strong>{value}</strong></div>)}</div>;
}

function ScenarioSection({ props }: { props: InspectorProps }) {
  const { workspace } = props;
  return <>
    <h2 className="section-title">{workspace.scenario.name}</h2>
    <p className="section-subtitle">{workspace.scenario.description || 'Без описания'}</p>
    <div className="detail-grid">
      <div className="detail-item"><small>Часовой пояс</small><strong>{workspace.scenario.timezone}</strong></div>
      <div className="detail-item"><small>Дата по умолчанию</small><strong>{formatDate(workspace.scenario.default_planning_date)}</strong></div>
      <div className="detail-item"><small>Seed</small><strong>{workspace.scenario.seed ?? 'не задан'}</strong></div>
      <div className="detail-item"><small>Обновлён</small><strong>{new Date(workspace.scenario.updated_at).toLocaleString('ru-RU')}</strong></div>
    </div>
    <div className="divider" />
    <div className="toolbar-row">
      <Button onClick={() => props.onCreate('scenario')}><Plus size={14} />Новый сценарий</Button>
      <Button variant="primary" onClick={() => props.onGenerateDemo()} disabled={props.busy}><RefreshCw size={14} />Demo scenario</Button>
      <Button onClick={() => props.onGenerateMultiDayDemo()} disabled={props.busy}><CalendarRange size={14} />Тест на 3 дня</Button>
      <Button onClick={() => props.onEdit('scenario', workspace.scenario)}><Edit3 size={14} />Изменить</Button>
      <Button onClick={props.onCloneScenario}><Copy size={14} />Клонировать</Button>
      <Button onClick={props.onExport}><Download size={14} />Экспорт JSON</Button>
      <Button onClick={props.onImport}><Upload size={14} />Импорт JSON</Button>
      <Button variant="danger" onClick={props.onDeleteScenario}><Trash2 size={14} />Удалить</Button>
    </div>
    <div className="explanation"><strong>Внутренний стенд.</strong><br />Нет клиентской регистрации, оплаты, GPS, приложения водителя и внешних API-ключей.</div>
  </>;
}

function WarehouseSection({ props }: { props: InspectorProps }) {
  return <>
    <h2 className="section-title">Склад</h2><p className="section-subtitle">Точка начала и завершения каждого рейсового цикла.</p>
    <div className="toolbar-row"><Button variant="primary" onClick={() => props.onSetMapTool('PLACE_WAREHOUSE')}><MapPinPlus size={14} />Поставить на карте</Button><Button onClick={() => props.onCreate('warehouse')}><Plus size={14} />Координатами</Button></div>
    <div className="entity-list">{props.workspace.warehouses.map((warehouse) => <EntityCard key={warehouse.id} title={warehouse.name} subtitle={`${warehouse.latitude.toFixed(5)}, ${warehouse.longitude.toFixed(5)} · ${warehouse.working_day_start}–${warehouse.working_day_end}`} onClick={() => props.onSelect('warehouse', warehouse.id)} onEdit={() => props.onEdit('warehouse', warehouse)} />)}</div>
    {!props.workspace.warehouses.length ? <EmptyState title="Склад не задан" description="Поставьте склад на карте. Без него планирование невозможно." /> : null}
  </>;
}

function ZonesSection({ props }: { props: InspectorProps }) {
  return <>
    <h2 className="section-title">Логистические зоны</h2><p className="section-subtitle">Принадлежность заявки определяет только backend. Изменение полигона не переклассифицирует старые заявки молча.</p>
    <div className="toolbar-row"><Button variant="primary" onClick={() => props.onSetMapTool('DRAW_ZONE')}><Plus size={14} />Нарисовать</Button><Button onClick={() => props.onSetMapTool('CUT_ZONE')}><Scissors size={14} aria-hidden="true" />Сделать вырез</Button><Button onClick={() => props.onSetMapTool('EDIT_ZONE')}><Edit3 size={14} />Вершины</Button><Button onClick={props.onReclassify}><RefreshCw size={14} />Пересчитать заявки</Button></div>
    <div className="entity-list">{props.workspace.zones.map((zone) => <EntityCard key={zone.id} title={`${zone.code} · ${zone.name}`} subtitle={`${zone.route_group} · приоритет ${zone.priority} · версия ${zone.version}${zone.stale_request_count ? ` · устарело ${zone.stale_request_count}` : ''}`} badges={<>{zone.locked ? <Badge tone="warning"><Lock size={10} />locked</Badge> : <Badge tone="neutral"><LockOpen size={10} />editable</Badge>}</>} onClick={() => props.onSelect('zone', zone.id)} onEdit={() => props.onEdit('zone', zone)} onDelete={() => props.onDelete('zone', zone.id, zone.name)} />)}</div>
  </>;
}

function RelationSection({ props }: { props: InspectorProps }) {
  const zones = props.workspace.zones;
  return <>
    <h2 className="section-title">Матрица связей зон</h2><p className="section-subtitle">Строка — откуда, столбец — куда. Направления редактируются независимо.</p>
    <div className="toolbar-row"><Button disabled={zones.length < 2} onClick={() => props.onSetMapTool('RELATE_ZONES')}><GitFork size={14} />Выбрать две зоны на карте</Button></div>
    {zones.length < 2 ? <EmptyState title="Недостаточно зон" description="Создайте минимум две зоны для настройки переходов." /> : <div className="relation-table-wrap"><table className="relation-table"><thead><tr><th>из \ в</th>{zones.map((zone) => <th key={zone.id}>{zone.code}</th>)}</tr></thead><tbody>{zones.map((from) => <tr key={from.id}><th>{from.code}</th>{zones.map((to) => {
      if (from.id === to.id) return <td key={to.id}>—</td>;
      const relation = props.workspace.zone_relations.find((item) =>
        (item.from_zone_id === from.id && item.to_zone_id === to.id) ||
        (item.is_bidirectional && item.from_zone_id === to.id && item.to_zone_id === from.id),
      );
      return <td key={to.id}><button className={`relation-cell ${relation?.relation_type === 'BLOCKED' ? 'relation-cell--blocked' : relation ? 'relation-cell--allowed' : ''}`} onClick={() => props.onZoneRelation(from.id, to.id)} aria-label={`Связь ${from.code} в ${to.code}`}>{relation?.relation_type === 'BLOCKED' ? '×' : relation ? '✓' : '·'}</button></td>;
    })}</tr>)}</tbody></table></div>}
  </>;
}

function CatalogSection({ props, kind }: { props: InspectorProps; kind: 'driver' | 'vehicle' }) {
  const values = kind === 'driver' ? props.workspace.drivers : props.workspace.vehicles;
  return <>
    <h2 className="section-title">{kind === 'driver' ? 'Водители' : 'Машины'}</h2><p className="section-subtitle">{kind === 'driver' ? 'Группа — мягкое предпочтение при назначении.' : 'Вместимость измеряется в бытовках.'}</p>
    <Button variant="primary" onClick={() => props.onCreate(kind)}><Plus size={14} />Добавить</Button><div className="divider" />
    <div className="entity-list">{values.map((value) => {
      const driver = kind === 'driver' ? value as Driver : null;
      const vehicle = kind === 'vehicle' ? value as Vehicle : null;
      return <EntityCard key={value.id} title={value.name} subtitle={driver ? `Группа ${driver.preferred_route_group || 'не задана'}` : `${vehicle?.registration_number} · вместимость ${vehicle?.capacity}`} badges={<Badge tone={value.active ? 'success' : 'neutral'}>{value.active ? 'активен' : 'выключен'}</Badge>} onClick={() => props.onSelect(kind, value.id)} onEdit={() => props.onEdit(kind, value)} onDelete={() => props.onDelete(kind, value.id, value.name)} />;
    })}</div>
  </>;
}

function ShiftsSection({ props }: { props: InspectorProps }) {
  return <>
    <h2 className="section-title">Смены</h2><p className="section-subtitle">Одна смена связывает водителя, машину и доступный интервал.</p>
    <Button variant="primary" disabled={!props.workspace.drivers.length || !props.workspace.vehicles.length} onClick={() => props.onCreate('shift')}><CalendarPlus size={14} />Добавить смену</Button><div className="divider" />
    <div className="entity-list">{props.workspace.shifts.map((shift) => {
      const driver = props.workspace.drivers.find((item) => item.id === shift.driver_id);
      const vehicle = props.workspace.vehicles.find((item) => item.id === shift.vehicle_id);
      const duration = (new Date(shift.end_at).getTime() - new Date(shift.start_at).getTime()) / 1000;
      return <EntityCard key={shift.id} title={`${driver?.name ?? 'Водитель'} · ${vehicle?.registration_number ?? 'машина'}`} subtitle={`${formatDate(shift.date)} · ${formatTime(shift.start_at, props.workspace.scenario.timezone)}–${formatTime(shift.end_at, props.workspace.scenario.timezone)} · ${formatDuration(duration)}`} badges={<Badge tone={shift.active ? 'success' : 'neutral'}>{shift.active ? 'активна' : 'выкл.'}</Badge>} onClick={() => props.onSelect('shift', shift.id)} onEdit={() => props.onEdit('shift', shift)} onDelete={() => props.onDelete('shift', shift.id, `смену ${driver?.name ?? ''}`)} />;
    })}</div>
  </>;
}

function RequestsSection({ props }: { props: InspectorProps }) {
  const requestDates = [...new Set(props.workspace.requests.flatMap(requestPlanningDates))].sort();
  const availableRequests = props.workspace.requests.filter((request) => isRequestVisibleOnDate(request, props.planningDate));
  const nextPlanningDate = nextDate(props.planningDate, 1);
  return <>
    <h2 className="section-title">Заявки</h2><p className="section-subtitle">Количество больше двух backend разбивает на транспортные части 2 + 2 + 1.</p>
    <div className="toolbar-row"><Button variant="primary" onClick={() => props.onSetMapTool('ADD_DELIVERY')}><MapPinPlus size={14} />Доставка</Button><Button onClick={() => props.onSetMapTool('ADD_PICKUP')}><MapPinPlus size={14} />Вывоз</Button><Button onClick={() => props.onCreate('request')}><Plus size={14} />Координатами</Button></div>
    <div className="date-board" aria-label="Заявки по допустимым датам">
      <strong>Показать дату</strong>
      <div>{requestDates.map((date) => {
        const count = props.workspace.requests.filter((request) => isRequestVisibleOnDate(request, date)).length;
        return <button type="button" key={date} aria-pressed={date === props.planningDate} onClick={() => props.onPlanningDateChange(date)}>{formatDate(date)} <small>{count}</small></button>;
      })}</div>
      <p>{formatDate(props.planningDate)}: доступно {availableRequests.length} из {props.workspace.requests.length}. Список ниже отфильтрован по этой дате.</p>
    </div>
    <div className="entity-list">{availableRequests.map((request) => {
      const zone = props.workspace.zones.find((item) => item.id === request.zone_id);
      const tone = request.zone_status === 'OUTSIDE_ZONES' ? 'danger' : request.zone_status === 'STALE' ? 'warning' : 'neutral';
      const selectedOption = request.date_options.find((option) => option.date === props.planningDate);
      const hasTomorrow = request.date_options.some((option) => option.date === nextPlanningDate);
      return <article className="entity-card" key={request.id} onClick={() => props.onSelect('request', request.id)}>
        <div className="entity-card__row"><strong>{request.type === 'DELIVERY' ? 'Д' : 'В'} · {request.name}</strong><span><Badge tone={request.type === 'DELIVERY' ? 'accent' : 'warning'}>{request.type}</Badge><Badge tone={tone}>{request.zone_status ?? 'CURRENT'}</Badge></span></div>
        <p>{request.quantity} бытов. · зона {zone?.code ?? 'OUTSIDE_ZONES'} v{request.zone_version ?? '—'}</p>
        <p>{request.scheduled_date ? `Выставлено на ${formatDate(request.scheduled_date)}` : 'Дата логистики не выбрана'}</p>
        <div className="request-date-options">{request.date_options.map((option) => <span key={option.date} className={option.date === props.planningDate ? 'request-date-options__active' : undefined}>{formatDate(option.date)}{option.is_hard ? ' · жёстко' : ''}</span>)}</div>
        <div className="toolbar-row" style={{ margin: '8px 0 0' }}>
          <Button size="sm" variant={request.scheduled_date === props.planningDate ? 'primary' : 'ghost'} onClick={(event) => { event.stopPropagation(); props.onScheduleRequestDate(request.id, props.planningDate, !selectedOption); }}>
            {request.scheduled_date === props.planningDate ? `Назначено ${formatDate(props.planningDate)}` : selectedOption ? `Выставить ${formatDate(props.planningDate)}` : `Добавить ${formatDate(props.planningDate)}`}
          </Button>
          <Button size="sm" variant="ghost" onClick={(event) => { event.stopPropagation(); props.onScheduleRequestDate(request.id, nextPlanningDate, !hasTomorrow); }}>
            {hasTomorrow ? `Выставить ${formatDate(nextPlanningDate)}` : `Согласовать ${formatDate(nextPlanningDate)}`}
          </Button>
          {request.scheduled_date ? <Button size="sm" variant="ghost" onClick={(event) => { event.stopPropagation(); props.onUnscheduleRequest(request.id); }}>Снять дату</Button> : null}
          <Button size="sm" variant="ghost" onClick={(event) => { event.stopPropagation(); props.onEdit('request', request); }}><Edit3 size={13} />Изменить</Button>
          <Button size="sm" variant="ghost" onClick={(event) => { event.stopPropagation(); props.onDelete('request', request.id, request.name); }}><Trash2 size={13} />Удалить</Button>
        </div>
      </article>;
    })}</div>
    {!availableRequests.length ? <EmptyState title="На выбранную дату заявок нет" description="Выберите другую дату выше или добавьте допустимую дату после согласования с клиентом." /> : null}
  </>;
}

function SimulationDrivers({ props }: { props: InspectorProps }) {
  if (!props.simulation) return null;
  return <><h2 className="section-title">Машины сейчас</h2><p className="section-subtitle">Состояние вычислено из плана и timestamp; прокрутка назад детерминирована.</p><div className="entity-list">{props.simulation.vehicles.map((vehicle) => <article className="entity-card" key={vehicle.driver_shift_id}><div className="entity-card__row"><strong>{vehicle.driver_name}</strong><Badge tone={vehicle.status === 'DELAYED' ? 'danger' : vehicle.status === 'FINISHED' ? 'neutral' : 'success'}>{vehicle.status}</Badge></div><p>{vehicle.registration_number} · загрузка {vehicle.load} · далее {vehicle.next_stop_label ?? '—'}</p><div className="toolbar-row" style={{ margin: '8px 0 0' }}><Button size="sm" onClick={() => props.onSimulationOverride('delay', vehicle.driver_shift_id)}>+ Задержка</Button><Button size="sm" variant="danger" onClick={() => props.onSimulationOverride('unavailable', vehicle.driver_shift_id)}>Недоступен</Button></div></article>)}</div>{props.simulation.warnings.map((warning) => <div className="error-panel" key={`${warning.code}-${warning.message}`}><strong>{warning.code}</strong><p>{warning.message}</p></div>)}</>;
}

export function Inspector(props: InspectorProps) {
  const section = useUiStore((state) => state.section);
  let content: React.ReactNode;
  if (props.simulation && section === 'ROUTES') content = <SimulationDrivers props={props} />;
  else switch (section) {
    case 'SCENARIO': content = <ScenarioSection props={props} />; break;
    case 'WAREHOUSE': content = <WarehouseSection props={props} />; break;
    case 'ZONES': content = <ZonesSection props={props} />; break;
    case 'ZONE_RELATIONS': content = <RelationSection props={props} />; break;
    case 'DRIVERS': content = <CatalogSection props={props} kind="driver" />; break;
    case 'VEHICLES': content = <CatalogSection props={props} kind="vehicle" />; break;
    case 'SHIFTS': content = <ShiftsSection props={props} />; break;
    case 'REQUESTS': content = <RequestsSection props={props} />; break;
    case 'ROUTES': content = <><div className="entity-card__row"><span><h2 className="section-title">Маршруты</h2><p className="section-subtitle">Нажмите водителя, чтобы выделить все его рейсы на карте; цикл — чтобы выделить один рейс.</p></span>{props.plan ? <Button size="sm" onClick={props.onClonePlan}><Copy size={13} />Клон</Button> : null}</div>{props.plan ? <><Metrics metrics={props.plan.metrics} /><div className="divider" /></> : null}<PlanPanel plan={props.plan} timeZone={props.workspace.scenario.timezone} onSelectCycle={(id) => props.onSelect('cycle', id)} onSelectDriverRoute={(id) => props.onSelect('driver', id)} onMove={props.onMoveTask} onToggleLock={props.onToggleCycleLock} /></>; break;
    case 'UNASSIGNED': content = <PlanPanel plan={props.plan} timeZone={props.workspace.scenario.timezone} showUnassignedOnly onSelectCycle={(id) => props.onSelect('cycle', id)} onSelectDriverRoute={(id) => props.onSelect('driver', id)} onMove={props.onMoveTask} onToggleLock={props.onToggleCycleLock} />; break;
    case 'SETTINGS': content = <SettingsEditor settings={props.workspace.scenario.settings} busy={props.busy} onSave={props.onSaveSettings} />; break;
  }
  return <aside className="inspector" aria-label="Инспектор"><header className="inspector__head"><strong>Инспектор</strong><Badge tone="accent">{props.workspace.scenario.timezone}</Badge></header><div className="inspector__body">{props.validation && !props.validation.valid ? <><ErrorPanel title="План содержит ошибки" error={new Error(props.validation.errors.map((error) => `${error.code}: ${error.message}`).join('\n'))} /><div className="divider" /></> : null}{props.validation?.warnings.map((warning) => <div className="explanation" key={`${warning.code}-${warning.message}`}><CircleAlert size={12} /> {warning.message}</div>)}{content}</div></aside>;
}
