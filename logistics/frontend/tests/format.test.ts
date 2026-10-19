import { describe, expect, it } from 'vitest';
import { normalizeWarehouse } from '../src/api/mappers';
import { DEFAULT_PLANNING_SETTINGS } from '../src/domain/defaults';
import { dateInTimeZone, formatTime, nextDate } from '../src/utils/format';
import { warehouseFixture } from './fixtures';
import { formatWarehouseLocalTime } from '../src/utils/format';
import { initialWarehouseSelection } from '../src/app/warehouse-selection';

describe('warehouse timezone helpers', () => {
  const now = new Date('2026-08-22T21:30:00Z');

  it('derives current and next dates from the warehouse timezone', () => {
    const today = dateInTimeZone(now, 'Europe/Moscow');
    expect(today).toBe('2026-08-23');
    expect(nextDate(today, 1)).toBe('2026-08-24');
  });

  it('formats route timestamps in the configured warehouse timezone', () => {
    expect(formatTime('2026-08-25T04:00:00Z', 'Europe/Moscow')).toBe('07:00');
  });

  it('immediately formats local time from the selected warehouse IANA timezone', () => {
    expect(formatWarehouseLocalTime(new Date('2026-08-30T12:00:00Z'), 'Europe/Moscow')).toBe('15:00');
    expect(formatWarehouseLocalTime(new Date('2026-08-30T12:00:00Z'), 'Asia/Novosibirsk')).toBe('19:00');
  });

  it('never falls back to SPB while a selected warehouse workspace is loading', () => {
    const spb = warehouseFixture({ id: 'warehouse-spb' });
    expect(initialWarehouseSelection('warehouse-vnovgorod', [spb])).toBe('warehouse-vnovgorod');
    expect(initialWarehouseSelection(null, [spb])).toBe(spb.id);
  });

  it('keeps a nullable planning date and normalizes partial settings for a warehouse', () => {
    const raw = {
      ...warehouseFixture(),
      default_planning_date: null,
      settings: { vehicle_capacity: 2 },
    };

    const warehouse = normalizeWarehouse(raw as never);

    expect(warehouse.default_planning_date).toBeNull();
    expect(warehouse.settings.vehicle_capacity).toBe(2);
    expect(warehouse.settings.max_delivery_stops).toBe(DEFAULT_PLANNING_SETTINGS.max_delivery_stops);
  });
});
