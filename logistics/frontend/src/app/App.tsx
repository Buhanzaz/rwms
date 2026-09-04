import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ChevronDown,
  LockKeyhole,
  PanelRight,
  PlayCircle,
  RefreshCw,
  Route as RouteIcon,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  api,
  ApiError,
  getWarehouseWorkspace,
  startOptimizationEventStream,
  WORKSPACE_REQUEST_PAGE_LIMIT,
  type RequestPlanningDetailsInput,
  type RequestRescheduleResultRead,
} from '../api/client';
import type {
  DriverShift,
  LogisticsRequest,
  OptimizationRun,
  OptimizationTraceEvent,
  RouteCycle,
  RoutePlan,
  Trailer,
  UUID,
  ValidationResult,
  Vehicle,
  Warehouse,
} from '../domain/types';
import { Button, EmptyState, ErrorPanel, NotificationCenter, Spinner, ThemeSwitch, Toasts } from '../components/ui';
import { DatePicker } from '../components/DatePicker';
import {
  CatalogDialog,
  ConfirmDialog,
  EmptyPositioningConfirmDialog,
  RequestDialog,
  ShiftDialog,
  SimulationOverrideDialog,
  WarehouseDialog,
} from '../components/EntityDialogs';
import { MapCanvas } from '../map/MapCanvas';
import { useUiStore } from '../stores/ui-store';
import { dateInTimeZone, formatDate } from '../utils/format';
import { deriveSimulationState, planTimeBounds } from '../simulation/deriveSimulationState';
import { Sidebar } from './Sidebar';
import { Inspector, type EditableEntity, type EntityKind } from './Inspector';
import type { PlanMove } from '../features/planning/PlanPanel';
import { SimulationBar } from '../features/simulation/SimulationBar';
import { WorkloadGeneratorDialog } from '../features/workload/WorkloadGeneratorDialog';
import { actionErrorFeedback } from './action-error';
import { WarehouseLocalTime } from './WarehouseLocalTime';
import { initialWarehouseSelection } from './warehouse-selection';
import { TrailerDialog } from '../features/trailers/TrailerDialog';
import { SlotAvailabilityPanel } from '../features/slot-availability/SlotAvailabilityPanel';
import type { SlotPlanningMapPresentation } from '../features/slot-availability/types';
import { TransferDraftDialog } from '../features/transfers/TransferDraftDialog';
import { ContractorAssignmentDialog } from '../features/contractors/ContractorAssignmentDialog';
import { userFacingErrorDetail } from '../utils/user-facing-error';
import { UnassignedDeliveryRescheduleDialog } from '../features/planning/UnassignedDeliveryRescheduleDialog';
import { warehouseDisplayName } from '../domain/warehouse-presentation';
import { useMapLayout } from './map-layout';
import logotypeUrl from '../assets/logotype.svg';
import { loadWarehouseKinds } from '../api/warehouse-directory';

type DialogState =
  | { kind: 'workload-generator' }
  | { kind: 'warehouse'; value: Warehouse }
  | { kind: 'transfer'; sourceWarehouseId?: UUID; destinationWarehouseId?: UUID }
  | { kind: 'contractor-assignment'; requestId: UUID }
  | { kind: 'delivery-reschedule'; requestId: UUID }
  | { kind: 'vehicle'; value?: Vehicle; intentKey: UUID }
  | { kind: 'trailer'; value?: Trailer; intentKey: UUID }
  | { kind: 'shift'; value?: DriverShift; intentKey: UUID }
  | { kind: 'request'; value?: LogisticsRequest; point?: { latitude: number; longitude: number }; address?: string; requestType: 'DELIVERY' | 'PICKUP'; intentKey: UUID }
  | { kind: 'delete-entity'; entityKind: 'driver' | 'vehicle' | 'trailer' | 'shift' | 'request'; id: UUID; label: string; expectedVersion: number }
  | { kind: 'delete-generated-workload'; date: string }
  | { kind: 'close-planning-day'; date: string }
  | { kind: 'confirm-cross-warehouse-plan' }
  | { kind: 'simulation'; overrideKind: 'delay' | 'unavailable'; driverShiftId: UUID }
  | null;

function isTerminal(status: OptimizationRun['status'] | undefined): boolean {
  return status ? ['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(status) : false;
}

const TRACE_PHASES = [
  'VALIDATING_INPUT',
  'BUILDING_TRAVEL_MATRIX',
  'GROUPING_DELIVERIES',
  'GENERATING_DELIVERY_PAIRS',
  'MATCHING_PICKUPS',
  'BUILDING_CYCLES',
  'ASSIGNING_DRIVERS',
  'LOCAL_SEARCH',
  'FINALIZING',
  'COMPLETED',
] as const;

const DEFAULT_INSPECTOR_WIDTH = 420;
const MIN_INSPECTOR_WIDTH = 320;
const LAST_WAREHOUSE_KEY = 'rwms:logistics:last-warehouse';
const PLANNING_DATE_KEY_PREFIX = 'rwms:logistics:planning-date:';

function savedWarehouseSelection(): UUID | null {
  try {
    return window.localStorage.getItem(LAST_WAREHOUSE_KEY);
  } catch {
    return null;
  }
}

function saveWarehouseSelection(warehouseId: UUID): void {
  try {
    window.localStorage.setItem(LAST_WAREHOUSE_KEY, warehouseId);
  } catch {
    // The current in-memory selection remains usable when storage is blocked.
  }
}

function savedPlanningDate(warehouseId: UUID): string | null {
  try {
    const value = window.localStorage.getItem(`${PLANNING_DATE_KEY_PREFIX}${warehouseId}`);
    return value && /^\d{4}-\d{2}-\d{2}$/u.test(value) ? value : null;
  } catch {
    return null;
  }
}

function savePlanningDate(warehouseId: UUID, planningDate: string): void {
  try {
    window.localStorage.setItem(`${PLANNING_DATE_KEY_PREFIX}${warehouseId}`, planningDate);
  } catch {
    // The current in-memory selection remains usable when storage is blocked.
  }
}

function warehouseOptionLabel(warehouse: Warehouse, mainWarehouse?: Warehouse | null): string {
  return warehouseDisplayName(warehouse, mainWarehouse);
}

function savedInspectorWidth(): number {
  try {
    const saved = Number(window.localStorage.getItem('rwms:logistics:inspector-width'));
    const requested = Number.isFinite(saved) && saved >= MIN_INSPECTOR_WIDTH ? saved : DEFAULT_INSPECTOR_WIDTH;
    return Math.min(Math.max(MIN_INSPECTOR_WIDTH, Math.floor(window.innerWidth / 2)), requested);
  } catch {
    return DEFAULT_INSPECTOR_WIDTH;
  }
}

function parseTraceEvent(value: string, runId: UUID): OptimizationTraceEvent | null {
  try {
    const raw = JSON.parse(value) as unknown;
    if (!raw || typeof raw !== 'object') return null;
    const record = raw as Record<string, unknown>;
    const eventType = typeof record.event_type === 'string' ? record.event_type : typeof record.type === 'string' ? record.type : null;
    if (!eventType) return null;
    return {
      id: typeof record.id === 'string' ? record.id : `${runId}-${typeof record.sequence === 'number' ? record.sequence : Date.now()}`,
      optimization_run_id: runId,
      sequence: typeof record.sequence === 'number' ? record.sequence : 0,
      event_type: eventType,
      payload: record.payload && typeof record.payload === 'object' ? record.payload as Record<string, unknown> : {},
      created_at: typeof record.created_at === 'string' ? record.created_at : new Date().toISOString(),
    };
  } catch {
    return null;
  }
}

export function App() {
  const queryClient = useQueryClient();
  const [warehouseId, setWarehouseId] = useState<UUID | null>(savedWarehouseSelection);
  const [planningDate, setPlanningDate] = useState('');
  const [planningDateContext, setPlanningDateContext] = useState<{
    warehouseId: UUID;
    timeZone: string;
  } | null>(null);
  const [planId, setPlanId] = useState<UUID | null>(null);
  const [plan, setPlan] = useState<RoutePlan | null>(null);
  const [runId, setRunId] = useState<UUID | null>(null);
  const [dialog, setDialog] = useState<DialogState>(null);
  const [validation, setValidation] = useState<ValidationResult | null>(null);
  const [warehouseSelectorOpen, setWarehouseSelectorOpen] = useState(false);
  const [slotPlannerOpen, setSlotPlannerOpen] = useState(false);
  const [slotPlannerPoint, setSlotPlannerPoint] = useState<{ latitude: number; longitude: number } | null>(null);
  const [slotPlanningMap, setSlotPlanningMap] = useState<SlotPlanningMapPresentation | null>(null);
  const [inspectorWidth, setInspectorWidth] = useState(savedInspectorWidth);
  const [inspectorOpen, setInspectorOpen] = useState(() => window.innerWidth > 760);
  const [routesNeedRefresh, setRoutesNeedRefresh] = useState(false);
  const [loadedRequestPage, setLoadedRequestPage] = useState<{
    contextKey: string;
    requests: LogisticsRequest[];
    nextCursor: UUID | null;
  } | null>(null);
  const [requestsLoadingMore, setRequestsLoadingMore] = useState(false);
  const requestPageContextRef = useRef('');
  const requestPageGenerationRef = useRef(0);
  const surfacedPlanIdRef = useRef<UUID | null>(null);
  const surfacedNotificationIdsRef = useRef(new Set<UUID>());
  const representativeRequestBaselineRef = useRef<{
    contextKey: string;
    requestIds: Set<UUID>;
  } | null>(null);
  const warehouseSelectorRef = useRef<HTMLDivElement>(null);
  const warehouseSelectionWasExplicitRef = useRef(warehouseId !== null);
  const mode = useUiStore((state) => state.mode);
  const mapLayout = useMapLayout(inspectorWidth, inspectorOpen || slotPlannerOpen, mode === 'SIMULATION' && plan !== null);
  const setMode = useUiStore((state) => state.setMode);
  const setSection = useUiStore((state) => state.setSection);
  const mapTool = useUiStore((state) => state.mapTool);
  const setMapTool = useUiStore((state) => state.setMapTool);
  const selected = useUiStore((state) => state.selected);
  const setSelected = useUiStore((state) => state.setSelected);
  const toast = useUiStore((state) => state.toast);
  const appendTrace = useUiStore((state) => state.appendTraceEvent);
  const clearTrace = useUiStore((state) => state.clearTraceEvents);
  const traceEvents = useUiStore((state) => state.traceEvents);
  const simulationTimestamp = useUiStore((state) => state.simulationTimestamp);
  const setSimulationTimestamp = useUiStore((state) => state.setSimulationTimestamp);
  const simulationPlaying = useUiStore((state) => state.simulationPlaying);
  const setSimulationPlaying = useUiStore((state) => state.setSimulationPlaying);
  const simulationSpeed = useUiStore((state) => state.simulationSpeed);
  const setSimulationSpeed = useUiStore((state) => state.setSimulationSpeed);
  const simulationOverrides = useUiStore((state) => state.simulationOverrides);
  const addSimulationOverride = useUiStore((state) => state.addSimulationOverride);
  const clearSimulationOverrides = useUiStore((state) => state.clearSimulationOverrides);

  const clearLocalPlanningState = useCallback(() => {
    queryClient.removeQueries({ queryKey: ['plan'] });
    queryClient.removeQueries({ queryKey: ['optimization-run'] });
    queryClient.removeQueries({ queryKey: ['automatic-plan'] });
    setPlanId(null);
    setPlan(null);
    setRunId(null);
    setValidation(null);
    setRoutesNeedRefresh(false);
    surfacedPlanIdRef.current = null;
    clearTrace();
    clearSimulationOverrides();
    setSimulationPlaying(false);
    setSimulationTimestamp(null);
    setSelected(null);
  }, [clearSimulationOverrides, clearTrace, queryClient, setSelected, setSimulationPlaying, setSimulationTimestamp]);

  const closeSlotPlanner = useCallback(() => {
    setSlotPlannerOpen(false);
    setSlotPlanningMap(null);
  }, []);

  const resetLoadedRequestPages = useCallback(() => {
    requestPageGenerationRef.current += 1;
    setLoadedRequestPage(null);
    setRequestsLoadingMore(false);
  }, []);

  useEffect(() => {
    if (!warehouseSelectorOpen) return undefined;
    const closeOnPointerDown = (event: PointerEvent) => {
      if (!warehouseSelectorRef.current?.contains(event.target as Node)) setWarehouseSelectorOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setWarehouseSelectorOpen(false);
    };
    document.addEventListener('pointerdown', closeOnPointerDown);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeOnPointerDown);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [warehouseSelectorOpen]);

  const selectPlanningDate = useCallback((date: string) => {
    setPlanningDate(date);
    if (warehouseId) savePlanningDate(warehouseId, date);
    if (plan?.date !== date) {
      setPlanId(null);
      setPlan(null);
      setValidation(null);
      setRoutesNeedRefresh(false);
      clearSimulationOverrides();
    }
  }, [clearSimulationOverrides, plan?.date, warehouseId]);

  const activateWarehouse = useCallback((nextWarehouseId: UUID) => {
    warehouseSelectionWasExplicitRef.current = true;
    saveWarehouseSelection(nextWarehouseId);
    setWarehouseId(nextWarehouseId);
  }, []);

  const resizeInspector = useCallback((requestedWidth: number) => {
    const width = Math.min(
      Math.max(MIN_INSPECTOR_WIDTH, Math.floor(window.innerWidth / 2)),
      Math.max(MIN_INSPECTOR_WIDTH, Math.round(requestedWidth)),
    );
    setInspectorWidth(width);
    try {
      window.localStorage.setItem('rwms:logistics:inspector-width', String(width));
    } catch {
      // The in-memory preference remains usable when storage is blocked.
    }
  }, []);

  const warehousesQuery = useQuery({ queryKey: ['warehouses'], queryFn: api.listWarehouses, refetchInterval: 15_000 });
  const warehouseKindsQuery = useQuery({ queryKey: ['warehouse-kinds'], queryFn: ({ signal }) => loadWarehouseKinds(signal), staleTime: 60_000, refetchInterval: 60_000, retry: false, enabled: Boolean(warehousesQuery.data?.length) });
  const availableWarehousesQuery = useQuery({ queryKey: ['available-warehouses'], queryFn: api.listAvailableWarehouses, refetchInterval: 15_000 });
  useEffect(() => {
    const warehouses = warehousesQuery.data;
    if (!warehouses?.length) return;
    if (warehouseId && warehouses.some((warehouse) => warehouse.id === warehouseId)) return;
    if (warehouseId) warehouseSelectionWasExplicitRef.current = false;
    const initial = initialWarehouseSelection(null, warehouses);
    if (initial !== null) setWarehouseId(initial);
  }, [warehouseId, warehousesQuery.data]);

  const selectedWarehouseMetadata = warehousesQuery.data?.find(
    (candidate) => candidate.id === warehouseId,
  );
  const planningDateReady = Boolean(
    warehouseId
      && planningDate
      && selectedWarehouseMetadata
      && planningDateContext?.warehouseId === warehouseId
      && planningDateContext.timeZone === selectedWarehouseMetadata.timezone,
  );
  useEffect(() => {
    if (!warehouseId || !selectedWarehouseMetadata) return;
    if (
      planningDateContext?.warehouseId === warehouseId
      && planningDateContext.timeZone === selectedWarehouseMetadata.timezone
    ) return;
    setPlanningDateContext({
      warehouseId,
      timeZone: selectedWarehouseMetadata.timezone,
    });
    setPlanningDate(
      savedPlanningDate(warehouseId)
        ?? dateInTimeZone(new Date(), selectedWarehouseMetadata.timezone),
    );
    clearLocalPlanningState();
  }, [clearLocalPlanningState, planningDateContext, selectedWarehouseMetadata, warehouseId]);

  const workspaceQuery = useQuery({
    queryKey: ['workspace', warehouseId, planningDate],
    queryFn: () => getWarehouseWorkspace(warehouseId as UUID, {
      planningDate,
      requestLimit: WORKSPACE_REQUEST_PAGE_LIMIT,
    }),
    enabled: planningDateReady,
    refetchInterval: 15_000,
  });
  const baseWorkspace = workspaceQuery.data ?? null;
  const workspaceWarehouseId = baseWorkspace?.warehouse.id;

  const requestPageContextKey = `${warehouseId ?? ''}:${planningDate}`;
  requestPageContextRef.current = requestPageContextKey;
  useEffect(() => {
    resetLoadedRequestPages();
  }, [requestPageContextKey, resetLoadedRequestPages]);
  const workspace = useMemo(() => {
    if (!baseWorkspace || loadedRequestPage?.contextKey !== requestPageContextKey) return baseWorkspace;
    const requests = new Map(baseWorkspace.requests.map((request) => [request.id, request]));
    loadedRequestPage.requests.forEach((request) => {
      if (!requests.has(request.id)) requests.set(request.id, request);
    });
    return {
      ...baseWorkspace,
      requests: [...requests.values()],
      request_next_cursor: loadedRequestPage.nextCursor,
    };
  }, [baseWorkspace, loadedRequestPage, requestPageContextKey]);
  const planningWarehouseId = workspace?.planning_root_warehouse_id ?? workspaceWarehouseId;
  const planningTimeZone = workspace
    ? workspace.warehouses.find((candidate) => candidate.id === planningWarehouseId)?.timezone
      ?? workspace.warehouse.timezone
    : null;
  useEffect(() => {
    if (
      !workspace?.warehouse.representative
      || warehouseSelectionWasExplicitRef.current
      || !workspace.planning_root_warehouse_id
      || workspace.planning_root_warehouse_id === workspace.warehouse.id
      || !warehousesQuery.data?.some((warehouse) => warehouse.id === workspace.planning_root_warehouse_id)
    ) return;
    warehouseSelectionWasExplicitRef.current = true;
    saveWarehouseSelection(workspace.planning_root_warehouse_id);
    setWarehouseId(workspace.planning_root_warehouse_id);
  }, [warehousesQuery.data, workspace]);
  const warehouseSelectorOptions = useMemo(() => {
    if (!workspace) return [];
    const knownWarehouses = workspace.warehouses.some((candidate) => candidate.id === workspace.warehouse.id)
      ? workspace.warehouses
      : [...workspace.warehouses, workspace.warehouse];
    const rootId = workspace.planning_root_warehouse_id
      ?? (!workspace.warehouse.representative ? workspace.warehouse.id : null);
    const groupWarehouseIds = new Set(workspace.planning_group_warehouse_ids ?? [workspace.warehouse.id]);
    if (rootId) groupWarehouseIds.add(rootId);
    const options: Warehouse[] = [];
    const append = (candidate: Warehouse | undefined) => {
      if (candidate && !options.some((option) => option.id === candidate.id)) options.push(candidate);
    };
    append(knownWarehouses.find((candidate) => candidate.id === rootId));
    knownWarehouses
      .filter((candidate) => candidate.representative && groupWarehouseIds.has(candidate.id))
      .forEach(append);
    if (workspace.warehouse.representative) append(workspace.warehouse);
    knownWarehouses
      .filter((candidate) => !candidate.representative && candidate.routing_ready)
      .forEach(append);
    return options;
  }, [workspace]);
  useEffect(() => {
    if (!workspace) return;
    const planningGroupWarehouseIds = new Set(
      workspace.planning_group_warehouse_ids ?? [workspace.warehouse.id],
    );
    const planningRootWarehouseId = workspace.planning_root_warehouse_id ?? workspace.warehouse.id;
    const notificationContextKey = [
      planningRootWarehouseId,
      ...[...planningGroupWarehouseIds].sort(),
    ].join(':');
    const representativeWarehouses = new Map(
      workspace.warehouses
        .filter((candidate) => candidate.representative && planningGroupWarehouseIds.has(candidate.id))
        .map((candidate) => [candidate.id, candidate]),
    );
    const representativeRequests = workspace.requests.filter((request) => (
      representativeWarehouses.has(request.warehouse_id) && request.source_system === 'RWMS'
    ));
    const baseline = representativeRequestBaselineRef.current;
    if (!baseline || baseline.contextKey !== notificationContextKey) {
      representativeRequestBaselineRef.current = {
        contextKey: notificationContextKey,
        requestIds: new Set(representativeRequests.map((request) => request.id)),
      };
      return;
    }
    representativeRequests.forEach((request) => {
      const representativeWarehouse = representativeWarehouses.get(request.warehouse_id);
      if (!representativeWarehouse) return;
      const requestDate = request.scheduled_date ?? [...request.date_options]
        .sort((left, right) => left.priority - right.priority || left.date.localeCompare(right.date))[0]?.date;
      if (!requestDate || baseline.requestIds.has(request.id)) return;
      baseline.requestIds.add(request.id);
      toast({
        tone: 'info',
        replacementKey: `representative-request-${request.id}`,
        title: `Новая заявка · ${representativeWarehouse.name}`,
        detail: `${formatDate(requestDate)} · ${request.name}`,
        action: {
          label: 'Открыть заявку',
          onActivate: () => {
            setMode('PLAN_DAY');
            selectPlanningDate(requestDate);
            setSection('REQUESTS');
            setMapTool('SELECT');
            setSelected({ kind: 'request', id: request.id });
          },
        },
      });
    });
  }, [selectPlanningDate, setMapTool, setMode, setSection, setSelected, toast, workspace]);
  const planQuery = useQuery({
    queryKey: ['plan', planId, workspace?.warehouse.updated_at],
    queryFn: () => api.getPlan(planId as UUID, workspace!),
    enabled: Boolean(planId && workspace),
  });
  const automaticPlanQuery = useQuery({
    queryKey: [
      'automatic-plan',
      planningWarehouseId,
      planningDate,
    ],
    queryFn: ({ signal }) => api.ensureAutomaticPlan(
      planningWarehouseId as UUID,
      planningDate,
      workspace!,
      signal,
    ),
    enabled: Boolean(
      planningDateReady
        && planningWarehouseId
        && workspace
        && !workspaceQuery.isFetching,
    ),
    retry: false,
    staleTime: Infinity,
  });
  const planningDayStatusQuery = useQuery({
    queryKey: ['planning-day-status', planningWarehouseId, planningDate],
    queryFn: () => api.getPlanningDayStatus(planningWarehouseId as UUID, planningDate),
    enabled: Boolean(planningDateReady && planningWarehouseId && workspace),
    retry: false,
  });
  const acceptingRequests = planningDayStatusQuery.data?.accepting_requests ?? false;
  useEffect(() => {
    if (!planningDayStatusQuery.error) return;
    const feedback = actionErrorFeedback(planningDayStatusQuery.error);
    toast({
      tone: 'error',
      title: 'Не удалось проверить приём доставок',
      detail: feedback.detail ?? feedback.title,
    });
  }, [planningDayStatusQuery.error, toast]);
  useEffect(() => {
    if (planningDayStatusQuery.data?.accepting_requests !== false) return;
    closeSlotPlanner();
  }, [closeSlotPlanner, planningDayStatusQuery.data?.accepting_requests]);
  useEffect(() => {
    const automaticPlan = automaticPlanQuery.data;
    if (automaticPlan === undefined) return;
    if (automaticPlan === null) {
      setRoutesNeedRefresh(false);
      if (plan?.date === planningDate) {
        setPlanId(null);
        setPlan(null);
        setValidation(null);
      }
      return;
    }
    setPlanId(automaticPlan.id);
    setPlan(automaticPlan);
    setRoutesNeedRefresh(false);
    queryClient.setQueryData(
      ['plan', automaticPlan.id, workspace?.warehouse.updated_at],
      automaticPlan,
    );
  }, [automaticPlanQuery.data, plan?.date, planningDate, queryClient, workspace?.warehouse.updated_at]);
  useEffect(() => {
    if (!automaticPlanQuery.error) return;
    const feedback = actionErrorFeedback(automaticPlanQuery.error);
    toast({
      tone: 'error',
      title: 'Автоплан не рассчитан',
      detail: feedback.detail ?? feedback.title,
    });
  }, [automaticPlanQuery.error, toast]);
  useEffect(() => {
    const loadedPlan = planQuery.data;
    if (!loadedPlan) return;
    setPlan(loadedPlan);
    if (surfacedPlanIdRef.current === loadedPlan.id) return;
    surfacedPlanIdRef.current = loadedPlan.id;
    const cycleCount = loadedPlan.driver_routes.reduce((total, route) => total + route.cycles.length, 0);
    if (cycleCount === 0 && loadedPlan.unassigned.length > 0) {
      toast({
        tone: 'warning',
        replacementKey: 'automatic-plan-result',
        title: 'Допустимые маршруты не найдены',
        detail: `${loadedPlan.unassigned.length} задач не распределено. Открыты конкретные причины и рекомендации.`,
      });
    } else {
      toast({
        tone: 'success',
        replacementKey: 'automatic-plan-result',
        title: `План готов: ${cycleCount} ${cycleCount === 1 ? 'рейс' : cycleCount < 5 ? 'рейса' : 'рейсов'}`,
        detail: loadedPlan.unassigned.length > 0 ? `Не распределено задач: ${loadedPlan.unassigned.length}.` : 'Все доступные задачи распределены.',
      });
    }
  }, [planQuery.data, toast]);
  useEffect(() => {
    plan?.notification_logs?.forEach((log) => {
      if (surfacedNotificationIdsRef.current.has(log.id)) return;
      surfacedNotificationIdsRef.current.add(log.id);
      toast({
        tone: 'success',
        replacementKey: `plan-notification-${log.id}`,
        title: `Уведомление отправлено: ${log.recipient_name || 'контактное лицо'}`,
        detail: log.message,
      });
    });
  }, [plan?.notification_logs, toast]);

  const runQuery = useQuery({
    queryKey: ['optimization-run', runId],
    queryFn: () => api.getOptimizationRun(runId as UUID, workspace!.warehouse.settings),
    enabled: Boolean(runId && workspace),
    refetchInterval: (query) => isTerminal(query.state.data?.status) ? false : 900,
  });
  useEffect(() => {
    const run = runQuery.data;
    if (!run || !isTerminal(run.status)) return;
    if (run.plan_id) {
      setPlanId(run.plan_id);
      setMode('PLAN_DAY');
      setSection(run.status === 'FAILED' ? 'UNASSIGNED' : 'PLAN_DAY');
      if (run.status !== 'COMPLETED') {
        const detail = run.status === 'TIMED_OUT' ? 'Показан лучший найденный план.' : userFacingErrorDetail(run.error_message ? new Error(run.error_message) : null, 'Планировщик не смог построить допустимый план.');
        toast({ tone: run.status === 'TIMED_OUT' ? 'warning' : 'error', title: run.status === 'TIMED_OUT' ? 'Лимит времени достигнут' : 'Оптимизация не завершена', ...(detail ? { detail } : {}) });
      }
    } else if (run.status === 'FAILED') {
      toast({ tone: 'error', title: 'Оптимизация завершилась с ошибкой', detail: userFacingErrorDetail(run.error_message ? new Error(run.error_message) : null, 'План не создан. Проверьте условия доставок и смены водителей.') });
    } else if (run.status === 'CANCELLED') {
      toast({ tone: 'info', title: 'Оптимизация отменена', detail: 'Существующие сохранённые планы не изменены.' });
    } else if (run.status === 'TIMED_OUT') {
      toast({ tone: 'warning', title: 'Лимит времени достигнут', detail: 'Сервис планирования не успел сохранить допустимый план.' });
    }
  }, [runQuery.data, setMode, setSection, toast]);

  useEffect(() => {
    if (!runId || isTerminal(runQuery.data?.status)) return;
    let stop: () => void = () => undefined;
    stop = startOptimizationEventStream(runId, {
      onEvent: (event) => {
        if (event.event === 'run_terminal') {
          stop();
          void queryClient.invalidateQueries({ queryKey: ['optimization-run', runId] });
          return;
        }
        const trace = parseTraceEvent(event.data, runId);
        if (trace) appendTrace(trace);
      },
      onError: () => stop(),
    });
    return stop;
  }, [appendTrace, queryClient, runId, runQuery.data?.status]);

  const currentRun = useMemo(() => {
    if (!runQuery.data) return null;
    const last = traceEvents.at(-1);
    const phase = last?.payload.phase;
    const progress = last?.payload.progress;
    const phaseIndex = typeof phase === 'string' ? TRACE_PHASES.indexOf(phase as typeof TRACE_PHASES[number]) : -1;
    return {
      ...runQuery.data,
      ...(typeof phase === 'string' ? { phase } : {}),
      ...(typeof progress === 'number'
        ? { progress: progress > 1 ? progress / 100 : progress }
        : phaseIndex >= 0
          ? { progress: Math.min(1, (phaseIndex + (last?.event_type === 'phase_completed' ? 1 : 0.35)) / TRACE_PHASES.length) }
          : {}),
    };
  }, [runQuery.data, traceEvents]);

  const actionMutation = useMutation({ mutationFn: async (operation: () => Promise<unknown>) => operation() });
  const busy = actionMutation.isPending;

  const refresh = useCallback(async () => {
    resetLoadedRequestPages();
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['warehouses'] }),
      queryClient.invalidateQueries({ queryKey: ['available-warehouses'] }),
    ]);
    if (warehouseId) {
      await queryClient.invalidateQueries({ queryKey: ['workspace', warehouseId] });
    }
    if (planningWarehouseId) {
      await queryClient.invalidateQueries({ queryKey: ['planning-day-status', planningWarehouseId] });
    }
  }, [planningWarehouseId, queryClient, resetLoadedRequestPages, warehouseId]);

  const loadMoreRequests = useCallback(async (): Promise<void> => {
    if (!warehouseId || !baseWorkspace || requestsLoadingMore) return;
    const cursor = loadedRequestPage?.contextKey === requestPageContextKey
      ? loadedRequestPage.nextCursor
      : baseWorkspace.request_next_cursor;
    if (!cursor) return;
    const contextKey = requestPageContextKey;
    const generation = requestPageGenerationRef.current;
    const isCurrentPageLoad = () => requestPageContextRef.current === contextKey
      && requestPageGenerationRef.current === generation;
    setRequestsLoadingMore(true);
    try {
      const page = await getWarehouseWorkspace(warehouseId, {
        planningDate,
        requestLimit: WORKSPACE_REQUEST_PAGE_LIMIT,
        requestCursor: cursor,
      });
      if (!isCurrentPageLoad()) return;
      setLoadedRequestPage((current) => {
        if (!isCurrentPageLoad()) return current;
        const requests = new Map(
          (current?.contextKey === contextKey ? current.requests : []).map((request) => [request.id, request]),
        );
        page.requests.forEach((request) => requests.set(request.id, request));
        return { contextKey, requests: [...requests.values()], nextCursor: page.request_next_cursor };
      });
    } catch (error: unknown) {
      if (isCurrentPageLoad()) {
        const feedback = actionErrorFeedback(error);
        toast({ tone: 'error', title: 'Не удалось загрузить следующую страницу заявок', detail: feedback.detail ?? feedback.title });
      }
    } finally {
      if (isCurrentPageLoad()) setRequestsLoadingMore(false);
    }
  }, [baseWorkspace, loadedRequestPage, planningDate, requestPageContextKey, requestsLoadingMore, toast, warehouseId]);

  const reportActionError = useCallback(async (error: unknown): Promise<void> => {
    const { refreshPlan, ...message } = actionErrorFeedback(error);
    toast(message);
    if (refreshPlan && planId) await queryClient.invalidateQueries({ queryKey: ['plan', planId] });
  }, [planId, queryClient, toast]);

  const execute = useCallback(async <T,>(operation: () => Promise<T>, success?: string): Promise<T> => {
    try {
      const result = await actionMutation.mutateAsync(operation) as T;
      if (success) toast({ tone: 'success', title: success });
      return result;
    } catch (error: unknown) {
      await reportActionError(error);
      throw error;
    }
  }, [actionMutation, reportActionError, toast]);

  const flagCurrentRoutesForRefresh = useCallback((): boolean => {
    if (!plan || plan.date !== planningDate || plan.status === 'CONFIRMED') return false;
    setRoutesNeedRefresh(true);
    setValidation(null);
    return true;
  }, [plan, planningDate]);

  const applyPlanningDayClosureState = useCallback(async (
    targetWarehouseId: UUID,
    targetDate: string,
    status: Awaited<ReturnType<typeof api.getPlanningDayStatus>>,
  ): Promise<void> => {
    queryClient.setQueryData(['planning-day-status', targetWarehouseId, targetDate], status);
    queryClient.removeQueries({ queryKey: ['plan'] });
    setPlanId(null);
    setPlan(null);
    setRunId(null);
    setValidation(null);
    surfacedPlanIdRef.current = null;
    clearTrace();
    clearSimulationOverrides();
    setSimulationPlaying(false);
    setSimulationTimestamp(null);
    closeSlotPlanner();
    setDialog(null);
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['planning-day-status', targetWarehouseId, targetDate] }),
      queryClient.invalidateQueries({ queryKey: ['automatic-plan', targetWarehouseId, targetDate] }),
    ]);
  }, [
    clearSimulationOverrides,
    clearTrace,
    closeSlotPlanner,
    queryClient,
    setSimulationPlaying,
    setSimulationTimestamp,
  ]);

  const openCreate = (kind: EntityKind) => {
    if (!workspace) return;
    const intentKey = crypto.randomUUID();
    if (kind === 'vehicle') setDialog({ kind: 'vehicle', intentKey });
    else if (kind === 'trailer') setDialog({ kind: 'trailer', intentKey });
    else if (kind === 'shift') setDialog({ kind: 'shift', intentKey });
    else if (kind === 'request') setDialog({ kind: 'request', requestType: 'DELIVERY', intentKey });
  };

  const openEdit = (kind: EntityKind, value: EditableEntity) => {
    if (kind === 'warehouse') setDialog({ kind: 'warehouse', value: value as Warehouse });
    else if (kind === 'vehicle') {
      const currentVehicle = workspace?.vehicles.find((candidate) => candidate.id === value.id) ?? value as Vehicle;
      setDialog({ kind: 'vehicle', value: currentVehicle, intentKey: crypto.randomUUID() });
    }
    else if (kind === 'trailer') setDialog({ kind: 'trailer', value: value as Trailer, intentKey: crypto.randomUUID() });
    else if (kind === 'shift') setDialog({ kind: 'shift', value: value as DriverShift, intentKey: crypto.randomUUID() });
    else if (kind === 'request') setDialog({ kind: 'request', value: value as LogisticsRequest, requestType: (value as LogisticsRequest).type, intentKey: crypto.randomUUID() });
  };

  const handleMapPoint = useCallback((kind: 'request', longitude: number, latitude: number) => {
    const intentKey = crypto.randomUUID();
    void api.reverseGeocode(latitude, longitude).then((resolved) => {
      setDialog({ kind, point: { longitude, latitude }, address: resolved.address, requestType: mapTool === 'ADD_PICKUP' ? 'PICKUP' : 'DELIVERY', intentKey });
    }).catch(() => {
      setDialog({ kind, point: { longitude, latitude }, requestType: mapTool === 'ADD_PICKUP' ? 'PICKUP' : 'DELIVERY', intentKey });
    });
  }, [mapTool]);

  const handleMapError = useCallback((message: string) => {
    toast({ tone: 'warning', title: 'Карта', detail: message });
  }, [toast]);
  const handleRequestMoveDraft = useCallback((requestId: UUID, longitude: number, latitude: number) => {
    const request = workspace?.requests.find((candidate) => candidate.id === requestId);
    if (!request) return;
    if (request.source_system === 'RWMS') {
      toast({ tone: 'warning', title: 'Координаты принадлежат RWMS', detail: 'Исправьте заказ в RWMS и повторите синхронизацию.' });
      return;
    }
    setDialog({ kind: 'request', value: request, point: { longitude, latitude }, requestType: request.type, intentKey: crypto.randomUUID() });
    toast({ tone: 'info', title: 'Новые координаты не сохранены', detail: 'Проверьте форму и сохраните: маршрут и доступность по изохронам будут проверены заново.' });
  }, [toast, workspace]);

  const openUnassignedReschedule = useCallback((requestId: UUID) => {
    const request = workspace?.requests.find((candidate) => candidate.id === requestId);
    if (!request) {
      toast({ tone: 'warning', title: 'Заявка уже изменилась', detail: 'Обновите рабочую область и повторите перенос.' });
      return;
    }
    if (request.type === 'PICKUP') {
      setDialog({ kind: 'request', value: request, requestType: 'PICKUP', intentKey: crypto.randomUUID() });
      return;
    }
    if (request.source_system !== 'RWMS' || !request.external_id) {
      toast({
        tone: 'warning',
        title: 'Для этой доставки нет клиентского заказа RWMS',
        detail: 'Ручной ввод временного окна из нераспределённых отключён. Измените исходную локальную заявку в разделе доставок.',
      });
      return;
    }
    setDialog({ kind: 'delivery-reschedule', requestId });
  }, [toast, workspace]);

  const finishDeliveryReschedule = async (result: RequestRescheduleResultRead) => {
    clearLocalPlanningState();
    await Promise.all([
      refresh(),
      planningWarehouseId
        ? queryClient.invalidateQueries({ queryKey: ['automatic-plan', planningWarehouseId, planningDate] })
        : Promise.resolve(),
      planningWarehouseId && result.scheduled_date !== planningDate
        ? queryClient.invalidateQueries({ queryKey: ['automatic-plan', planningWarehouseId, result.scheduled_date] })
        : Promise.resolve(),
    ]);
    setDialog(null);
    toast({
      tone: 'success',
      title: 'Доставка перенесена в подтверждённый слот',
      detail: `Новая дата: ${formatDate(result.scheduled_date)}. План выбранного дня обновляется.`,
    });
  };
  const cancelOptimization = async () => {
    if (!runId || !workspace) return;
    await execute(async () => {
      const cancelled = await api.cancelOptimizationRun(runId, workspace.warehouse.settings);
      queryClient.setQueryData(['optimization-run', runId], cancelled);
    }, 'Отмена оптимизации запрошена').catch(() => undefined);
  };

  const confirmPlan = async (emptyPositioningReason?: string) => {
    if (!workspace || !plan) return;
    const requiresReason = plan.driver_routes.some(
      (route) => route.cross_warehouse_service?.outbound_positioning_empty
        && route.cross_warehouse_service.empty_positioning_reason_required,
    );
    if (requiresReason && !emptyPositioningReason) {
      setDialog({ kind: 'confirm-cross-warehouse-plan' });
      return;
    }
    await execute(async () => {
      const confirmed = await api.confirmPlan(
        plan.id,
        plan.version,
        true,
        workspace,
        emptyPositioningReason,
      );
      setPlan(confirmed);
      setPlanId(confirmed.id);
      setValidation(null);
      await refresh();
      setDialog(null);
    }, 'План утверждён').catch(() => undefined);
  };

  const resetManualChanges = async () => {
    if (!workspace || !plan || plan.status === 'CONFIRMED') return;
    await execute(async () => {
      const reset = await api.resetManualChanges(plan.id, plan.version, workspace);
      setPlan(reset);
      setPlanId(reset.id);
      setValidation(null);
    }, 'Ручные изменения отменены').catch(() => undefined);
  };

  const moveTask = async (move: PlanMove) => {
    if (!workspace || !plan) return;
    await execute(async () => {
      const result = await api.manualChange(plan.id, {
        expected_version: plan.version,
        change_type: move.kind,
        task_id: move.taskId,
        ...(move.sourceCycleId ? { source_cycle_id: move.sourceCycleId } : {}),
        target_cycle_id: move.targetCycleId,
        target_sequence: move.targetSequence,
        reason: 'Ручное перемещение в редакторе маршрута',
      }, workspace, plan);
      setValidation(result);
      if (result.updated_schedule) setPlan(result.updated_schedule);
      else await planQuery.refetch();
    }, 'Изменение проверено и применено').catch(() => undefined);
  };

  const refreshRoutes = async () => {
    if (!workspace || !plan || plan.status === 'CONFIRMED') return;
    await execute(async () => {
      const refreshed = await api.ensureAutomaticPlan(
        planningWarehouseId as UUID,
        planningDate,
        workspace,
      );
      if (!refreshed) throw new Error('Для выбранного дня пока недостаточно данных для построения маршрутов');
      setPlanId(refreshed.id);
      setPlan(refreshed);
      setValidation(null);
      setRoutesNeedRefresh(false);
      queryClient.setQueryData(
        ['automatic-plan', planningWarehouseId, planningDate],
        refreshed,
      );
      queryClient.setQueryData(
        ['plan', refreshed.id, workspace.warehouse.updated_at],
        refreshed,
      );
    }, 'Маршруты обновлены; ручной порядок сохранён').catch(() => undefined);
  };

  const saveRequestPlanning = async (requestId: UUID, input: RequestPlanningDetailsInput) => {
    await execute(async () => {
      const refreshExistingPlan = plan?.date === input.date && plan.status !== 'CONFIRMED';
      const expectedVersion = workspace?.requests.find((request) => request.id === requestId)?.version;
      if (expectedVersion === undefined) throw new Error('Заявка больше не доступна в текущей рабочей области');
      await api.saveRequestPlanningDetails(requestId, input, expectedVersion);
      await refresh();
      if (refreshExistingPlan) {
        flagCurrentRoutesForRefresh();
      } else {
        await queryClient.invalidateQueries({ queryKey: ['automatic-plan', planningWarehouseId, input.date] });
      }
    }, 'Условия доставки сохранены').catch(() => undefined);
  };

  const splitRequestIntoSubtasks = async (requestId: UUID, quantities: number[]) => {
    await execute(async () => {
      const refreshExistingPlan = plan?.date === planningDate && plan.status !== 'CONFIRMED';
      const expectedVersion = workspace?.requests.find((request) => request.id === requestId)?.version;
      if (expectedVersion === undefined) throw new Error('Заявка больше не доступна в текущей рабочей области');
      await api.splitRequest(requestId, quantities, expectedVersion);
      await refresh();
      if (refreshExistingPlan) {
        flagCurrentRoutesForRefresh();
      } else {
        await queryClient.invalidateQueries({ queryKey: ['automatic-plan', planningWarehouseId, planningDate] });
      }
    }, `Созданы подзадачи: ${quantities.join(' + ')}`).catch(() => undefined);
  };

  const toggleCycleLock = async (cycle: RouteCycle) => {
    if (!workspace || !plan) return;
    await execute(async () => {
      setPlan(await api.patchCycle(plan.id, cycle.id, { expected_version: plan.version, locked: !cycle.locked, reason: 'Изменение блокировки в редакторе маршрута' }, workspace));
    }, cycle.locked ? 'Цикл разблокирован' : 'Цикл заблокирован').catch(() => undefined);
  };

  const simulationState = useMemo(() => {
    if (!plan || simulationTimestamp === null) return null;
    return deriveSimulationState(plan, simulationTimestamp, simulationOverrides);
  }, [plan, simulationOverrides, simulationTimestamp]);
  useEffect(() => {
    if (mode !== 'SIMULATION' || !plan) return;
    const bounds = planTimeBounds(plan);
    if (bounds && (simulationTimestamp === null || simulationTimestamp < bounds.start || simulationTimestamp > bounds.end)) setSimulationTimestamp(bounds.start);
  }, [mode, plan, setSimulationTimestamp, simulationTimestamp]);

  const changeMode = (nextMode: 'PLAN_DAY' | 'SIMULATION') => {
    if (nextMode === 'SIMULATION' && plan && !plan.driver_routes.some((route) => route.cycles.length > 0)) {
      setSection('UNASSIGNED');
      toast({ tone: 'warning', title: 'Симуляцию пока запустить нельзя', detail: 'В плане нет ни одного рейса. Открыты причины нераспределения.' });
      return;
    }
    if (nextMode === 'SIMULATION' && !plan) {
      toast({ tone: 'info', title: 'Автоплан ещё не готов', detail: 'Заполните обязательные условия заявок выбранного дня.' });
      return;
    }
    setMode(nextMode);
    setSection('PLAN_DAY');
  };

  const generateWorkload = async (input: Parameters<typeof api.generateWorkload>[1]) => {
    if (!workspace) return;
    try {
      await actionMutation.mutateAsync(async () => {
        const result = await api.generateWorkload(
          workspace.planning_root_warehouse_id ?? workspace.warehouse.id,
          input,
        );
        clearLocalPlanningState();
        selectPlanningDate(result.start_date);
        await refresh();
        setRunId(result.auto_plan_run_ids?.at(-1) ?? null);
        setPlanId(result.auto_plan_ids?.at(-1) ?? null);
        setDialog(null);
        toast({
          tone: 'success',
          title: result.replaced_requests > 0 || result.deleted_plans > 0
            ? `Нагрузка заменена: ${result.created_requests} позиций`
            : `Нагрузка создана: ${result.created_requests} позиций`,
          detail: `Тестовая нагрузка: ${result.created_deliveries} доставок · ${result.created_pickups} вывозов · ${formatDate(result.start_date)}–${formatDate(result.end_date)} · заменено прежних позиций: ${result.replaced_requests} · удалено планов: ${result.deleted_plans}${result.capacity_projection_status === 'FAILED' && result.capacity_projection_warning ? ` · Внимание: ${userFacingErrorDetail(new Error(result.capacity_projection_warning), 'Не удалось обновить доступную мощность RWMS.')}` : ''}`,
        });
      });
    } catch (error: unknown) {
      if (error instanceof ApiError && error.code?.startsWith('RWMS_')) {
        // The generator commits its deterministic local mutation before the
        // retryable capacity publication. Reconcile the authoritative local
        // state so a remote outage cannot leave a successful generation hidden.
        clearLocalPlanningState();
        selectPlanningDate(input.start_date);
        await refresh();
        setDialog(null);
        const feedback = actionErrorFeedback(error);
        toast({
          tone: 'warning',
          title: 'Тестовая нагрузка сохранена; ёмкость не опубликована',
          detail: feedback.detail ?? feedback.title,
        });
        return;
      }
      await reportActionError(error);
    }
  };

  const scheduleRequestDate = async (requestId: UUID, date: string, addIfMissing: boolean) => {
    await execute(async () => {
      const expectedVersion = workspace?.requests.find((request) => request.id === requestId)?.version;
      if (expectedVersion === undefined) throw new Error('Заявка больше не доступна в текущей рабочей области');
      await api.scheduleRequest(requestId, { date, add_if_missing: addIfMissing }, expectedVersion);
      await refresh();
      flagCurrentRoutesForRefresh();
      selectPlanningDate(date);
    }, addIfMissing ? 'Новая дата согласована и назначена' : 'Доставка или вывоз назначены на выбранную дату').catch(() => undefined);
  };

  const unscheduleRequest = async (requestId: UUID) => {
    await execute(async () => {
      const expectedVersion = workspace?.requests.find((request) => request.id === requestId)?.version;
      if (expectedVersion === undefined) throw new Error('Заявка больше не доступна в текущей рабочей области');
      await api.scheduleRequest(requestId, { date: null }, expectedVersion);
      await refresh();
      flagCurrentRoutesForRefresh();
    }, 'Назначение снято; доставка или вывоз снова доступны во все согласованные даты').catch(() => undefined);
  };

  if (warehousesQuery.isLoading || availableWarehousesQuery.isLoading) return <div className="app-shell" style={{ placeItems: 'center' }}><Spinner label="Загружаем склады…" /><Toasts /></div>;
  if (warehousesQuery.isError || availableWarehousesQuery.isError) return <div className="app-shell" style={{ placeItems: 'center' }}><ErrorPanel title="Сервис недоступен" error={warehousesQuery.error ?? availableWarehousesQuery.error} onRetry={() => { void warehousesQuery.refetch(); void availableWarehousesQuery.refetch(); }} /><Toasts /></div>;
  if (!warehousesQuery.data?.length) {
    return (
      <div className="app-shell app-shell--bootstrap">
        <header className="topbar">
          <div className="topbar__brand"><Button className="brand-mark" aria-label="Логистика" title="Логистика">L</Button></div>
          <div />
          <div className="topbar__actions">
            <ThemeSwitch />
            <NotificationCenter />
          </div>
        </header>
        <div className="bootstrap-workspace" style={{ placeItems: 'center' }}>
          <EmptyState title="Склады RWMS синхронизируются" description="Склады с координатами появятся на карте автоматически. Ручное подключение больше не требуется." />
          <div className="warehouse-sync-list" aria-label="Склады RWMS">
            {(availableWarehousesQuery.data ?? []).map((candidate) => {
              const missingCoordinates = candidate.latitude === null || candidate.longitude === null;
              return <div className="detail-item" key={candidate.warehouse_id}><strong>{candidate.name}</strong><span>{missingCoordinates ? 'Нет координат в RWMS' : candidate.routing_ready ? 'Ожидает автоматической синхронизации' : candidate.routing_unavailable_reason ?? 'Недоступен для маршрутизации'}</span></div>;
            })}
          </div>
        </div>
        <Toasts />
      </div>
    );
  }
  if (!warehouseId) return <div className="app-shell" style={{ placeItems: 'center' }}><Spinner label="Выбираем склад…" /><Toasts /></div>;
  if (workspaceQuery.isError) return <div className="app-shell" style={{ placeItems: 'center' }}><ErrorPanel error={workspaceQuery.error} onRetry={() => void workspaceQuery.refetch()} /><Toasts /></div>;
  if (workspaceQuery.isLoading || !workspace) return <div className="app-shell" style={{ placeItems: 'center' }}><Spinner label="Загружаем рабочую область…" /><Toasts /></div>;

  const selectedOverrideRoute = dialog?.kind === 'simulation' ? plan?.driver_routes.find((route) => route.driver_shift_id === dialog.driverShiftId) : undefined;
  const contractorAssignmentRequest = dialog?.kind === 'contractor-assignment'
    ? workspace.requests.find((request) => request.id === dialog.requestId) ?? null
    : null;
  const deliveryRescheduleRequest = dialog?.kind === 'delivery-reschedule'
    ? workspace.requests.find((request) => request.id === dialog.requestId) ?? null
    : null;
  const contractorAssignmentWarehouse = contractorAssignmentRequest
    ? workspace.warehouses.find((warehouse) => warehouse.id === contractorAssignmentRequest.warehouse_id) ?? workspace.warehouse
    : null;
  const shiftWarehouseId = dialog?.kind === 'shift'
    ? dialog.value?.warehouse_id ?? workspace.warehouse.id
    : workspace.warehouse.id;
  const shiftDrivers = workspace.drivers.filter((driver) => driver.warehouse_id === shiftWarehouseId);
  const shiftVehicles = workspace.vehicles.filter((vehicle) => vehicle.warehouse_id === shiftWarehouseId);
  const capacityPublicationPending =
    workspace.warehouse.capacity_generation > workspace.warehouse.capacity_published_generation
    && (workspace.warehouse.capacity_publish_status === 'PENDING'
      || workspace.warehouse.capacity_publish_status === 'FAILED');
  const workspaceWarning = workspace.rwms_refresh_warning
    ? userFacingErrorDetail(new Error(workspace.rwms_refresh_warning), 'Не все заявки удалось обновить. Повторите загрузку позже.')
    : capacityPublicationPending
      ? 'Локальные настройки сохранены. Доступные слоты RWMS обновляются автоматически; повторно сохранять форму не нужно.'
      : null;
  const mainWarehouse = workspace.warehouses.find((candidate) => (
    candidate.id === (workspace.planning_root_warehouse_id ?? workspace.warehouse.id)
  ));

  return (
    <div className={`app-shell app-shell--map ${mode === 'SIMULATION' ? 'app-shell--simulation' : 'app-shell--plan'}`} style={mapLayout.style}>
      <div className="workspace-chrome" ref={mapLayout.chromeRef}>
      <header className="topbar">
        <div className="topbar__brand">
          <Button className="brand-mark" onClick={() => { setMode('PLAN_DAY'); setSection('WAREHOUSE'); setInspectorOpen(true); closeSlotPlanner(); setMapTool('SELECT'); setSelected({ kind: 'warehouse', id: warehouseId }); }} aria-label="Открыть склад" title="Открыть склад"><img src={logotypeUrl} width={26} height={25} alt="" /></Button>
          <div className="topbar__warehouse-selector" ref={warehouseSelectorRef}>
            <button
              type="button"
              className="topbar__warehouse-selector-trigger"
              aria-label="Склад логистической группы"
              aria-haspopup="listbox"
              aria-expanded={warehouseSelectorOpen}
              aria-controls="warehouse-group-options"
              onClick={() => setWarehouseSelectorOpen((current) => !current)}
            >
              <span className="topbar__warehouse-selector-copy">
                <strong>{warehouseOptionLabel(workspace.warehouse, mainWarehouse)}</strong>
                <WarehouseLocalTime timeZone={workspace.warehouse.timezone} />
              </span>
              <ChevronDown size={14} aria-hidden="true" />
            </button>
            {warehouseSelectorOpen ? (
              <div id="warehouse-group-options" className="topbar__warehouse-selector-popover" role="listbox" aria-label="Склад логистической группы">
                {warehouseSelectorOptions.map((candidate) => {
                  const selectedWarehouse = candidate.id === workspace.warehouse.id;
                  return (
                    <button
                      type="button"
                      role="option"
                      aria-selected={selectedWarehouse}
                      className={`topbar__warehouse-option${selectedWarehouse ? ' topbar__warehouse-option--selected' : ''}`}
                      key={candidate.id}
                      onClick={() => {
                        setWarehouseSelectorOpen(false);
                        activateWarehouse(candidate.id);
                      }}
                    >
                      <span>{warehouseOptionLabel(candidate, mainWarehouse)}</span>
                    </button>
                  );
                })}
              </div>
            ) : null}
          </div>
        </div>
        <div className="topbar__date">
          {routesNeedRefresh && plan && plan.status !== 'CONFIRMED' ? <Button variant="primary" disabled={busy} onClick={() => void refreshRoutes()}><RefreshCw size={15} aria-hidden="true" /><span>Обновить маршруты</span></Button> : null}
          <DatePicker className="topbar-date-picker" label="Дата планирования" value={planningDate} onChange={selectPlanningDate} />
        </div>
        <div className="topbar__actions">
          <Button aria-label={inspectorOpen && !slotPlannerOpen ? 'Скрыть панель логистики' : 'Открыть панель логистики'} aria-expanded={inspectorOpen && !slotPlannerOpen} aria-controls="logistics-inspector" onClick={() => { closeSlotPlanner(); setInspectorOpen(slotPlannerOpen || !inspectorOpen); }}><PanelRight size={16} aria-hidden="true" /></Button>
          <Button
            variant={acceptingRequests ? 'secondary' : 'ghost'}
            aria-label={planningDayStatusQuery.isPending ? 'Проверяем приём доставок' : planningDayStatusQuery.isError ? 'Статус приёма недоступен' : acceptingRequests ? 'Закрыть приём доставок' : 'Приём закрыт'}
            title="Управление приёмом доставок"
            disabled={busy || planningDayStatusQuery.isPending || !acceptingRequests}
            onClick={() => setDialog({ kind: 'close-planning-day', date: planningDate })}
          ><LockKeyhole size={15} aria-hidden="true" /><span>{planningDayStatusQuery.isPending ? 'Проверяем приём…' : planningDayStatusQuery.isError ? 'Статус приёма недоступен' : acceptingRequests ? 'Закрыть приём доставок' : 'Приём закрыт'}</span></Button>
          <Button aria-label="Проверить слот" title="Проверить слот" disabled={!acceptingRequests} variant={slotPlannerOpen ? 'primary' : 'secondary'} onClick={() => slotPlannerOpen ? closeSlotPlanner() : setSlotPlannerOpen(true)}><RouteIcon size={15} aria-hidden="true" /><span>Проверить слот</span></Button>
          {currentRun && !isTerminal(currentRun.status) ? <Button className="topbar__cancel" variant="danger" disabled={busy || currentRun.cancel_requested} onClick={() => void cancelOptimization()}><span>{currentRun.cancel_requested ? 'Отменяем…' : 'Отменить'}</span></Button> : null}
          <div className="segmented" aria-label="Режим приложения">{([['PLAN_DAY', 'План дня'], ['SIMULATION', 'Симуляция']] as const).map(([value, label]) => <button type="button" key={value} aria-pressed={mode === value} onClick={() => changeMode(value)}>{value === 'PLAN_DAY' ? <RouteIcon size={13} aria-hidden="true" /> : <PlayCircle size={13} aria-hidden="true" />}{label}</button>)}</div>
          <ThemeSwitch />
          <NotificationCenter />
        </div>
      </header>
      {workspaceWarning ? (
        <div className="workspace-refresh-warning" role="alert">
          <strong>{workspace.rwms_refresh_warning ? 'RWMS обновлён частично' : 'Слоты обновляются'}</strong>
          <span>{workspaceWarning}</span>
        </div>
      ) : null}
      </div>
      <div className="workspace">
        <Sidebar
          workspace={workspace}
          plan={plan}
          pendingActionCount={planningDayStatusQuery.data?.pending_action_count ?? 0}
          onNavigate={() => { closeSlotPlanner(); setInspectorOpen(true); }}
        />
        <MapCanvas
          workspace={workspace}
          plan={plan}
          simulation={simulationState}
          traceEvents={traceEvents}
          optimizationRun={currentRun}
          selected={selected}
          onSelect={setSelected}
          onWarehouseActivate={activateWarehouse}
          onPlacePoint={handleMapPoint}
          onRequestMoveDraft={handleRequestMoveDraft}
          onMapError={handleMapError}
          planningDate={planningDate}
          busy={busy}
          onScheduleRequestDate={(requestId, date, addIfMissing) => void scheduleRequestDate(requestId, date, addIfMissing)}
          onUnscheduleRequest={(requestId) => void unscheduleRequest(requestId)}
          onMoveTask={(move) => void moveTask(move)}
          planningCheck={slotPlanningMap}
          onPlanningCheckPoint={setSlotPlannerPoint}
          pendingWarehousePoint={null}
          cameraPadding={mapLayout.padding}
          warehouseKinds={warehouseKindsQuery.isError ? undefined : warehouseKindsQuery.data}
          warehouseKindsFailed={warehouseKindsQuery.isError}
        />
        {inspectorOpen && !slotPlannerOpen ? <Inspector
          workspace={workspace} plan={plan} simulation={simulationState} validation={validation} busy={busy}
          onCreate={openCreate} onEdit={openEdit} onDelete={(entityKind, id, label, expectedVersion) => setDialog({ kind: 'delete-entity', entityKind, id, label, expectedVersion })}
          onGenerateWorkload={() => setDialog({ kind: 'workload-generator' })}
          onDeleteGeneratedWorkload={() => setDialog({ kind: 'delete-generated-workload', date: planningDate })}
          onSetMapTool={(tool) => { setMapTool(tool); toast({ tone: 'info', title: 'Инструмент карты включён' }); }}
          onSelect={(kind, id) => setSelected({ kind, id })} onMoveTask={(move) => void moveTask(move)} onToggleCycleLock={(cycle) => void toggleCycleLock(cycle)}
          onSaveSettings={async (input) => { await execute(async () => { await api.updateWarehouse(workspace.warehouse.id, input, workspace.warehouse.version); await refresh(); flagCurrentRoutesForRefresh(); }, 'Настройки сохранены'); }}
          onCreateTransfer={(sourceWarehouseId, destinationWarehouseId) => setDialog({
            kind: 'transfer',
            ...(sourceWarehouseId ? { sourceWarehouseId } : {}),
            ...(destinationWarehouseId ? { destinationWarehouseId } : {}),
          })}
          onAssignContractor={(requestId) => setDialog({ kind: 'contractor-assignment', requestId })}
          onRescheduleUnassigned={openUnassignedReschedule}
          onDispatchContractor={async (contractorWorkerId, dispatchMode, requestIds) => {
            const result = await execute(async () => {
              const assigned = await api.dispatchContractor(
                workspace.warehouse.id,
                contractorWorkerId,
                planningDate,
                dispatchMode,
                requestIds,
              );
              queryClient.removeQueries({ queryKey: ['plan'] });
              setPlanId(null);
              setPlan(null);
              setValidation(null);
              await refresh();
              await queryClient.invalidateQueries({ queryKey: ['automatic-plan', planningWarehouseId, planningDate] });
              return assigned;
            });
            toast({
              tone: result.assigned_count > 0 ? 'success' : 'info',
              title: result.assigned_count > 0
                ? `Рейс сформирован: ${result.contractor_name}`
                : 'Подходящих заданий не найдено',
              detail: result.assigned_count > 0
                ? `Передано заданий: ${result.assigned_count}. Дата: ${planningDate}.`
                : 'На выбранную в хедере дату нет нераспределённых доставок или вывозов.',
            });
            return result;
          }}
          onConfirmPlan={() => void confirmPlan()}
          onResetManualChanges={() => void resetManualChanges()}
          onSimulationOverride={(overrideKind, driverShiftId) => setDialog({ kind: 'simulation', overrideKind, driverShiftId })}
          planningDate={planningDate} onPlanningDateChange={selectPlanningDate}
          onSaveRequestPlanning={saveRequestPlanning}
          onSplitRequest={splitRequestIntoSubtasks}
          loadingMoreRequests={requestsLoadingMore}
          onLoadMoreRequests={loadMoreRequests}
          inspectorWidth={mapLayout.panelWidth}
          onInspectorWidthChange={resizeInspector}
          onClose={() => setInspectorOpen(false)}
        /> : null}
      </div>
      {slotPlannerOpen ? <SlotAvailabilityPanel
        warehouseId={workspace.warehouse.id}
        warehouses={[workspace.warehouse]}
        planningDate={planningDate}
        point={slotPlannerPoint}
        onPointChange={setSlotPlannerPoint}
        onClose={closeSlotPlanner}
        onPresentationChange={setSlotPlanningMap}
        calculate={api.calculateSlotAvailability}
        suggestAddresses={api.suggestAddresses}
        resolveAddressSuggestion={api.resolveAddressSuggestion}
        reverseGeocode={api.reverseGeocode}
      /> : null}
      {mode === 'SIMULATION' && plan && simulationState && simulationTimestamp !== null ? <div className="simulation-dock" ref={mapLayout.simulationRef}><SimulationBar plan={plan} state={simulationState} timestamp={simulationTimestamp} timeZone={workspace.warehouse.timezone} playing={simulationPlaying} speed={simulationSpeed} overrides={simulationOverrides} onTimestamp={setSimulationTimestamp} onPlaying={setSimulationPlaying} onSpeed={setSimulationSpeed} /></div> : null}

      {dialog?.kind === 'workload-generator' ? <WorkloadGeneratorDialog
        planningDate={planningDate}
        busy={busy}
        onClose={() => setDialog(null)}
        onSubmit={generateWorkload}
      /> : null}
      {dialog?.kind === 'confirm-cross-warehouse-plan' ? (
        <EmptyPositioningConfirmDialog
          busy={busy}
          onClose={() => setDialog(null)}
          onConfirm={async (reason) => confirmPlan(reason)}
        />
      ) : null}
      {dialog?.kind === 'transfer' ? <TransferDraftDialog warehouses={availableWarehousesQuery.data ?? []} sourceWarehouseId={dialog.sourceWarehouseId} destinationWarehouseId={dialog.destinationWarehouseId ?? workspace.warehouse.external_warehouse_id} scheduledDate={planningDate} onClose={() => setDialog(null)} onCreated={(draft) => { setDialog(null); toast({ tone: 'success', title: 'Черновик перемещения создан', detail: `Документ ${draft.id}` }); }} /> : null}
      {dialog?.kind === 'delivery-reschedule' && deliveryRescheduleRequest ? (
        <UnassignedDeliveryRescheduleDialog
          request={deliveryRescheduleRequest}
          timeZone={planningTimeZone ?? workspace.warehouse.timezone}
          busy={busy}
          calculate={api.getRequestRescheduleOptions}
          onClose={() => setDialog(null)}
          onSubmit={async (input, idempotencyKey) => {
            try {
              const result = await execute(() => api.rescheduleRequest(
                deliveryRescheduleRequest.id,
                input,
                idempotencyKey,
              ));
              await finishDeliveryReschedule(result);
            } catch (error: unknown) {
              if (error instanceof ApiError && error.status === 409) await refresh();
              throw error;
            }
          }}
          onRetryQuarantined={async (requestId, input, idempotencyKey) => {
            const result = await execute(() => api.retryRequestReschedule(
              requestId,
              input,
              idempotencyKey,
            ));
            await finishDeliveryReschedule(result);
          }}
        />
      ) : null}
      {dialog?.kind === 'contractor-assignment' && contractorAssignmentRequest && contractorAssignmentWarehouse ? <ContractorAssignmentDialog
        request={contractorAssignmentRequest}
        warehouseId={contractorAssignmentWarehouse.external_warehouse_id}
        busy={busy}
        onClose={() => setDialog(null)}
        onAssign={async (contractorWorkerId) => {
          try {
            const assigned = await execute(async () => {
              const updated = await api.assignRequestToContractor(contractorAssignmentRequest.id, contractorWorkerId);
              queryClient.removeQueries({ queryKey: ['plan'] });
              setPlanId(null);
              setPlan(null);
              setValidation(null);
              await refresh();
              await queryClient.invalidateQueries({ queryKey: ['automatic-plan', planningWarehouseId, planningDate] });
              return updated;
            });
            setDialog(null);
            setSelected({ kind: 'request', id: assigned.id });
            toast({ tone: 'success', title: `Передано наёмному водителю: ${assigned.assigned_contractor_name ?? 'подрядчик'}` });
          } catch {
            // execute already showed the safe operator-facing explanation.
          }
        }}
      /> : null}
      {dialog?.kind === 'warehouse' ? <WarehouseDialog warehouse={dialog.value} busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => {
        await api.updateWarehouse(dialog.value.id, input, dialog.value.version);
        await refresh();
        flagCurrentRoutesForRefresh();
        setDialog(null); setMapTool('SELECT');
      }, 'Настройки склада сохранены'); }} /> : null}
      {dialog?.kind === 'vehicle' ? <CatalogDialog value={dialog.value} busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => {
        const { load_profiles: loadProfiles, ...vehicleInput } = input;
        const configuration = {
          vehicle: vehicleInput,
          load_profiles: loadProfiles.map(({ configuration_type, max_actual_axle_load_kg }) => ({
            configuration_type,
            max_actual_axle_load_kg,
          })),
        };
        if (dialog.value) await api.updateVehicleConfiguration(dialog.value.id, configuration, dialog.value.version);
        else await api.createVehicleConfiguration(workspace.warehouse.id, configuration, dialog.intentKey);
        await refresh();
        flagCurrentRoutesForRefresh();
        setDialog(null);
      }, 'Транспортное средство сохранено').catch(() => undefined); }} /> : null}
      {dialog?.kind === 'trailer' ? <TrailerDialog trailer={dialog.value} busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => {
        if (dialog.value) await api.updateTrailer(dialog.value.id, input, dialog.value.version);
        else await api.createTrailer(workspace.warehouse.id, input, dialog.intentKey);
        await refresh();
        flagCurrentRoutesForRefresh();
        setDialog(null);
      }, 'Прицеп сохранён').catch(() => undefined); }} /> : null}
      {dialog?.kind === 'shift' ? <ShiftDialog shift={dialog.value} warehouse={workspace.warehouses.find((warehouse) => warehouse.id === shiftWarehouseId) ?? workspace.warehouse} drivers={shiftDrivers} vehicles={shiftVehicles} shifts={workspace.shifts} busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => { if (dialog.value) await api.updateShift(dialog.value.id, input, dialog.value.version); else await api.createShift(workspace.warehouse.id, input, dialog.intentKey); await refresh(); flagCurrentRoutesForRefresh(); setDialog(null); }, 'Смена сохранена').catch(() => undefined); }} /> : null}
      {dialog?.kind === 'request' ? <RequestDialog request={dialog.value} point={dialog.point} initialAddress={dialog.address} type={dialog.requestType} defaultDate={planningDate} busy={busy} onClose={() => { setDialog(null); setMapTool('SELECT'); }} onSubmit={async (input) => { await execute(async () => { if (dialog.value) await api.updateRequest(dialog.value.id, input, dialog.value.version); else await api.createRequest(workspace.warehouse.id, input, dialog.intentKey); await refresh(); flagCurrentRoutesForRefresh(); setDialog(null); setMapTool('SELECT'); }, input.type === 'DELIVERY' ? 'Доставка сохранена; ограничения проверены' : 'Вывоз сохранён; ограничения проверены').catch(() => undefined); }} /> : null}
      {dialog?.kind === 'delete-entity' ? <ConfirmDialog title={`Удалить «${dialog.label}»?`} description="Система проверит связанные рейсы и не удалит используемый объект." confirmLabel="Удалить" dangerous busy={busy} onClose={() => setDialog(null)} onConfirm={async () => { await execute(async () => { if (dialog.entityKind === 'driver') await api.deleteDriver(dialog.id, dialog.expectedVersion); else if (dialog.entityKind === 'vehicle') await api.deleteVehicle(dialog.id, dialog.expectedVersion); else if (dialog.entityKind === 'trailer') await api.deleteTrailer(dialog.id, dialog.expectedVersion); else if (dialog.entityKind === 'shift') await api.deleteShift(dialog.id, dialog.expectedVersion); else await api.deleteRequest(dialog.id, dialog.expectedVersion); await refresh(); flagCurrentRoutesForRefresh(); setDialog(null); }, 'Объект удалён'); }} /> : null}
      {dialog?.kind === 'delete-generated-workload' ? <ConfirmDialog title={`Удалить нагрузку за ${formatDate(dialog.date)}?`} description="Будут удалены доставки и вывозы, созданные генератором на эту дату, и все сохранённые планы этой даты. Ручные и RWMS-операции, а также нагрузка и планы других дат останутся без изменений." confirmLabel="Удалить нагрузку" dangerous busy={busy} onClose={() => setDialog(null)} onConfirm={async () => { await execute(async () => {
        const result = await api.deleteGeneratedWorkload(
          workspace.planning_root_warehouse_id ?? workspace.warehouse.id,
          dialog.date,
        );
        await refresh();
        if (dialog.date === planningDate) clearLocalPlanningState();
        setDialog(null);
        toast(result.deleted_requests > 0 || result.deleted_plans > 0
          ? { tone: 'success', title: `Тестовая нагрузка удалена: ${result.deleted_requests} позиций · ${result.deleted_plans} планов`, detail: `${formatDate(result.date)}${result.capacity_projection_status === 'FAILED' && result.capacity_projection_warning ? ` · Внимание: ${userFacingErrorDetail(new Error(result.capacity_projection_warning), 'Не удалось обновить доступную мощность RWMS.')}` : ''}` }
          : { tone: 'info', title: 'На выбранную дату нагрузки генератора нет', detail: formatDate(result.date) });
      }, undefined).catch(() => undefined); }} /> : null}
      {dialog?.kind === 'close-planning-day' ? <ConfirmDialog
        title={`Закрыть приём доставок на ${formatDate(dialog.date)}?`}
        description="Новые клиентские слоты на эту дату станут недоступны, а текущие доставки и вывозы будут ещё раз собраны в оптимальный план. Повторно открыть приём нельзя."
        confirmLabel="Закрыть приём доставок"
        busy={busy}
        onClose={() => setDialog(null)}
        onConfirm={async () => {
          const targetWarehouseId = planningWarehouseId as UUID;
          const targetDate = dialog.date;
          await queryClient.cancelQueries({ queryKey: ['automatic-plan', targetWarehouseId, targetDate] });
          try {
            const status = await actionMutation.mutateAsync(
              () => api.closePlanningDay(targetWarehouseId, targetDate),
            ) as Awaited<ReturnType<typeof api.getPlanningDayStatus>>;
            await applyPlanningDayClosureState(targetWarehouseId, targetDate, status);
            toast({ tone: 'success', title: 'Приём доставок закрыт; финальный план пересчитан' });
          } catch (error: unknown) {
            let reconciledStatus: Awaited<ReturnType<typeof api.getPlanningDayStatus>> | null = null;
            try {
              reconciledStatus = await api.getPlanningDayStatus(targetWarehouseId, targetDate);
            } catch {
              // Preserve the original close failure when status reconciliation
              // is unavailable as well.
            }
            if (reconciledStatus?.accepting_requests === false) {
              await applyPlanningDayClosureState(
                targetWarehouseId,
                targetDate,
                reconciledStatus,
              );
              const feedback = actionErrorFeedback(error);
              toast({
                tone: 'warning',
                title: 'Приём закрыт; внешний обмен требует повтора',
                detail: feedback.detail ?? feedback.title,
              });
              return;
            }
            await reportActionError(error);
          }
        }}
      /> : null}
      {dialog?.kind === 'simulation' && selectedOverrideRoute && plan && simulationTimestamp !== null ? <SimulationOverrideDialog kind={dialog.overrideKind} driverName={selectedOverrideRoute.driver_name} busy={busy} onClose={() => setDialog(null)} onSubmit={async (delayMinutes, reason) => { await execute(async () => {
        const override = { id: crypto.randomUUID(), kind: dialog.overrideKind === 'delay' ? 'DELAY' as const : 'DRIVER_UNAVAILABLE' as const, driver_shift_id: dialog.driverShiftId, effective_at: new Date(simulationTimestamp).toISOString(), delay_minutes: delayMinutes, reason };
        const result = dialog.overrideKind === 'delay'
          ? await api.applyDelay(plan.id, { expected_version: plan.version, driver_shift_id: dialog.driverShiftId, effective_at: override.effective_at, delay_minutes: delayMinutes, reason, persist: false }, workspace, plan)
          : await api.markDriverUnavailable(plan.id, { expected_version: plan.version, driver_shift_id: dialog.driverShiftId, effective_at: override.effective_at, reason, persist: false }, workspace, plan);
        setValidation(result); addSimulationOverride(override); setDialog(null);
      }, dialog.overrideKind === 'delay' ? 'Задержка применена к симуляции' : 'Недоступность применена к симуляции'); }} /> : null}
      <Toasts />
    </div>
  );
}
