import type { IsoDate, LogisticsRequest } from './types';

/**
 * Returns whether a request belongs to a planning day.
 *
 * An explicit logistics date is authoritative. Until it is selected, every
 * customer-approved date option remains visible to the dispatcher.
 */
export function isRequestVisibleOnDate(request: LogisticsRequest, date: IsoDate): boolean {
  return request.scheduled_date
    ? request.scheduled_date === date
    : request.date_options.some((option) => option.date === date);
}

/** Returns all dates that should appear on the request date board. */
export function requestPlanningDates(request: LogisticsRequest): IsoDate[] {
  return [...new Set([
    ...request.date_options.map((option) => option.date),
    ...(request.scheduled_date ? [request.scheduled_date] : []),
  ])].sort();
}
