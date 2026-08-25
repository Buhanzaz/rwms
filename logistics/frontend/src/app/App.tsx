import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  CalendarDays,
  CheckCircle2,
  CirclePlus,
  CloudCog,
  DatabaseZap,
  PlayCircle,
  Route as RouteIcon,
  Save,
  Sparkles,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { MultiPolygon, Polygon } from 'geojson';
import { api, getScenarioWorkspace, optimizationStreamUrl, type ZoneInput } from '../api/client';
import type {
  Driver,
  DriverShift,
  LogisticsRequest,
  OptimizationRun,
  OptimizationTraceEvent,
  RouteCycle,
  RoutePlan,
  Scenario,
  UUID,
  ValidationResult,
  Vehicle,
  Warehouse,
  Zone,
  ZoneRelation,
} from '../domain/types';
import { Button, EmptyState, ErrorPanel, Spinner, Toasts } from '../components/ui';
import {
  CatalogDialog,
  ConfirmDialog,
  RelationDialog,
  RequestDialog,
  ScenarioDialog,
  ShiftDialog,
  SimulationOverrideDialog,
  WarehouseDialog,
  ZoneDialog,
} from '../components/EntityDialogs';
import { MapCanvas } from '../map/MapCanvas';
import { isRequestVisibleOnDate } from '../domain/request-dates';
import { useUiStore } from '../stores/ui-store';
import { dateInTimeZone, formatDate, nextDate } from '../utils/format';
import { deriveSimulationState, planTimeBounds } from '../simulation/deriveSimulationState';
import { Sidebar } from './Sidebar';
import { Inspector, type EditableEntity, type EntityKind } from './Inspector';
import type { PlanMove } from '../features/planning/PlanPanel';
import { SimulationBar } from '../features/simulation/SimulationBar';
import { RwmsIntegrationDialog } from '../features/rwms/RwmsIntegrationDialog';
import { WorkloadGeneratorDialog } from '../features/scenarios/WorkloadGeneratorDialog';
import { saveZoneUpdate } from '../features/zones/zone-update';
import { actionErrorFeedback } from './action-error';

type DialogState =
  | { kind: 'scenario'; value?: Scenario }
  | { kind: 'workload-generator' }
  | { kind: 'warehouse'; value?: Warehouse; point?: { latitude: number; longitude: number } }
  | { kind: 'zone'; value?: Zone; geometry: Polygon | MultiPolygon }
  | { kind: 'zone-cutout'; sourceZone: Zone; geometry: Polygon; initialValues: Partial<Omit<ZoneInput, 'geometry'>> }
  | { kind: 'driver'; value?: Driver }
  | { kind: 'vehicle'; value?: Vehicle }
  | { kind: 'shift'; value?: DriverShift }
  | { kind: 'request'; value?: LogisticsRequest; point?: { latitude: number; longitude: number }; requestType: 'DELIVERY' | 'PICKUP' }
  | { kind: 'relation'; from: Zone; to: Zone; value?: ZoneRelation }
  | { kind: 'delete-entity'; entityKind: 'zone' | 'driver' | 'vehicle' | 'shift' | 'request'; id: UUID; label: string }
  | { kind: 'delete-scenario' }
  | { kind: 'reset-demo' }
  | { kind: 'reclassify-and-generate'; outsideCount: number; staleCount: number }
  | { kind: 'confirm-plan'; warnings: number }
  | { kind: 'rwms' }
  | { kind: 'simulation'; overrideKind: 'delay' | 'unavailable'; driverShiftId: UUID }
  | null;

function isTerminal(status: OptimizationRun['status'] | undefined): boolean {
  return status ? ['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(status) : false;
}

const TRACE_PHASES = [
  'VALIDATING_INPUT',
  'CLASSIFYING_ZONES',
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
  const [scenarioId, setScenarioId] = useState<UUID | null>(null);
  const [planningDate, setPlanningDate] = useState(dateInTimeZone(new Date(), 'Europe/Moscow'));
  const [planId, setPlanId] = useState<UUID | null>(null);
  const [plan, setPlan] = useState<RoutePlan | null>(null);
  const [runId, setRunId] = useState<UUID | null>(null);
  const [dialog, setDialog] = useState<DialogState>(null);
  const [validation, setValidation] = useState<ValidationResult | null>(null);
  const importRef = useRef<HTMLInputElement>(null);
  const surfacedPlanIdRef = useRef<UUID | null>(null);
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

  const selectPlanningDate = useCallback((date: string) => {
    setPlanningDate(date);
    if (plan?.date !== date) {
      setPlanId(null);
      setPlan(null);
      setValidation(null);
      clearSimulationOverrides();
    }
  }, [clearSimulationOverrides, plan?.date]);

  const scenariosQuery = useQuery({ queryKey: ['scenarios'], queryFn: api.listScenarios });
  useEffect(() => {
    if (!scenarioId && scenariosQuery.data?.[0]) setScenarioId(scenariosQuery.data[0].id);
    if (scenarioId && scenariosQuery.data && !scenariosQuery.data.some((scenario) => scenario.id === scenarioId)) {
      setScenarioId(scenariosQuery.data[0]?.id ?? null);
    }
  }, [scenarioId, scenariosQuery.data]);

  const workspaceQuery = useQuery({
    queryKey: ['workspace', scenarioId],
    queryFn: () => getScenarioWorkspace(scenarioId as UUID),
    enabled: Boolean(scenarioId),
  });
  const workspace = workspaceQuery.data ?? null;
  const workspaceScenarioId = workspace?.scenario.id;
  const workspaceDefaultPlanningDate = workspace?.scenario.default_planning_date;
  useEffect(() => {
    if (workspaceDefaultPlanningDate) setPlanningDate(workspaceDefaultPlanningDate);
    setPlanId(null);
    setPlan(null);
    setRunId(null);
    setValidation(null);
    clearTrace();
    clearSimulationOverrides();
  }, [clearSimulationOverrides, clearTrace, workspaceDefaultPlanningDate, workspaceScenarioId]);

  const planQuery = useQuery({
    queryKey: ['plan', planId, workspace?.scenario.updated_at],
    queryFn: () => api.getPlan(planId as UUID, workspace!),
    enabled: Boolean(planId && workspace),
  });
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
        title: 'Допустимые маршруты не найдены',
        detail: `${loadedPlan.unassigned.length} задач не распределено. Открыты конкретные причины и рекомендации.`,
      });
    } else {
      toast({
        tone: 'success',
        title: `План готов: ${cycleCount} ${cycleCount === 1 ? 'рейс' : cycleCount < 5 ? 'рейса' : 'рейсов'}`,
        detail: loadedPlan.unassigned.length > 0 ? `Не распределено задач: ${loadedPlan.unassigned.length}.` : 'Все доступные задачи распределены.',
      });
    }
  }, [planQuery.data, setSection, toast]);

  const runQuery = useQuery({
    queryKey: ['optimization-run', runId],
    queryFn: () => api.getOptimizationRun(runId as UUID, workspace!.scenario.settings),
    enabled: Boolean(runId && workspace),
    refetchInterval: (query) => isTerminal(query.state.data?.status) ? false : 900,
  });
  useEffect(() => {
    const run = runQuery.data;
    if (!run || !isTerminal(run.status)) return;
    if (run.plan_id) {
      setPlanId(run.plan_id);
      setMode('PLAN');
      setSection(run.status === 'FAILED' ? 'UNASSIGNED' : 'ROUTES');
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
    await queryClient.invalidateQueries({ queryKey: ['scenarios'] });
    if (scenarioId) await queryClient.invalidateQueries({ queryKey: ['workspace', scenarioId] });
  }, [queryClient, scenarioId]);

  const execute = useCallback(async <T,>(operation: () => Promise<T>, success?: string): Promise<T> => {
    try {
      const result = await actionMutation.mutateAsync(operation) as T;
      if (success) toast({ tone: 'success', title: success });
      return result;
    } catch (error: unknown) {
      const { refreshPlan, ...message } = actionErrorFeedback(error);
      toast(message);
      if (refreshPlan && planId) await queryClient.invalidateQueries({ queryKey: ['plan', planId] });
      throw error;
    }
  }, [actionMutation, planId, queryClient, toast]);

  const openCreate = (kind: EntityKind) => {
    if (!workspace && kind !== 'scenario') return;
    if (kind === 'scenario') setDialog({ kind: 'scenario' });
    else if (kind === 'warehouse') setDialog({ kind: 'warehouse' });
    else if (kind === 'driver') setDialog({ kind: 'driver' });
    else if (kind === 'vehicle') setDialog({ kind: 'vehicle' });
    else if (kind === 'shift') setDialog({ kind: 'shift' });
    else if (kind === 'request') setDialog({ kind: 'request', requestType: 'DELIVERY' });
  };

  const openEdit = (kind: EntityKind, value: EditableEntity) => {
    if (kind === 'scenario') setDialog({ kind: 'scenario', value: value as Scenario });
    else if (kind === 'warehouse') setDialog({ kind: 'warehouse', value: value as Warehouse });
    else if (kind === 'zone') setDialog({ kind: 'zone', value: value as Zone, geometry: (value as Zone).geometry });
    else if (kind === 'driver') setDialog({ kind: 'driver', value: value as Driver });
    else if (kind === 'vehicle') setDialog({ kind: 'vehicle', value: value as Vehicle });
    else if (kind === 'shift') setDialog({ kind: 'shift', value: value as DriverShift });
    else if (kind === 'request') setDialog({ kind: 'request', value: value as LogisticsRequest, requestType: (value as LogisticsRequest).type });
  };

  const handleMapPoint = useCallback((kind: 'warehouse' | 'request', longitude: number, latitude: number) => {
    if (kind === 'warehouse') setDialog({ kind: 'warehouse', point: { longitude, latitude } });
    else setDialog({ kind: 'request', point: { longitude, latitude }, requestType: mapTool === 'ADD_PICKUP' ? 'PICKUP' : 'DELIVERY' });
  }, [mapTool]);

  const handleZoneDraw = useCallback((geometry: Polygon) => setDialog({ kind: 'zone', geometry }), []);
  const handleMapError = useCallback((message: string) => {
    toast({ tone: 'warning', title: 'Grid mode', detail: message });
  }, [toast]);
  const handleRequestMoveDraft = useCallback((requestId: UUID, longitude: number, latitude: number) => {
    const request = workspace?.requests.find((candidate) => candidate.id === requestId);
    if (!request) return;
    setDialog({ kind: 'request', value: request, point: { longitude, latitude }, requestType: request.type });
    toast({ tone: 'info', title: 'Новые координаты не сохранены', detail: 'Проверьте форму и сохраните; backend заново определит зону.' });
  }, [toast, workspace]);
  const handleZoneGeometryChanged = useCallback((zoneId: UUID, geometry: Polygon | MultiPolygon) => {
    if (!workspace) return;
    void execute(async () => {
      await api.updateZone(zoneId, { geometry });
      await refresh();
    }, 'Геометрия зоны сохранена; версия увеличена').catch(() => undefined);
  }, [execute, refresh, workspace]);

  const handleZoneCutout = useCallback((zoneId: UUID, geometry: Polygon) => {
    if (!workspace) return;
    const sourceZone = workspace.zones.find((zone) => zone.id === zoneId);
    if (!sourceZone) return;
    const prefix = `${sourceZone.code}-IN`;
    const innerNumber = workspace.zones.filter((zone) => zone.code.startsWith(prefix)).length + 1;
    setMapTool('SELECT');
    setDialog({
      kind: 'zone-cutout',
      sourceZone,
      geometry,
      initialValues: {
        name: `${sourceZone.name} · внутренняя ${innerNumber}`,
        code: `${prefix}${innerNumber}`,
        route_group: sourceZone.route_group,
        delivery_price: sourceZone.delivery_price,
        pickup_price: sourceZone.pickup_price,
        priority: sourceZone.priority + 1,
        locked: false,
      },
    });
  }, [setMapTool, workspace]);

  const startPlanGeneration = async (reclassify: boolean) => {
    if (!workspace) return;
    await execute(async () => {
      if (reclassify) {
        const result = await api.reclassifyRequests(workspace.scenario.id);
        await refresh();
        if (result.outside_zones > 0) {
          toast({
            tone: 'warning',
            title: `После пересчёта вне зон: ${result.outside_zones}`,
            detail: 'Эти точки останутся нераспределёнными, остальные заявки будут переданы планировщику.',
          });
        }
      }
      clearTrace();
      setValidation(null);
      surfacedPlanIdRef.current = null;
      const accepted = await api.generatePlan(workspace.scenario.id, planningDate, workspace.scenario.seed ?? 42, workspace.scenario.settings);
      setRunId(accepted.run_id);
      if (accepted.plan_id) setPlanId(accepted.plan_id);
    }, reclassify ? 'Зоны пересчитаны, оптимизация запущена' : 'Оптимизация запущена').catch(() => undefined);
  };

  const generatePlan = async () => {
    if (!workspace) return;
    const missing: string[] = [];
    if (!workspace.warehouses.length) missing.push('не задан склад');
    if (!workspace.drivers.some((driver) => driver.active)) missing.push('нет активных водителей');
    if (!workspace.vehicles.some((vehicle) => vehicle.active)) missing.push('нет активных машин');
    if (!workspace.shifts.some((shift) => shift.active && shift.date === planningDate)) missing.push('нет активных смен на дату');
    const readyRequests = workspace.requests.filter((request) => request.status === 'READY' && isRequestVisibleOnDate(request, planningDate));
    if (!readyRequests.length) missing.push('нет готовых заявок на дату');
    if (missing.length) {
      toast({ tone: 'warning', title: 'План пока построить нельзя', detail: missing.join('; ') });
      return;
    }
    const outsideCount = readyRequests.filter((request) => request.zone_status === 'OUTSIDE_ZONES').length;
    const staleCount = readyRequests.filter((request) => request.zone_status === 'STALE').length;
    if (outsideCount + staleCount > 0) {
      if (!workspace.zones.length) {
        toast({
          tone: 'warning',
          title: 'Заявки не привязаны к логистическим зонам',
          detail: 'Сначала нарисуйте хотя бы одну зону, затем снова нажмите «Построить маршруты».',
        });
        return;
      }
      setDialog({ kind: 'reclassify-and-generate', outsideCount, staleCount });
      return;
    }
    await startPlanGeneration(false);
  };

  const cancelOptimization = async () => {
    if (!runId || !workspace) return;
    await execute(async () => {
      const cancelled = await api.cancelOptimizationRun(runId, workspace.scenario.settings);
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

  const confirmPlan = async (acceptWarnings: boolean) => {
    if (!workspace || !plan) return;
    await execute(async () => {
      const confirmed = await api.confirmPlan(plan.id, plan.version, acceptWarnings, workspace);
      setPlan(confirmed);
      setValidation(null);
      setDialog(null);
    }, 'План подтверждён').catch(() => undefined);
  };

  const savePlan = async () => {
    const result = await validatePlan();
    if (!result || !result.valid) return;
    if (result.warnings.length) setDialog({ kind: 'confirm-plan', warnings: result.warnings.length });
    else await confirmPlan(false);
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

  const changeMode = (nextMode: 'EDITOR' | 'PLAN' | 'SIMULATION') => {
    if (nextMode !== 'EDITOR' && !plan) {
      toast({ tone: 'info', title: 'Сначала постройте план' });
      return;
    }
    if (nextMode === 'SIMULATION' && plan && !plan.driver_routes.some((route) => route.cycles.length > 0)) {
      setSection('UNASSIGNED');
      toast({ tone: 'warning', title: 'Симуляцию пока запустить нельзя', detail: 'В плане нет ни одного рейса. Открыты причины нераспределения.' });
      return;
    }
    setMode(nextMode);
    if (nextMode === 'SIMULATION') setSection('ROUTES');
  };

  const exportScenario = async () => {
    if (!workspace) return;
    await execute(async () => {
      const payload = await api.exportScenario(workspace.scenario.id, true);
      const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `logistics-${workspace.scenario.name.replace(/[^a-zа-я0-9]+/gi, '-').toLowerCase()}.json`;
      anchor.click();
      URL.revokeObjectURL(url);
    }, 'Сценарий экспортирован').catch(() => undefined);
  };

  const generateMultiDayDemo = async () => {
    await execute(async () => {
      const scenario = await api.generateMultiDayDemo();
      await refresh();
      setScenarioId(scenario.id);
    }, 'Создан отдельный тестовый стенд на три дня').catch(() => undefined);
  };

  const generateWorkload = async (input: Parameters<typeof api.generateWorkload>[1]) => {
    if (!workspace) return;
    await execute(async () => {
      const result = await api.generateWorkload(workspace.scenario.id, input);
      await refresh();
      selectPlanningDate(result.start_date);
      setDialog(null);
      toast({
        tone: 'success',
        title: input.replace_existing_generated || result.replaced_requests > 0
          ? `Нагрузка перегенерирована: ${result.created_requests} заявок`
          : `Нагрузка создана: ${result.created_requests} заявок`,
        detail: `${result.created_deliveries} доставок · ${result.created_pickups} вывозов · ${formatDate(result.start_date)}–${formatDate(result.end_date)}${result.replaced_requests > 0 ? ` · заменено заявок: ${result.replaced_requests}` : ''}`,
      });
    }, undefined).catch(() => undefined);
  };

  const scheduleRequestDate = async (requestId: UUID, date: string, addIfMissing: boolean) => {
    await execute(async () => {
      await api.scheduleRequest(requestId, { date, add_if_missing: addIfMissing });
      await refresh();
      selectPlanningDate(date);
    }, addIfMissing ? 'Новая дата согласована и назначена' : 'Заявка выставлена на выбранную дату').catch(() => undefined);
  };

  const unscheduleRequest = async (requestId: UUID) => {
    await execute(async () => {
      await api.scheduleRequest(requestId, { date: null });
      await refresh();
    }, 'Назначение снято; заявка снова доступна во все согласованные даты').catch(() => undefined);
  };

  const importScenario = async (file: File) => {
    await execute(async () => {
      let payload: unknown;
      try { payload = JSON.parse(await file.text()) as unknown; }
      catch { throw new Error('Файл не является корректным JSON'); }
      const imported = await api.importScenario(payload);
      await refresh();
      setScenarioId(imported.id);
    }, 'Сценарий импортирован атомарно').catch(() => undefined);
  };

  if (scenariosQuery.isLoading) return <div className="app-shell" style={{ placeItems: 'center' }}><Spinner label="Загружаем сценарии…" /><Toasts /></div>;
  if (scenariosQuery.isError) return <div className="app-shell" style={{ placeItems: 'center' }}><ErrorPanel title="Backend недоступен" error={scenariosQuery.error} onRetry={() => void scenariosQuery.refetch()} /><Toasts /></div>;
  if (!scenariosQuery.data?.length || !scenarioId) {
    return <div className="app-shell" style={{ placeItems: 'center' }}><EmptyState icon={<DatabaseZap size={34} />} title="Начните с логистического сценария" description="Данные хранятся на backend. После создания можно загрузить demo или импортировать воспроизводимый JSON." action={<div className="toolbar-row"><Button variant="primary" onClick={() => setDialog({ kind: 'scenario' })}><CirclePlus size={15} />Создать сценарий</Button><Button onClick={() => importRef.current?.click()}>Импорт JSON</Button></div>} /><input ref={importRef} hidden type="file" accept="application/json,.json" onChange={(event) => { const file = event.target.files?.[0]; if (file) void importScenario(file); event.currentTarget.value = ''; }} />{dialog?.kind === 'scenario' ? <ScenarioDialog busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => { const scenario = await api.createScenario(input); await refresh(); setScenarioId(scenario.id); setDialog(null); }, 'Сценарий создан'); }} /> : null}<Toasts /></div>;
  }
  if (workspaceQuery.isError) return <div className="app-shell" style={{ placeItems: 'center' }}><ErrorPanel error={workspaceQuery.error} onRetry={() => void workspaceQuery.refetch()} /><Toasts /></div>;
  if (workspaceQuery.isLoading || !workspace) return <div className="app-shell" style={{ placeItems: 'center' }}><Spinner label="Загружаем рабочую область…" /><Toasts /></div>;

  const openRelation = (fromId: UUID, toId: UUID) => {
    const from = workspace.zones.find((zone) => zone.id === fromId);
    const to = workspace.zones.find((zone) => zone.id === toId);
    if (!from || !to) return;
    const value = workspace.zone_relations.find((relation) => relation.from_zone_id === fromId && relation.to_zone_id === toId);
    setDialog({ kind: 'relation', from, to, ...(value ? { value } : {}) });
  };

  const selectedOverrideRoute = dialog?.kind === 'simulation' ? plan?.driver_routes.find((route) => route.driver_shift_id === dialog.driverShiftId) : undefined;

  return (
    <div className={`app-shell ${mode === 'SIMULATION' ? 'app-shell--simulation' : ''}`}>
      <header className="topbar">
        <div className="topbar__brand"><div className="brand-mark">L</div><label className="scenario-select"><small>RWMS · Логистический стенд</small><select aria-label="Текущий сценарий" value={scenarioId} onChange={(event) => setScenarioId(event.target.value)}>{scenariosQuery.data.map((scenario) => <option value={scenario.id} key={scenario.id}>{scenario.name}</option>)}</select></label></div>
        <div className="topbar__date"><label className="date-field"><CalendarDays size={15} /><input aria-label="Дата планирования" type="date" value={planningDate} onChange={(event) => selectPlanningDate(event.target.value)} /></label><Button size="sm" onClick={() => selectPlanningDate(dateInTimeZone(new Date(), workspace.scenario.timezone))}>Сегодня</Button><Button size="sm" onClick={() => selectPlanningDate(nextDate(dateInTimeZone(new Date(), workspace.scenario.timezone), 1))}>Завтра</Button></div>
        <div className="topbar__actions">
          <Button variant="primary" disabled={busy || Boolean(currentRun && !isTerminal(currentRun.status))} onClick={() => void generatePlan()}><Sparkles size={15} /><span>Построить маршруты</span></Button>
          <Button disabled={busy} onClick={() => setDialog({ kind: 'rwms' })}><CloudCog size={15} /><span>Обмен с RWMS</span></Button>
          {currentRun && !isTerminal(currentRun.status) ? <Button variant="danger" disabled={busy || currentRun.cancel_requested} onClick={() => void cancelOptimization()}><span>{currentRun.cancel_requested ? 'Отменяем…' : 'Отменить'}</span></Button> : null}
          <Button disabled={!plan || busy} onClick={() => void validatePlan()}><CheckCircle2 size={15} /><span>Проверить</span></Button>
          <Button disabled={!plan || busy} onClick={() => void savePlan()}><Save size={15} /><span>Сохранить план</span></Button>
          <div className="segmented" aria-label="Режим приложения">{([['EDITOR', 'Редактор'], ['PLAN', 'План'], ['SIMULATION', 'Симуляция']] as const).map(([value, label]) => <button key={value} aria-pressed={mode === value} onClick={() => changeMode(value)}>{value === 'EDITOR' ? <RouteIcon size={13} /> : value === 'SIMULATION' ? <PlayCircle size={13} /> : null}{label}</button>)}</div>
        </div>
      </header>
      <div className={`workspace ${sidebarsCollapsed ? 'workspace--collapsed' : ''}`}>
        <Sidebar workspace={workspace} plan={plan} />
        <MapCanvas
          workspace={workspace}
          plan={plan}
          simulation={simulationState}
          traceEvents={traceEvents}
          optimizationRun={currentRun}
          selected={selected}
          onSelect={setSelected}
          onZoneRelation={openRelation}
          onPlacePoint={handleMapPoint}
          onZoneDrawn={handleZoneDraw}
          onZoneCutout={handleZoneCutout}
          onZoneGeometryChanged={handleZoneGeometryChanged}
          onRequestMoveDraft={handleRequestMoveDraft}
          onMapError={handleMapError}
          planningDate={planningDate}
          busy={busy}
          onScheduleRequestDate={(requestId, date, addIfMissing) => void scheduleRequestDate(requestId, date, addIfMissing)}
          onUnscheduleRequest={(requestId) => void unscheduleRequest(requestId)}
        />
        <Inspector
          workspace={workspace} plan={plan} simulation={simulationState} validation={validation} busy={busy}
          onCreate={openCreate} onEdit={openEdit} onDelete={(entityKind, id, label) => setDialog({ kind: 'delete-entity', entityKind, id, label })}
          onGenerateDemo={() => setDialog({ kind: 'reset-demo' })}
          onGenerateMultiDayDemo={() => void generateMultiDayDemo()}
          onGenerateWorkload={() => setDialog({ kind: 'workload-generator' })}
          onCloneScenario={() => void execute(async () => { const clone = await api.cloneScenario(workspace.scenario.id, `${workspace.scenario.name} · копия`); await refresh(); setScenarioId(clone.id); }, 'Сценарий клонирован')}
          onDeleteScenario={() => setDialog({ kind: 'delete-scenario' })}
          onExport={() => void exportScenario()} onImport={() => importRef.current?.click()}
          onReclassify={() => void execute(async () => { const result = await api.reclassifyRequests(workspace.scenario.id); await refresh(); toast({ tone: result.outside_zones ? 'warning' : 'success', title: `Пересчитано: ${result.updated}`, detail: `Вне зон: ${result.outside_zones}; без изменений: ${result.unchanged}` }); }, undefined)}
          onZoneRelation={openRelation} onSetMapTool={(tool) => { setMapTool(tool); toast({ tone: 'info', title: 'Инструмент карты включён' }); }}
          onSelect={(kind, id) => setSelected({ kind, id })} onMoveTask={(move) => void moveTask(move)} onToggleCycleLock={(cycle) => void toggleCycleLock(cycle)}
          onSaveSettings={async (settings) => { await execute(async () => { await api.updateScenario(workspace.scenario.id, { settings }); await refresh(); }, 'Настройки сохранены'); }}
          onClonePlan={() => plan && void execute(async () => { const clone = await api.clonePlan(plan.id, `Копия плана ${plan.date}`, workspace); setPlanId(clone.id); setPlan(clone); }, 'Версия плана клонирована')}
          onSimulationOverride={(overrideKind, driverShiftId) => setDialog({ kind: 'simulation', overrideKind, driverShiftId })}
          planningDate={planningDate} onPlanningDateChange={selectPlanningDate}
          onScheduleRequestDate={(requestId, date, addIfMissing) => void scheduleRequestDate(requestId, date, addIfMissing)}
          onUnscheduleRequest={(requestId) => void unscheduleRequest(requestId)}
        />
      </div>
      {mode === 'SIMULATION' && plan && simulationState && simulationTimestamp !== null ? <SimulationBar plan={plan} state={simulationState} timestamp={simulationTimestamp} timeZone={workspace.scenario.timezone} playing={simulationPlaying} speed={simulationSpeed} overrides={simulationOverrides} onTimestamp={setSimulationTimestamp} onPlaying={setSimulationPlaying} onSpeed={setSimulationSpeed} /> : null}
      <input ref={importRef} hidden type="file" accept="application/json,.json" onChange={(event) => { const file = event.target.files?.[0]; if (file) void importScenario(file); event.currentTarget.value = ''; }} />

      {dialog?.kind === 'scenario' ? <ScenarioDialog scenario={dialog.value} busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => {
        let createdScenarioId: UUID | null = null;
        if (dialog.value) await api.updateScenario(dialog.value.id, input);
        else createdScenarioId = (await api.createScenario(input)).id;
        await refresh();
        if (createdScenarioId) setScenarioId(createdScenarioId);
        setDialog(null);
      }, 'Сценарий сохранён'); }} /> : null}
      {dialog?.kind === 'workload-generator' ? <WorkloadGeneratorDialog
        planningDate={planningDate}
        seed={workspace.scenario.seed ?? 42}
        busy={busy}
        onClose={() => setDialog(null)}
        onSubmit={generateWorkload}
      /> : null}
      {dialog?.kind === 'warehouse' ? <WarehouseDialog warehouse={dialog.value} point={dialog.point} busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => { if (dialog.value) await api.updateWarehouse(dialog.value.id, input); else await api.createWarehouse(workspace.scenario.id, input); await refresh(); setDialog(null); setMapTool('SELECT'); }, 'Склад сохранён'); }} /> : null}
      {dialog?.kind === 'zone' ? <ZoneDialog zone={dialog.value} geometry={dialog.geometry} busy={busy} onClose={() => { setDialog(null); setMapTool('SELECT'); }} onSubmit={async (input) => { await execute(async () => {
        if (dialog.value) {
          await saveZoneUpdate(dialog.value, input, {
            setLocked: (locked) => api.setZoneLocked(dialog.value!.id, locked),
            update: (payload) => api.updateZone(dialog.value!.id, payload),
          });
        } else await api.createZone(workspace.scenario.id, input);
        await refresh(); setDialog(null); setMapTool('SELECT');
      }, 'Зона сохранена'); }} /> : null}
      {dialog?.kind === 'zone-cutout' ? <ZoneDialog
        geometry={dialog.geometry}
        initialValues={dialog.initialValues}
        title={`Новая зона внутри ${dialog.sourceZone.code}`}
        description="Сохранение одной транзакцией вырежет этот контур из большой зоны и создаст здесь отдельную логистическую зону. Отмена не изменит геометрию."
        submitLabel="Вырезать и создать зону"
        busy={busy}
        onClose={() => { setDialog(null); setMapTool('SELECT'); }}
        onSubmit={async (input) => { await execute(async () => {
          const result = await api.cutZone(dialog.sourceZone.id, input);
          await refresh();
          setSelected({ kind: 'zone', id: result.inner_zone.id });
          setDialog(null);
          setMapTool('SELECT');
        }, 'Вырез сохранён, внутренняя зона создана'); }}
      /> : null}
      {dialog?.kind === 'driver' || dialog?.kind === 'vehicle' ? <CatalogDialog kind={dialog.kind} value={dialog.value} busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => { if (dialog.kind === 'driver') { const value = input as Parameters<typeof api.createDriver>[1]; if (dialog.value) await api.updateDriver(dialog.value.id, value); else await api.createDriver(workspace.scenario.id, value); } else { const value = input as Parameters<typeof api.createVehicle>[1]; if (dialog.value) await api.updateVehicle(dialog.value.id, value); else await api.createVehicle(workspace.scenario.id, value); } await refresh(); setDialog(null); }, dialog.kind === 'driver' ? 'Водитель сохранён' : 'Машина сохранена'); }} /> : null}
      {dialog?.kind === 'shift' ? <ShiftDialog shift={dialog.value} scenario={workspace.scenario} drivers={workspace.drivers} vehicles={workspace.vehicles} busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => { if (dialog.value) await api.updateShift(dialog.value.id, input); else await api.createShift(workspace.scenario.id, input); await refresh(); setDialog(null); }, 'Смена сохранена'); }} /> : null}
      {dialog?.kind === 'request' ? <RequestDialog request={dialog.value} point={dialog.point} type={dialog.requestType} defaultDate={planningDate} busy={busy} onClose={() => { setDialog(null); setMapTool('SELECT'); }} onSubmit={async (input) => { await execute(async () => { if (dialog.value) await api.updateRequest(dialog.value.id, input); else await api.createRequest(workspace.scenario.id, input); await refresh(); setDialog(null); setMapTool('SELECT'); }, 'Заявка сохранена; зона определена backend'); }} /> : null}
      {dialog?.kind === 'relation' ? <RelationDialog fromZone={dialog.from} toZone={dialog.to} relation={dialog.value} busy={busy} onClose={() => setDialog(null)} onSubmit={async (input) => { await execute(async () => { if (dialog.value) await api.updateZoneRelation(dialog.value.id, { relation_type: input.relation_type, delivery_pair_allowed: input.delivery_pair_allowed, pickup_allowed: input.pickup_allowed, max_detour_minutes: input.max_detour_minutes, max_detour_ratio: input.max_detour_ratio, penalty: input.penalty, is_bidirectional: input.is_bidirectional }); else await api.createZoneRelation(workspace.scenario.id, input); await refresh(); setDialog(null); }, 'Связь зон сохранена'); }} onDelete={dialog.value ? async () => { await execute(async () => { await api.deleteZoneRelation(dialog.value!.id); await refresh(); setDialog(null); }, 'Связь удалена'); } : undefined} /> : null}
      {dialog?.kind === 'delete-entity' ? <ConfirmDialog title={`Удалить «${dialog.label}»?`} description="Действие изменит только текущий тестовый сценарий. Backend проверит ссылки и вернёт ошибку, если объект используется." confirmLabel="Удалить" dangerous busy={busy} onClose={() => setDialog(null)} onConfirm={async () => { await execute(async () => { if (dialog.entityKind === 'zone') await api.deleteZone(dialog.id); else if (dialog.entityKind === 'driver') await api.deleteDriver(dialog.id); else if (dialog.entityKind === 'vehicle') await api.deleteVehicle(dialog.id); else if (dialog.entityKind === 'shift') await api.deleteShift(dialog.id); else await api.deleteRequest(dialog.id); await refresh(); setDialog(null); }, 'Объект удалён'); }} /> : null}
      {dialog?.kind === 'delete-scenario' ? <ConfirmDialog title={`Удалить сценарий «${workspace.scenario.name}»?`} description="Сценарий и его тестовые данные будут удалены. Это не затрагивает другие сценарии." confirmLabel="Удалить сценарий" dangerous busy={busy} onClose={() => setDialog(null)} onConfirm={async () => { await execute(async () => { await api.deleteScenario(workspace.scenario.id); setDialog(null); setScenarioId(null); await refresh(); }, 'Сценарий удалён'); }} /> : null}
      {dialog?.kind === 'reset-demo' ? <ConfirmDialog title={`Заменить данные сценария «${workspace.scenario.name}» демонстрационными?`} description="Склады, зоны, ресурсы, заявки и сохранённые планы только этого сценария будут удалены и созданы заново. Другие сценарии не изменятся." confirmLabel="Создать demo" dangerous busy={busy} onClose={() => setDialog(null)} onConfirm={async () => { await execute(async () => { await api.generateDemo(workspace.scenario.id); await refresh(); setDialog(null); }, 'Demo scenario готов'); }} /> : null}
      {dialog?.kind === 'reclassify-and-generate' ? <ConfirmDialog title="Пересчитать зоны заявок перед построением?" description={`На ${formatDate(planningDate)}: без зоны — ${dialog.outsideCount}, с устаревшей версией — ${dialog.staleCount}. Это явное действие обновит принадлежность по текущим полигонам, затем сразу запустит построение маршрутов.`} confirmLabel="Пересчитать и построить" busy={busy} onClose={() => setDialog(null)} onConfirm={async () => { setDialog(null); await startPlanGeneration(true); }} /> : null}
      {dialog?.kind === 'confirm-plan' ? <ConfirmDialog title="Подтвердить план с предупреждениями?" description={`Проверка не нашла жёстких ошибок, но осталось предупреждений: ${dialog.warnings}. Подтверждение будет явным.`} confirmLabel="Подтвердить с предупреждениями" busy={busy} onClose={() => setDialog(null)} onConfirm={() => confirmPlan(true)} /> : null}
      {dialog?.kind === 'rwms' ? <RwmsIntegrationDialog
        scenarioId={workspace.scenario.id}
        planningDate={planningDate}
        warehouses={workspace.warehouses}
        plan={plan}
        busy={busy}
        onClose={() => setDialog(null)}
        onSync={(warehouseId, date) => execute(async () => {
          const result = await api.syncRwmsRequests(workspace.scenario.id, { warehouse_id: warehouseId, date_from: date, date_to: date });
          await refresh();
          return result;
        })}
        onApply={(selectedPlanId, expectedVersion, publishUnassignedTaskIds) => execute(
          () => api.applyPlanToRwms(selectedPlanId, expectedVersion, publishUnassignedTaskIds),
        )}
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
