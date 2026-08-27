import type { LogisticsRequest } from './types';

/** Return operator-visible missing prerequisites for one selected planning date. */
export function requestPlanningMissingFields(request: LogisticsRequest, planningDate: string): string[] {
  const option = request.date_options.find((candidate) => candidate.date === planningDate);
  const missing: string[] = [];
  if (!option) missing.push('дата не согласована');
  else if (!option.window_start || !option.window_end) missing.push('не задано окно времени');
  if (typeof request.trailer_access_allowed !== 'boolean') missing.push('не согласован проезд с прицепом');
  return missing;
}
