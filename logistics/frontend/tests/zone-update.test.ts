import { describe, expect, it, vi } from 'vitest';
import type { ZoneInput } from '../src/api/client';
import type { Zone } from '../src/domain/types';
import { buildZoneUpdatePayload, saveZoneUpdate } from '../src/features/zones/zone-update';

const geometry: Zone['geometry'] = {
  type: 'Polygon',
  coordinates: [[[37, 55], [38, 55], [38, 56], [37, 56], [37, 55]]],
};

const zone: Zone = {
  id: '00000000-0000-0000-0000-000000000001',
  scenario_id: '00000000-0000-0000-0000-000000000002',
  name: 'Москва',
  code: 'MSK',
  route_group: 'CITY',
  delivery_price: 12_500,
  pickup_price: 9_000,
  geometry,
  version: 4,
  priority: 10,
  locked: false,
  created_at: '2026-08-24T08:00:00Z',
  updated_at: '2026-08-24T08:00:00Z',
};

function input(overrides: Partial<ZoneInput> = {}): ZoneInput {
  return {
    name: zone.name,
    code: zone.code,
    route_group: zone.route_group,
    delivery_price: zone.delivery_price,
    pickup_price: zone.pickup_price,
    geometry,
    priority: zone.priority,
    locked: zone.locked,
    ...overrides,
  };
}

describe('zone update payload', () => {
  it('omits unchanged geometry when only tariffs change', () => {
    const payload = buildZoneUpdatePayload(zone, input({ delivery_price: 13_000, pickup_price: 9_500 }));

    expect(payload).toMatchObject({ delivery_price: 13_000, pickup_price: 9_500 });
    expect(payload).not.toHaveProperty('geometry');
  });

  it('includes geometry only when its coordinates change', () => {
    const changedGeometry: Zone['geometry'] = {
      type: 'Polygon',
      coordinates: [[[37, 55], [39, 55], [39, 56], [37, 56], [37, 55]]],
    };

    expect(buildZoneUpdatePayload(zone, input({ geometry: changedGeometry }))).toHaveProperty(
      'geometry',
      changedGeometry,
    );
  });

  it('returns no patch for a lock-only change handled by the lock command', () => {
    expect(buildZoneUpdatePayload(zone, input({ locked: true }))).toBeNull();
  });

  it('temporarily unlocks a locked zone that is edited and locked again', async () => {
    const calls: string[] = [];
    const lockedZone = { ...zone, locked: true };

    await saveZoneUpdate(lockedZone, input({ name: 'Москва в кольце', locked: true }), {
      setLocked: vi.fn((locked: boolean) => {
        calls.push(`lock:${locked}`);
        return Promise.resolve();
      }),
      update: vi.fn(() => {
        calls.push('patch');
        return Promise.resolve();
      }),
    });

    expect(calls).toEqual(['lock:false', 'patch', 'lock:true']);
  });

  it('best-effort restores the original lock and preserves a patch failure', async () => {
    const calls: string[] = [];
    const failure = new Error('zone patch failed');
    const lockedZone = { ...zone, locked: true };

    await expect(saveZoneUpdate(lockedZone, input({ name: 'Москва в кольце', locked: true }), {
      setLocked: vi.fn((locked: boolean) => {
        calls.push(`lock:${locked}`);
        return Promise.resolve();
      }),
      update: vi.fn(() => {
        calls.push('patch');
        return Promise.reject(failure);
      }),
    })).rejects.toBe(failure);

    expect(calls).toEqual(['lock:false', 'patch', 'lock:true']);
  });

  it('does not relock when the saved operator intent is to leave the zone editable', async () => {
    const calls: string[] = [];
    const lockedZone = { ...zone, locked: true };

    await saveZoneUpdate(lockedZone, input({ name: 'Москва в кольце', locked: false }), {
      setLocked: vi.fn((locked: boolean) => {
        calls.push(`lock:${locked}`);
        return Promise.resolve();
      }),
      update: vi.fn(() => {
        calls.push('patch');
        return Promise.resolve();
      }),
    });

    expect(calls).toEqual(['lock:false', 'patch']);
  });
});
