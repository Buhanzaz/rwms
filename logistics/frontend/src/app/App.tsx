import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  CheckCircle2,
  LockKeyhole,
  PlayCircle,
  RefreshCw,
  Route as RouteIcon,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import {
  api,
  ApiError,
  getWarehouseWorkspace,
  optimizationStreamUrl,
  type RequestPlanningDetailsInput,
} from '../api/client';
import type {
  Driver,
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
  type VehicleEditorInput,
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
import { TrailerDialog } from '../features/trailers/TrailerDialog';
import { SlotAvailabilityPanel } from '../features/slot-availability/SlotAvailabilityPanel';
import type { SlotPlanningMapPresentation } from '../features/slot-availability/types';
import { TransferDraftDialog } from '../features/transfers/TransferDraftDialog';

type DialogState =
  | { kind: 'workload-generator' }
  | { kind: 'warehouse'; value: Warehouse }
  | { kind: 'transfer'; sourceWarehouseId?: UUID; destinationWarehouseId?: UUID }
  | { kind: 'driver'; value?: Driver }
  | { kind: 'vehicle'; value?: Vehicle }
  | { kind: 'trailer'; value?: Trailer }
  | { kind: 'shift'; value?: DriverShift }
  | { kind: 'request'; value?: LogisticsRequest; point?: { latitude: number; longitude: number }; address?: string; requestType: 'DELIVERY' | 'PICKUP' }
  | { kind: 'delete-entity'; entityKind: 'driver' | 'vehicle' | 'trailer' | 'shift' | 'request'; id: UUID; label: string }
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
  const [warehouseId, setWarehouseId] = useState<UUID | null>(null);
  const [planningDate, setPlanningDate] = useState(dateInTimeZone(new Date(), 'Europe/Moscow'));
  const [planId, setPlanId] = useState<UUID | null>(null);
  const [plan, setPlan] = useState<RoutePlan | null>(null);
  const [runId, setRunId] = useState<UUID | null>(null);
  const [dialog, setDialog] = useState<DialogState>(null);
  const [validation, setValidation] = useState<ValidationResult | null>(null);
  const [slotPlannerOpen, setSlotPlannerOpen] = useState(false);
  const [slotPlannerPoint, setSlotPlannerPoint] = useState<{ latitude: number; longitude: number } | null>(null);
  const [slotPlanningMap, setSlotPlanningMap] = useState<SlotPlanningMapPresentation | null>(null);
  const [inspectorWidth, setInspectorWidth] = useState(savedInspectorWidth);
  const [routesNeedRefresh, setRoutesNeedRefresh] = useState(false);
  const surfacedPlanIdRef = useRef<UUID | null>(null);
  const surfacedNotificationIdsRef = useRef(new Set<UUID>());
  const mode = useUiStore((state) => state.mode);
  const sidebarsCollapsed = useUiStore((state) => state.sidebarsCollapsed);
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
    setMode('PLAN_DAY');
    setSelected(null);
  }, [clearSimulationOverrides, clearTrace, queryClient, setMode, setSelected, setSimulationPlaying, setSimulationTimestamp]);

  const closeSlotPlanner = useCallback(() => {
    setSlotPlannerOpen(false);
    setSlotPlanningMap(null);
  }, []);

  const selectPlanningDate = useCallback((date: string) => {
    setPlanningDate(date);
    if (plan?.date !== date) {
      setPlanId(null);
      setPlan(null);
      setValidation(null);
      setRoutesNeedRefresh(false);
      clearSimulationOverrides();
    }
    if (mode === 'PLAN_DAY') setSection('PLAN_DAY');
  }, [clearSimulationOverrides, mode, plan?.date, setSection]);

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
  const availableWarehousesQuery = useQuery({ queryKey: ['available-warehouses'], queryFn: api.listAvailableWarehouses, refetchInterval: 15_000 });
  useEffect(() => {
    if (!warehouseId && warehousesQuery.data?.[0]) setWarehouseId(warehousesQuery.data[0].id);
    if (warehouseId && warehousesQuery.data && !warehousesQuery.data.some((warehouse) => warehouse.id === warehouseId)) {
      setWarehouseId(warehousesQuery.data[0]?.id ?? null);
    }
  }, [warehouseId, warehousesQuery.data]);

  const workspaceQuery = useQuery({
    queryKey: ['workspace', warehouseId],
    queryFn: () => getWarehouseWorkspace(warehouseId as UUID),
    enabled: Boolean(warehouseId),
    refetchInterval: 15_000,
  });
  const baseWorkspace = workspaceQuery.data ?? null;
  const workspaceWarehouseId = baseWorkspace?.warehouse.id;
  const workspaceDefaultPlanningDate = baseWorkspace?.warehouse.default_planning_date;
  const workspaceTimeZone = baseWorkspace?.warehouse.timezone;
  useEffect(() => {
    if (!workspaceWarehouseId || !workspaceTimeZone) return;
    setPlanningDate(
      workspaceDefaultPlanningDate ?? dateInTimeZone(new Date(), workspaceTimeZone),
    );
    clearLocalPlanningState();
  }, [clearLocalPlanningState, workspaceDefaultPlanningDate, workspaceWarehouseId, workspaceTimeZone]);

  const workspace = baseWorkspace;
  const availableDriversQuery = useQuery({
    queryKey: ['available-drivers', workspaceWarehouseId],
    queryFn: () => api.listAvailableDrivers(workspaceWarehouseId as UUID),
    enabled: Boolean(workspaceWarehouseId && dialog?.kind === 'driver'),
  });
  const planQuery = useQuery({
    queryKey: ['plan', planId, workspace?.warehouse.updated_at],
    queryFn: () => api.getPlan(planId as UUID, workspace!),
    enabled: Boolean(planId && workspace),
  });
  const automaticPlanQuery = useQuery({
    queryKey: [
      'automatic-plan',
      workspaceWarehouseId,
      planningDate,
    ],
    queryFn: ({ signal }) => api.ensureAutomaticPlan(
      workspaceWarehouseId as UUID,
      planningDate,
      workspace!,
      signal,
    ),
    enabled: Boolean(workspaceWarehouseId && workspace && !workspaceQuery.isFetching),
    retry: false,
    staleTime: Infinity,
  });
  const planningDayStatusQuery = useQuery({
    queryKey: ['planning-day-status', workspaceWarehouseId, planningDate],
    queryFn: () => api.getPlanningDayStatus(workspaceWarehouseId as UUID, planningDate),
    enabled: Boolean(workspaceWarehouseId && workspace),
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
      setSection('UNASSIGNED');
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
  }, [planQuery.data, setSection, toast]);
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
        const detail = run.status === 'TIMED_OUT' ? 'Показан лучший найденный план.' : run.error_message;
        toast({ tone: run.status === 'TIMED_OUT' ? 'warning' : 'error', title: run.status === 'TIMED_OUT' ? 'Лимит времени достигнут' : `Оптимизация: ${run.status}`, ...(detail ? { detail } : {}) });
      }
    } else if (run.status === 'FAILED') {
      toast({ tone: 'error', title: 'Оптимизация завершилась с ошибкой', detail: run.error_message ?? 'План не создан' });
    } else if (run.status === 'CANCELLED') {
      toast({ tone: 'info', title: 'Оптимизация отменена', detail: 'Существующие сохранённые планы не изменены.' });
    } else if (run.status === 'TIMED_OUT') {
      toast({ tone: 'warning', title: 'Лимит времени достигнут', detail: 'Backend не успел сохранить допустимый план.' });
    }
  }, [runQuery.data, setMode, setSection, toast]);

  useEffect(() => {
    if (!runId || isTerminal(runQuery.data?.status)) return;
    const stream = new EventSource(optimizationStreamUrl(runId));
    const consume = (event: Event) => {
      if (!(event instanceof MessageEvent) || typeof event.data !== 'string') return;
      const trace = parseTraceEvent(event.data, runId);
      if (trace) appendTrace(trace);
    };
    const customEventTypes = [
      'trace', 'phase_started', 'phase_progress', 'candidate_edge_considered', 'candidate_edge_rejected',
      'candidate_cycle_created', 'candidate_cycle_rejected', 'cycle_assigned', 'assignment_changed',
      'best_score_updated', 'phase_completed',
    ];
    stream.onmessage = consume;
    customEventTypes.forEach((eventType) => stream.addEventListener(eventType, consume));
    const terminal = () => {
      stream.close();
      void queryClient.invalidateQueries({ queryKey: ['optimization-run', runId] });
    };
    stream.addEventListener('run_terminal', terminal);
    stream.onerror = () => stream.close();
    return () => {
      customEventTypes.forEach((eventType) => stream.removeEventListener(eventType, consume));
      stream.removeEventListener('run_terminal', terminal);
      stream.close();
    };
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
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['warehouses'] }),
      queryClient.invalidateQueries({ queryKey: ['available-warehouses'] }),
    ]);
    if (warehouseId) {
      await queryClient.invalidateQueries({ queryKey: ['workspace', warehouseId] });
      await queryClient.invalidateQueries({ queryKey: ['planning-day-status', warehouseId] });
    }
  }, [queryClient, warehouseId]);

  const reportActionError = useCallback(async (error: unknown): Promise<void> => {
    const { refreshPlan, ...message } = actionErrorFeedback(error);
    toast(error instanceof ApiError && error.code
      ? { ...message, detail: `${error.code}: ${message.detail}` }
      : message);
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
    if (kind === 'driver') setDialog({ kind: 'driver' });
    else if (kind === 'vehicle') setDialog({ kind: 'vehicle' });
    else if (kind === 'trailer') setDialog({ kind: 'trailer' });
    else if (kind === 'shift') setDialog({ kind: 'shift' });
    else if (kind === 'request') setDialog({ kind: 'request', requestType: 'DELIVERY' });
  };

  const openEdit = (kind: EntityKind, value: EditableEntity) => {
    if (kind === 'warehouse') setDialog({ kind: 'warehouse', value: value as Warehouse });
    else if (kind === 'driver') setDialog({ kind: 'driver', value: value as Driver });
    else if (kind === 'vehicle') {
      if (workspace?.trailers === undefined) {
        toast({ tone: 'info', title: 'Загружаем грузовые параметры', detail: 'Повторите открытие машины через секунду.' });
        return;
      }
      const currentVehicle = workspace.vehicles.find((candidate) => candidate.id === value.id) ?? value as Vehicle;
      setDialog({ kind: 'vehicle', value: currentVehicle });
    }
    else if (kind === 'trailer') setDialog({ kind: 'trailer', value: value as Trailer });
    else if (kind === 'shift') setDialog({ kind: 'shift', value: value as DriverShift });
    else if (kind === 'request') setDialog({ kind: 'request', value: value as LogisticsRequest, requestType: (value as LogisticsRequest).type });
  };

  const handleMapPoint = useCallback((kind: 'request', longitude: number, latitude: number) => {
    void api.reverseGeocode(latitude, longitude).then((resolved) => {
      setDialog({ kind, point: { longitude, latitude }, address: resolved.address, requestType: mapTool === 'ADD_PICKUP' ? 'PICKUP' : 'DELIVERY' });
    }).catch(() => {
      setDialog({ kind, point: { longitude, latitude }, requestType: mapTool === 'ADD_PICKUP' ? 'PICKUP' : 'DELIVERY' });
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
    setDialog({ kind: 'request', value: request, point: { longitude, latitude }, requestType: request.type });
    toast({ tone: 'info', title: 'Новые координаты не сохранены', detail: 'Проверьте форму и сохраните; backend заново проверит маршрут и доступность по изохронам.' });
  }, [toast, workspace]);
  const cancelOptimization = async () => {
    if (!runId || !workspace) return;
    await execute(async () => {
      const cancelled = await api.cancelOptimizationRun(runId, workspace.warehouse.settings);
      queryClient.setQueryData(['optimization-run', runId], cancelled);
    }, 'Отмена оптимизации запрошена').catch(() => undefined);
  };

  const validatePlan = async (): Promise<ValidationResult | null> => {
    if (!workspace || !plan) return null;
    let result: ValidationResult | null = null;
    await execute(async () => {
      result = await api.validatePlan(plan.id, plan.version, workspace, plan);
      setValidation(result);
      if (result.updated_schedule) setPlan(result.updated_schedule);
    }, 'Проверка плана завершена').catch(() => undefined);
    return result;
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
        changed_by: 'local-admin',
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
        workspace.warehouse.id,
        planningDate,
        workspace,
      );
      if (!refreshed) throw new Error('Для выбранного дня пока недостаточно данных для построения маршрутов');
      setPlanId(refreshed.id);
      setPlan(refreshed);
      setValidation(null);
      setRoutesNeedRefresh(false);
      queryClient.setQueryData(
        ['automatic-plan', workspace.warehouse.id, planningDate],
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
      await api.saveRequestPlanningDetails(requestId, input);
      await refresh();
      if (refreshExistingPlan) {
        flagCurrentRoutesForRefresh();
      } else {
        await queryClient.invalidateQueries({ queryKey: ['automatic-plan', workspaceWarehouseId, input.date] });
      }
      setMode('PLAN_DAY');
      setSection('PLAN_DAY');
    }, 'Условия доставки сохранены').catch(() => undefined);
  };

  const splitRequestIntoSubtasks = async (requestId: UUID, quantities: number[]) => {
    await execute(async () => {
      const refreshExistingPlan = plan?.date === planningDate && plan.status !== 'CONFIRMED';
      await api.splitRequest(requestId, quantities);
      await refresh();
      if (refreshExistingPlan) {
        flagCurrentRoutesForRefresh();
      } else {
        await queryClient.invalidateQueries({ queryKey: ['automatic-plan', workspaceWarehouseId, planningDate] });
      }
      setMode('PLAN_DAY');
      setSection('PLAN_DAY');
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
        const result = await api.generateWorkload(workspace.warehouse.id, input);
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
          detail: `${result.created_deliveries} доставок · ${result.created_pickups} вывозов · ${formatDate(result.start_date)}–${formatDate(result.end_date)} · заменено прежних позиций: ${result.replaced_requests} · удалено планов: ${result.deleted_plans}`,
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
          title: 'Нагрузка сохранена; публикация в RWMS требует повтора',
          detail: `${error.code}: ${feedback.detail ?? feedback.title}`,
        });
        return;
      }
      await reportActionError(error);
    }
  };

  const scheduleRequestDate = async (requestId: UUID, date: string, addIfMissing: boolean) => {
    await execute(async () => {
      await api.scheduleRequest(requestId, { date, add_if_missing: addIfMissing });
      await refresh();
      flagCurrentRoutesForRefresh();
      selectPlanningDate(date);
    }, addIfMissing ? 'Новая дата согласована и назначена' : 'Доставка или вывоз назначены на выбранную дату').catch(() => undefined);
  };

  const unscheduleRequest = async (requestId: UUID) => {
    await execute(async () => {
      await api.scheduleRequest(requestId, { date: null });
      await refresh();
      flagCurrentRoutesForRefresh();
    }, 'Назначение снято; доставка или вывоз снова доступны во все согласованные даты').catch(() => undefined);
  };

  if (warehousesQuery.isLoading || availableWarehousesQuery.isLoading) return <div className="app-shell" style={{ placeItems: 'center' }}><Spinner label="Загружаем склады…" /><Toasts /></div>;
  if (warehousesQuery.isError || availableWarehousesQuery.isError) return <div className="app-shell" style={{ placeItems: 'center' }}><ErrorPanel title="Backend недоступен" error={warehousesQuery.error ?? availableWarehousesQuery.error} onRetry={() => { void warehousesQuery.refetch(); void availableWarehousesQuery.refetch(); }} /><Toasts /></div>;
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

  return (
    <div className={`app-shell ${mode === 'SIMULATION' ? 'app-shell--simulation' : 'app-shell--plan'} ${workspace.rwms_refresh_warning ? 'app-shell--refresh-warning' : ''}`}>
      <header className="topbar">
        <div className="topbar__brand">
          <Button className="brand-mark" onClick={() => { setMode('PLAN_DAY'); setSection('WAREHOUSE'); setMapTool('SELECT'); setSelected({ kind: 'warehouse', id: warehouseId }); }} aria-label="Открыть склад" title="Открыть склад">L</Button>
          <span className="topbar__warehouse-context"><strong>{workspace.warehouse.name}</strong>{workspace.warehouse.city ? ` · ${workspace.warehouse.city}` : ''}</span>
        </div>
        <div className="topbar__date">
          {routesNeedRefresh && plan && plan.status !== 'CONFIRMED' ? <Button variant="primary" disabled={busy} onClick={() => void refreshRoutes()}><RefreshCw size={15} aria-hidden="true" /><span>Обновить маршруты</span></Button> : null}
          <DatePicker className="topbar-date-picker" label="Дата планирования" value={planningDate} onChange={selectPlanningDate} />
        </div>
        <div className="topbar__actions">
          <Button
            variant={acceptingRequests ? 'secondary' : 'ghost'}
            disabled={busy || planningDayStatusQuery.isPending || !acceptingRequests}
            onClick={() => setDialog({ kind: 'close-planning-day', date: planningDate })}
          ><LockKeyhole size={15} aria-hidden="true" /><span>{planningDayStatusQuery.isPending ? 'Проверяем приём…' : planningDayStatusQuery.isError ? 'Статус приёма недоступен' : acceptingRequests ? 'Закрыть приём доставок' : 'Приём закрыт'}</span></Button>
          <Button disabled={!acceptingRequests} variant={slotPlannerOpen ? 'primary' : 'secondary'} onClick={() => slotPlannerOpen ? closeSlotPlanner() : setSlotPlannerOpen(true)}><RouteIcon size={15} aria-hidden="true" /><span>Проверить слот</span></Button>
          {currentRun && !isTerminal(currentRun.status) ? <Button variant="danger" disabled={busy || currentRun.cancel_requested} onClick={() => void cancelOptimization()}><span>{currentRun.cancel_requested ? 'Отменяем…' : 'Отменить'}</span></Button> : null}
          <Button disabled={!plan || busy} onClick={() => void validatePlan()}><CheckCircle2 size={15} aria-hidden="true" /><span>Проверить</span></Button>
          <div className="segmented" aria-label="Режим приложения">{([['PLAN_DAY', 'План дня'], ['SIMULATION', 'Симуляция']] as const).map(([value, label]) => <button type="button" key={value} aria-pressed={mode === value} onClick={() => changeMode(value)}>{value === 'PLAN_DAY' ? <RouteIcon size={13} aria-hidden="true" /> : <PlayCircle size={13} aria-hidden="true" />}{label}</button>)}</div>
          <ThemeSwitch />
          <NotificationCenter />
        </div>
      </header>
      {workspace.rwms_refresh_warning ? (
        <div className="workspace-refresh-warning" role="alert">
          <strong>RWMS обновлён частично</strong>
          <span>{workspace.rwms_refresh_warning}</span>
        </div>
      ) : null}
      <div
        className={`workspace ${sidebarsCollapsed ? 'workspace--collapsed' : ''}`}
        style={{ '--inspector-width': `${inspectorWidth}px` } as CSSProperties}
      >
        <Sidebar workspace={workspace} plan={plan} />
        <MapCanvas
          workspace={workspace}
          plan={plan}
          simulation={simulationState}
          traceEvents={traceEvents}
          optimizationRun={currentRun}
          selected={selected}
          onSelect={setSelected}
          onWarehouseActivate={setWarehouseId}
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
        />
        <Inspector
          workspace={workspace} plan={plan} simulation={simulationState} validation={validation} busy={busy}
          onCreate={openCreate} onEdit={openEdit} onDelete={(entityKind, id, label) => setDialog({ kind: 'delete-entity', entityKind, id, label })}
          onGenerateWorkload={() => setDialog({ kind: 'workload-generator' })}
          onDeleteGeneratedWorkload={() => setDialog({ kind: 'delete-generated-workload', date: planningDate })}
          onSetMapTool={(tool) => { setMapTool(tool); toast({ tone: 'info', title: 'Инструмент карты включён' }); }}
          onSelect={(kind, id) => setSelected({ kind, id })} onMoveTask={(move) => void moveTask(move)} onToggleCycleLock={(cycle) => void toggleCycleLock(cycle)}
          onSaveSettings={async (input) => { await execute(async () => { await api.updateWarehouse(workspace.warehouse.id, input); await refresh(); flagCurrentRoutesForRefresh(); }, 'Настройки сохранены'); }}
          onCreateTransfer={(sourceWarehouseId, destinationWarehouseId) => setDialog({
            kind: 'transfer',
            ...(sourceWarehouseId ? { sourceWarehouseId } : {}),
            ...(destinationWarehouseId ? { destinationWarehouseId } : {}),
          })}
          onConfirmPlan={() => void confirmPlan()}
          onResetManualChanges={() => void resetManualChanges()}
          onSimulationOverride={(overrideKind, driverShiftId) => setDialog({ kind: 'simulation', overrideKind, driverShiftId })}
          planningDate={planningDate} onPlanningDateChange={selectPlanningDate}
          onSaveRequestPlanning={saveRequestPlanning}
          onSplitRequest={splitRequestIntoSubtasks}
          inspectorWidth={inspectorWidth}
          onInspectorWidthChange={resizeInspector}
        />
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
      {mode === 'SIMULATION' && plan && simulationState && simulationTimestamp !== null ? <SimulationBar plan={plan} state={simulationState} timestamp={simulationTimestamp} timeZone={workspace.warehouse.timezone} playing={simulationPlaying} speed={simulationSpeed} overrides={simulationOverrides} onTimestamp={setSimulationTimestamp} onPlaying={setSimulationPlaying} onSpeed={setSimulationSpeed} /> : null}

      {dialog?.kind === 'workload-generator' ? <WorkloadGeneratorDialog
        planningDate={planningDate}
        seed={workspace.warehouse.seed ?? 42}
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
      {dialog?.kind === 'warehouse' ? <WarehouseDialog warehouse={dialog.value} busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => {
        await api.updateWarehouse(dialog.value.id, input);
        await refresh();
        flagCurrentRoutesForRefresh();
        setDialog(null); setMapTool('SELECT');
      }, 'Настройки склада сохранены'); }} /> : null}
      {dialog?.kind === 'driver' || dialog?.kind === 'vehicle' ? <CatalogDialog kind={dialog.kind} value={dialog.value} trailers={workspace.trailers ?? []} availableDrivers={availableDriversQuery.data ?? []} busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => {
        if (dialog.kind === 'driver') {
          const value = input as Parameters<typeof api.createDriver>[1];
          if (dialog.value) await api.updateDriver(dialog.value.id, value);
          else await api.createDriver(workspace.warehouse.id, value);
        } else {
          const { load_profiles: loadProfiles, ...vehicleInput } = input as VehicleEditorInput;
          const configuration = {
            vehicle: vehicleInput,
            load_profiles: loadProfiles.map(({ configuration_type, max_actual_axle_load_kg }) => ({
              configuration_type,
              max_actual_axle_load_kg,
            })),
          };
          if (dialog.value) await api.updateVehicleConfiguration(dialog.value.id, configuration);
          else await api.createVehicleConfiguration(workspace.warehouse.id, configuration);
        }
        await refresh();
        flagCurrentRoutesForRefresh();
        setDialog(null);
      }, dialog.kind === 'driver' ? 'Водитель сохранён' : 'Машина и грузовые профили сохранены'); }} /> : null}
      {dialog?.kind === 'trailer' ? <TrailerDialog trailer={dialog.value} busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => {
        if (dialog.value) await api.updateTrailer(dialog.value.id, input);
        else await api.createTrailer(workspace.warehouse.id, input);
        await refresh();
        flagCurrentRoutesForRefresh();
        setDialog(null);
      }, 'Прицеп сохранён'); }} /> : null}
      {dialog?.kind === 'shift' ? <ShiftDialog shift={dialog.value} warehouse={workspace.warehouse} drivers={workspace.drivers} vehicles={workspace.vehicles} busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => { if (dialog.value) await api.updateShift(dialog.value.id, input); else await api.createShift(workspace.warehouse.id, input); await refresh(); flagCurrentRoutesForRefresh(); setDialog(null); }, 'Смена сохранена'); }} /> : null}
      {dialog?.kind === 'request' ? <RequestDialog request={dialog.value} point={dialog.point} initialAddress={dialog.address} type={dialog.requestType} defaultDate={planningDate} busy={busy} onClose={() => { setDialog(null); setMapTool('SELECT'); }} onSubmit={async (input) => { await execute(async () => { if (dialog.value) await api.updateRequest(dialog.value.id, input); else await api.createRequest(workspace.warehouse.id, input); await refresh(); flagCurrentRoutesForRefresh(); setDialog(null); setMapTool('SELECT'); }, input.type === 'DELIVERY' ? 'Доставка сохранена; ограничения проверены backend' : 'Вывоз сохранён; ограничения проверены backend'); }} /> : null}
      {dialog?.kind === 'delete-entity' ? <ConfirmDialog title={`Удалить «${dialog.label}»?`} description="Изменение относится к выбранному складу. Backend проверит ссылки и вернёт ошибку, если объект используется." confirmLabel="Удалить" dangerous busy={busy} onClose={() => setDialog(null)} onConfirm={async () => { await execute(async () => { if (dialog.entityKind === 'driver') await api.deleteDriver(dialog.id); else if (dialog.entityKind === 'vehicle') await api.deleteVehicle(dialog.id); else if (dialog.entityKind === 'trailer') await api.deleteTrailer(dialog.id); else if (dialog.entityKind === 'shift') await api.deleteShift(dialog.id); else await api.deleteRequest(dialog.id); await refresh(); flagCurrentRoutesForRefresh(); setDialog(null); }, 'Объект удалён'); }} /> : null}
      {dialog?.kind === 'delete-generated-workload' ? <ConfirmDialog title={`Удалить нагрузку за ${formatDate(dialog.date)}?`} description="Будут удалены доставки и вывозы, созданные генератором на эту дату, и все сохранённые планы этой даты. Ручные и RWMS-операции, а также нагрузка и планы других дат останутся без изменений." confirmLabel="Удалить нагрузку" dangerous busy={busy} onClose={() => setDialog(null)} onConfirm={async () => { await execute(async () => {
        const result = await api.deleteGeneratedWorkload(workspace.warehouse.id, dialog.date);
        await refresh();
        if (dialog.date === planningDate) clearLocalPlanningState();
        setDialog(null);
        toast(result.deleted_requests > 0 || result.deleted_plans > 0
          ? { tone: 'success', title: `Удалено позиций: ${result.deleted_requests} · планов: ${result.deleted_plans}`, detail: formatDate(result.date) }
          : { tone: 'info', title: 'На выбранную дату нагрузки генератора нет', detail: formatDate(result.date) });
      }, undefined).catch(() => undefined); }} /> : null}
      {dialog?.kind === 'close-planning-day' ? <ConfirmDialog
        title={`Закрыть приём доставок на ${formatDate(dialog.date)}?`}
        description="Новые клиентские слоты на эту дату станут недоступны, а текущие доставки и вывозы будут ещё раз собраны в оптимальный план. Повторно открыть приём нельзя."
        confirmLabel="Закрыть приём доставок"
        busy={busy}
        onClose={() => setDialog(null)}
        onConfirm={async () => {
          const targetWarehouseId = workspace.warehouse.id;
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
                detail: error instanceof ApiError && error.code
                  ? `${error.code}: ${feedback.detail ?? feedback.title}`
                  : feedback.detail ?? feedback.title,
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
