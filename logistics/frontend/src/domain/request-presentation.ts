import type { RequestStatus } from './types';

/** Human-readable lifecycle labels shared by request cards and map popups. */
export const requestStatusLabels: Record<RequestStatus, string> = {
  DRAFT: 'Черновик',
  READY: 'Готова к планированию',
  PLANNED: 'В плане',
  IN_PROGRESS: 'В работе',
  COMPLETED: 'Выполнена',
  CANCELLED: 'Отменена',
  UNASSIGNED: 'Не распределена',
};
