import { describe, expect, it } from 'vitest';
import { normalizeScenario } from '../src/api/mappers';
import { dateInTimeZone, formatTime, nextDate } from '../src/utils/format';

describe('scenario timezone helpers', () => {
  const instant = new Date('2026-08-22T22:30:00Z');

  it('derives today and tomorrow from the scenario timezone', () => {
    expect(dateInTimeZone(instant, 'Europe/Moscow')).toBe('2026-08-23');
    expect(dateInTimeZone(instant, 'America/Los_Angeles')).toBe('2026-08-22');
    expect(nextDate(dateInTimeZone(instant, 'Europe/Moscow'), 1)).toBe('2026-08-24');
  });

  it('formats route timestamps in the configured scenario timezone', () => {
    expect(formatTime(instant, 'Europe/Moscow')).toBe('01:30');
    expect(formatTime(instant, 'America/Los_Angeles')).toBe('15:30');
  });

  it('normalizes a nullable backend planning date to scenario-local today', () => {
    const scenario = normalizeScenario({
      id: 'scenario-id',
      name: 'Без даты',
      description: '',
      timezone: 'Europe/Moscow',
      default_planning_date: null,
      created_at: '2026-08-22T00:00:00Z',
      updated_at: '2026-08-22T00:00:00Z',
      seed: 1,
      settings: {},
    }, instant);
    expect(scenario.default_planning_date).toBe('2026-08-23');
    expect(scenario.settings.vehicle_capacity).toBe(2);
  });
});
