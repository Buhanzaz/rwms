import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Activity,
  CalendarCheck2,
  ClipboardCheck,
  Plus,
  RefreshCw,
  TriangleAlert,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ApiError,
  api,
  type LogisticsAction,
  type LogisticsDecision,
  type LogisticsDecisionInput,
  type LogisticsEventInput,
  type LogisticsNotice,
  type PlanningDayMode,
  type RecoveryProposal,
} from '../../api/client';
import type { RoutePlan, UUID, WarehouseWorkspace } from '../../domain/types';
import { Badge, Button, EmptyState, ErrorPanel, Modal, SelectField, Spinner } from '../../components/ui';
import { formatDate } from '../../utils/format';
import { actionErrorFeedback } from '../../app/action-error';
import { useUiStore } from '../../stores/ui-store';
import { customerLegalTypeFromRequest, customerLegalTypeLabel } from '../../utils/customer-presentation';
import { OperationsEventDialog } from './OperationsEventDialog';
import { operationalHistoryEntries } from './operations-history';
import {
  actionIsPending,
  actionNeedsCustomerDecision,
  BASE_TASK_KIND_LABELS,
  baseTaskCandidateGroups,
  customerPhoneLabel,
  DAY_MODE_LABELS,
  proposalAgreementLabel,
  proposalApplicationLabel,
  proposalCanApply,
  proposalCustomerAgreementRecorded,
  proposalStatusLabel,
} from './operations-presentation';

interface OperationsPanelProps {
  workspace: WarehouseWorkspace;
  plan: RoutePlan | null;
  planningDate: string;
  busy: boolean;
  onSelectRequest: (requestId: UUID) => void;
}

type CacheImpact = 'projection' | 'workspace' | 'plan';

function proposalTone(status: string): 'neutral' | 'success' | 'warning' | 'danger' | 'accent' {
  if (status === 'APPLIED') return 'success';
  if (status === 'FAILED' || status === 'REJECTED') return 'danger';
  if (status === 'CUSTOMER_AGREED' || status === 'READY_TO_APPLY') return 'accent';
  return 'warning';
}

function actionStatusLabel(status: string): string {
  if (status === 'PENDING') return 'Ожидает решения';
  if (status === 'IN_PROGRESS') return 'В работе';
  if (status === 'RESOLVED') return 'Завершено';
  return 'Неактуально';
}

function linkedProposalForAction(
  action: LogisticsAction,
  proposals: readonly RecoveryProposal[],
): RecoveryProposal | null {
  return proposals
    .filter((proposal) => (
      proposal.action_id === action.id
      || (
        proposal.event_id === action.event_id
        && proposal.proposal_type === 'PARTIAL_REPLAN_DELAY'
        && proposal.status !== 'APPLIED'
        && proposal.status !== 'REJECTED'
      )
    ))
    .sort((left, right) => left.created_at.localeCompare(right.created_at))
    .at(-1) ?? null;
}

function initialDecisionType(
  recommendedDate: string | null,
  alternativeDates: readonly string[],
  isDelayContact: boolean,
): LogisticsDecisionInput['decision_type'] {
  if (isDelayContact) return 'ACCEPT_DELAY';
  if (recommendedDate) return 'ACCEPT_RECOMMENDATION';
  if (alternativeDates.length) return 'ACCEPT_OTHER_DATE';
  return 'UNREACHABLE';
}

/** Renders only resolved request, task, and route identities, never transport UUIDs. */
function OperationsLinkage({
  workspace,
  plan,
  requestId = null,
  taskId = null,
  cycleId = null,
}: {
  workspace: WarehouseWorkspace;
  plan: RoutePlan | null;
  requestId?: UUID | null;
  taskId?: UUID | null;
  cycleId?: UUID | null;
}) {
  const requestByTask = taskId
    ? workspace.requests.find((candidate) => candidate.tasks?.some((task) => task.id === taskId)) ?? null
    : null;
  const request = workspace.requests.find((candidate) => candidate.id === requestId) ?? requestByTask;
  const task = taskId
    ? request?.tasks?.find((candidate) => candidate.id === taskId) ?? null
    : null;
  const routeContext = plan?.driver_routes.flatMap((route) => route.cycles.map((cycle) => ({ route, cycle })))
    .find(({ cycle }) => cycle.id === cycleId || (taskId != null && cycle.stops.some((stop) => stop.task_id === taskId))) ?? null;

  if (!request && !task && !routeContext) return null;
  return (
    <div className="operations-affected" aria-label="Привязка к заявке и маршруту">
      <small>Привязка</small>
      {request ? (
        <span>
          Заявка: <strong>{request.name}</strong> · {customerLegalTypeLabel(customerLegalTypeFromRequest(request))}
        </span>
      ) : null}
      {task ? <span>Задание: часть {task.part_number} · {task.quantity} бытов.</span> : null}
      {routeContext ? (
        <span>
          Маршрут: <strong>{routeContext.route.driver_name}</strong> · {routeContext.route.vehicle_name} · {routeContext.route.registration_number} · рейс {routeContext.cycle.sequence}
        </span>
      ) : null}
    </div>
  );
}

/** Collects explicit relation fields from proposal presentation data without interpreting business state. */
function proposalLinkedIds(changes: Record<string, unknown>, field: 'cycle_id' | 'task_id' | 'request_id'): UUID[] {
  const result = new Set<UUID>();
  const pluralField = `${field}s`;
  const visit = (value: unknown) => {
    if (!value || typeof value !== 'object') return;
    Object.entries(value as Record<string, unknown>).forEach(([key, nested]) => {
      if (key === field && typeof nested === 'string') result.add(nested);
      if (key === pluralField && Array.isArray(nested)) {
        nested.forEach((candidate) => {
          if (typeof candidate === 'string') result.add(candidate);
        });
      }
      visit(nested);
    });
  };
  visit(changes);
  return [...result];
}

/** Shows owner-provided internal work as informational candidates, without claiming or assigning it. */
function BaseTaskCandidates({ source }: { source: Record<string, unknown> }) {
  const groups = baseTaskCandidateGroups(source);
  if (!groups.length) return null;
  return (
    <div className="operations-affected" aria-label="Резервные внутренние задания">
      <small>Низкоприоритетные задания после возврата</small>
      <p>Это кандидаты для логиста; они не назначены автоматически.</p>
      {groups.map((group) => (
        <div key={group.warehouseName}>
          <strong>{group.warehouseName}</strong>
          <ul>
            {group.tasks.map((task) => (
              <li key={`${task.kind}-${task.unitNumber}-${task.summary}`}>
                Бытовка №{task.unitNumber} · {BASE_TASK_KIND_LABELS[task.kind]} · {task.summary}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}

function ActionCard({
  action,
  notices,
  proposals,
  workspace,
  plan,
  busy,
  onSelectRequest,
  onDecide,
}: {
  action: LogisticsAction;
  notices: readonly LogisticsNotice[];
  proposals: readonly RecoveryProposal[];
  workspace: WarehouseWorkspace;
  plan: RoutePlan | null;
  busy: boolean;
  onSelectRequest: (requestId: UUID) => void;
  onDecide: (action: LogisticsAction, input: LogisticsDecisionInput) => Promise<void>;
}) {
  const proposal = linkedProposalForAction(action, proposals);
  const needsCustomerDecision = actionNeedsCustomerDecision(action, proposals);
  const isDelayContact = action.action_type === 'CONTACT_CUSTOMER_DELAY';
  const [decisionType, setDecisionType] = useState<LogisticsDecisionInput['decision_type']>(() => (
    initialDecisionType(action.recommended_date, action.alternative_dates, isDelayContact)
  ));
  const [selectedDate, setSelectedDate] = useState(action.alternative_dates[0] ?? '');
  const [comment, setComment] = useState('');
  const notice = notices.find((candidate) => candidate.id === action.notice_id);
  const request = workspace.requests.find((candidate) => candidate.id === action.request_id);

  useEffect(() => {
    setDecisionType(initialDecisionType(action.recommended_date, action.alternative_dates, isDelayContact));
    setSelectedDate(action.alternative_dates[0] ?? '');
    setComment('');
  }, [action.alternative_dates, action.recommended_date, action.version, isDelayContact]);

  const submit = async () => {
    if (!needsCustomerDecision) return;
    await onDecide(action, {
      expected_version: action.version,
      ...(proposal ? { expected_proposal_version: proposal.version } : {}),
      decision_type: decisionType,
      ...(decisionType === 'ACCEPT_OTHER_DATE' ? { selected_date: selectedDate } : {}),
      comment: comment.trim() || null,
    });
  };
  const decisionReady = decisionType === 'ACCEPT_RECOMMENDATION' || decisionType === 'REJECT'
    ? Boolean(action.recommended_date)
    : decisionType === 'ACCEPT_OTHER_DATE' ? Boolean(selectedDate) : true;

  return (
    <article className="operations-action-card" data-testid={`operations-action-${action.id}`}>
      <header className="operations-card-header">
        <div>
          <small>Требуется действие</small>
          <h3>{request?.name ?? action.customer_name ?? 'Клиентская заявка'}</h3>
        </div>
        <Badge tone="warning">{actionStatusLabel(action.status)}</Badge>
      </header>

      <div className="operations-contact-grid">
        <div><small>Клиент</small><strong>{action.customer_name ?? 'Контакт не указан'}</strong></div>
        <div><small>Правовой статус</small><strong>{customerLegalTypeLabel(request ? customerLegalTypeFromRequest(request) : action.customer_type)}</strong></div>
        <div>
          <small>Телефон</small>
          <strong>{customerPhoneLabel(action.customer_phone)}</strong>
        </div>
      </div>

      {notice ? (
        <div className="operations-reason">
          <strong>Причина</strong>
          <p>{notice.message_ru}</p>
          {notice.recommended_action_ru ? <small>{notice.recommended_action_ru}</small> : null}
          <OperationsLinkage workspace={workspace} plan={plan} requestId={notice.request_id ?? action.request_id} taskId={notice.task_id} cycleId={notice.cycle_id} />
          <BaseTaskCandidates source={notice.facts} />
        </div>
      ) : null}

      <div className="operations-date-options">
        <div><small>Текущая дата</small><strong>{action.current_date ? formatDate(action.current_date) : 'Не назначена'}</strong></div>
        <div><small>Рекомендуемая дата</small><strong>{action.recommended_date ? formatDate(action.recommended_date) : 'Вариант пока не найден'}</strong></div>
        <div><small>Альтернативы</small><strong>{action.alternative_dates.length ? action.alternative_dates.map(formatDate).join(', ') : 'Нет'}</strong></div>
      </div>

      {action.request_id ? (
        <Button size="sm" variant="ghost" onClick={() => onSelectRequest(action.request_id as UUID)}>
          Показать заявку на карте
        </Button>
      ) : null}

      {needsCustomerDecision ? (
        <fieldset className="operations-decision" aria-label={`Решение по ${request?.name ?? action.id}`}>
          <legend>Результат связи</legend>
          {isDelayContact ? <>
            <label><input type="radio" name={`decision-${action.id}`} checked={decisionType === 'ACCEPT_DELAY'} onChange={() => setDecisionType('ACCEPT_DELAY')} disabled={busy} />Клиент согласен на опоздание</label>
            <label><input type="radio" name={`decision-${action.id}`} checked={decisionType === 'REJECT_DELAY'} onChange={() => setDecisionType('REJECT_DELAY')} disabled={busy} />Клиент не согласен на опоздание</label>
            {decisionType === 'REJECT_DELAY' ? <small>Сохранится ограничение исходного окна; сервис подготовит частичное перепланирование.</small> : null}
          </> : <>
            <label><input type="radio" name={`decision-${action.id}`} checked={decisionType === 'ACCEPT_RECOMMENDATION'} onChange={() => setDecisionType('ACCEPT_RECOMMENDATION')} disabled={!action.recommended_date || busy} />Согласована рекомендованная дата</label>
            <label><input type="radio" name={`decision-${action.id}`} checked={decisionType === 'ACCEPT_OTHER_DATE'} onChange={() => setDecisionType('ACCEPT_OTHER_DATE')} disabled={!action.alternative_dates.length || busy} />Согласована другая дата</label>
            {decisionType === 'ACCEPT_OTHER_DATE' ? (
              <SelectField label="Согласованная дата" value={selectedDate} onChange={(event) => setSelectedDate(event.target.value)}>
                {action.alternative_dates.map((date) => <option value={date} key={date}>{formatDate(date)}</option>)}
              </SelectField>
            ) : null}
            <label><input type="radio" name={`decision-${action.id}`} checked={decisionType === 'REJECT'} onChange={() => setDecisionType('REJECT')} disabled={!action.recommended_date || busy} />Клиент отказался</label>
            {decisionType === 'REJECT' ? <small>Дата станет новым ограничением, затем сервис вернёт обновлённое предложение.</small> : null}
          </>}
          <label><input type="radio" name={`decision-${action.id}`} checked={decisionType === 'UNREACHABLE'} onChange={() => setDecisionType('UNREACHABLE')} disabled={busy} />Не удалось связаться</label>
          {decisionType === 'UNREACHABLE' ? <small>Исходный план останется без изменений, действие сохранится в очереди.</small> : null}
          <label className="field">
            <span className="field__label">Комментарий</span>
            <textarea className="input operations-comment" value={comment} maxLength={2000} onChange={(event) => setComment(event.target.value)} />
          </label>
          <Button variant="primary" disabled={busy || !decisionReady} onClick={() => void submit().catch(() => undefined)}>
            Сохранить решение
          </Button>
        </fieldset>
      ) : proposal ? (
        <div className="operations-reason">
          <strong>Операционное решение рассчитано</strong>
          <p>Подтвердите применение связанного предложения отдельно. До подтверждения исходный план не меняется.</p>
          <Button size="sm" onClick={() => document.getElementById(`proposal-${proposal.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })}>
            Перейти к предложению
          </Button>
        </div>
      ) : null}
    </article>
  );
}

function ProposalCard({
  proposal,
  decisions,
  workspace,
  plan,
  busy,
  onSelectRequest,
  onConfirmApply,
}: {
  proposal: RecoveryProposal;
  decisions: readonly LogisticsDecision[];
  workspace: WarehouseWorkspace;
  plan: RoutePlan | null;
  busy: boolean;
  onSelectRequest: (requestId: UUID) => void;
  onConfirmApply: (proposal: RecoveryProposal) => void;
}) {
  const requests = proposal.affected_request_ids.map((id) => ({
    id,
    request: workspace.requests.find((candidate) => candidate.id === id),
  }));
  const linkedTaskIds = [...new Set([
    ...proposal.affected_task_ids,
    ...proposalLinkedIds(proposal.changes, 'task_id'),
  ])];
  const linkedRequestIds = proposalLinkedIds(proposal.changes, 'request_id')
    .filter((id) => !proposal.affected_request_ids.includes(id));
  const linkedCycleIds = proposalLinkedIds(proposal.changes, 'cycle_id');
  return (
    <article className="operations-proposal-card" id={`proposal-${proposal.id}`} data-testid={`operations-proposal-${proposal.id}`}>
      <header className="operations-card-header">
        <div><small>Предложение восстановления</small><h3>{proposal.summary_ru}</h3></div>
        <Badge tone={proposalTone(proposal.status)}>{proposalStatusLabel(proposal.status)}</Badge>
      </header>
      <div className="operations-proposal-stages" aria-label="Стадии предложения">
        <div className="operations-stage operations-stage--complete"><span>1</span><strong>Предложение рассчитано</strong></div>
        <div className={`operations-stage ${proposal.proposal_type !== 'RESCHEDULE_REQUEST' || proposalCustomerAgreementRecorded(proposal, decisions) ? 'operations-stage--complete' : ''}`}><span>2</span><strong>{proposalAgreementLabel(proposal, decisions)}</strong></div>
        <div className={`operations-stage ${proposal.status === 'APPLIED' ? 'operations-stage--complete' : proposal.status === 'FAILED' ? 'operations-stage--failed' : ''}`}><span>3</span><strong>{proposalApplicationLabel(proposal)}</strong></div>
      </div>
      <div className="operations-affected">
        <small>Затронутые позиции</small>
        {proposal.proposal_type === 'PARTIAL_REPLAN_RESOURCE_LOSS' && proposal.status !== 'APPLIED' ? (
          <p className="field__hint">Если свободной машины нет, откройте заявку ниже: согласуйте перенос с клиентом или передачу наёмному водителю. Поломка не влечёт неустойку клиента.</p>
        ) : null}
        {requests.length ? requests.map(({ id, request }) => (
          <Button size="sm" variant="ghost" key={id} onClick={() => onSelectRequest(id)}>
            {request?.name ?? 'Заявка вне загруженной страницы'} · {customerLegalTypeLabel(customerLegalTypeFromRequest(request))}
          </Button>
        )) : <strong>Клиентские заявки не затронуты</strong>}
        {proposal.affected_task_ids.length ? <span>Заданий: {proposal.affected_task_ids.length}</span> : null}
      </div>
      {linkedRequestIds.map((id) => <OperationsLinkage key={`request-${id}`} workspace={workspace} plan={plan} requestId={id} />)}
      {linkedTaskIds.map((id) => <OperationsLinkage key={`task-${id}`} workspace={workspace} plan={plan} taskId={id} />)}
      {linkedCycleIds.map((id) => <OperationsLinkage key={`cycle-${id}`} workspace={workspace} plan={plan} cycleId={id} />)}
      <BaseTaskCandidates source={proposal.changes} />
      {proposal.status === 'FAILED' ? (
        <p className="operations-inline-warning">Изменение не применено; исходный план сохранён. Обновите данные и повторите подтверждение.</p>
      ) : null}
      {proposalCanApply(proposal) ? (
        <Button variant="primary" disabled={busy} onClick={() => onConfirmApply(proposal)}>
          {proposal.status === 'FAILED' ? 'Повторить применение' : 'Проверить и применить'}
        </Button>
      ) : null}
    </article>
  );
}

export function OperationsPanel({ workspace, plan, planningDate, busy, onSelectRequest }: OperationsPanelProps) {
  const queryClient = useQueryClient();
  const toast = useUiStore((state) => state.toast);
  const [eventDialogOpen, setEventDialogOpen] = useState(false);
  const [proposalToApply, setProposalToApply] = useState<RecoveryProposal | null>(null);
  const intentKeysRef = useRef(new Map<string, { signature: string; key: UUID }>());
  const surfacedOperationsRef = useRef<{ context: string; signatures: Map<string, string> } | null>(null);
  const operationsWarehouseId = workspace.planning_root_warehouse_id ?? workspace.warehouse.id;
  const context = `${operationsWarehouseId}:${planningDate}`;
  const operationsQueryKey = ['planning-day-operations', operationsWarehouseId, planningDate] as const;
  const operationsQuery = useQuery({
    queryKey: operationsQueryKey,
    queryFn: () => api.getPlanningDayOperations(operationsWarehouseId, planningDate),
    retry: false,
    refetchInterval: 15_000,
  });
  const refetchOperations = operationsQuery.refetch;
  const commandMutation = useMutation<unknown, unknown, () => Promise<unknown>>({
    mutationFn: (operation) => operation(),
  });
  const operationBusy = busy || commandMutation.isPending;
  const operations = operationsQuery.data;
  const pendingActions = useMemo(
    () => operations?.actions.filter(actionIsPending) ?? [],
    [operations?.actions],
  );

  useEffect(() => {
    if (!operations) return;
    const entries = operationalHistoryEntries(operations).slice(-200);
    let current = surfacedOperationsRef.current;
    const isInitialContext = !current || current.context !== context;
    if (!current || current.context !== context) {
      current = { context, signatures: new Map<string, string>() };
    }
    surfacedOperationsRef.current = current;

    entries.forEach((entry) => {
      if (current.signatures.get(entry.key) === entry.signature) return;
      current.signatures.set(entry.key, entry.signature);
      toast({
        tone: entry.tone,
        replacementKey: entry.key,
        title: entry.title,
        detail: entry.detail,
        createdAt: entry.createdAt,
        visible: !isInitialContext,
        read: isInitialContext,
        ...(entry.requestId ? {
          action: { label: 'Показать заявку', onActivate: () => onSelectRequest(entry.requestId as UUID) },
        } : {}),
      });
    });

    const truncated = operations.truncated_collections ?? [];
    if (truncated.length) {
      const key = `logistics-history-truncated-${context}`;
      const signature = [...truncated].sort().join(',');
      if (current.signatures.get(key) !== signature) {
        current.signatures.set(key, signature);
        toast({
          tone: 'warning',
          replacementKey: key,
          title: 'История операций показана частично',
          detail: 'Сервис вернул только последнее окно событий и решений за выбранный день.',
        });
      }
    }
  }, [context, onSelectRequest, operations, toast]);

  const intentKey = (scope: string, payload: unknown): UUID => {
    const signature = JSON.stringify(payload);
    const current = intentKeysRef.current.get(scope);
    if (current?.signature === signature) return current.key;
    const key = crypto.randomUUID();
    intentKeysRef.current.set(scope, { signature, key });
    return key;
  };

  const refresh = async (impact: CacheImpact) => {
    const refreshes: Array<Promise<unknown>> = [
      queryClient.invalidateQueries({ queryKey: operationsQueryKey }),
      queryClient.invalidateQueries({ queryKey: ['planning-day-status', operationsWarehouseId, planningDate] }),
    ];
    if (impact === 'workspace' || impact === 'plan') {
      refreshes.push(queryClient.invalidateQueries({ queryKey: ['workspace', workspace.warehouse.id] }));
    }
    if (impact === 'plan') {
      refreshes.push(queryClient.invalidateQueries({ queryKey: ['plan'] }));
      refreshes.push(queryClient.invalidateQueries({ queryKey: ['automatic-plan', operationsWarehouseId, planningDate] }));
      if (operationsWarehouseId !== workspace.warehouse.id) {
        refreshes.push(queryClient.invalidateQueries({ queryKey: ['automatic-plan', workspace.warehouse.id, planningDate] }));
      }
    }
    await Promise.all(refreshes);
  };

  const reportError = async (error: unknown) => {
    const feedback = actionErrorFeedback(error);
    toast({ tone: feedback.tone, title: feedback.title, detail: feedback.detail });
    if (error instanceof ApiError && error.status === 409) {
      await Promise.all([
        operationsQuery.refetch(),
        queryClient.invalidateQueries({ queryKey: ['plan'] }),
      ]);
    }
  };

  const runCommand = async <T,>(operation: () => Promise<T>): Promise<T> => {
    try {
      return await commandMutation.mutateAsync(operation) as T;
    } catch (error: unknown) {
      await reportError(error);
      throw error;
    }
  };

  const changeMode = async (mode: PlanningDayMode) => {
    if (!operations || mode === operations.mode) return;
    const currentPlan = plan?.warehouse_id === operationsWarehouseId && plan.date === planningDate
      ? plan
      : null;
    const input = {
      expected_version: operations.mode_version,
      plan_id: currentPlan?.id ?? null,
      expected_plan_version: currentPlan?.version ?? null,
      mode,
    };
    const scope = `mode:${context}`;
    try {
      const result = await runCommand(() => api.updatePlanningDayMode(
        operationsWarehouseId,
        planningDate,
        input,
        intentKey(scope, input),
      ));
      intentKeysRef.current.delete(scope);
      await refresh('projection');
      toast({
        tone: result.conflict_count ? 'warning' : 'success',
        title: result.conflict_count ? 'Режим изменён — требуется согласование' : 'Режим дня изменён',
        detail: result.conflict_count
          ? `Затронуто позиций: ${result.conflict_count}. Они загружены в очередь действий.`
          : 'Конфликтующих заданий нет. Очередь действий не создавалась.',
      });
    } catch {
      // The stable intent remains available for an exact retry.
    }
  };

  const createEvent = async (input: LogisticsEventInput) => {
    const scope = `event:${context}`;
    try {
      const result = await runCommand(() => api.createLogisticsEvent(
        operationsWarehouseId,
        planningDate,
        input,
        intentKey(scope, input),
      ));
      intentKeysRef.current.delete(scope);
      await refresh('workspace');
      setEventDialogOpen(false);
      const requiresAction = result.notices.some((notice) => notice.requires_action) || result.actions.length > 0;
      toast({
        tone: requiresAction ? 'warning' : 'info',
        title: requiresAction ? 'Событие зафиксировано — требуется действие' : 'Событие зафиксировано',
        detail: requiresAction
          ? `Системных комментариев: ${result.notices.length}; действий: ${result.actions.length}.`
          : 'Влияние рассчитано. Текущий план не изменён автоматически.',
      });
    } catch {
      // Dialog stays open and the exact command can be retried safely.
    }
  };

  const decide = async (action: LogisticsAction, input: LogisticsDecisionInput) => {
    if (!operations) return;
    const scope = `decision:${action.id}`;
    try {
      const decision = await runCommand(() => api.decideLogisticsAction(action.id, input, intentKey(scope, input)));
      intentKeysRef.current.delete(scope);
      await refresh('projection');
      const entry = operationalHistoryEntries({
        ...operations,
        decisions: [decision],
      }).find((candidate) => candidate.key === `logistics-decision-${decision.id}`);
      if (!entry) return;
      if (surfacedOperationsRef.current?.context === context) {
        surfacedOperationsRef.current.signatures.set(entry.key, entry.signature);
      }
      toast({
        tone: entry.tone,
        replacementKey: entry.key,
        title: entry.title,
        detail: entry.detail,
        createdAt: entry.createdAt,
        ...(entry.requestId ? {
          action: { label: 'Показать заявку', onActivate: () => onSelectRequest(entry.requestId as UUID) },
        } : {}),
      });
    } catch {
      // The current choice and stable intent remain available for an exact retry.
    }
  };

  const applyProposal = async (proposal: RecoveryProposal) => {
    const input = { expected_version: proposal.version };
    const scope = `apply:${proposal.id}`;
    try {
      const applied = await runCommand(() => api.applyRecoveryProposal(
        proposal.id,
        proposal.version,
        intentKey(scope, input),
      ));
      intentKeysRef.current.delete(scope);
      setProposalToApply(null);
      await refresh(applied.status === 'APPLIED' ? 'plan' : 'projection');
      toast({
        tone: applied.status === 'APPLIED' ? 'success' : 'warning',
        title: applied.status === 'APPLIED' ? 'Предложение применено' : 'Предложение пока не применено',
        detail: applied.status === 'APPLIED'
          ? 'Затронутая часть логистики пересчитана; обновлённый план загружается.'
          : 'Исходный план сохранён. Проверьте актуальность предложения и повторите применение.',
      });
    } catch {
      // Confirmation stays open after a transport/conflict failure.
    }
  };

  if (operationsQuery.isPending) return <Spinner label="Загружаем операции дня…" />;
  if (operationsQuery.isError) {
    return <ErrorPanel title="Операции дня недоступны" error={operationsQuery.error} onRetry={() => void operationsQuery.refetch()} />;
  }
  if (!operations) {
    return <EmptyState title="Операционная проекция не загружена" description="Обновите данные выбранного склада и дня." action={<Button onClick={() => void refetchOperations()}>Обновить</Button>} />;
  }
  return (
    <section className="operations-panel" aria-label="Динамические операции дня">
      <header className="operations-heading">
        <div><small>Операционный контур</small><h2>Операции на {formatDate(planningDate)}</h2></div>
        <Button size="sm" variant="ghost" disabled={operationBusy} onClick={() => void operationsQuery.refetch()} aria-label="Обновить операции дня"><RefreshCw size={14} /></Button>
      </header>

      <section className="operations-mode-card" aria-label="Режим логистического дня">
        <div className="operations-section-title"><CalendarCheck2 size={17} /><div><h3>Режим дня</h3></div></div>
        <div className="segmented operations-mode-picker">
          {(Object.entries(DAY_MODE_LABELS) as Array<[PlanningDayMode, string]>).map(([value, label]) => (
            <button type="button" key={value} aria-pressed={operations.mode === value} disabled={operationBusy} onClick={() => void changeMode(value)}>{label}</button>
          ))}
        </div>
      </section>

      <div className="operations-primary-action">
        <div><Activity size={18} /><span><strong>Изменилось выполнение дня?</strong><small>Запишите подтверждённый факт — влияние и варианты рассчитает сервис.</small></span></div>
        <Button variant="primary" disabled={operationBusy} onClick={() => setEventDialogOpen(true)}><Plus size={14} />Зафиксировать событие</Button>
      </div>

      <section className="operations-section" aria-labelledby="operations-queue-title">
        <div className="operations-section-title"><TriangleAlert size={17} /><div><h3 id="operations-queue-title">Очередь действий</h3><p>Только позиции, где действительно требуется решение логиста.</p></div><Badge tone={pendingActions.length ? 'warning' : 'neutral'}>{operations.pending_action_count}</Badge></div>
        {pendingActions.length ? pendingActions.map((action) => (
          <ActionCard
            key={action.id}
            action={action}
            notices={operations.notices}
            proposals={operations.proposals}
            workspace={workspace}
            plan={plan}
            busy={operationBusy}
            onSelectRequest={onSelectRequest}
            onDecide={decide}
          />
        )) : <EmptyState title="Действий не требуется" description="Новые информационные события появятся в уведомлениях и журнале в настройках." />}
      </section>

      <section className="operations-section" aria-labelledby="operations-proposals-title">
        <div className="operations-section-title"><ClipboardCheck size={17} /><div><h3 id="operations-proposals-title">Предложения</h3><p>Расчёт, согласование клиента и применение показаны как разные стадии.</p></div><Badge>{operations.proposals.length}</Badge></div>
        {operations.proposals.length ? [...operations.proposals].reverse().map((proposal) => (
          <ProposalCard key={proposal.id} proposal={proposal} decisions={operations.decisions} workspace={workspace} plan={plan} busy={operationBusy} onSelectRequest={onSelectRequest} onConfirmApply={setProposalToApply} />
        )) : <EmptyState title="Предложений нет" description="Сервис создаст предложение только после события или реального конфликта." />}
      </section>

      {eventDialogOpen ? (
        <OperationsEventDialog workspace={workspace} plan={plan} planningDate={planningDate} busy={operationBusy} onClose={() => setEventDialogOpen(false)} onSubmit={createEvent} />
      ) : null}
      {proposalToApply ? (
        <Modal
          title="Применить предложение?"
          description="Предложение уже рассчитано, но ещё не применено. Подтверждение создаст новую версию плана; исходный план останется в истории."
          onClose={() => setProposalToApply(null)}
          footer={(
            <>
              <Button disabled={operationBusy} onClick={() => setProposalToApply(null)}>Отмена</Button>
              <Button variant="primary" disabled={operationBusy} onClick={() => void applyProposal(proposalToApply)}>
                {operationBusy ? 'Применяем…' : 'Да, применить предложение'}
              </Button>
            </>
          )}
        >
          <div className="operations-apply-summary">
            <strong>{proposalToApply.summary_ru}</strong>
            <p>{proposalAgreementLabel(proposalToApply, operations.decisions)}</p>
            <p>Затронуто заявок: {proposalToApply.affected_request_ids.length}; заданий: {proposalToApply.affected_task_ids.length}.</p>
          </div>
        </Modal>
      ) : null}
    </section>
  );
}
