import type {
  LogisticsDecision,
  LogisticsNotice,
  PlanningDayOperations,
} from '../../api/client';
import type { UUID } from '../../domain/types';
import { formatDate } from '../../utils/format';
import {
  BASE_TASK_KIND_LABELS,
  baseTaskCandidateGroups,
  decisionActorLabel,
  decisionTone,
  decisionTypeLabel,
  eventTypeLabel,
  noticeStatusLabel,
} from './operations-presentation';

/** One user-facing record shared by transient notifications and the settings journal. */
export interface OperationalHistoryEntry {
  key: string;
  signature: string;
  createdAt: string;
  tone: 'success' | 'warning' | 'error' | 'info';
  title: string;
  detail: string;
  requestId: UUID | null;
}

function noticeEntry(
  notice: LogisticsNotice,
  operations: PlanningDayOperations,
): OperationalHistoryEntry {
  const event = operations.events.find((candidate) => candidate.id === notice.event_id);
  const baseTasks = baseTaskCandidateGroups(notice.facts).flatMap((group) => (
    group.tasks.map((task) => `${group.warehouseName}: бытовка №${task.unitNumber} · ${BASE_TASK_KIND_LABELS[task.kind]}`)
  ));
  const detail = [
    notice.message_ru,
    notice.recommended_action_ru,
    `Статус: ${noticeStatusLabel(notice.status)}.`,
    baseTasks.length ? `Низкоприоритетные задания на базе: ${baseTasks.join('; ')}.` : null,
  ].filter(Boolean).join(' ');
  return {
    key: `logistics-notice-${notice.id}`,
    signature: JSON.stringify([
      notice.created_at,
      notice.severity,
      notice.status,
      notice.requires_action,
      notice.message_ru,
      notice.recommended_action_ru,
      notice.resolved_at,
    ]),
    createdAt: notice.created_at,
    tone: notice.severity === 'ERROR' || notice.severity === 'CRITICAL'
      ? 'error'
      : notice.severity === 'WARNING' ? 'warning' : 'info',
    title: notice.requires_action
      ? 'Логистика требует внимания'
      : `Логистика · ${eventTypeLabel(event?.event_type ?? notice.notice_type)}`,
    detail,
    requestId: notice.request_id,
  };
}

function decisionEntry(
  decision: LogisticsDecision,
  operations: PlanningDayOperations,
): OperationalHistoryEntry {
  const action = operations.actions.find((candidate) => candidate.id === decision.action_id);
  const notice = operations.notices.find((candidate) => candidate.id === action?.notice_id);
  const detail = [
    decisionTypeLabel(decision.decision_type),
    decision.selected_date ? `Согласованная дата: ${formatDate(decision.selected_date)}.` : null,
    decision.comment?.trim() ? `Комментарий: ${decision.comment.trim()}` : null,
  ].filter(Boolean).join(' ');
  const tone = decisionTone(decision.decision_type);
  return {
    key: `logistics-decision-${decision.id}`,
    signature: JSON.stringify([
      decision.created_at,
      decision.decision_type,
      decision.selected_date,
      decision.comment,
    ]),
    createdAt: decision.created_at,
    tone: tone === 'danger' ? 'error' : tone === 'warning' ? 'warning' : tone === 'success' ? 'success' : 'info',
    title: decisionActorLabel(decision.actor),
    detail,
    requestId: notice?.request_id ?? action?.request_id ?? null,
  };
}

/** Merges immutable server notices and dispatcher decisions in chronological order. */
export function operationalHistoryEntries(
  operations: PlanningDayOperations,
): OperationalHistoryEntry[] {
  return [
    ...operations.notices.map((notice) => noticeEntry(notice, operations)),
    ...operations.decisions.map((decision) => decisionEntry(decision, operations)),
  ].sort((left, right) => left.createdAt.localeCompare(right.createdAt));
}
