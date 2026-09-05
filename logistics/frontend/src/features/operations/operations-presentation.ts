import type {
  LogisticsAction,
  LogisticsDecision,
  LogisticsEventInput,
  PlanningDayMode,
  RecoveryProposal,
} from '../../api/client';

export const DAY_MODE_LABELS: Readonly<Record<PlanningDayMode, string>> = {
  DELIVERIES_AND_PICKUPS: 'Доставки и вывозы',
  DELIVERIES_ONLY: 'Только доставки',
  PICKUPS_ONLY: 'Только вывозы',
};

export type DispatcherEventType = Exclude<
  LogisticsEventInput['event_type'],
  'MANUAL_PLAN_CHANGE' | 'PLANNING_MODE_CHANGED'
>;

export const DISPATCHER_EVENT_LABELS: Readonly<Record<DispatcherEventType, string>> = {
  VEHICLE_BREAKDOWN: 'Поломка машины',
  TRAILER_BREAKDOWN: 'Поломка прицепа',
  VEHICLE_DELAY: 'Задержка машины',
  DRIVER_UNAVAILABLE: 'Водитель недоступен',
  DELIVERY_CANCELLED: 'Доставка отменена',
  PICKUP_CANCELLED: 'Вывоз отменён',
  ORDER_CANCELLED: 'Заказ отменён',
  TASK_BLOCKED: 'Задание заблокировано',
};

/** Supported internal RWMS task kinds exposed as low-priority return-to-base candidates. */
export type BaseTaskKind =
  | 'GENERAL_MOVEMENT'
  | 'DELIVER_TO_REPAIR'
  | 'REMOVE_FROM_REPAIR'
  | 'CAPITAL_TO_PRODUCTION'
  | 'TRANSFER';

/** Safe dispatcher-facing subset of one internal RWMS task. */
export interface BaseTaskCandidate {
  kind: BaseTaskKind;
  unitNumber: string;
  summary: string;
}

/** Internal task candidates grouped by their owning return warehouse. */
export interface BaseTaskCandidateGroup {
  warehouseName: string;
  tasks: BaseTaskCandidate[];
}

export const BASE_TASK_KIND_LABELS: Readonly<Record<BaseTaskKind, string>> = {
  GENERAL_MOVEMENT: 'Общее перемещение',
  DELIVER_TO_REPAIR: 'Доставить в ремонт',
  REMOVE_FROM_REPAIR: 'Забрать из ремонта',
  CAPITAL_TO_PRODUCTION: 'Вернуть в производство после капремонта',
  TRANSFER: 'Перемещение между складами',
};

function baseTaskKind(value: unknown): BaseTaskKind | null {
  if (
    value === 'GENERAL_MOVEMENT'
    || value === 'DELIVER_TO_REPAIR'
    || value === 'REMOVE_FROM_REPAIR'
    || value === 'CAPITAL_TO_PRODUCTION'
    || value === 'TRANSFER'
  ) return value;
  return null;
}

/** Extracts only the known base-task presentation structure from generic notice/proposal data. */
export function baseTaskCandidateGroups(value: Record<string, unknown>): BaseTaskCandidateGroup[] {
  const rawGroups = value.base_task_groups;
  if (!Array.isArray(rawGroups)) return [];
  return rawGroups.flatMap((rawGroup) => {
    if (!rawGroup || typeof rawGroup !== 'object') return [];
    const group = rawGroup as Record<string, unknown>;
    if (typeof group.warehouse_name !== 'string' || !group.warehouse_name.trim() || !Array.isArray(group.tasks)) {
      return [];
    }
    const tasks = group.tasks.flatMap((rawTask): BaseTaskCandidate[] => {
      if (!rawTask || typeof rawTask !== 'object') return [];
      const task = rawTask as Record<string, unknown>;
      const kind = baseTaskKind(task.kind);
      if (
        !kind
        || typeof task.unitNumber !== 'string'
        || !task.unitNumber.trim()
        || typeof task.summary !== 'string'
        || !task.summary.trim()
      ) return [];
      return [{ kind, unitNumber: task.unitNumber.trim(), summary: task.summary.trim() }];
    });
    return tasks.length ? [{ warehouseName: group.warehouse_name.trim(), tasks }] : [];
  });
}

export function customerPhoneLabel(value: string | null): string {
  if (!value?.trim()) return 'Телефон не указан';
  const digits = value.replace(/\D/gu, '');
  const russian = digits.length === 11 && (digits.startsWith('7') || digits.startsWith('8'))
    ? `7${digits.slice(1)}`
    : digits.length === 10 ? `7${digits}` : null;
  if (!russian) return value.trim();
  return `+${russian[0]} ${russian.slice(1, 4)} ${russian.slice(4, 7)}-${russian.slice(7, 9)}-${russian.slice(9, 11)}`;
}

/** Maps the persisted system-comment lifecycle to dispatcher language. */
export function noticeStatusLabel(status: string): string {
  const labels: Readonly<Record<string, string>> = {
    REQUIRES_ACTION: 'Требует действия',
    ACCEPTED: 'Принято',
    REJECTED: 'Отклонено',
    COMPLETED: 'Выполнено',
    OBSOLETE: 'Больше не актуально',
  };
  return labels[status] ?? 'Статус обновляется';
}

/** Maps the persisted system-comment lifecycle to an existing badge tone. */
export function noticeStatusTone(status: string): 'neutral' | 'success' | 'warning' | 'danger' | 'accent' {
  if (status === 'REQUIRES_ACTION') return 'warning';
  if (status === 'ACCEPTED') return 'accent';
  if (status === 'REJECTED') return 'danger';
  if (status === 'COMPLETED') return 'success';
  return 'neutral';
}

/** Describes an immutable logistics decision without exposing its transport enum. */
export function decisionTypeLabel(type: string): string {
  const labels: Readonly<Record<string, string>> = {
    ACCEPT_RECOMMENDATION: 'Клиент согласовал рекомендованную дату',
    ACCEPT_OTHER_DATE: 'Клиент согласовал другую дату',
    REJECT: 'Клиент отказался',
    UNREACHABLE: 'Не удалось связаться с клиентом',
    ACCEPT_DELAY: 'Клиент согласен на опоздание',
    REJECT_DELAY: 'Клиент не согласен на опоздание',
  };
  return labels[type] ?? 'Решение логиста сохранено';
}

/** Maps an immutable logistics decision to the shared status badge tone. */
export function decisionTone(type: string): 'neutral' | 'success' | 'warning' | 'danger' | 'accent' {
  if (type === 'REJECT') return 'danger';
  if (type === 'REJECT_DELAY') return 'danger';
  if (type === 'UNREACHABLE') return 'warning';
  if (
    type === 'ACCEPT_RECOMMENDATION'
    || type === 'ACCEPT_OTHER_DATE'
    || type === 'ACCEPT_DELAY'
  ) return 'success';
  return 'neutral';
}

/** Keeps a readable actor name while removing an embedded technical identifier. */
export function decisionActorLabel(actor: string): string {
  const readable = actor.split('<', 1)[0]?.trim();
  if (!readable || readable.toLocaleLowerCase('ru-RU') === 'dispatcher') return 'Логист';
  return `Логист · ${readable}`;
}

export function actionIsPending(action: LogisticsAction): boolean {
  return action.status === 'PENDING' || action.status === 'IN_PROGRESS';
}

export function actionNeedsCustomerDecision(
  action: LogisticsAction,
  proposals: readonly RecoveryProposal[],
): boolean {
  return proposals.some((proposal) => (
    (
      proposal.action_id === action.id
      && proposal.proposal_type === 'RESCHEDULE_REQUEST'
      && (proposal.status === 'PROPOSED' || proposal.status === 'CUSTOMER_AGREED')
    )
    || (
      action.action_type === 'CONTACT_CUSTOMER_DELAY'
      && proposal.event_id === action.event_id
      && proposal.proposal_type === 'PARTIAL_REPLAN_DELAY'
      && (proposal.status === 'PROPOSED' || proposal.status === 'READY_TO_APPLY')
    )
  ));
}

export function proposalCanApply(proposal: RecoveryProposal): boolean {
  return proposal.status === 'CUSTOMER_AGREED'
    || proposal.status === 'READY_TO_APPLY'
    || proposal.status === 'FAILED';
}

function latestProposalDecision(
  proposal: RecoveryProposal,
  decisions: readonly LogisticsDecision[],
): LogisticsDecision | null {
  if (!proposal.action_id) return null;
  return decisions
    .filter((decision) => decision.action_id === proposal.action_id)
    .sort((left, right) => left.created_at.localeCompare(right.created_at))
    .at(-1) ?? null;
}

export function proposalCustomerAgreementRecorded(
  proposal: RecoveryProposal,
  decisions: readonly LogisticsDecision[],
): boolean {
  if (proposal.proposal_type !== 'RESCHEDULE_REQUEST' || proposal.status === 'REJECTED') return false;
  const decision = latestProposalDecision(proposal, decisions);
  return decision?.decision_type === 'ACCEPT_RECOMMENDATION'
    || decision?.decision_type === 'ACCEPT_OTHER_DATE';
}

export function proposalAgreementLabel(
  proposal: RecoveryProposal,
  decisions: readonly LogisticsDecision[],
): string {
  if (proposal.proposal_type !== 'RESCHEDULE_REQUEST') return 'Согласование клиента не требуется';
  if (proposal.status === 'REJECTED') return 'Клиент отказался';
  if (proposalCustomerAgreementRecorded(proposal, decisions)) return 'Согласовано с клиентом';
  return 'С клиентом не согласовано';
}

export function proposalApplicationLabel(proposal: RecoveryProposal): string {
  if (proposal.status === 'APPLIED') return 'Изменение применено';
  if (proposal.status === 'FAILED') return 'Применение завершилось ошибкой';
  return 'Изменение не применено';
}

export function proposalStatusLabel(status: string): string {
  const labels: Readonly<Record<string, string>> = {
    PROPOSED: 'Предложено',
    CUSTOMER_AGREED: 'Согласовано',
    READY_TO_APPLY: 'Готово к применению',
    APPLIED: 'Применено',
    REJECTED: 'Отклонено',
    FAILED: 'Ошибка применения',
  };
  return labels[status] ?? 'Статус обновляется';
}

export function eventTypeLabel(value: string): string {
  if (value === 'PLANNING_MODE_CHANGED') return 'Изменён режим дня';
  if (value === 'MANUAL_PLAN_CHANGE') return 'План изменён вручную';
  return DISPATCHER_EVENT_LABELS[value as DispatcherEventType] ?? 'Операционное событие';
}
